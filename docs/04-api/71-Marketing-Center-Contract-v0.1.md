# 营销活动中心契约 v0.1（MKT-001）

状态：IMPLEMENTED_INTERNAL_DEFAULT_OFF，最终以实现 PR 的 CI 为准。2026-10-27。
依据：[38 号裁决补充](../01-prd/38-营销活动中心裁决补充-v1.0.md)、
[CCR-MKT-001](../../planning/ccr/CCR-MKT-001.md)、C 端 PRD 5.1.2/5.1.13/5.1.14/5.1.21、
商家端 PRD 5.7、运营端 PRD 6.4.2/6.11.5、Figma `689:1190`/`110:480`/`96:3328`/`96:4355`。
券活动与领取的字段/规则以 [72 号](72-Coupon-Campaign-Contract-v0.1.md) 为准；券实例生命周期
仍以 [61 号](61-Coupon-Lifecycle-Contract-v0.1.md) 为准。

## 1. 范围与边界

营销中心负责：活动位、活动会场、参与门店、商家报名、商家发起活动、页面 Schema、券活动投放位。
不负责：券实例冻结/消费/释放/返券（61 号）、订单/支付/退款/核销/售后、商家资金结算/分摊。
营销不得创建订单、不得修改 `pay_amount`、不得绕过券生命周期。

角色：
- 平台运营：活动/活动位/报名审核/商家活动审核/券活动配置与发布；
- 商家：店铺促销字段（既有）、报名平台活动、发起自己的活动（审核后展示）；
- 用户：浏览活动、进入门店/服务、领取券活动券。

## 2. 对象与状态

### 2.1 活动 `marketing_campaign`

| 字段 | 类型 | 约束 |
|---|---|---|
| campaignId | String | Snowflake 十进制 |
| campaignCode | String | 2..32，`[A-Z0-9_-]`，全局唯一 |
| name | String | 2..100 |
| ownerType | enum | `PLATFORM` / `MERCHANT` |
| ownerMerchantId/ownerStoreId | String/null | 商家发起时必填，且必须为本人门店 |
| campaignType | enum | `DISPLAY` / `COUPON` / `MERCHANT_ENROLL` |
| participationMode | enum | `PLATFORM_SELECTED` / `MERCHANT_ENROLL` |
| status | enum | `DRAFT/PENDING_REVIEW/SCHEDULED/ONLINE/PAUSED/ENDED/CANCELED/REJECTED` |
| title/subtitle/tagCode/tagName/coverUrl | String | 标题 1..100，副标题 ≤200，角标 ≤32，封面/后台素材 URL 生产 HTTPS ≤512（本地 E2E 可显式 allow-http） |
| cityCodes | String[] | 空数组=全部开放城市；否则必须是开放城市 |
| serviceCategoryCodes | String[] | 空数组=全部；否则必须是服务字典启用分类 |
| startAt/endAt | ISO-8601 | 必须 `startAt < endAt` |
| enrollStartAt/enrollEndAt | ISO-8601/null | `MERCHANT_ENROLL` 必填，且在活动期内 |
| enrollmentQuota | int/null | 1..10000，null=不限 |
| couponCampaignId | String/null | `COUPON` 类型可填；指向 72 号券活动 |
| pageVersion | int | 当前发布版本，从 1 开始 |
| version | int | 乐观锁 |
| createdAt/updatedAt | ISO-8601 | UTC 毫秒 |

状态机：
`DRAFT → PENDING_REVIEW（商家）→ SCHEDULED/ONLINE（运营发布）→ PAUSED → ENDED`；
`DRAFT/PENDING_REVIEW/SCHEDULED/PAUSED → CANCELED`；`PENDING_REVIEW → REJECTED`。
平台活动：`DRAFT → SCHEDULED/ONLINE`（运营直接发布，SSOT §24 单运营）。
发布时间早于 `startAt` 落 `SCHEDULED`，否则落 `ONLINE`；读取时仍按时间窗口过滤。

### 2.2 活动门店 `marketing_campaign_store`

