# 79-商家自建优惠券契约 v0.1

状态：**ACCEPTED_CONTRACT / 2026-11-27**。依据 [41 号裁决补充](../01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md) §5（APPROVED）、[CCR-MKT-MERCHANT-COUPON-001](../../planning/ccr/CCR-MKT-MERCHANT-COUPON-001.md)。修订 [61 号券生命周期](61-Coupon-Lifecycle-Contract-v0.1.md) §2/§3、[72 号券活动与领取](72-Coupon-Campaign-Contract-v0.1.md) §2/§3/§7、[75 号支付中心](75-Payment-Center-Contract-v0.1.md) §5.5、[78 号结算](78-Payment-Settlement-Contract-v0.1.md) §3、[12 号错误码](12-Error-Code-Registry-v0.5.md)。
Schema：[SQL84](../03-database/84-Merchant-Coupon-Schema-v0.1.sql)；迁移 `db/marketing-migration/V38__merchant_coupon.sql` + `db/order-migration/V32__order_coupon_funding.sql`。

## 1. 范围与开关

商家自建优惠券 = **商家出资的无门槛券/满减券**。券实例仍走 61 号冻结/消费/释放/返券；领取沿用 72 号主动领取。不新增券类型、不新增订单类型。

| 开关 | 默认 | 作用 |
|---|---|---|
| `pet.coupon.merchant.enabled` | `false` | 关闭时：本契约 §3/§4 的 HTTP 端点不注册、C 端列表不返回商家券活动；已存在的券实例仍按 61 号处理（冻结闸门照常生效）。`application-dev/test/prod.yml` 为 `true` |

## 2. 数据模型

### 2.1 既有表增量

| 表 | 增量 |
|---|---|
| `coupon_template` | `owner_type`（`PLATFORM/MERCHANT`，默认 `PLATFORM`）、`owner_merchant_id`（平台券为 NULL）、`funding_type`（出资方，默认 `PLATFORM`）。商家模板必须 `owner_type=MERCHANT` 且 `funding_type=MERCHANT`。`rule_json` 四键不变 |
| `coupon_campaign` | `owner_type`、`owner_merchant_id`（平台活动为 `0`）、`owner_store_id`（NULL=全商家门店）、`created_by_type`、`description`（≤300）、`reject_reason`、`submitted_at`、`reviewed_at`、`reviewed_by_type`、`reviewed_by_id`；`status` 增加 `PENDING_REVIEW/REJECTED/CANCELED`；名称唯一键由全局改为**按归属商家**，且只约束未终结的活动——已取消/已结束的活动释放名称（72 号：同期不重名）；平台活动之间仍不重名 |
| `coupon_claim` | `claim_no`（同一用户在同一活动的第几次领取，从 1 起）；唯一键由 `(campaign_id,user_id)` 改为 `(campaign_id,user_id,claim_no)` |
| `pet_order` | `coupon_funding_type`（`PLATFORM/MERCHANT`，无券为 NULL）、`merchant_coupon_discount`（商家券面额，无商家券为 `0.00`） |

### 2.2 新增表（归属 `pet-coupon`，DDL 见 SQL84）

- `merchant_coupon_budget`：每个商家券活动一行，`budget_limit/issued_amount/used_amount/version`。
- `merchant_coupon_budget_ledger`：append-only 预算账本，与 `coupon_ledger` 分离。`entry_type` 取 `RESERVE`（领取）、`USE`（订单支付成功）、`UNUSE`（全额退款返券）；`request_id` 唯一。

不变量：`issued_amount` = 该活动已发放券面额合计 = `coupon_campaign.issued_amount`；`used_amount` = 该活动处于 `USED` 的券实例面额合计。

### 2.3 活动字段

