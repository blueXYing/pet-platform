# 券活动与领取契约 v0.1（CPN-003）

状态：IMPLEMENTED_INTERNAL_DEFAULT_OFF，最终以实现 PR 的 CI 为准。2026-10-27。
依据：[38 号裁决补充](../01-prd/38-营销活动中心裁决补充-v1.0.md)、
[CCR-MKT-001](../../planning/ccr/CCR-MKT-001.md)、运营端 PRD 6.4.2、C 端 PRD 5.1.21/5.4.4、
[61 号券生命周期契约](61-Coupon-Lifecycle-Contract-v0.1.md)、
[65 号运营治理台契约](65-Admin-Ops-Console-Contract-v0.1.md) §4.1/§4.3。

## 1. 范围

补齐 61 号 V1 边界中“无券活动/批次、无用户主动领取”的两项：运营配置券活动/批次，C 端主动
领取，库存/预算/每人限领守卫；领到的券实例继续走 61 号冻结/消费/释放/返券，不改变既有链路。
不新增券类型：仅无门槛券/满减券（PRD 6.4.1）。不实现被动发券自动触发；运营仍可使用既有
65 号发放渠道。

## 2. 数据

### 2.1 `coupon_campaign`

| 字段 | 类型 | 约束 |
|---|---|---|
| campaignId | String | Snowflake |
| name | String | 2..50，同期不重名 |
| templateId | String | 必须是 ACTIVE 券模板，且模板在有效期内 |
| audienceType | enum | `ALL` / `NEW_USER` |
| perUserLimit | int | V1 固定 1（PRD 1..10，多张同活动后续扩展） |
| totalLimit | int | 1..1,000,000 |
| budgetLimit | Decimal(18,2) | >0；`>= amountOff × 最低计划发放量` 由运营输入保证，服务端只校验 >0 |
| issuedCount | int | 已领取数 |
| issuedAmount | Decimal(18,2) | 已领取券面额合计；预算守卫使用 |
| startAt/endAt | ISO-8601 | `startAt < endAt`，且必须在模板有效期内 |
| claimMethod | enum | V1 固定 `ACTIVE_CLAIM` |
| status | enum | `DRAFT/SCHEDULED/ACTIVE/PAUSED/ENDED` |
| version | int | 乐观锁 |

### 2.2 `coupon_claim`

| 字段 | 类型 | 约束 |
|---|---|---|
| claimId | String | Snowflake |
| campaignId | String | 券活动 |
| userId | String | C 端用户 |
| couponInstanceId | String | 61 号券实例 |
| claimedAt | ISO-8601 | UTC 毫秒 |
| requestId | String | 终端幂等键 |

`UNIQUE(campaign_id,user_id)` 防一人多领；`UNIQUE(request_id)` 防重放。V1 每人限领固定为 1；
perUserLimit>1 的多次领取需另开 CCR 调整唯一键与并发守卫。

## 3. 规则

- 发布：模板必须 `ACTIVE` 且 `now ∈ [valid_start_at, valid_end_at]`；`startAt/endAt` 在模板
  有效期内；发布后 `startAt>now` 落 `SCHEDULED`，否则 `ACTIVE`；到期自动 `ENDED`（读侧按时间过滤，
  运营可手动暂停/结束）。
- 领取：活动 `ACTIVE`（或 `SCHEDULED` 且已到 `startAt`）、当前在发券期、模板 ACTIVE/有效、
  未达每人限领、未达总上限、`issuedAmount + amountOff <= budgetLimit`、新用户活动通过
  `NEW_USER` 资格端口。
- 领取事务：锁券活动行 → 重建模板/活动事实 → 条件更新 `issued_count/issued_amount`
  （`issued_count < total_limit AND issued_amount + amountOff <= budget_limit`）→ 插入
  `coupon_instance`（`status='AVAILABLE'`，`expire_at = template.valid_end_at`）→ 插入
  `coupon_ledger` ISSUE 行（`request_id='CPN:CAMPAIGN:{instanceId}'`，remark 含
  `source=CAMPAIGN:{campaignId}`）→ 插入 `coupon_claim`。任一步失败回滚整事务。
- 新用户口径：V1 采用“首次注册且无已支付订单”（PRD 6.4.2 建议后者）；用户不存在/资格端口
  不可用失败关闭 503/404，不默认放行。