| 字段 | 类型 | 约束 |
|---|---|---|
| campaignId/storeId/merchantId | String | 必填 |
| source | enum | `PLATFORM_SELECTED` / `MERCHANT_ENROLLED` / `MERCHANT_OWN` |
| status | enum | `ACTIVE` / `REMOVED` |
| offerTag/offerTitle/offerCopy | String/null | 角标 ≤32，标题 ≤100，文案 ≤100 |
| sortWeight | int | 0..9999 |

`(campaignId,storeId)` 唯一；同一门店同一活动只保留一行。活动发布会校验门店当前可见
（C 端门店三条件合取）；不可见门店不展示，不使整页失败（后台保存时 409）。

### 2.3 投放位 `marketing_placement`

| 字段 | 类型 | 约束 |
|---|---|---|
| placementId | String | Snowflake |
| positionCode | enum | `HOME_BANNER` / `HOME_ACTIVITY` / `STORE_LIST_TOP` |
| campaignId | String | 必须指向 `SCHEDULED/ONLINE` 活动 |
| cityCode | String/null | null=全部开放城市 |
| titleOverride/subtitleOverride/imageUrlOverride | String/null | 覆盖活动展示字段；`imageUrlOverride` 可为后台素材 URL |
| templateCode | String/null | 首页活动卡白名单模板，如 `new_user` / `beauty`；未知模板按通用版式 |
| sortWeight | int | 0..9999 |
| startAt/endAt | ISO-8601 | 投放窗口，必须在活动窗口内或相交 |
| status | enum | `DRAFT/ENABLED/DISABLED` |
| version | int | 乐观锁 |

规则：`HOME_BANNER` 同时启用最多 3 条；投放位/活动/落地页任一失效，C 端隐藏并告警；
跳转参数不得含手机号等敏感信息。同位置同城市按 `sortWeight ASC, placementId ASC` 稳定排序。

### 2.4 商家报名 `marketing_enrollment`

| 字段 | 类型 | 约束 |
|---|---|---|
| enrollmentId/campaignId/merchantId/storeId | String | 必填 |
| status | enum | `SUBMITTED/UNDER_REVIEW/APPROVED/REJECTED/WITHDRAWN` |
| offerTag/offerTitle/offerCopy | String/null | 同活动门店 |
| materialsJson | JSON/null | 运营可见的结构化材料；不得含证件明文/手机号 |
| rejectReason | String/null | 审核驳回 1..500 |
| submittedAt/decidedAt/decidedBy | ISO-8601/String | 事实字段 |
| version | int | 乐观锁 |

`(campaignId,storeId)` 唯一；同一门店同一活动重复报名在同参重放时返回首回执，异参 409。
活动必须 `participationMode=MERCHANT_ENROLL` 且当前处于报名窗口；门店必须属于当前商家且
当前可见；名额按已通过报名数 + 平台已选门店数校验。

### 2.5 页面 Schema `pageSchema`

```json
{
  "schemaVersion": 1,
  "components": [
    {"type": "hero", "title": "...", "subtitle": "...", "imageUrl": "https://...", "tag": "..."},
    {"type": "coupon", "buttonText": "立即领取"},
    {"type": "storeList", "title": "参与活动商家"},
    {"type": "richText", "title": "活动说明", "paragraphs": ["..."]},
    {"type": "notice", "text": "..."},
    {"type": "buttonGroup", "buttons": [{"label": "去看看", "action": {"type": "STORE", "storeId": "..."}}]},
    {"type": "spacer", "height": 24},
    {"type": "divider"}
  ]
}
```

组件白名单：`hero/coupon/storeList/richText/notice/buttonGroup/spacer/divider`。
`components` 1..10；标题 ≤100、副标题 ≤200、段落 ≤10×500、notice ≤200、角标 ≤32、
图片 HTTPS ≤512、按钮 1..3 个且 label ≤20。动作类型：`STORE/SERVICE/CAMPAIGN/SERVICE_CATEGORY/
MINIAPP_PATH/COUPON_CLAIM/NONE`；`MINIAPP_PATH` 仅允许已审批白名单路由。
发布时服务端校验；未知组件类型拒绝发布。C 端渲染遇到未知组件跳过并继续，不白屏。
发布快照写入 `marketing_page_version`；回滚只切换发布版本，不修改历史快照。

