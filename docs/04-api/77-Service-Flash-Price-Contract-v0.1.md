# 77-服务限时秒杀（限时价格）契约 v0.1

状态：**ACCEPTED_CONTRACT / 2026-11-18**。依据 [41 号裁决补充](../01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md) §4（APPROVED）、[CCR-SVC-FLASH-PRICE-001](../../planning/ccr/CCR-SVC-FLASH-PRICE-001.md)（APPROVED）；修订 [38 号原子下单](38-Atomic-Booking-Create-Contract-v0.1.md)、[07 号内部 API](07-内部API-Contract-v0.6.md)、[10 号 HTTP](10-HTTP-API-Contract-v0.4.md)、[74 号服务目录页](74-Service-Catalog-Page-Contract-v0.1.md)、[75 号支付中心](75-Payment-Center-Contract-v0.1.md) §5.5、[12 号错误码](12-Error-Code-Registry-v0.5.md)。
Schema：[SQL73](../03-database/73-Service-Flash-Price-Schema-v0.1.sql)；迁移 `db/service-migration/V31__service_flash_price.sql` + `db/order-migration/V31__order_flash_price.sql`。

## 1. 范围与开关

秒杀是**服务项目的限时价格**，不是新订单类型、不是套餐/次卡/商品；仍单次服务、必须预约。价格权威在 `pet-service`；订单只消费服务域返回的权威价并落快照。

| 开关 | 默认 | 作用 |
|---|---|---|
| `pet.service.flash-price.enabled` | `false` | 关闭时：本契约所有 HTTP 端点不注册、reserve 返回 `FLASH_PRICE_DISABLED`（订单走 LIST）、读侧不返回闪价字段、OrderPaidEvent 消费者与释放任务不注册、`service_flash_price` 迁移仍可执行（opt-in 数据面） |

无闪价/开关关闭时，服务读取、下单、支付、退款行为与现状完全一致（`priceSource=LIST`）。

## 2. 数据模型

四张表归属 `pet-service` 模块，DDL 见 SQL73（与 `db/service-migration/V31__service_flash_price.sql` 一致）：`service_flash_price`、`service_flash_price_order`（预占/消费/释放）、`service_flash_price_audit`（append-only，`request_id` 唯一）、`service_flash_price_command`（requestId 幂等命令表，`IN_PROGRESS/SUCCEEDED`）。

### 2.1 `service_flash_price` 字段

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | String(API) | Snowflake | 主键 |
| serviceId/merchantId/storeId | String | Snowflake | 服务必须属于该门店且 ACTIVE |
| title | String | 1..100 码点 | 展示名，默认服务名 |
| description | String/null | ≤300 码点 | 活动说明 |
| priceMode | enum | `DISCOUNT` | P2 固定折扣；「限时折扣/限时秒杀」仅为前端标签 |
| discountRate | number | (0,10)，最多 1 位小数，如 `8.8` | 商家输入折扣 |
| flashPrice | number | >0 且 < listPriceSnapshot | 服务端计算：`round(listPrice × discountRate / 10, 2, HALF_UP)`；商家不可直接传 |
| listPriceSnapshot | number | >0 | 审核通过时快照的服务原价 |
| startAt/endAt | ISO-8601 | `startAt < endAt` | 生效窗口 |
| totalQuota | int/null | null 或 1..1,000,000 | null=不限总量 |
| perUserLimit | int/null | null 或 1..1,000 | null=不限每人 |
| soldCount | int | ≥0 | RESERVED+CONSUMED 计数（= totalQuota − remaining） |
| status | enum | 见 §3 | |
| rejectReason | String/null | 1..500 码点 | 驳回时必填 |
| version | int | ≥1 | 乐观锁 |
| createdByType/createdById | | MERCHANT/ADMIN | 创建者 |

商家请求不得携带 `status/merchantId/storeId/flashPrice/listPriceSnapshot/soldCount`；服务端固定。

### 2.2 状态机

```text
DRAFT ──submit──> PENDING_REVIEW ──approve(startAt>now)──> SCHEDULED ──(到 startAt)──> ACTIVE
                        │──approve(startAt<=now)──> ACTIVE
                        │──reject──> REJECTED
                        └──cancel(商家)──> CANCELED
SCHEDULED/ACTIVE ──pause(运营)──> PAUSED ──approve(运营,恢复)──> SCHEDULED/ACTIVE
SCHEDULED/ACTIVE/PAUSED ──end(运营)──> ENDED（到 endAt 后读侧视为结束）
DRAFT/REJECTED ──cancel(商家)──> CANCELED
```

