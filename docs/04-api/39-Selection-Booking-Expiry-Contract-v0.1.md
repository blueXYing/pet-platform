# 选窗与预约到期协调 v0.1

状态：实现中、默认关闭，验收以本轮 PR 及集成回执为准。来源为已批准 34/36/37/38 号、最终 PRD 十分钟待支付占位和 Scheduler 09 §11 的支付未知保护；不修改产品规则，不开放消费者创建订单，不执行生产迁移。

## 1. 原窗选择投影

`pet.schedule.query.enabled=true` 且 `pet.schedule.selection.enabled=true` 时，既有登录态 GET `/api/v1/c/services/{serviceId}/availability` 的 item 增加正 Long 十进制字符串 `windowId`、`kind=GENERAL/PICKUP/RETURN`，其余六字段和日期范围、时间格式、no-store、鉴权规则保持。关闭 selection 时仍是旧六字段，拒绝 kind 参数。开启后允许可选 kind；未知值、重复参数、与服务履约方式不符返回 400。

SERVICE 公共事实确定服务可见性和履约方式。到店只输出 GENERAL，接送只输出 PICKUP/RETURN；旧接送 GENERAL 不猜测方向、不复制、不释放。SCH 读取自己的窗口、父预约及 claim，TEMP_LOCKED/CONFIRMED 按真实 claim 统计占用。过了截止时间但尚未成功关闭的 TEMP_LOCKED 仍占容量；接送两段之间不因父区间包络而占用。

每条仍代表原始营业窗口，不代表某个服务开始分钟或接送组合已通过可行性证明。长到店窗口的整窗剩余量是保守展示；不能据此宣称窗口内每个更短区间都不可约。接送两窗单独有余量也不等于同一个员工能覆盖两段。最终创建始终以 36/37/38 号当前事实和完整人员证明为准；本轮不切固定槽、不新增开始时间枚举算法。

## 2. 无支付、无券的内部到期关闭

`OrderExpiryApi.expire` 仅 SYSTEM 内部调用，无 HTTP 路由。命令携带 orderId、reservationId、原始预约版本 0、精确到毫秒的 expectedPaymentExpireAt；requestId 固定为 `TASK:RESERVATION_HOLD_EXPIRE:{reservationId}:0`。同一代不能换对象或截止时间执行。

独立顶层 READ_COMMITTED 事务内，ORDER 先读取所属店、获取既有同店 guard，再锁当前订单、核对绑定、原截止时间及 PENDING_PAYMENT/INIT/UNVERIFIED。使用数据库 UTC 当前时间；未到期返回 NOT_DUE，已处理或已进入已知后续阶段返回 NOOP，未知或矛盾事实 503。

PAYMENT `BookingPaymentExposureApi.requireNoPayment`、COUPON `BookingCouponExposureApi.requireNoCoupon` 同 guard、同 DataSource 读取各自持久表。只接受确实没有支付记录、没有关联券实例或流水；任意支付记录（包括 INIT/FAILED/CLOSED）、券记录、孤立支付流水或读取失败均 503 保留占用。不能把“不是 PAID”当“未支付”，不能用 discount=0 代替 Owner 事实。此能力不判断渠道失败、不调用渠道、不冻结/解冻优惠券、不处理退款。后续支付及券写入必须遵守同一 guard，接入前须扩展其 Owner 协调契约并验收。

已确认无外部占用后，同事务将 ORDER 改为 CANCELED、记录 SYSTEM/PAYMENT_TIMEOUT 状态日志，调用 SCH 将原 TEMP_LOCKED 版本 0 改为 EXPIRED 并写 SYSTEM 审计。claim 不删除。SCH 提交前通过 ORDER 公共事实验证对应订单已取消且无需保护；独立过期、绑定不符、审计或任意后续失败均回滚。已处理重放确认原日志及 SCH EXPIRED，不重新写日志或释放。

## 3. 持久任务、补投与启用

创建订单时，在 hold、订单、快照、审计、成功回执的同一事务里提交 AsyncTask：owner ORDER、type RESERVATION_HOLD_EXPIRE、bizType RESERVATION、bizId reservationId、expectedVersion 0、key `RESERVATION_HOLD_EXPIRE:{reservationId}:0`。payload 为 orderId/reservationId/expectedReservationVersion/expectedPaymentExpireAt；首调 execute_at 为原截止时间。任务提交失败则创建整体回滚。

SQL39 保留 submitted_execute_at 作为首次计划时间，execute_at 可随重试变化；enqueueAt 重放必须初调时间和 payload 一致。此任务始终使用 enqueueAt，不与历史立即 enqueue 混用同 key。新的 worker 工厂只领取注册的 taskType，空 handler 集合拒绝启动。原无过滤基础设施构造入口留给已有完整 dispatcher 的测试和集成，不用于本轮部分 worker。

内部能力开关 `pet.order.expiry.enabled` 和后台轮询开关 `pet.order.expiry.worker.enabled` 均默认 false，依赖 SQL06/13/37/38/39、真实 foundation 与 ID provider。FAST_INTERNAL 退避 5s/15s/60s/5m/15m/30m，最多重试 20 次；异常支付/券事实最终 DEAD 仍保留业务占用，须运维核验，不假装自动支付查询已实现。任务租约丢失后的重派依靠稳定代际命令安全重放。

PR86 创建但未登记任务的历史订单，可经 SYSTEM 内部分页补投 API 按 orderId 游标扫描，每笔重新加 guard、读当前订单后调用同一幂等提交器。补投不重启 DEAD、不修改业务截止时间，也不自动运行生产补偿。启用前需核验 SQL 和历史任务覆盖；本轮在隔离库验收。

Scheduler 09 §12 旧“无需查询订单直接 EXPIRED”不适用于此类原子创建订单，按 36/38 号及本节执行。PAYMENT_EXPIRE 仍以 paymentId 为目标，本轮不挪用其语义；不新增集成事件。

## 4. 交付边界

验收覆盖真实 MySQL 原子关闭/释放、未到期、重放、支付/券未知保留、异常回滚、计划时间与任务幂等、投影双时段和 HTTP 兼容。完整支付、优惠券结算、备注审核、消费者创建页面和生产发布仍未交付。各默认开关保持关闭；选窗投影和内部到期验证不等于端到端结算已上线。
