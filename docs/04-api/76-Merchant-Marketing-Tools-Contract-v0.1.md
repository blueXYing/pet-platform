# 76 — 商家营销工具契约 v0.1（P1）

状态：**APPROVED / 2026-11-17**（依据 [41 号裁决补充](../01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md)、[CCR-MKT-TOOLS-001](../../planning/ccr/CCR-MKT-TOOLS-001.md)）。
本契约是 [71 号营销活动中心契约](71-Marketing-Center-Contract-v0.1.md) §5.3 的增量；71 号 §1–§4、§6–§7 的信封、分页、幂等、权限、状态机、错误码总则继续适用。冲突时以本契约对商家自建活动的字段规定为准。

## 1. 范围

P1 只做：

1. 营销工具 hub 的只读概览；
2. 商家营销封面素材上传；
3. 商家自建活动的字段扩展（主题/玩法/说明/主打项目/封面资产）；
4. 节日营销列表/创建/编辑所需读侧；
5. 运营审核展示新字段。

不做：商家直接发布；优惠券/会员卡/秒杀/裂变/赠品真实能力；订单归因/营收统计；交易规则变更。

## 2. 通用

- 会话：小程序 C 会话 + 门店 OWNER 事实；每次调用重新校验 `merchantId/storeId` 归属与门店可运营状态。非 OWNER/越权 403；门店不可运营 409 `MARKETING_STORE_NOT_ELIGIBLE`。
- 信封 `{code,message,data,traceId}`；`Cache-Control: no-store`；ID 十进制 String；时间 ISO-8601 UTC 毫秒；分页 `page` 1..10000、`pageSize` 1..50。
- 写接口必须带终结性 `X-Request-Id`；同键同参返回首回执，异参 409 `IDEMPOTENCY_KEY_CONFLICT`；乐观版本 `expectedVersion`；审计 append-only。
- 商家不可设置 `couponCampaignId`、参与门店列表、发布状态；服务端忽略/拒绝这些字段。

## 3. 数据字段

### 3.1 `marketing_campaign` 增量

| 字段 | 类型 | 约束 |
|---|---|---|
| `description` | String/null | ≤300 码点 |
| `themeCode` | String | 必填（创建/编辑）；`SPRING_FESTIVAL/MID_AUTUMN/NATIONAL_DAY/CHRISTMAS/NEW_YEAR/DOUBLE_11/OTHER` |
| `highlightText` | String/null | ≤100 码点 |
| `playType` | String | 可空，默认 `THEME`；P1 仅允许 `THEME`；未知 400 `CAMPAIGN_PLAY_TYPE_INVALID` |
| `coverAssetId` | String/null | 必须指向 ACTIVE、`owner_type=MERCHANT`、`owner_merchant_id=本人`、`owner_store_id=本店或空` 的营销素材 |
| `coverUrl` | String/null | 只读派生：`pet.marketing.asset.public-base-url`（去尾 `/`）+ `/api/v1/c/marketing/assets/{assetId}`；商家请求中传非空 `coverUrl` 一律 400 `MARKETING_ASSET_INVALID` |

### 3.2 `marketing_asset` 增量

| 字段 | 类型 | 约束 |
|---|---|---|
| `owner_type` | `PLATFORM/MERCHANT` | 存量迁移默认 `PLATFORM` |
| `owner_merchant_id` | BIGINT/null | 商家素材必填 |
| `owner_store_id` | BIGINT/null | 可空；用于门店隔离 |
| `purpose` | `CAMPAIGN_COVER/null` | 商家上传写 `CAMPAIGN_COVER` |

## 4. 接口

### 4.1 概览

`GET /api/v1/merchant/marketing/overview?merchantId=&storeId=`

data：

```json
{
  "totalCampaignCount": 2,
  "activeCampaignCount": 1,
  "pendingReviewCount": 1,
  "enrolledCampaignCount": 3
}
```

- 全部为当前 `merchantId+storeId` 的计数；`active` 指 `ONLINE` 且当前时间在窗口内；`pendingReview` 指 `PENDING_REVIEW`；`enrolled` 指该门店有效报名（`SUBMITTED/UNDER_REVIEW/APPROVED`）。
- 不返回订单数、营收、转化率、曝光。读取失败 503，前端显示「—」或隐藏。

### 4.2 商家封面素材上传

`POST /api/v1/merchant/marketing/assets?merchantId=&storeId=`，`multipart/form-data`；`merchantId`/`storeId` 为 query 参数（与 71 号商家读接口同一 scope 惯例），multipart 唯一 part 为 `file`；`X-Request-Id` 必填。

- 仅 OWNER；门店当前可运营；PNG/JPEG；≤2 MiB；≤2000×2000；服务端嗅探媒体类型，不信任客户端 Content-Type。
- `pet.marketing.asset.public-base-url` 未配置时返回 503（与运营上传一致）。
- 成功 `201`/`200`：

```json
{
  "assetId": "123456789",
  "url": "https://.../api/v1/c/marketing/assets/123456789",
  "contentType": "image/png",
  "bytes": 123456,
  "width": 1200,
  "height": 800,
  "sha256": "…64 hex…"
}
```

- 失败：400 `MARKETING_ASSET_INVALID`（类型/像素/文件非法）；413 `MARKETING_ASSET_TOO_LARGE`；403；503。
- 写 `marketing_asset` + `marketing_asset_audit`；同 requestId 同参重放返回首回执。

### 4.3 我的活动列表

`GET /api/v1/merchant/marketing/my-campaigns?merchantId=&storeId=&status=&page=&pageSize=`

data：`{items:[CampaignCard],page,pageSize,total}`。