- 商家可编辑 `DRAFT/REJECTED`（REJECTED 编辑后须重新 submit）；`PENDING_REVIEW` 后不可编辑。
- `SCHEDULED → ACTIVE`、窗口到期 `ENDED` 由读侧按时间判定（`now >= startAt` 即 ACTIVE 语义；`now >= endAt` 即结束），不强制落库迁移；可选 durable task 批量归档。
- 商家不可发布/暂停/结束；运营不可改价（只能驳回让商家改）。
- 运营动作全部 append-only 审计（action_type/version 变化/原因/requestId/traceId）。

## 3. 商家端接口

前缀 `/api/v1/merchant/services/{serviceId}/flash-prices`。鉴权：MINIAPP Bearer（C 会话）+ 门店 OWNER（员工不可写）；服务必须属于本人门店且 ACTIVE，否则 404/409。所有写请求带 `X-Request-Id`（幂等）与 `expectedVersion`（乐观锁）。开关 `pet.service.flash-price.enabled=true` 时才注册。

### 3.1 列表

`GET /api/v1/merchant/services/{serviceId}/flash-prices?status=&page=&pageSize=`

分页沿用 07 号规范（page 1..10000、pageSize 1..50，默认 1/20；`created_at DESC, id DESC`）。data.items[] 字段：§2.1 全量 + `participantCount`（该场次 `service_flash_price_order` 中 RESERVED/CONSUMED 去重用户数）。

### 3.2 创建（DRAFT）

`POST /api/v1/merchant/services/{serviceId}/flash-prices`

```json
{
  "requestId": "…",
  "title": "洗护秒杀",
  "description": "…",
  "priceModeLabel": "FLASH_SALE",
  "discountRate": 8.8,
  "startAt": "2026-11-20T10:00:00Z",
  "endAt": "2026-11-20T12:00:00Z",
  "totalQuota": 20,
  "perUserLimit": 1
}
```

- `title` 可省略（默认服务名）；`priceModeLabel` ∈ `LIMITED_DISCOUNT`（限时折扣）/ `FLASH_SALE`（限时秒杀），仅展示标签，服务端统一落 `priceMode=DISCOUNT`；其他值 400。
- 校验：`discountRate` (0,10) 且最多 1 位小数；窗口重叠检查（同 service 的 `PENDING_REVIEW/SCHEDULED/ACTIVE/PAUSED` 与新区间相交 → 409 `FLASH_PRICE_WINDOW_OVERLAP`）；重叠检查必须在锁定服务行的串行化区间内完成（FOR UPDATE 或等价），不能只靠先查后插。
- 响应 data：完整记录（含服务端计算的 `flashPrice` 预览）。
- 幂等：同 requestId 重放返回首次结果；同 requestId 异参 → `IDEMPOTENCY_KEY_CONFLICT`。

### 3.3 编辑

`PUT /api/v1/merchant/services/{serviceId}/flash-prices/{id}`

body 同创建（不带 requestId 语义字段重复说明：仍需 `X-Request-Id` 头 + `expectedVersion`）。仅 `DRAFT/REJECTED`；提交后不可编辑。重新校验全部业务规则（窗口重叠对"自身以外的活跃窗口"判定）。

### 3.4 提交审核

`POST /api/v1/merchant/services/{serviceId}/flash-prices/{id}/submit`（body: `expectedVersion`）

`DRAFT/REJECTED → PENDING_REVIEW`。

### 3.5 取消

`POST /api/v1/merchant/services/{serviceId}/flash-prices/{id}/cancel`（body: `expectedVersion`）

仅 `DRAFT/REJECTED/PENDING_REVIEW`；已生效（SCHEDULED/ACTIVE/PAUSED）不可取消，只能由运营 end。

## 4. 运营端接口

前缀 `/api/v1/admin/service-flash-prices`。鉴权：ADMIN_WEB Bearer + 动作码 `service.flash-price.read`（读）/ `service.flash-price.review`（approve/reject/pause/end；新动作码登记入 `AdminPermissionEvaluator.DEPLOYED_ACTIONS` 与 12 号）。单运营、数据范围 fail-closed 沿用 SSOT §24。

### 4.1 列表

`GET /api/v1/admin/service-flash-prices?status=&page=&pageSize=`