| 字段 | 类型 | 约束 |
|---|---|---|
| campaignId | String | Snowflake |
| merchantId / storeId | String / String(null) | 归属商家；`storeId` 为创建时所在门店（仅记录） |
| name | String | 2..50 码点，同一商家内不重名 |
| couponType | enum | `THRESHOLD`（满减券）/ `NO_THRESHOLD`（无门槛券） |
| amountOff | String | 面额，两位小数，> 0 |
| thresholdAmount | String/null | 门槛；无门槛券为 null |
| typeLabel | String | `满减券` / `无门槛券` |
| totalLimit | int | 1..1,000,000 |
| perUserLimit | int | 1..10，且 ≤ totalLimit |
| budgetLimit | String | > 0，且 ≥ amountOff |
| issuedCount / issuedAmount | int / String | 已领取数 / 已领取面额 |
| startAt / endAt | ISO-8601 | `startAt < endAt`；既是领取期也是券有效期 |
| description | String/null | ≤300 码点 |
| status / rejectReason | enum / String(null) | 见 §2.4；驳回原因 1..500 |
| version | int | ≥1，乐观锁 |

### 2.4 状态机

```text
DRAFT ──submit(商家)──> PENDING_REVIEW ──approve(startAt>now)──> SCHEDULED ──(到 startAt)──> ACTIVE
   │                          │──approve(startAt<=now)──> ACTIVE
   │                          ├──reject(运营)──> REJECTED ──edit+submit──> PENDING_REVIEW
   │                          └──cancel(商家)──> CANCELED
   └──cancel(商家)──> CANCELED          REJECTED ──cancel(商家)──> CANCELED
SCHEDULED/ACTIVE ──pause(运营)──> PAUSED ──end(运营)──> ENDED
SCHEDULED/ACTIVE/PAUSED ──end(运营)──> ENDED
```

- 商家可编辑 `DRAFT/REJECTED`；`PENDING_REVIEW` 后不可编辑；商家不可发布/暂停/结束。
- 每个商家券活动对应**一个**专属 `coupon_template`（1:1，`status=DRAFT`，有效期同活动窗口）。运营审核通过时模板置 `ACTIVE`。模板与活动作为一个整体审核。
- 运营动作全部写 `coupon_campaign_audit`（append-only）。
- 暂停/结束不作废已发放的券：已发券继续按 61 号有效至到期（与 72 号一致）。

## 3. 商家端接口

前缀 `/api/v1/merchant/coupon-campaigns`。鉴权：MINIAPP Bearer（C 会话）+ 商家 OWNER；坐标 `merchantId`、`storeId` 走 query string。商家准入沿用服务域既定口径（`MerchantAdmissionQueryApi`）：当前用户不是该门店 OWNER 时 404（与资源不存在不可区分），商家或门店不可运营时 409。写请求带 `X-Request-Id`（幂等）与 `expectedVersion`（乐观锁）。

| 方法 / 路径 | 请求 | 成功 data |
|---|---|---|
| GET `?merchantId=&storeId=&status=&page=&pageSize=` | — | 分页 `items[]`（§2.3 字段 + `claimedCount/usedCount/drivenOrderCount/budgetUsedAmount/createdAt/updatedAt`），`created_at DESC, id DESC` |
| GET `overview?merchantId=&storeId=` | — | `{totalCampaignCount, activeCampaignCount, pendingReviewCount, monthIssuedCount, redemptionRate, drivenOrderCount}` |
| POST `?merchantId=&storeId=` | §3.1 | `campaignId/status/version` |
| GET `{id}?merchantId=&storeId=` | — | 单条全字段 |
| PUT `{id}?merchantId=&storeId=` | §3.1 + `expectedVersion` | `campaignId/status/version` |
| POST `{id}/submit` | `{expectedVersion}` | `campaignId/status/version` |
| POST `{id}/cancel` | `{expectedVersion}` | `campaignId/status/version` |

### 3.1 创建/编辑请求

```json
{
  "name": "新客立减20元券",
  "amountOff": "20.00",
  "thresholdAmount": "100.00",
  "totalLimit": 100,
  "perUserLimit": 1,
  "budgetLimit": "2000.00",
  "startAt": "2026-12-01T00:00:00.000Z",
  "endAt": "2026-12-31T15:59:59.000Z",
  "description": "介绍活动规则、适用服务、注意事项等"
}
```