## 3. C 端接口（匿名可读；券领取需登录）

信封 `{code,message,data,traceId}`；ID 十进制 String；时间 ISO-8601 UTC 毫秒；
分页 `page` 1..10000、`pageSize` 1..50；`Cache-Control: no-store`（V1 先保证热更新一致性）。

### 3.1 活动位

`GET /api/v1/c/marketing/placements?positionCode=HOME_ACTIVITY&cityCode=310100`

- `positionCode` 必填，允许 `HOME_BANNER/HOME_ACTIVITY/STORE_LIST_TOP`；`cityCode` 可选。
- data：`{ items: [{ placementId, positionCode, campaignId, title, subtitle, tagCode, tagName,
  coverUrl, templateCode, startAt, endAt, jumpType, jumpParams }], version }`
- 过滤：投放位 `ENABLED`、当前在投放窗口、活动 `ONLINE` 且当前在活动窗口、城市匹配；
  落地页失效的条目不返回并告警。`HOME_BANNER` 最多 3 条。
- 失败：400 参数非法；503 读取失败（C 端隐藏该模块，不阻塞页面）。

### 3.2 活动聚合页

`GET /api/v1/c/marketing/campaigns?tagCode=&cityCode=&page=1&pageSize=20`

- data：`{ items: [{ campaignId, title, subtitle, tagCode, tagName, coverUrl, startAt, endAt,
  storeCount }], page, pageSize, total }`
- 只返回 `ONLINE`、当前时间窗口、城市匹配的活动；按 `sortWeight ASC, startAt ASC, campaignId ASC`。
- `storeCount` = 当前可见参与门店数（活动门店去掉不可见门店）。

`GET /api/v1/c/marketing/tags?cityCode=`

- data：`{ items: [{ tagCode, tagName, campaignCount }] }`；只统计当前城市可见活动；按 tagCode 稳定排序。

### 3.3 活动详情

`GET /api/v1/c/marketing/campaigns/{campaignId}`

data：
```json
{
  "campaignId": "...", "title": "...", "subtitle": "...", "tagCode": "...", "tagName": "...",
  "coverUrl": "...", "startAt": "...", "endAt": "...", "status": "ONLINE",
  "pageVersion": 3,
  "schema": { "schemaVersion": 1, "components": [] },
  "stores": [{ "storeId": "...", "storeName": "...", "merchantName": "...", "cityCode": "...",
               "address": "...", "offerTag": "...", "offerTitle": "...", "offerCopy": "...",
               "promotion": { "level": 5, "title": "...", "text": "..." } }],
  "couponCampaign": { "campaignId": "...", "name": "...", "amountOff": "20.00",
                      "thresholdAmount": "100.00", "typeLabel": "满减券",
                      "remaining": 88, "perUserLimit": 1, "claimed": false, "canClaim": true } 
}
```
- 活动不存在/不可见：404 `CAMPAIGN_NOT_FOUND`（防枚举）。
- 门店按活动门店 `sortWeight` 排序；不可见门店不返回。
- `couponCampaign` 仅在活动绑定 72 号券活动时返回；`claimed` 需要可选 C 会话，匿名返回 false。
- Schema 缺失时返回服务端默认模板（hero + coupon + storeList + richText）。

### 3.4 商家钜惠横排

`GET /api/v1/c/marketing/merchant-deals?cityCode=&limit=10`

- data：`{ items: [{ storeId, storeName, merchantName, cityCode, address, offerTag, offerTitle,
  offerCopy, source: "MERCHANT_PROMOTION"|"CAMPAIGN", campaignId, promotion }] }`
- 来源：有有效商家促销（`promo_level/title/text` 非空）的门店 + `STORE_LIST_TOP` 投放活动
  当前可见参与门店；同店去重，活动来源优先；`limit` 1..20，默认 10。
- 门店不可见/活动失效自动剔除；整页读取失败返回 503，C 端隐藏该横排。

### 3.5 门店详情营销投影