data.items[]：§2.1 全量 + 服务名/门店名/商家名 + `participantCount`。按 `updated_at DESC, id DESC`。

### 4.2 审核

`POST /api/v1/admin/service-flash-prices/{id}/approve`（body: `expectedVersion`）

- `PENDING_REVIEW → SCHEDULED(startAt>now)/ACTIVE(startAt<=now)`；`PAUSED → 恢复原 SCHEDULED/ACTIVE`。
- 审核时重新校验：服务归属/服务仍 ACTIVE/窗口重叠/折扣/价格；重新读取服务**当前价**计算并落 `list_price_snapshot`，若 `flashPrice >= 当前价` → 409 `FLASH_PRICE_INVALID`（提示商家调整）。
- `POST .../reject`（body: `expectedVersion, reason` 1..500）→ `REJECTED`。
- `POST .../pause`：`SCHEDULED/ACTIVE → PAUSED`。暂停期间 reserve 拒绝、读侧不展示闪价。
- `POST .../end`：`SCHEDULED/ACTIVE/PAUSED → ENDED`；已有 RESERVED 预占按原 `expire_at` 释放，已支付不受影响。

## 5. C 端读模型（不新增端点）

`ServiceSnapshotDTO`（07 号 §5.1）与 C 端服务列表/详情响应（74 号 §5）增加可空字段；无闪价/开关关闭时字段为 `null`，`priceSource=LIST`，行为与现状一致：

| 字段 | 类型 | 说明 |
|---|---|---|
| priceSource | String | `LIST` / `FLASH` |
| listPrice | number/null | 闪价生效时的服务原价（划线价） |
| flashPrice | number/null | 生效闪价 |
| flashStartAt/flashEndAt | ISO-8601/null | 窗口 |
| flashRemaining | int/null | `totalQuota - soldCount`；无总量时 null |

生效规则：`status` 语义为 SCHEDULED/ACTIVE（或 PAUSED 之外）且 `startAt <= now < endAt` 且 `flashPrice < 服务当前价` → `priceSource=FLASH`；否则按 LIST。闪价在窗口内但 `flashPrice >= 当前价` → 按 LIST 返回且不展示闪价，不阻断服务读取。`price`（salePrice）字段本身在闪价生效时返回闪价值。

## 6. 下单与配额（配合 38 号）

### 6.1 内部 reserve API

`pet-service-api` 新增 `ServiceFlashPriceReservationApi`：

```java
ReservationResult reserve(ReserveCommand command);
// ReserveCommand(CommandContext context, String flashPriceId, String orderId, String userId, OffsetDateTime expireAt)
// ReservationResult(String flashPriceId, BigDecimal price, BigDecimal listPrice, BigDecimal flashDiscount, String status)
```

- 调用方：`OrderCreationService.execute` 在 `reservations.hold` 成功后、券冻结前调用；`expireAt = held.holdExpireAt()`。
- 行为：锁 `service_flash_price` 行 → 校验 status∈{SCHEDULED,ACTIVE}（含时间窗）→ 校验剩余（`totalQuota` 有值时 `soldCount < totalQuota`）→ 校验每人限领（同 `(flashPriceId,userId)` 的 RESERVED/CONSUMED 计数）→ `sold_count+1` + 插 `service_flash_price_order(RESERVED, expire_at, reserved_price)` → 返回权威价格。
- 失败：`FLASH_PRICE_NOT_FOUND`(404)/`FLASH_PRICE_STATE_NOT_ALLOWED`(409)/`FLASH_PRICE_QUOTA_EXCEEDED`(409)/`FLASH_PRICE_LIMIT_REACHED`(409)；订单创建事务整体回滚（预占随事务消失）。
- reserve 必须与订单创建在同一 DataSource 事务内（同库串行化）。

### 6.2 支付成功

service 模块消费既有 `OrderPaidEvent.v1`（Transactional Outbox 订阅，owner=SERVICE）：按 `orderId` 找 RESERVED → 置 `CONSUMED(consumed_at)`。幂等：已 CONSUMED 直接 NOOP；不存在或已 RELEASED（迟到支付）→ NOOP，不回补 `sold_count`。订单迟到支付退款按既有规则处理，与本模块无关。

### 6.3 过期释放（durable task）

任务类型 `FLASH_PRICE_RELEASE_EXPIRED`，owner=SERVICE。扫描 `service_flash_price_order` 中 `RESERVED AND expire_at < now - 5min`，逐行 CAS 置 `RELEASED(released_at)` 并 `sold_count-1`（行锁 + `version` CAS）。分批（≤500/批）、幂等、可重入；不读订单表、不新增订单取消事件。订单过期关闭由既有 39 号链路完成，双方按各自权威行结算（间隔 5 分钟容错）。