- `thresholdAmount` 省略或为 null = 无门槛券。未知字段 400。
- 校验：`amountOff > 0`；`budgetLimit >= amountOff`；`endAt > now`；同一商家存在同名且未终结（非 `CANCELED/ENDED`）的活动 → 409 `MERCHANT_COUPON_NAME_DUPLICATE`。
- 请求不得携带 `status/issuedCount/issuedAmount/rejectReason/merchantId` 等服务端字段。
- 统计口径：`monthIssuedCount` = 自然月（Asia/Shanghai）内领取张数；`redemptionRate` = `usedCount / issuedCount`（四位小数字符串，如 `"0.6200"`；未发放时 `"0.0000"`）；`drivenOrderCount` = 使用过该商家券的去重订单数。不展示曝光/点击/净 GMV。

## 4. 运营端接口

前缀 `/api/v1/admin/coupon-campaigns/merchant`（沿用既有 `/api/v1/admin/coupon-campaigns` 安全链前缀）。鉴权：ADMIN_WEB Bearer + 动作码 `coupon.campaign.read`（读）/ `coupon.campaign.publish`（审核与处置）。不新增动作码。

| 方法 / 路径 | 请求 | 成功 data |
|---|---|---|
| GET ``?status=&merchantId=&page=&pageSize=`` | — | 分页 `items[]`（§2.3 + 商家名/门店名 + 统计），`updated_at DESC, id DESC` |
| GET `{id}` | — | 单条全字段 + 商家名/门店名 + 审核信息 |
| POST `{id}/approve` | `{expectedVersion}` | `campaignId/status/version` |
| POST `{id}/reject` | `{expectedVersion, reason}`（1..500） | `campaignId/status/version` |
| POST `{id}/pause` | `{expectedVersion}` | `campaignId/status/version` |
| POST `{id}/end` | `{expectedVersion}` | `campaignId/status/version` |

- 审核通过时重新校验：模板存在且属于该商家、`endAt > now`；不满足 → 409 `MERCHANT_COUPON_INVALID`。
- 平台券活动既有接口只返回 `owner_type=PLATFORM` 的活动，行为不变。

## 5. C 端接口

| 方法 / 路径 | 变化 |
|---|---|
| GET `/api/v1/c/coupon-campaigns?storeId=` | **新增**。返回该门店所属商家可领取的商家券活动（状态为 `SCHEDULED/ACTIVE` 且在领取期内、模板可用、总量与预算有余量、开关开启），按 `endAt` 升序；条目同 72 号 §5.1 并增加 `merchantId/merchantName/fundingType="MERCHANT"`。门店不存在 404。不含他商家活动 |
| GET `/api/v1/c/coupon-campaigns/{campaignId}` | 72 号 §5.1 响应增加 `fundingType`（`PLATFORM/MERCHANT`）与可空 `merchantId/merchantName`；`claimed` 表示"至少领过一次"，`canClaim` 按 `perUserLimit` 判定 |
| POST `/api/v1/c/coupon-campaigns/{campaignId}/claims` | 不变。商家券活动适用 `perUserLimit` 1..10：未达上限可再次领取，达上限 409 `COUPON_CLAIM_LIMIT_REACHED`（上限为 1 时为 `COUPON_CLAIMED`）；预算/总量耗尽 409 `COUPON_CAMPAIGN_SOLD_OUT` |
| POST `/api/v1/c/orders` | 不变。商家券用于非本商家订单时 409 `COUPON_SCOPE_MISMATCH`（见 §6.1） |

「我的券」投影（`GET /api/v1/c/coupons`）本契约**不改动**：小程序对其做精确键集解码，出资方标识随 C 端领券入口一并设计后再加。

## 6. 使用与结算

### 6.1 冻结闸门

`CouponFreezeCommand` 增加 `merchantId`（订单商家，由下单内核给出）。`CouponFreezeResult` 增加 `fundingType`。资格判定在 61 号 §3 七条之上增加一条：

> 实例所属模板 `funding_type=MERCHANT` 时，模板 `owner_merchant_id` 必须等于订单 `merchantId`，否则 409 `COUPON_SCOPE_MISMATCH`。