`CampaignCard` 在 71 号基础上增加：

```json
{
  "campaignId": "...", "name": "...", "title": "...", "subtitle": "...",
  "description": "...", "themeCode": "MID_AUTUMN", "themeLabel": "中秋",
  "highlightText": "...", "playType": "THEME",
  "coverAssetId": "...", "coverUrl": "...",
  "status": "DRAFT", "startAt": "...", "endAt": "...", "version": "3",
  "rejectReason": null
}
```

- 排序：`updated_at DESC, id DESC` 稳定；只返回 `ownerType=MERCHANT` 且 `ownerMerchantId/ownerStoreId` 匹配的活动。
- `themeLabel` 由服务端按白名单返回展示名；未知 code 按 `OTHER`。

### 4.4 创建/编辑

`POST /api/v1/merchant/marketing/my-campaigns`

`PUT /api/v1/merchant/marketing/my-campaigns/{campaignId}`

请求：

```json
{
  "merchantId": "1001",
  "storeId": "2001",
  "name": "中秋宠物月饼礼盒",
  "title": "中秋宠物月饼礼盒",
  "subtitle": "节日限定礼盒",
  "description": "活动规则与参与方式…",
  "themeCode": "MID_AUTUMN",
  "highlightText": "宠物月饼礼盒",
  "playType": "THEME",
  "coverAssetId": "123456789",
  "startAt": "2026-09-01T00:00:00.000Z",
  "endAt": "2026-09-30T23:59:59.000Z",
  "expectedVersion": 3
}
```

- `name` 2..100；`title` 1..100；`subtitle` ≤200；`description` ≤300；`highlightText` ≤100；`themeCode` 必填白名单；`playType` 空=`THEME`；`startAt < endAt`。
- `coverAssetId` 可选（草稿）；提供时校验归属/ACTIVE。
- 创建成功 `data:{id,status:"DRAFT",version:"1",occurredAt}`（`id` 即 campaignId，沿用 71 号 `Receipt` 线形状）；编辑成功 `version` 自增（沿用 71 号页面草稿同步规则）。
- 仅 `DRAFT/REJECTED` 可编辑；否则 409 `CAMPAIGN_STATE_NOT_ALLOWED`。
- 商家请求中的 `coverUrl` 非空、`couponCampaignId`、`stores`、`ownerType`、`participationMode` 一律 400/忽略；以服务端固定值为准。

### 4.5 提交审核 / 取消

`POST .../my-campaigns/{id}/submit` body `{merchantId,storeId,expectedVersion}`

- 仅 `DRAFT/REJECTED`；提交前校验：`coverAssetId` 非空且有效；`themeCode` 合法；`playType=THEME`；`endAt > now`；活动至少绑定本店（服务端已绑定）。
- 成功 `data:{id,status:"PENDING_REVIEW",version,occurredAt}`（`id` 即 campaignId，同 71 号 `Receipt`）。

`POST .../my-campaigns/{id}/cancel` body 同上

- 允许 `DRAFT/REJECTED/PENDING_REVIEW`；成功 `data:{id,status:"CANCELED",version,occurredAt}`；已发布活动不可由商家取消（409）。

### 4.6 平台活动报名

沿用 71 号 §5.1/§5.2 端点与语义，本契约不修改。前端迁入独立页不改变 API。

## 5. 运营端增量

- `GET/POST/PUT /api/v1/admin/marketing/campaigns` 的 `CampaignCard/CampaignDetail` 增加 `description/themeCode/themeLabel/highlightText/playType/coverAssetId/coverUrl`。
- 运营创建/编辑可设置这些字段；商家提交的 `playType=THEME` 活动发布前校验封面 ACTIVE。
- 其他审核/发布/驳回流程不变。

## 6. 错误码

| 码 | HTTP | 场景 |
|---|---|---|
| `CAMPAIGN_THEME_INVALID` | 400 | themeCode 缺失/不在白名单 |
| `CAMPAIGN_PLAY_TYPE_INVALID` | 400 | playType 非 `THEME`（P1） |
| `MARKETING_ASSET_INVALID` | 400 | 文件类型/像素/归属/coverUrl 非法 |
| `MARKETING_ASSET_TOO_LARGE` | 413 | 文件 >2 MiB |
| `CAMPAIGN_STATE_NOT_ALLOWED` | 409 | 状态不允许编辑/提交/取消 |
| `MARKETING_STORE_NOT_ELIGIBLE` | 409 | 门店不可运营 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 同 requestId 异参 |
| `COMMON_FORBIDDEN` | 403 | 非 OWNER/跨店 |
| `COMMON_NOT_FOUND` | 404 | 活动/素材不存在（防枚举） |

新增 `CAMPAIGN_THEME_INVALID`、`CAMPAIGN_PLAY_TYPE_INVALID` 到 12 号错误码登记。

## 7. 验收

1. 商家从 hub 进入节日营销，创建活动、上传封面、选主题、填说明、保存草稿、提交审核。
2. 未上传封面不能提交；提交后运营可见新字段并可发布/驳回；商家不可发布。
3. 商家传任意 `coverUrl`、非法主题、非法玩法均 400；跨店素材/活动 403/404。
4. 平台活动报名/撤回无回归。
5. overview 只返回 4 个计数，不含订单/营收/转化率。
6. MySQL 集成、架构测试、`check-persistence-style.py`、前端 typecheck/单测/build 通过。

## 8. 不做/边界

- 商家直接发布、Figma「立即上架」。
- 优惠券/会员卡/秒杀/裂变/赠品真实能力。
- 曝光/点击/成交归因。
- 交易/退款/售后/核销规则变更。