`GET /api/v1/c/stores/{storeId}` 增加可选字段：
```json
{
  "promotion": { "level": 5, "title": "...", "text": "..." },
  "campaign": { "campaignId": "...", "title": "...", "subtitle": "...", "tagName": "...",
                "offerTag": "...", "offerTitle": "...", "offerCopy": "..." }
}
```
- 无促销/无进行中活动时字段为 `null`；不虚构数据。
- `promotion` 来自 `merchant_store_profile`（SQL27，启用营销前须安装该表）；`campaign` 来自当前门店参与的 `ONLINE` 活动。
- 门店详情页复用既有页面；有 `campaign` 时在门店卡上方展示活动横幅，服务区仍只展示单次服务。

### 3.6 店铺列表促销投影

`GET /api/v1/c/stores?city=&keyword=&promoted=true&page=&pageSize=`

- `promoted=true` 只返回存在有效商家促销的门店；排序 `promo_level ASC, updated_at DESC, storeId ASC`。
- 列表项增加 `promotion` 字段；其余可见性规则不变。

### 3.7 公开素材

`GET /api/v1/c/marketing/assets/{assetId}`

- 返回 ACTIVE 素材原始字节；`Content-Type`、`ETag`、`Cache-Control: public, max-age=31536000, immutable`。
- 不存在或已退役统一 404 `COMMON_NOT_FOUND`，不暴露素材是否曾存在。
- 素材 URL 是后台活动/投放的唯一图片来源；小程序不内置活动插画。
- 存储为 OSS 优先：配置 `OSS_*` 时字节落私有对象桶，`marketing_asset.object_key` 记录键，本端点在应用侧取回并长缓存；未配置 OSS（本地/CI）时回退为 `marketing_asset.content` BLOB。`pet.marketing.asset.public-base-url` 指向公开 HTTPS 网关；后续上 CDN 时保持 URL 或在端点重定向，前端与活动数据无需改。

## 4. 运营端接口

统一 `ADMIN_WEB` Bearer、`Cache-Control: no-store`、信封、分页、`X-Request-Id` 幂等、
`expectedVersion` 乐观锁；未知 query 参数 400；资源不存在/不可见 404；状态冲突 409；
依赖不可用 503。动作码与范围见 §6。

### 4.1 活动

| 方法/路径 | 动作码 | 请求 | 成功 data |
|---|---|---|---|
| GET `/api/v1/admin/marketing/campaigns` | marketing.campaign.read | status?/ownerType?/keyword?/page/pageSize | 分页活动卡（含 storeCount、pageVersion、version） |
| POST `/api/v1/admin/marketing/campaigns` | marketing.campaign.manage | name/campaignType/participationMode/title/subtitle?/tagCode?/tagName?/coverUrl?/cityCodes?/serviceCategoryCodes?/startAt/endAt/enrollStartAt?/enrollEndAt?/enrollmentQuota?/couponCampaignId? | campaignId/status/version |
| POST `/api/v1/admin/marketing/assets` | marketing.campaign.manage | multipart `file`：PNG/JPEG，≤2MiB，≤2000×2000 | assetId/url/contentType/bytes/width/height/sha256 |
| GET `/api/v1/admin/marketing/campaigns/{id}` | marketing.campaign.read | — | 全字段 + stores + pageSchema + pageVersions |
| PUT `/api/v1/admin/marketing/campaigns/{id}` | marketing.campaign.manage | 同上 + expectedVersion | campaignId/status/version |
| POST `/api/v1/admin/marketing/campaigns/{id}/stores` | marketing.campaign.manage | `{ stores:[{storeId,offerTag?,offerTitle?,offerCopy?,sortWeight?}], expectedVersion }` | stores[]/version |
| POST `/api/v1/admin/marketing/campaigns/{id}/page` | marketing.campaign.manage | `{ schema, expectedVersion }` | pageDraftVersion/version |
| POST `/api/v1/admin/marketing/campaigns/{id}/publish` | marketing.campaign.publish | `{ expectedVersion }` | status/pageVersion/version |
| POST `/api/v1/admin/marketing/campaigns/{id}/pause` | marketing.campaign.publish | `{ expectedVersion }` | status/version |
| POST `/api/v1/admin/marketing/campaigns/{id}/end` | marketing.campaign.publish | `{ expectedVersion }` | status/version |
| POST `/api/v1/admin/marketing/campaigns/{id}/cancel` | marketing.campaign.publish | `{ expectedVersion }` | status/version |
| POST `/api/v1/admin/marketing/campaigns/{id}/reject` | marketing.campaign.publish | `{ reason(1..500), expectedVersion }` | status/version |
| POST `/api/v1/admin/marketing/campaigns/{id}/page/rollback` | marketing.campaign.publish | `{ pageVersion, expectedVersion }` | pageVersion/status/version |
| GET `/api/v1/admin/marketing/campaigns/{id}/preview` | marketing.campaign.read | — | 草稿 schema + 门店（结构预览） |