- 同一用户重复领取同活动：`(campaign_id,user_id)` 已存在时，同 requestId 同参返回首回执；
  新 requestId 返回 409 `COUPON_CLAIMED`；`perUserLimit` 满返回 409 `COUPON_CLAIM_LIMIT_REACHED`。
- 券实例/台账/订单链路零改动：下单仍走 61 号冻结，支付成功消费，超时释放，退款按 SSOT §9。

## 4. 运营端接口

动作码：`coupon.campaign.read` / `coupon.campaign.manage` / `coupon.campaign.publish`。
统一 ADMIN_WEB Bearer、`no-store`、信封、分页、`X-Request-Id`、`expectedVersion`。

| 方法/路径 | 动作码 | 请求 | 成功 data |
|---|---|---|---|
| GET `/api/v1/admin/coupon-campaigns` | coupon.campaign.read | status?/page/pageSize | 分页（name/template/amount/issued/limit/budget/status） |
| POST `/api/v1/admin/coupon-campaigns` | coupon.campaign.manage | name/templateId/audienceType/perUserLimit/totalLimit/budgetLimit/startAt/endAt | campaignId/status/version |
| GET `/api/v1/admin/coupon-campaigns/{id}` | coupon.campaign.read | — | 全字段 + template 投影 + claimCount |
| PUT `/api/v1/admin/coupon-campaigns/{id}` | coupon.campaign.manage | 同上 + expectedVersion | campaignId/status/version |
| POST `/api/v1/admin/coupon-campaigns/{id}/publish` | coupon.campaign.publish | `{expectedVersion}` | status/version |
| POST `/api/v1/admin/coupon-campaigns/{id}/pause` | coupon.campaign.publish | `{expectedVersion}` | status/version |
| POST `/api/v1/admin/coupon-campaigns/{id}/end` | coupon.campaign.publish | `{expectedVersion}` | status/version |
| GET `/api/v1/admin/coupon-campaigns/{id}/claims` | coupon.campaign.read | page/pageSize | 领取记录（claimId/userId/instanceId/claimedAt） |

规则：已发布活动关键字段（模板、面额、门槛、发放对象、限领、总上限、预算、发券期）不可修改；
需停用后新建。暂停/结束不作废已发券；已发券继续按 61 号有效至到期。

## 5. C 端接口

### 5.1 活动详情

`GET /api/v1/c/coupon-campaigns/{campaignId}`（可选 C 会话）

```json
{
  "campaignId": "...", "name": "...", "templateId": "...",
  "amountOff": "20.00", "thresholdAmount": "100.00", "typeLabel": "满减券",
  "remaining": 88, "perUserLimit": 1, "claimed": false, "canClaim": true,
  "startAt": "...", "endAt": "...", "status": "ACTIVE"
}
```
匿名 `claimed=false`；登录后按 `(campaignId,userId)` 返回。不可见/不存在 404
`COUPON_CAMPAIGN_NOT_FOUND`；不可领取时 `canClaim=false` 并给 `reason` 文案键。

### 5.2 领取

`POST /api/v1/c/coupon-campaigns/{campaignId}/claims`

- 需要 C 会话；请求体为空；终结性 `X-Request-Id`。
- 成功：`{ campaignId, claimId, couponInstanceId, amountOff, expireAt, claimedAt }`。
- 错误：401 未登录；404 活动不存在/不可见；409 `COUPON_CAMPAIGN_NOT_CLAIMABLE` /
  `COUPON_CAMPAIGN_SOLD_OUT` / `COUPON_CLAIM_LIMIT_REACHED` / `COUPON_CLAIMED` /
  `COUPON_NOT_AVAILABLE`；503 资格/模板读不可用。
- 幂等：同 requestId 同参返回首回执；同用户新 requestId 已领返回 409 `COUPON_CLAIMED`
  （`perUserLimit=1` 时）；异参 409 `IDEMPOTENCY_KEY_CONFLICT`。

## 6. 错误码登记

`COUPON_CAMPAIGN_NOT_FOUND` 404、`COUPON_CAMPAIGN_NOT_CLAIMABLE` 409、
`COUPON_CAMPAIGN_SOLD_OUT` 409、`COUPON_CLAIM_LIMIT_REACHED` 409、`COUPON_CLAIMED` 409、
`COUPON_NOT_AVAILABLE` 409（复用 61 号）。其余复用通用码。

## 7. 不做

折扣券、积分抵扣混合、第三方券包、被动发券自动触发、商家自主发券、券转赠、券活动预算分摊/结算。
