# 优惠券生命周期契约 v0.1（CPN-001/CPN-002）

状态：IMPLEMENTED_INTERNAL_DEFAULT_OFF，最终以对应实现 PR 的 CI 为准。2026-10-14。
依据 SSOT §九（退款返券）、§二十二（迟到支付）、§二十（封板规则）、事件目录 08 号 §4/§8、
[38 号](38-Atomic-Booking-Create-Contract-v0.1.md)（原子创建）、[39 号](39-Selection-Booking-Expiry-Contract-v0.1.md)
（到期关闭）、[40/41 号](40-Payment-Foundation-Contract-v0.1.md)（支付）、[42 号](42-Late-Payment-Refund-Contract-v0.1.md)、
[49 号](49-Refund-Application-Contract-v0.1.md)（退款）、[07 号](07-内部API-Contract-v0.6.md) §12、CCR-C006（读侧 D1/D2 裁决）与
[CCR-CPN-LIFECYCLE-RULES-001](../../planning/ccr/CCR-CPN-LIFECYCLE-RULES-001.md)（规则冻结裁决记录）。
本文件为 61 号唯一契约文档（62/63 号已预分配给并行批次，不占用）。

## 1. 范围与产品边界

交付优惠券"冻结 → 消费 → 释放/返券"完整写链路（ISSUE CPN-001、CPN-002、QA-002 券并发部分、C-006 券页收尾）：

- 下单带券：38 号原子创建内核锁内冻结券、计算折后应付，同事务落订单/预约/券/台账；
- 支付成功：支付结果消费事务内券 FROZEN→USED（核销）；
- 支付超时关闭：39 号关闭事务内券自动释放（FROZEN→AVAILABLE/EXPIRED）；
- 退款成功：消费 `RefundSucceededEvent.v1`，全额退款返券（含 24 小时有效期延长），部分退款不返券，
  `LATE_PAYMENT_TIMEOUT` 来源一律 NOOP；
- C 端：券列表沿用 10 号 §3.15.1（已交付），本契约新增下单选券入口的展示与提交面。

V1 边界（不变）：无券模板管理/发放渠道（券实例仅运营侧数据，导入路径 V1 不做）、无积分抵扣混合、
无第三方券包、部分退款仅运营售后裁决、积分扣回不归本契约（PTS 系列）。

## 2. rule_json 结构冻结（D1 收口）

CCR-C006 D1 遗留的 rule_json 结构随本契约冻结为固定四键对象（与既有 C 端投影键一一对应，键名不变）：

```json
{"amountOff":"20", "thresholdAmount":"100.00", "scopeSummary":"全场通用", "typeLabel":"满减券"}
```

- `amountOff`：抵扣金额，十进制字符串、两位小数、>0（必填）。
- `thresholdAmount`：金额门槛，十进制字符串、两位小数、>=0；缺省/null 视为无门槛（选填）。
- `scopeSummary`/`typeLabel`：展示文本，服务端投影输出，不参与资格判定（选填）。
- 解析失败/类型不符/精度超限的成员按缺失处理；`amountOff` 缺失的券视为不可用（FREEZE 拒绝）。
- V1 资格判定仅使用 amountOff/thresholdAmount；scopeSummary 为展示级字段，类目级适用范围的结构化
  scope 键随券模板管理 Issue 交付后再启用（CCR-CPN-LIFECYCLE-RULES-001 §3，V1 不自行发明）。

## 3. 冻结（下单选券，38 号锁内）

内部命令：`pet-coupon-api` 新增 `CouponCommandApi.freeze(CouponFreezeCommand)`，返回
`CouponFreezeResult(couponInstanceId, discountAmount)`。调用方为 ORDER 创建内核，约束：

- 仅可在已持有同店 ScheduleCapacityGuard 的可写事务内调用（`requireHeld` 强制）；缺提供器时维持
  38 号既有语义 503 失败关闭、不创建业务行。
- 资格判定（任一不满足即 409 `COUPON_NOT_AVAILABLE`，不创建任何写副作用）：
  ① 实例存在且属于命令用户；② 实例 `status='AVAILABLE'`；③ 实例 `expire_at > now`；
  ④ 模板 `status='ACTIVE'` 且 `now ∈ [valid_start_at, valid_end_at]`；
  ⑤ `rule.amountOff` 可解析且 >0；⑥ `thresholdAmount` 存在时 `orderAmount >= thresholdAmount`；
  ⑦ `amountOff < orderAmount`（折后应付必须大于零：渠道不可收 0 元单，V1 无 0 元单规则，CCR §4）。