规则：`publish` 校验 Schema、参与门店（至少 1 家可见门店；`MERCHANT_ENROLL` 活动允许 0 家但
报名期内可报名）、券活动（若绑定，必须 ACTIVE/SCHEDULED 且模板有效）；发布成功复制草稿
Schema 为发布快照并 `pageVersion+1`；商家发起活动只有 `PENDING_REVIEW` 可发布/驳回。

### 4.2 投放位

| 方法/路径 | 动作码 | 请求 | 成功 data |
|---|---|---|---|
| GET `/api/v1/admin/marketing/placements` | marketing.placement.read | positionCode?/status?/page/pageSize | 分页投放位 |
| POST `/api/v1/admin/marketing/placements` | marketing.placement.manage | positionCode/campaignId/cityCode?/titleOverride?/subtitleOverride?/imageUrlOverride?/templateCode?/sortWeight/startAt/endAt | placementId/status/version |
| PUT `/api/v1/admin/marketing/placements/{id}` | marketing.placement.manage | 同上 + expectedVersion | placementId/status/version |
| POST `/api/v1/admin/marketing/placements/{id}/enable` | marketing.placement.manage | `{expectedVersion}` | status/version |
| POST `/api/v1/admin/marketing/placements/{id}/disable` | marketing.placement.manage | `{expectedVersion}` | status/version |

规则：启用时校验活动 `SCHEDULED/ONLINE`、窗口相交、落地页有效；`HOME_BANNER` 启用后
同时启用数 ≤3；城市必须是开放城市。

### 4.3 商家报名审核

| 方法/路径 | 动作码 | 请求 | 成功 data |
|---|---|---|---|
| GET `/api/v1/admin/marketing/enrollments` | marketing.enrollment.read | campaignId?/status?/page/pageSize | 分页报名（含门店名/商家名/材料/状态） |
| POST `/api/v1/admin/marketing/enrollments/{id}/review` | marketing.enrollment.review | `{ decision:"APPROVE"|"REJECT", reason?, expectedVersion }` | enrollmentId/status/version |
| POST `/api/v1/admin/marketing/enrollments/{id}/approve` | marketing.enrollment.review | `{ expectedVersion }` | 同上 |
| POST `/api/v1/admin/marketing/enrollments/{id}/reject` | marketing.enrollment.review | `{ reason, expectedVersion }` | 同上 |

规则：审核前复核活动报名窗口、名额、门店可见性；通过后写入/替换活动门店
（`source=MERCHANT_ENROLLED`），报名 `APPROVED`；驳回必须有 1..500 字原因；已决策报名不可重复审核。

### 4.4 商家活动审核

商家发起活动走同一 `marketing_campaign` 表：`ownerType=MERCHANT`、`status=PENDING_REVIEW`。
运营在活动列表按 `ownerType=MERCHANT&status=PENDING_REVIEW` 查看，使用 §4.1 的 `publish`/`reject`。
通过后活动归平台发布，C 端不区分发起方；商家不可直接发布、不可绑定券活动、不可绑定他人门店。

### 4.5 门店选择

`GET /api/v1/admin/marketing/stores?keyword=&cityCode=&page=&pageSize=`

- 复用 C 端可见门店投影（三条件合取 + 开放城市），返回 `storeId/storeName/merchantName/cityCode/
  address/promotion`；只用于运营选择参与门店，不授予任何门店写权限。
