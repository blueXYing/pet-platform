# CCR-MKT-TOOLS-001：商家营销工具 hub 与自建活动（P1）

- 状态：**APPROVED / 2026-11-17**（依据 [41 号裁决补充](../../docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md)；用户已确认 P1 范围与「保留运营审核」）
- 关联契约：[71 号营销活动中心契约](../../docs/04-api/71-Marketing-Center-Contract-v0.1.md)、[76 号商家营销工具契约](../../docs/04-api/76-Merchant-Marketing-Tools-Contract-v0.1.md)
- 关联设计：Figma `12:7982`（营销工具）、`18:11090`（节日营销）、`18:11706`（创建节日活动）；保留既有平台活动报名
- 是否涉及产品裁决：**是**（41 号补充 §3；覆盖 38 号 §3 中与 P1 相关的最小范围）

## 1. 问题

1. 现有 `merchant/pages/marketing/index` 是「可报名活动 / 我的报名 / 我的活动」三 tab，与 Figma 的「营销工具 hub + 分类工具」结构不对齐。
2. 后端 71 号 §5.3 已有商家发起 `DISPLAY` 活动能力，但字段只有名称/标题/副标题/标签/封面 URL/起止时间；前端没有封面上传、主题、玩法、说明、列表/详情页。
3. `marketing_asset` 仅支持运营上传，商家无法上传活动封面；前端若直接填 URL 会产生安全/归属问题。
4. Figma 的「立即上架」与 38 号/71 号「商家活动必须审核、商家不可发布」冲突。

## 2. P1 范围

### 2.1 做

- 新增营销工具 hub（6 个工具卡 + 平台活动入口）；仅「节日营销」可用，其余显示筹备中/暂不开放。
- 新增节日营销列表/创建编辑页；沿用商家 `DISPLAY` 活动与运营审核状态机。
- 新增商家营销素材上传（封面），扩展 `marketing_asset` 归属。
- 扩展商家活动字段：`description/themeCode/highlightText/playType/coverAssetId`。
- 新增只读概览：活动总数/进行中/待审核/已报名平台活动数。
- 运营后台营销中心展示新字段；发布/驳回流程不变。
- 保留平台活动报名/我的报名，迁入独立页。

### 2.2 不做

- 商家直接发布/「立即上架」。
- 优惠券/会员卡/秒杀/裂变/赠品的真实创建能力；入口仅占位。
- 订单/营收/转化率/曝光归因。
- 节日套餐/买赠/抽奖/优惠力度；未配置真实规则前不展示。
- 赠品活动（41 号补充明确不做）。
- 核销记录页改造（`12:7426` 已实现）。

## 3. 决策

1. 商家活动仍为 `campaignType=DISPLAY`、`participationMode=PLATFORM_SELECTED`、`ownerType=MERCHANT`，只绑定本人门店。
2. 状态机沿用 71 号 §2.1：`DRAFT → PENDING_REVIEW → SCHEDULED/ONLINE`；`PENDING_REVIEW → REJECTED`；`DRAFT/REJECTED/PENDING_REVIEW → CANCELED`。商家不可发布。
3. `playType` P1 仅 `THEME`；后续阶段按 41 号补充扩展白名单。
4. 封面：商家必须通过新端点上传；`DRAFT` 可空，`submit` 前必填；服务端由 `coverAssetId` 解析 URL，商家不得直接传任意 URL。
5. 统计只返回可证明计数；不返回订单/营收/转化率。
6. 未开放工具卡不可导航；不得做空壳表单。
7. 所有写接口 requestId 幂等、乐观版本、append-only 审计；权限仅 OWNER 主账号。

## 4. 契约与 Schema 变更

- 新增 `docs/04-api/76-Merchant-Marketing-Tools-Contract-v0.1.md`（P1 API/字段/错误码）。
- 更新 71 号契约到 v0.4：版本记录 + §5.3 字段增补 + §8 说明 P1 不改变交易规则。
- 新增迁移 `backend/pet-boot/src/main/resources/db/marketing-migration/V34__merchant_marketing_tools.sql`：
  - `marketing_campaign` 增 `description/theme_code/highlight_text/play_type/cover_asset_id`。
  - `marketing_asset` 增 `owner_type/owner_merchant_id/owner_store_id/purpose`；存量默认 `PLATFORM`。
  - 同步更新 `docs/03-database/63-Marketing-Storage-v0.1.sql`。
- 不新增事件；不新增调度任务。
- 错误码复用 71 号 + 新增 `CAMPAIGN_THEME_INVALID`、`CAMPAIGN_PLAY_TYPE_INVALID`（登记 12 号）。

## 5. 接口摘要

| 方法/路径 | 说明 |
|---|---|
| `GET /api/v1/merchant/marketing/overview` | 只读计数 |
| `POST /api/v1/merchant/marketing/assets` | 商家封面素材上传（multipart） |
| `GET /api/v1/merchant/marketing/my-campaigns` | 列表，返回新字段 |
| `POST/PUT /api/v1/merchant/marketing/my-campaigns` | 创建/编辑，新增字段 |
| `POST .../submit` | 提交审核；校验封面必填 |
| `POST .../cancel` | 取消，规则不变 |

完整字段、校验、错误码见 76 号契约。

## 6. 前端变更

- `merchant/pages/marketing/index` 改为 hub（`12:7982`）。
- 新增 `merchant/pages/marketing/platform/index`：迁移现有报名/我的报名。
- 新增 `merchant/pages/marketing/campaigns/index`（`18:11090`）、`campaigns/edit`（`18:11706`）。
- 新增商家封面选择/上传；主题/玩法/说明/日期；「提交审核」替代「立即上架」。
- 注册 `frontend-miniapp/src/app.config.ts` merchant 子包路由。
- 运营后台 `frontend-admin/src/pages/MarketingCenterPage.tsx` 展示新字段。

## 7. 测试与验收

- MySQL 集成：创建/编辑/提交/审核/驳回；封面归属；非法主题/玩法；跨店越权；幂等重放；版本冲突。
- 前端：模型解码、状态文案、上传流程、typecheck、单测、`npm run build:weapp`。
- 架构：`check-persistence-style.py`、模块依赖测试。
- 验收见 76 号契约 §7 与 41 号补充 §10。

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 商家照抄 Figma「立即上架」 | CCR/契约/UI 明确改为提交审核 |
| 商家传任意封面 URL | 只接受资产 ID；服务端校验归属 |
| 统计伪造 | overview 仅计数；不返回订单/营收 |
| 平台报名回归 | 独立 platform 页 + 回归测试 |
| 迁移污染存量 | 存量 asset 默认 PLATFORM；不猜测归属 |