- `discountAmount = rule.amountOff`（全额抵扣券面额，不做比例/阶梯折算）；`payAmount = orderAmount - discountAmount`。
- 写入（与 ORDER/SCH 同事务，全部同 DataSource 当前读）：
  `coupon_instance` 行级 CAS：`UPDATE ... SET status='FROZEN', order_id=:orderId, frozen_at=now,
  freeze_expire_at=:deadline, version=version+1 WHERE id=:id AND user_id=:userId AND status='AVAILABLE'
  AND expire_at > now`，受影响行数≠1 即 409 `COUPON_NOT_AVAILABLE`——同券并发下单最多一单成功
  （QA-002/CPN-004），败者回滚整单。冻结前以 `order_id` 锁读校验该订单未绑定过其他券（一单一券）。
- 台账：`coupon_ledger` 写 `FREEZE` 行，`request_id='CPN:FREEZE:{orderId}'`（uk 幂等），
  `from_status='AVAILABLE'`、`to_status='FROZEN'`。
- 冻结有效期 `freeze_expire_at = 订单 payment_expire_at`（=预约 hold 截止，PRD"冻结有效期与槽位锁定一致"）。
- 冻结发生回滚（hold 失败、订单写失败等）与业务写入同事务一起回滚，无孤儿冻结；释放补偿不需要。

## 4. 消费（支付成功，40/41 号结果事务内）

`CouponCommandApi.consume(CouponConsumeCommand)`，由 ORDER 支付结果消费（`markNormal`）在标记 PAID
的同一 guard 事务内调用（与 SCH confirm 同一模式；OrderPaidEvent 的 couponInstanceId 字段同事务补真值）：

- 前置：`order.discount_amount > 0`；`discount_amount = 0` 的订单改走既有"无券证明"
  （`BookingCouponExposureApi.requireNoCoupon`），出现任何券行即 503 保留（防账实分歧）。
- CAS：`UPDATE ... SET status='USED', used_at=:paidAt, version=version+1
  WHERE id=:id AND status='FROZEN' AND order_id=:orderId`，受影响行数≠1 即 503（不确定，重放安全：
  幂等重放命中既有成功回执时不重复消费）。
- 台账：`CONSUME` 行，`request_id='CPN:CONSUME:{orderId}'`；`used_at = 渠道 paidAt`。
- 迟到支付（`markLate`）不调用 consume：券在关闭时已释放，迟到支付链路对券整体 NOOP（SSOT §二十二）。

## 5. 释放（支付超时关闭，39 号关闭事务内）

`CouponCommandApi.release(CouponReleaseCommand)`，由 ORDER 到期关闭（`close`）在取消订单的同一
guard 事务内调用：

- 前置分支与 §4 相同：`discount_amount > 0` → release；`= 0` → 既有 `requireNoCoupon` 无券证明。
- CAS：`UPDATE ... SET status=(CASE WHEN expire_at > now THEN 'AVAILABLE' ELSE 'EXPIRED' END),
  order_id=NULL, frozen_at=NULL, freeze_expire_at=NULL, version=version+1
  WHERE id=:id AND status='FROZEN' AND order_id=:orderId`，受影响行数≠1 即 503。
- 释放不改 `expire_at`（24 小时延长规则仅适用于退款返券，SSOT §9.1）；释放后的券可立即用于下一单
  （SSOT §二十二"不抢回、不重新冻结已经释放的优惠券"指的是迟到支付场景不得回收，正常释放即恢复可用）。
- 台账：`RELEASE` 行，`request_id='CPN:RELEASE:{orderId}'`，`to_status` 记录实际落点（AVAILABLE/EXPIRED）。

## 6. 返券（退款成功，消费 RefundSucceededEvent.v1）

新事件消费者 `COUPON_REFUND_RESTORE`（pet-coupon-biz，经 Transactional Outbox 分派，注册开关
`pet.coupon.lifecycle.enabled`，默认 false；outbox 关闭时事件等待不丢失）：

- 载荷精确键集：`refundOrderId/refundNo/orderId/refundType/refundSource/refundAmount/
  originalPaidAmount/channelRefundNo/succeededAt`（08 号 §8）；严格 exact-key 解码，失败 503 重试。