- `keyword` 1..30；分页 1..50；无匹配返回空页。

## 5. 商家端接口

统一小程序 C 会话（`CBearerSessionFilter`）+ 门店 OWNER 事实；`X-Request-Id` 幂等；
`Cache-Control: no-store`；信封/分页同 C 端；错误码同 §6。

### 5.1 可报名活动

`GET /api/v1/merchant/marketing/campaigns?status=ENROLLING&page=&pageSize=`

- `ENROLLING` 表示 `participationMode=MERCHANT_ENROLL`、`SCHEDULED/ONLINE`、当前在报名窗口、
  城市/服务分类与本店匹配；返回活动卡 + `enrolled` 状态 + `enrollmentStatus`。
- 服务分类匹配：活动 `serviceCategoryCodes` 为空或与本店任一上架服务分类相交；
  无服务分类不阻止报名（运营审核时判断）。

### 5.2 报名

| 方法/路径 | 请求 | 成功 data |
|---|---|---|
| POST `/api/v1/merchant/marketing/campaigns/{campaignId}/enrollments` | `{ merchantId, storeId, offerTag?, offerTitle?, offerCopy?, materials? }` | enrollmentId/status/version |
| GET `/api/v1/merchant/marketing/enrollments` | `status?/page/pageSize` | 分页报名 |
| POST `/api/v1/merchant/marketing/enrollments/{id}/withdraw` | `{ expectedVersion }` | status/version |

规则：报名窗口内、门店归属当前用户、门店可见、未重复报名；驳回后可在报名窗口内重新提交
（同参重放返回首回执，异参 409）；撤回仅 `SUBMITTED/UNDER_REVIEW`。

### 5.3 商家发起活动

| 方法/路径 | 请求 | 成功 data |
|---|---|---|
| GET `/api/v1/merchant/marketing/my-campaigns` | status?/page/pageSize | 分页自己的活动 |
| POST `/api/v1/merchant/marketing/my-campaigns` | `{ merchantId, storeId, name, title, subtitle?, tagCode?, tagName?, coverUrl?, startAt, endAt }` | campaignId/status/version |
| PUT `/api/v1/merchant/marketing/my-campaigns/{id}` | 同上 + expectedVersion | campaignId/status/version |
| POST `/api/v1/merchant/marketing/my-campaigns/{id}/submit` | `{ expectedVersion }` | status/version |
| POST `/api/v1/merchant/marketing/my-campaigns/{id}/cancel` | `{ expectedVersion }` | status/version |

规则：商家活动 `campaignType=DISPLAY`、`participationMode=PLATFORM_SELECTED`、只绑定本人门店；
`DRAFT/REJECTED` 可编辑，提交后 `PENDING_REVIEW` 不可编辑；C 端展示由运营发布控制；
商家不可设置 `couponCampaignId`、不可设置参与门店列表、不可发布。

## 6. 权限、错误、幂等

动作码：
- `marketing.campaign.read`：活动/投放/报名/门店选择读；
- `marketing.campaign.manage`：活动创建/编辑/门店替换/页面草稿；
- `marketing.campaign.publish`：发布/暂停/结束/取消/驳回/页面回滚；
- `marketing.placement.read` / `marketing.placement.manage`：投放位读/写；
- `marketing.enrollment.read` / `marketing.enrollment.review`：报名读/审核；
- `coupon.campaign.read/manage/publish`：72 号券活动。

数据范围：平台配置默认 `ALL`；城市范围账号按活动/投放 `cityCode` 复核；商家范围账号
仅能读/审核其商户范围；越权 403，fail-closed。C 端公共读不需要登录；券领取/商家接口需要会话。