### 6.4 退款

已支付订单退款**不恢复**名额；CONSUMED 不变。不消费 `RefundSucceededEvent` 做配额回补。

### 6.5 订单快照

`pet_order` 新增：`original_price DECIMAL(18,2) NOT NULL`（下单时服务原价）、`flash_discount DECIMAL(18,2) NOT NULL DEFAULT 0.00`（原价−闪价）、`price_source VARCHAR(16) NOT NULL DEFAULT 'LIST'`、`flash_price_id BIGINT NULL`。存量迁移后 `price_source='LIST'`、`flash_discount=0.00`、`original_amount=original_price` 语义对齐。

`order_service_snapshot` 新增 `service_list_price DECIMAL(18,2) NULL`、`service_price_source VARCHAR(16) NULL`、`service_flash_price_id BIGINT NULL`；`snapshot_json` 同步记录。`price`（既有列）= 生效价（闪价时即闪价）；`original_amount` = 生效价；`pay_amount = price − 券抵扣`。

`BookingServiceFacts`（38 号）新增：`listPrice`、`priceSource`、`flashPriceId`、`flashDiscount`。`price` = 生效价（闪价时为闪价）。`readCurrentService` 在闪价生效时返回 `price=flashPrice`。

### 6.6 价格叠加

闪价是基准价；券在闪价后计算（61 号 `amountOff < orderAmount`，折后应付 >0）；P2 每单仍最多一张券。分账口径见 75 号 §5.5（P2 只保证订单快照字段，不实现支付中心阶段 2）。

## 7. 错误码（登记 12 号）

| Code | HTTP | 含义 |
|---|---|---|
| `FLASH_PRICE_NOT_FOUND` | 404 | 闪价记录不存在或不属于该服务/门店 |
| `FLASH_PRICE_STATE_NOT_ALLOWED` | 409 | 状态机不允许该动作（含 PAUSED/窗口外 reserve） |
| `FLASH_PRICE_WINDOW_OVERLAP` | 409 | 同服务活跃窗口重叠 |
| `FLASH_PRICE_QUOTA_EXCEEDED` | 409 | 总量售罄 |
| `FLASH_PRICE_LIMIT_REACHED` | 409 | 超出每人限领 |
| `FLASH_PRICE_INVALID` | 409 | 折扣/价格约束不满足（flashPrice ≥ 当前价等） |
| `FLASH_PRICE_DISABLED` | 503 | 开关关闭时内部 reserve 被调用（fail-closed，不应发生） |

通用复用：`COMMON_INVALID_ARGUMENT`(400)、`COMMON_NOT_FOUND`(404)、`COMMON_CONFLICT`(409)、`IDEMPOTENCY_KEY_CONFLICT`(409)、`COMMON_FORBIDDEN`(403)。

## 8. 权限与审计

- 商家：C 会话 + 门店 OWNER；员工不可写（读也仅 OWNER 可见营销数据，沿用 P1）。
- 运营：`service.flash-price.read/review` 动作码；超管默认全量。
- 所有写：`X-Request-Id` 终端幂等（`service_flash_price_command`）+ 乐观 `version` + `service_flash_price_audit` append-only（operator/requestId/traceId/reason/fromStatus/toStatus）。

## 9. 前端（摘要）

- 小程序商家端：`/merchant/pages/marketing/flash/index`（Figma `18:9537` 列表）、`/merchant/pages/marketing/flash/edit`（`18:9694` 创建/编辑）；「立即上架」→「提交审核」；不展示使用门槛/头图上传；优惠形式 chips 仅标签；所有数字来自 API。营销 hub「限时秒杀」卡从「筹备中」改为可用。
- C 端：服务卡/详情/订单确认展示限时价、原价划线、倒计时、剩余名额（有总量时）；订单确认不传价格。
- 运营后台：秒杀审核列表/详情 + 通过/驳回/暂停/结束；不新增改价入口。

## 10. 测试与验收

见 CCR-SVC-FLASH-PRICE-001 §7 与 HANDOFF §9.2 十场景。关键不变量：无闪价路径行为不变；下单事务原子性（预占随事务回滚）；配额并发安全；退款不回补；迟到支付不恢复；开关关闭零影响。
