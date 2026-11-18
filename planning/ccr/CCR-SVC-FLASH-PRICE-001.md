# CCR-SVC-FLASH-PRICE-001：服务限时秒杀/限时价格（P2）

- 状态：**APPROVED / 2026-11-18**（依据 [41 号裁决补充](../../docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md) §4；PLAN §7 建议稿已定稿）
- 关联契约：[77 号服务限时秒杀契约](../../docs/04-api/77-Service-Flash-Price-Contract-v0.1.md)；修订 [38 号](../../docs/04-api/38-Atomic-Booking-Create-Contract-v0.1.md)、[07 号](../../docs/04-api/07-内部API-Contract-v0.6.md)、[10 号](../../docs/04-api/10-HTTP-API-Contract-v0.4.md)、[74 号](../../docs/04-api/74-Service-Catalog-Page-Contract-v0.1.md)、[75 号](../../docs/04-api/75-Payment-Center-Contract-v0.1.md)、[12 号](../../docs/04-api/12-Error-Code-Registry-v0.5.md)
- 关联设计：Figma `18:9537`（秒杀列表）、`18:9694`（创建秒杀）
- 是否涉及产品裁决：**是**（41 号 §4 已批；本 CCR 固化技术决策，不再向用户提问）

## 1. 问题

1. V1 无服务限时价格能力；Figma 商家端有秒杀页面但无后端支撑。
2. 下单价格权威在 `BookingServiceFacts.price`（服务表 `sale_price`），无闪价/原价/优惠来源快照，无法支持分账基数扣除与 C 端展示。
3. 配额（总量/每人限领）需要与订单支付截止时间联动的预占/消费/释放机制；现有订单过期链路（39 号）不感知营销配额。

## 2. 范围

### 2.1 做

- `pet-service` 新增 `service_flash_price` 四表 + 商家创建/编辑/提交/取消 + 运营审核（approve/reject/pause/end）。
- 读侧价格解析：服务卡/详情/`BookingServiceFacts` 返回生效价与 `priceSource`。
- 下单事务内预占（`ServiceFlashPriceReservationApi.reserve`）；`OrderPaidEvent.v1` 消费置 CONSUMED；service 自有 durable task 释放过期预占。
- `pet_order`/`order_service_snapshot` 价快照四字段；38 号下单内核接入。
- 小程序商家端 flash 列表/编辑页 + 营销 hub 卡解封；C 端展示闪价；运营后台审核页。
- 分账基数契约口径（75 号 §5.5），不实现支付中心阶段 2。

### 2.2 不做

- 新订单类型/支付方式；30 分钟确认/预约/排期/退款/售后/核销规则改动。
- 使用门槛、活动头图上传、买赠/套餐/次卡、拼团、转化率统计。
- 商家直接发布；商家改价入口给运营。
- 读取/修改其他模块表；跨模块 Mapper 访问。

## 3. 决策（固定）

1. 秒杀=服务限时价格；价格权威在 `pet-service`；订单只消费权威值+落快照。
2. 状态机：`DRAFT → PENDING_REVIEW → SCHEDULED/ACTIVE`；`REJECTED/CANCELED/PAUSED/ENDED`；商家仅可 submit/cancel（未审/驳回）。
3. `flashPrice = round(listPrice × discountRate / 10, 2, HALF_UP)`，>0 且 < 当前价；折扣率最多 1 位小数；商家不传价格，服务端计算。
4. 同服务同时最多一个活跃窗口（PENDING_REVIEW/SCHEDULED/ACTIVE/PAUSED 相交 → 409）；重叠校验在服务行锁的串行化区间内。
5. 预占在订单创建事务内：`reserve(flashPriceId, orderId, userId, expireAt=holdExpireAt)`；任何失败整体回滚。
6. 支付成功 `OrderPaidEvent.v1` → CONSUMED（幂等，迟到 NOOP）；过期 `FLASH_PRICE_RELEASE_EXPIRED`（owner=SERVICE，`expire_at < now-5min`）→ RELEASED + `sold_count-1`；退款不回补。
7. 每人限领按 RESERVED/CONSUMED 计；RELEASED 不计。
8. 券在闪价后计算（61 号）；分账基数=原价−闪价优惠−商家券优惠（契约口径）。
9. 开关 `pet.service.flash-price.enabled` 默认 false；关闭=零影响。
10. 运营动作码新增 `service.flash-price.read/review`。

## 4. Schema/API/Event 变更

- 新增：SQL73（四表）、`db/service-migration/V31__service_flash_price.sql`、`db/order-migration/V31__order_flash_price.sql`（pet_order/order_service_snapshot 四列）、77 号契约。
- 修订：06 号（pet_order/snapshot 列）、07 号（BookingServiceFacts/ServiceSnapshotDTO 字段）、10 号（C 端订单详情字段）、38 号（价格来源与预占注记）、74 号（服务卡闪价展示规则）、75 号（§5.5 分账基数）、12 号（错误码+动作码）、设计源登记表、`E2eCoreLoopServer.loadSchemas`（加 SQL73）。
- Event：消费既有 `OrderPaidEvent.v1`；**不新增事件**；不新增订单取消事件。

## 5. 订单快照口径

`price`=生效价（闪价时即闪价）；`original_price`=listPrice；`flash_discount=original_price−price`（无闪价 0）；`price_source=LIST/FLASH`；`flash_price_id`。`original_amount`=生效价（既有语义不变）；`pay_amount=price−券`。存量数据迁移后 `price_source='LIST'`、`flash_discount=0.00`、`original_price=original_amount`。

## 6. 兼容与回滚

- 开关默认关闭；迁移 opt-in；无闪价路径行为字节级不变。
- 回滚：关开关即可断开新预占；已产生订单快照列无回写需求（LIST 行为不变）。

## 7. 测试

- MySQL 集成：状态机全迁移、重叠 409（并发 2 请求成功 1）、审核时价格重校验、reserve 并发配额（总量 1 抢 2 成功 1）、限领 409、CONSUMED/RELEASED 幂等、sold_count 守恒、开关关闭零影响。
- E2E（18082/新库）：商家创建→运营审核→C 下单闪价→沙箱支付→CONSUMED；另造不支付单→过期 RELEASED；断言快照/支付金额/名额。
- 前端：typecheck/单测/build/package-check；admin build/boundaries。

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 38 号内核改动引入回归 | reserve 是加法步骤、无闪价零调用；全量 order 测试 + E2E |
| 预占与订单过期双权威漂移 | service 不读订单表，按自身 expire_at 释放；5 分钟容错 + 幂等 |
| 闪价与服务改价竞争 | reserve/审核均重读当前价校验 flashPrice<当前价 |
| 并发超卖 | 行锁 + sold_count 条件更新 + UNIQUE(order_id) |
| P1 页面回归 | 仅改 hub 一张卡的 available/badge/url |