错误码（12 号登记）：
- `CAMPAIGN_NOT_FOUND` 404、`CAMPAIGN_STATE_NOT_ALLOWED` 409、`CAMPAIGN_LANDING_INVALID` 409、
  `CAMPAIGN_PAGE_INVALID` 400、`PLACEMENT_LIMIT_EXCEEDED` 409、`ENROLLMENT_NOT_OPEN` 409、
  `ENROLLMENT_ALREADY_EXISTS` 409、`ENROLLMENT_QUOTA_EXCEEDED` 409、`ENROLLMENT_STATE_NOT_ALLOWED` 409、
  `MARKETING_STORE_NOT_ELIGIBLE` 409、`COUPON_CAMPAIGN_NOT_CLAIMABLE` 409、
  `COUPON_CAMPAIGN_SOLD_OUT` 409、`COUPON_CLAIM_LIMIT_REACHED` 409、`COUPON_CLAIMED` 409、
  `MARKETING_ASSET_INVALID` 400、`MARKETING_ASSET_TOO_LARGE` 413。
复用 `COMMON_INVALID_ARGUMENT`/`COMMON_NOT_FOUND`/`COMMON_CONFLICT`/`COMMON_DEPENDENCY_UNAVAILABLE`/
`IDEMPOTENCY_KEY_CONFLICT`。

幂等：全部写接口终结性 `X-Request-Id`；键空间 `MKT:{commandType}:{operatorType}:{operatorId}:{requestId}`
（商家命令含 merchantId/storeId）；同 ID 同参重放返回首回执，异参 409；审计/领券写与业务行同事务。

## 7. 热更新与缓存

- 活动位/活动/活动页全部由后端读侧下发；新活动 = 新增数据行，不改小程序代码。
- C 端活动页是单一通用页面；组件白名单与 Schema 版本冻结在本契约 §2.5。
- 新增组件类型、新增原生页面需要发版；本契约不承诺“任意页面零发版”。
- V1 读接口 `no-store`，保证发布/暂停立即生效；后续可加 Redis + 版本号，不改变接口语义。
- 发布/暂停/结束/投放启停均写审计；C 端读取失败隐藏模块，不白屏、不阻塞首页。

## 8. 不做什么

买一送一、团购套餐/次卡/普通商品、折扣券类型、商家自主发券、商家资金结算/分摊、
秒杀/拼团/裂变/会员卡/直播、个性化推荐、活动曝光/成交归因报表、被动发券自动触发。

## 10. v0.2 补充：首页运营内容位（2026-10-28，CCR-MKT-003）

新增 `marketing_home_block`（`position_code` = `HOME_SERVICE|HOME_COMMUNITY|HOME_EXOTIC_PLANT`，
`template_code` = `HOT_SERVICE_FEATURE|COMMUNITY_HOT|EXOTIC_PLANT`）。运营手动配置标题/文案/图片/
落地目标/城市/排序/生效窗口；`sponsor_merchant_id`/`sponsor_store_id` 预留后续商家竞价，V1 不做
出价/扣费/结算。图片复用 `POST /api/v1/admin/marketing/assets`。

| 方法/路径 | 权限 | 参数 | 返回 |
|---|---|---|---|
| GET `/api/v1/admin/marketing/home-blocks` | marketing.placement.read | positionCode?/status?/page/pageSize | 分页内容位 |
| POST `/api/v1/admin/marketing/home-blocks` | marketing.placement.manage | blockCode/positionCode/templateCode/title/subtitle?/tagName?/imageUrl?/targetType/targetValue?/cityCode?/sponsorMerchantId?/sponsorStoreId?/sortWeight/startAt/endAt | receipt |
| PUT `/api/v1/admin/marketing/home-blocks/{id}` | marketing.placement.manage | 同上 + expectedVersion | receipt |
| POST `/api/v1/admin/marketing/home-blocks/{id}/enable` | marketing.placement.manage | `{expectedVersion}` | receipt |
| POST `/api/v1/admin/marketing/home-blocks/{id}/disable` | marketing.placement.manage | `{expectedVersion}` | receipt |

C 端 `GET /api/v1/c/marketing/home-blocks?cityCode=` 返回当前 `ENABLED` 且窗口内内容位（城市为空
或匹配；空/失败前端整块隐藏）。`targetType` 白名单：`SERVICE_DIRECTORY|SERVICE_CATEGORY|STORE|
SERVICE|CAMPAIGN|COMMUNITY|MINIAPP_PATH|NONE`；目标由前端白名单解释。写操作 requestId 幂等、
append-only 审计；模板变更仍需小程序发版，内容/图片/排序/上下线不需要。