- 判定（按 SSOT §九 + §二十二）：
  - `refundSource = 'LATE_PAYMENT_TIMEOUT'` → NOOP（该来源券从未被本订单消费或早已释放；不返券、
    不二次释放；claim 后直接成功）。
  - `refundType != 'FULL'`（部分退款，仅运营售后裁决）→ NOOP（部分退款不返券）。
  - `refundType = 'FULL'` 且存在本订单消费的券（`coupon_instance.order_id = orderId AND status='USED'`）：
    - `expire_at > succeededAt`：恢复 `AVAILABLE`，清 `order_id/used_at`；
      若 `succeededAt + 24h > expire_at` 则 `expire_at = succeededAt + 24h`（SSOT §9.1 延长规则）。
    - `expire_at <= succeededAt`：券已过有效期，不恢复、保持 `USED`（SSOT §9.1"仍处于有效期"前提）。
  - 本订单无券（`order_id` 无 USED 行）→ NOOP（无券订单全额退款无需返券）。
- 幂等：事件 claim（`JdbcOutboxConsumeGuard`，consumerName `COUPON_REFUND_RESTORE`）+ 台账
  `REFUND_RESTORE` 行 `request_id='CPN:RESTORE:{refundOrderId}'` uk + 状态 CAS 三重防护；重复投递、
  重放、与后续用券竞态均收敛为一次恢复或安全 NOOP；任何账实分歧（CAS 不中且无台账行）503 失败关闭
  交运维，不猜测。
- 事务：消费者自带 REQUIRES_NEW RC 事务；券域行均为用户维度，不涉及门店协调表，不需要 store guard
  （与 freeze/consume/release 的店 guard 事务相区分，随 PR 披露）。
- 核销与退款关系不变：`refund_order` 创建后禁止核销、核销成功使未履约售后失效等既有规则不受本契约影响；
  售后全额退款按 SSOT §9.1 同样返券（PRD"售后存在退款不返还"与 SSOT 冲突，按资料优先级以 SSOT 执行，
  CCR-CPN-LIFECYCLE-RULES-001 §2 记录）。

## 7. C 端 HTTP 面

不新增路由（契约 smoke 家族计数不变）：

- 选券列表 = 既有 `GET /api/v1/c/coupons?status=AVAILABLE`（10 号 §3.15.1，D2 冻结态永不出现；
  服务端权威过滤可用性）。客户端门槛过滤仅作展示提示，最终资格以冻结时服务端判定为准
  （409 `COUPON_NOT_AVAILABLE`，前端按 23 号 retire 语义释放槽位、去券重选）。
- 下单提交 = 既有 `POST /api/v1/c/orders`，`couponInstanceId` 键已存在于 11 号 `CreateOrderRequest`
  （可选可空）。本契约起该键由"如实透传 503 失败关闭"改为真实冻结语义：
  - 冻结成功：回执 `payAmount` 为折后应付（两位小数字符串），`paymentExpireAt` 不变；
  - 券不可用/并发失败：409 `COUPON_NOT_AVAILABLE`，不创建业务行（幂等键可携新载荷重试）；
  - 未装配券提供器（`pet.coupon.lifecycle.enabled=false`）：维持 503 `COMMON_DEPENDENCY_UNAVAILABLE`
    失败关闭（38 号既有语义）。
- 券展示字段（amountOff/thresholdAmount/scopeSummary/typeLabel/validTo）语义沿用 §3.15.1 D1 投影；
  rule_json 结构自本契约冻结（§2），投影键不再允许为 null 之外的新形态。
- 12 号错误码表无新增码：复用 `COUPON_NOT_AVAILABLE`（冻结资格/并发失败）、`COUPON_NOT_FOUND`
  （C 端读侧既有 404 语义不变）。

## 8. 事件与任务族对齐

- 不新增事件类型、不新增任务类型：冻结/消费/释放全部内联于 ORDER 既有 guard 事务（38/39/41 号时点），
  返券消费既有 `RefundSucceededEvent.v1`；OrderPaidEvent.v1 的 `couponInstanceId` 字段自本契约起为
  真值（有券订单）或 null（无券订单），字段集不变，无消费方破坏性变化。
- `OrderPaymentExpiredEvent.v1`（券列消费者含 coupon）仍维持未交付状态，不在本契约范围。

## 9. 验收与遗留

- QA-002 券并发：真实 MySQL 下同券并发冻结最多一单成功（CPN-004）；冻结/消费/释放/返券幂等重放；
  迟到支付来源 NOOP（CPN-005）。
- Schedule 容量并发沿用既有覆盖（ScheduleSoldOutLinkMySqlTest 双用户抢位/超卖拒绝、
  OrderCreationMySqlTest 同 key 并发一单回执），本轮核验无缺口、不重复建设。
- 遗留（披露）：券模板管理/发放渠道不在 V1；类目级 scope 结构化待模板管理；生产迁移未执行；
  默认开关保持关闭；积分扣回消费者未交付（PTS 系列）。