平台券不受影响。每单最多一张券、平台券与商家券不叠加由既有"一单一券"结构保证。

### 6.2 订单快照与分账基数

下单内核把冻结结果落到 `pet_order.coupon_funding_type` 与 `merchant_coupon_discount`。`OrderSettlementFact` 增加 `merchantCouponDiscount`。结算单注册时：

- `merchant_discount_amount = fact.merchantCouponDiscount`（原写死 0）；
- `platform_subsidy_amount = max(0, discountAmount − merchantCouponDiscount)`（原为全部券抵扣）。

78 号 §3 公式不变：`base0 = original_price − flash_discount_amount − merchant_discount_amount`。退款重算与分账回退读结算单快照，无需改动。

### 6.3 预算账本

- 领取事务内写 `RESERVE`（`request_id = MCPB:RESERVE:{couponInstanceId}`），`issued_amount += 面额`。
- 券模块消费既有 `OrderPaidEvent.v1`：订单对应券实例已 `USED` 且为商家券时写 `USE`（`MCPB:USE:{orderId}`），`used_amount += 面额`。
- 既有 `COUPON_REFUND_RESTORE` 消费者全额返券成功后写 `UNUSE`（`MCPB:UNUSE:{refundOrderId}`），`used_amount −= 面额`。部分退款与迟到支付来源不处理。

### 6.4 日对账（后续独立 PR 交付，不在本契约首个实现内）

设计定为三类核对、只告警不自动改账：

1. 结算单 `merchant_discount_amount` ≠ 订单快照 `merchant_coupon_discount`。
2. 订单快照 `merchant_coupon_discount` ≠ 该订单已使用券实例的面额（或该券并非商家出资）。
3. 预算账本不变量被破坏（`issued_amount`/`used_amount` 与券实例汇总不一致）。

未随首个实现合入的原因：支付中心既有差异表 `reconcile_diff` 对业务类型与差异类型设有取值约束（仅渠道比对的 5 种类型），接入需要新增迁移并改动其日对账服务与差异页面。实现时由券模块自建告警表并挂入每日调度。在此之前，第 1、3 条由自动化测试钉住。

## 7. 错误码（登记 12 号）

| Code | HTTP | 含义 |
|---|---|---|
| `MERCHANT_COUPON_INVALID` | 400 | 请求字段不合法 |
| `MERCHANT_COUPON_NOT_FOUND` | 404 | 活动不存在或不属于该商家 |
| `MERCHANT_COUPON_STATE_NOT_ALLOWED` | 409 | 状态机不允许该动作，或动作的前置条件不满足（版本已变除外） |
| `MERCHANT_COUPON_NAME_DUPLICATE` | 409 | 同一商家活动名重复 |
| `COUPON_SCOPE_MISMATCH` | 409 | 商家券用于非本商家订单（既有码，本次启用） |

商家准入不新增错误码：非 OWNER 复用 `COMMON_NOT_FOUND`(404)，不可运营复用服务域既定的 409 状态码。

领取侧复用 72 号：`COUPON_CAMPAIGN_NOT_CLAIMABLE`、`COUPON_CAMPAIGN_SOLD_OUT`、`COUPON_CLAIM_LIMIT_REACHED`、`COUPON_CLAIMED`。

## 8. 前端（摘要）

- 小程序商家端：`/merchant/pages/marketing/coupon/index`（Figma `12:8436`）、`/merchant/pages/marketing/coupon/edit`（`12:8575`）。折扣券/兑换券置灰"暂不支持"；「立即上架」改为「提交审核」；增加「预算」输入。营销 hub「优惠券」卡从「筹备中」改为可用。
- 运营后台：商家券审核列表/详情 + 通过/驳回/暂停/结束。
- C 端领取入口：后端已就绪（§5），界面入口待产品拍板，本契约不新增 C 端页面。

## 9. 不做

折扣券、兑换券、券包；平台券与商家券叠加；商家向指定用户发券；活动头图上传；新客专享（`audienceType` 固定 `ALL`）；商家直接发布；曝光/点击/净 GMV 展示；暂停后恢复。
