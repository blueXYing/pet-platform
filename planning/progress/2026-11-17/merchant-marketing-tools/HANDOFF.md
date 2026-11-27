# 商家营销工具改造执行手册（HANDOFF）

> **给执行智能体的唯一入口。** 读完本文即可开工，不要向用户提问。
> 本文是用户 2026-11-17 确认的范围与决策的执行版；与 `PLAN.md` 冲突时以本文为准。
> **一次只执行一个 Phase**；若用户没有指定，默认执行 **Phase 1**。不要一次做多个 Phase，不要把 P2–P5 的页面提前做成空壳。

- 执行依据（已存在，先读）：
  - `AGENTS.md`
  - `docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md`
  - `planning/ccr/CCR-MKT-TOOLS-001.md`
  - `docs/04-api/76-Merchant-Marketing-Tools-Contract-v0.1.md`
  - `docs/04-api/71-Marketing-Center-Contract-v0.1.md`
  - `planning/progress/2026-11-17/merchant-marketing-tools/PLAN.md`（更详细的分析与后续阶段设计）
- 本文固定了所有产品/技术决策。**不要就是否要做、字段怎么定、状态怎么走、页面怎么显示再问用户。**
- 如果遇到本文未覆盖且与现有契约冲突的事项：停止并报告「冲突点 + 文件/行号 + 建议」，不要自行改交易/退款/售后/核销规则。

---

## 0. 你该怎么做

1. **先确认工作区与分支**（见 §0.1），再确认用户指定的 Phase（默认 Phase 1）。
2. 读 §1 规则、§2 固定决策、§3 阶段门禁。
3. 按 §4（Phase 1）或后续章节执行；一次只做一个 Phase。
4. 每个工作包完成后跑对应测试；测试失败先修复；无法修复则停止并报告。
5. 不要 commit/push `main`/`develop`；不要开新分支以外的事；不要改 `.git` 内备份文件。
6. 完成后按 §10 模板报告，不要只说「已完成」。

### 0.1 工作区与分支（开工前必须确认）

默认值（用户在本条消息中明确指定其他 worktree/分支时，以用户消息为准）：

| 项 | 值 |
|---|---|
| worktree | `/mnt/d/wt-marketing-tools` |
| 分支 | `codex/merchant-marketing-tools-20261117`（当前 worktree 的当前分支） |
| 仓库远端 | `origin` → `https://github.com/blueXYing/pet-platform.git` |

开工前必须运行并核对：

```bash
git -C /mnt/d/wt-marketing-tools rev-parse --show-toplevel
git -C /mnt/d/wt-marketing-tools branch --show-current
git -C /mnt/d/wt-marketing-tools status --short
```

- 文中所有相对路径（`AGENTS.md`、`docs/...`、`planning/...`、`frontend-miniapp/...`、`backend/...`）均相对于该 worktree；执行前先 `cd` 到该 worktree 或在命令中写绝对路径。
- 若根目录不是 `/mnt/d/wt-marketing-tools`，或分支不是 `codex/merchant-marketing-tools-20261117`：**停止并报告**，不要自行切换/创建分支，不要到其他 worktree 开工。
- 不要操作 `/mnt/d/wt-*` 下的其他 worktree；不要删除 `.git/untracked-backup-*` 或 `.git/figma-review-*`。
- 如果用户给了新的 worktree 路径，但该路径没有本执行包/41 号/CCR/76 号文档：停止并报告，先把文档提交/复制到该 worktree，或按用户指示处理。
- 当前 worktree 已有未跟踪的规划文档（`planning/progress/2026-11-17/`、本执行包），这是预期状态；不要清理它们。
- 提交时只 stage 本阶段相关文件；**不要 `git add -A`**；不要提交无关的未跟踪文件（例如 `docs/04-api/74-Service-Catalog-Page-Contract-v0.1-draft.md`、`planning/progress/2026-10-23/devtools-api/`）。
- 只在当前 feature 分支提交；是否 push 按用户指示；**绝不 push `main`/`develop`**。

---

## 1. 仓库规则（硬约束）

- 资料优先级：SSOT > 最终 PRD > 技术基线 > Schema/API/Event/Scheduler > Test。
- 不自行改变产品规则；不实现 41 号补充未列出的能力。
- `biz` 不依赖另一个 `biz`；跨模块只经 `*-api`；不跨模块访问 Repository/Mapper/DO/Entity。
- 持久层统一 MyBatis：SQL 只写在本模块 Mapper XML；禁止新增 `JdbcTemplate`/原生 JDBC/SQL 注解/Java 内联 SQL；通过 `backend/tools/check-persistence-style.py`。
- 所有写操作 `X-Request-Id` 幂等；乐观 `version`；append-only 审计。
- 金额 `BigDecimal` / `DECIMAL(18,2)`；ID Snowflake BIGINT，HTTP/JSON String。
- 新增开关默认 `false`；迁移 opt-in；不授权生产启用。
- 不伪造统计/余额/优惠；页面数字必须有接口事实。
- 不直接 push `main`/`develop`；不扩大 Issue Scope；Contract 缺失走 CCR。
- 本地测试**不要用 Docker**：复用本机 MySQL/Redis 与 `E2eCoreLoopServer`（见 §4.8）。

---

## 2. 固定决策（不得再问、不得改）

### 2.1 P1（本次默认执行）

1. 保留平台活动报名/我的报名；迁入独立页，不删除。
2. 营销工具 hub 6 个工具卡：优惠券、会员卡、限时秒杀、裂变拉新、赠品活动、节日营销。
   - 仅「节日营销」P1 可用；其余显示「筹备中」/「暂不开放」，不可导航。
3. 商家活动仍为 `campaignType=DISPLAY`、`participationMode=PLATFORM_SELECTED`、`ownerType=MERCHANT`，只绑定本人门店。
4. 保留运营审核：`DRAFT → PENDING_REVIEW → SCHEDULED/ONLINE`；商家不可发布；Figma「立即上架」→「提交审核」。
5. P1 新增字段：`description`（≤300）、`themeCode`（白名单）、`highlightText`（≤100）、`playType`（仅 `THEME`）、`coverAssetId`。
6. 封面：草稿可空，提交审核前必填；只能上传到新商家素材端点；商家不得传任意 `coverUrl`。
7. 主题白名单：`SPRING_FESTIVAL/MID_AUTUMN/NATIONAL_DAY/CHRISTMAS/NEW_YEAR/DOUBLE_11/OTHER`。
8. 统计只返回计数：活动总数/进行中/待审核/已报名平台活动数；不返回订单/营收/转化率。
9. 不做节日套餐/买赠/抽奖/优惠力度；未配置真实规则前不展示。
10. 核销记录 `12:7426` 已实现，不改；第三方来源（美团/抖音/点评）不支持。

### 2.2 P2（限时秒杀）

1. 秒杀是服务限时价格，不是新订单类型；仍单次服务、必须预约。
2. 商家创建 → 运营审核 → 窗口生效；商家不可发布。
3. 秒杀价 >0 且 < 服务原价；同一服务同一时间最多一个有效窗口。
4. 下单事务内预占名额；支付成功消费；取消/超时释放；已支付退款不恢复名额。
5. 每单最多一张券；券在秒杀价后计算；折后应付必须 >0。
6. 分账基数 = 服务原价 − 秒杀优惠 − 商家券优惠；平台券不扣商家。
7. 无秒杀时价格来源 `LIST`，行为与现状一致。

### 2.3 P3（商家券）

1. 仅无门槛券/满减券；商家出资；运营审核；商家不可直接发布/发券。
2. 每单最多一张券；**不做平台券+商家券叠加**。
3. 预算/总量/每人限领/有效期由商家配置；预算不足停止领取，不超发。
4. 领取/使用/退款返券沿用 61/72；全额退款返券、部分不返。
5. 结算：商家券面额从商家分账基数扣除；支付中心阶段 2 未交付前不得上线。
6. 统计只展示发券/领取/核销率/带动订单数/净 GMV（定义退款口径后）；不展示曝光/点击。

### 2.4 P4（裂变拉新）

1. P4a 奖励固定为**商家券**（P3），双方奖励；不做现金、不做积分、不做拼团。
2. 被邀请人绑定后获得新客券；邀请人在被邀请人首笔订单**核销完成且 7 天无退款/售后**后获得商家券。
3. 被邀请人必须是该商家新客（无已支付订单）；每人终身只能绑定一个商家邀请关系；禁止自邀；月度上限/总预算封顶。
4. 奖励发放前退款不发；发放后退款撤销未使用券，已使用不追回、不现金追偿。
5. P4b 现金返现/拼团不做；Figma「返现」显示为「返券」，拼团入口不展示。

### 2.5 P5（会员卡）

1. 做完整储值卡/次卡/年卡；资金模型：平台代收充值款，按实际消费/核销结算给商家（依赖支付中心阶段 2–6）。
2. **禁止商家在「添加会员信息」直接填写可用余额**；余额只能来自支付成功的充值单；线下现金收款另立裁决。
3. 储值卡：未消费实付余额可退，赠送金额不退；过期规则由产品配置并购买页明示。
4. 次卡：`实付/总次数×剩余次数` 向下取整退；已使用不退。
5. 年卡：激活后不退；未激活可退；过期作废（购买页明示）；可配置总次数/每月公平使用上限。
6. 订单新增支付方式 `MEMBER_BALANCE/MEMBER_COUNT/MEMBER_YEAR`；不创建微信支付单；禁止 0 元微信单。
7. 账本 append-only；余额/次数不得为负；所有资金写 requestId 幂等 + 审计 + 日终对账。
8. 仅 OWNER 可开卡/退款/查看资金面；员工不可见；手机号脱敏；遵守 69 号隐私红线。
9. 法务/财务书面确认 + 支付中心阶段 2–6 未完成前，不得开启生产开关；可开发但默认关闭。

### 2.6 不做

赠品活动、拼团、买一送一、直播、AI 客服、私信 IM、积分消费、曝光/点击/成交归因（P6 前）。

---

## 3. 阶段门禁（客观判断，不向用户提问）

| 阶段 | 可开始（外部前置；本阶段 CCR/契约由执行者第一步创建） | 可上线 |
|---|---|---|
| P1 | 41 号 + CCR-MKT-TOOLS-001 + 76 号契约在仓库（已存在） | 测试/架构/构建通过；开关默认关闭 |
| P2 | 41 号补充在仓库；执行者先创建 CCR-SVC-FLASH-PRICE-001 + 77 号契约 + 38/11/74/75 修订 | P2 测试通过；开关默认关闭 |
| P3 | 支付中心阶段 2 代码/契约在仓库（检查 `settlement_order`/`SettlementAdminApi`）；执行者先创建 CCR-MKT-MERCHANT-COUPON-001 + 79 号契约 | 预算/分账/退款联调 |
| P4a | P3 可用；执行者先创建 CCR-MKT-REFERRAL-001 + 79 号契约 | 奖励/防刷/退款撤销测试 |
| P4b | 支付中心阶段 5+ | 另立 CCR |
| P5 | 法务/财务书面确认 + 支付中心阶段 2–6 + 订单支付方式契约；执行者先创建 CCR-MEMBER-CARD-001 + 80 号契约 + CCR-PAY-CENTER-002 | 各子阶段验收；资金对账一致 |
| P6 | 新裁决 + 事件/隐私评审 | 归因口径验收 |

编号说明——`docs/04-api` 的 78、80 已被支付结算契约占用（PR #235），P3 改用 79；P4/P5 原预留的 79/80 已不可用，开工时另择空号。

**门禁缺失时**：停止，报告缺失的文件/接口/表，不要用 stub/假数据绕过，也不要问用户「是否继续」。

---

## 4. Phase 1 执行规格（本次默认）

### 4.1 目标交付

- 营销工具 hub（`12:7982`）
- 节日营销列表（`18:11090`）
- 创建/编辑节日活动（`18:11706`）
- 保留平台活动报名（独立页）
- 商家封面素材上传
- 运营后台可见新字段并审核
- 测试/构建全绿

### 4.2 WP-00：文档与契约

已存在（不要重写，校验后使用）：
- `docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md`
- `planning/ccr/CCR-MKT-TOOLS-001.md`
- `docs/04-api/76-Merchant-Marketing-Tools-Contract-v0.1.md`

必须更新：
1. `docs/04-api/71-Marketing-Center-Contract-v0.1.md`
   - 版本记录增加 `v0.4 | 2026-11-17 | 商家自建活动字段（description/themeCode/highlightText/playType/coverAssetId）、商家封面素材、审核展示；P1 不改变交易规则`。
   - §5.3 增加字段/校验/「商家不可发布」说明，并注明「76 号契约为商家字段增量」。
   - §8 增加一句：P1 仅营销工具 hub/自建活动，不放开券/秒杀/裂变/会员卡。
2. `docs/03-database/63-Marketing-Storage-v0.1.sql`
   - `marketing_campaign` 增加 `description/theme_code/highlight_text/play_type/cover_asset_id`。
   - `marketing_asset` 增加 `owner_type/owner_merchant_id/owner_store_id/purpose`。
   - 与迁移 V34 保持一致。
3. `docs/04-api/12-Error-Code-Registry-v0.5.md`
   - 登记 `CAMPAIGN_THEME_INVALID`、`CAMPAIGN_PLAY_TYPE_INVALID`。
4. `docs/08-engineering/20-设计源登记表-figma-map.md` / `planning/progress/2026-10-19/figma-coverage-audit/REPORT.md`
   - 将 `12:7982/18:11090/18:11706` 标注为「P1 已实现」；其余营销工具仍为 V1 外/后续阶段。
5. 不需要新事件/调度；不要改 38/61/72/75 的交易规则。

### 4.3 WP-01：DB 迁移

新增 `backend/pet-boot/src/main/resources/db/marketing-migration/V34__merchant_marketing_tools.sql`（若 V34 已被占用，顺延下一个编号并在文档注明）：

```sql
ALTER TABLE marketing_campaign
    ADD COLUMN description      VARCHAR(300) NULL AFTER subtitle,
    ADD COLUMN theme_code       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER tag_name,
    ADD COLUMN highlight_text   VARCHAR(100) NULL AFTER theme_code,
    ADD COLUMN play_type        VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER highlight_text,
    ADD COLUMN cover_asset_id   BIGINT NULL AFTER cover_url,
    ADD CONSTRAINT chk_marketing_campaign_play_type CHECK (
        play_type IS NULL OR play_type IN ('THEME')
    );

ALTER TABLE marketing_asset
    ADD COLUMN owner_type       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM' AFTER id,
    ADD COLUMN owner_merchant_id BIGINT NULL AFTER owner_type,
    ADD COLUMN owner_store_id   BIGINT NULL AFTER owner_merchant_id,
    ADD COLUMN purpose          VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER owner_store_id,
    ADD CONSTRAINT chk_marketing_asset_owner CHECK (
        owner_type IN ('PLATFORM','MERCHANT')
        AND (owner_type = 'PLATFORM' OR (owner_merchant_id IS NOT NULL AND purpose = 'CAMPAIGN_COVER'))
    ),
    ADD KEY idx_marketing_asset_owner (owner_type, owner_merchant_id, owner_store_id, status);
```

要求：
- 存量 `marketing_asset` 全部默认 `PLATFORM`；`purpose` 保持 NULL；不猜测商家归属。
- `play_type` 存量保持 NULL；新建商家活动写 `THEME`。
- 同步更新 `docs/03-database/63-Marketing-Storage-v0.1.sql` 的 CREATE TABLE。
- 迁移只做结构变更，不写业务数据；不在迁移中回填封面/主题。
- 测试数据库通过 Flyway/测试装配执行；不要用 Docker。

### 4.4 WP-02：后端

#### 4.4.1 API/DTO

- `backend/pet-marketing-api/src/main/java/com/petplatform/marketing/api/MarketingMerchantApi.java`
  - `MerchantCreateCampaignCommand` 增加 `String description, String themeCode, String highlightText, String playType, String coverAssetId`。
  - `MerchantUpdateCampaignCommand` 同样增加（放在 `coverUrl` 之后、`startAt` 之前，保持兼容）。
  - 新增 `record MerchantOverviewQuery(MerchantScope scope, QueryContext context)`。
  - 新增 `record UploadMerchantAssetCommand(MerchantScope scope, byte[] content, String contentType, CommandContext context)`。
  - 新增方法 `MarketingViews.MerchantOverview overview(MerchantOverviewQuery query)`、`MarketingViews.AssetView uploadAsset(UploadMerchantAssetCommand command)`。
- `MarketingViews.java`
  - `CampaignCard` 增加 `description/themeCode/themeLabel/highlightText/playType/coverAssetId/coverUrl`（放在 `subtitle` 后，保持字段可空）。
  - 新增 `record MerchantOverview(long totalCampaignCount, long activeCampaignCount, long pendingReviewCount, long enrolledCampaignCount)`。
  - `AssetView` **不加** `url`；HTTP 响应的 `url` 由控制器按 `pet.marketing.asset.public-base-url` 拼接（与 `MarketingAdminController.uploadAsset` 完全一致）。
- `MarketingEntities.java`
  - `CampaignRow` 增加 `description/themeCode/highlightText/playType/coverAssetId`。
  - `AssetRow` 增加 `ownerType/ownerMerchantId/ownerStoreId/purpose`。

#### 4.4.2 Service

`backend/pet-marketing-biz/src/main/java/com/petplatform/marketing/biz/application/MarketingMerchantService.java`：

- 构造器增加 `MarketingAssetObjectStore assetObjectStore` 与 `String assetPublicBaseUrl`（或共享 `MarketingAssetWriter`）；同步改：
  - `MarketingMerchantApiImpl` 构造器；
  - `backend/pet-boot/src/main/java/com/petplatform/boot/config/MarketingConfiguration.java` 的 `marketingMerchantApi(...)` Bean（`MarketingAssetObjectStore` 已是同配置中的 Bean；新增 `@Value("${pet.marketing.asset.public-base-url:}")`）；
  - `MarketingHotServiceMySqlTest` 中 `new MarketingMerchantApiImpl(...)` 的测试装配。
- `assetPublicBaseUrl` 为空时：商家上传封面返回 503（与运营上传一致）；创建/编辑带 `coverAssetId` 也返回 503，不落无 URL 的活动。
- `createMyCampaign` / `updateMyCampaign`：
  - 校验 `description` ≤300、`highlightText` ≤100、`themeCode` 白名单、`playType` 为空或 `THEME`。
  - `coverAssetId` 可选；提供时 `requireOwnedAsset(scope, coverAssetId)`：查 `marketing_asset`，要求 `ACTIVE`、`owner_type=MERCHANT`、`owner_merchant_id=scope.merchantId`、`owner_store_id` 为空或 `scope.storeId`、`purpose=CAMPAIGN_COVER`；否则 400 `MARKETING_ASSET_INVALID`。
  - 设置 `coverUrl` = `assetPublicBaseUrl`（去掉末尾 `/`）+ `/api/v1/c/marketing/assets/{assetId}`；商家请求中的 `coverUrl` 非空 → 400。与运营上传返回的 URL 规则一致。
  - `playType` 默认 `THEME`；`themeLabel` 由服务端映射（见下）。
  - 其余状态/幂等/版本逻辑不变。
- `submitMyCampaign`：
  - 在现有状态校验后增加：`coverAssetId` 非空且 `requireOwnedAsset` 通过；`endAt > now`；`themeCode` 合法；`playType=THEME`。失败 409 `CAMPAIGN_STATE_NOT_ALLOWED` 或 400。
- `listMyCampaigns`：返回新字段与 `themeLabel`；`themeLabel` 映射：春节/中秋/国庆/圣诞/元旦/双11/其他。
- `overview`：用 Mapper 计数，不读订单表；`active` 按 `status=ONLINE AND startAt<=now<endAt`；`pendingReview`；`enrolled` 按 `marketing_enrollment` 的 `SUBMITTED/UNDER_REVIEW/APPROVED` 且 `merchantId/storeId` 匹配。
- `uploadAsset`：
  - 校验 OWNER/门店可运营；文件非空、≤2MiB、PNG/JPEG、1..2000 像素；
  - 复用运营上传的校验/存储逻辑：建议抽出 `MarketingAssetWriter`（`pet-marketing-biz/application`）供 admin/merchant 共用；不要复制两套逻辑。
  - 写 `marketing_asset` 时设置 `owner_type=MERCHANT`、`owner_merchant_id`、`owner_store_id`、`purpose=CAMPAIGN_COVER`；写 `marketing_asset_audit`；返回 `AssetView`（含 `url`）。
  - 幂等键：`MKT:merchant.asset.upload:{merchantId}:{storeId}:{requestId}`；同 requestId 同参返回首回执。

`MarketingSupport`/错误映射：`CAMPAIGN_THEME_INVALID`、`CAMPAIGN_PLAY_TYPE_INVALID` 用 400；素材错误沿用 `MARKETING_ASSET_INVALID/TOO_LARGE`。

#### 4.4.3 Mapper XML

`backend/pet-marketing-biz/src/main/resources/mapper/MarketingMapper.xml`：

- `insertCampaign`/`updateCampaign` 增加新字段。
- `selectMyCampaigns`/`selectCampaignById`/`selectCampaignForUpdate` 等所有 `CampaignRow` 查询增加新列；避免漏列导致字段丢失。
- `insertAsset` 增加归属字段；`selectAssetById`（若不存在则新增）按 id 读归属/status。
- 新增计数 SQL：
  - `countMerchantCampaigns(merchantId, storeId, status?, activeOnly?)`
  - `countMerchantEnrollments(merchantId, storeId, statuses)`。
- 所有 SQL 写在本 XML；不新增注解/内联 SQL。
- 更新 `MarketingMapper.java` 方法签名。

#### 4.4.4 Controller

`backend/pet-boot/src/main/java/com/petplatform/boot/adapter/web/marketing/MarketingMerchantController.java`：

- `POST /api/v1/merchant/marketing/assets`：`@RequestPart("file") MultipartFile`，严格单文件/单参数；`X-Request-Id`；调用 `marketing.uploadAsset`。
- 注入 `@Value("${pet.marketing.asset.public-base-url:}")`；base 为空 → 503；成功响应按 `MarketingAdminController.uploadAsset` 的格式拼接 `url = base + "/api/v1/c/marketing/assets/" + assetId`，并返回 `assetId/url/contentType/bytes/width/height/sha256/createdAt`。
- `GET /api/v1/merchant/marketing/overview`：query `merchantId/storeId`；调用 `overview`。
- `POST/PUT my-campaigns`：严格 JSON 读取新字段；未知字段拒绝（沿用现有 `JsonReader` 习惯）；把新字段传入 command。
- 响应沿用信封与分页；`Cache-Control: no-store`。
- 不新增商家发布端点。

#### 4.4.5 运营端

- `MarketingAdminService`/`MarketingAdminController`：`CampaignInput` 增加新字段；`campaignRow` 写入；detail/list 返回；发布前校验 `playType` 与封面（若 `coverAssetId` 非空）。
- 运营仍可设置/覆盖封面（平台素材或商家素材）；不得让商家活动绕过审核。
- 运营页面展示新字段。

### 4.5 WP-03：前端

#### 4.5.1 API/模型

`frontend-miniapp/src/merchant/marketing/api.ts`：

- `MyCampaign` 增加 `description/themeCode/themeLabel/highlightText/playType/coverAssetId/coverUrl/rejectReason`。
- 新增 `Overview` 类型与 `overview(merchantId, storeId)`：`{totalCampaignCount, activeCampaignCount, pendingReviewCount, enrolledCampaignCount}`。
- 新增 `uploadMarketingAsset(filePath): Promise<{assetId,url,contentType,bytes,width,height,sha256}>`：走新 transport（见下），不要用 `api.request` 发 multipart。
- `createCampaign`/`updateCampaign` 输入增加新字段；`coverUrl` 不再由前端传。
- 严格解码：字符串长度、枚举白名单、ID/版本格式；未知字段忽略但类型不符 fail。

#### 4.5.2 上传通道

`frontend-miniapp/src/shared/consumer-api.ts` + `consumer-runtime.ts`：

- 新增 `MarketingAssetTransport` 类型：`(input:{filePath,requestId,authorization}) => Promise<{statusCode,data}>`。
- `ConsumerApi` 构造器增加可选 `marketingAssetTransport`；新增 `uploadMarketingAsset` 方法（**仿照现有 `uploadPrivateAsset` 的 credential/session/scope ticket 校验**）：
  - 需要登录会话；`Authorization: Bearer ...`；`X-Request-Id`；`timeout 60000`。
  - 解析 JSON 信封，校验 `assetId/url/contentType/bytes/width/height/sha256`；失败抛 `ApiError`；401 时 `this.clear()`（与 `uploadPrivateAsset` 一致）。
  - 不要把 `assetId` 写进私有素材 journal；营销封面使用独立 pending key（按 userId/merchantId/storeId 隔离），上传成功/放弃后删除本地副本。
- `consumer-runtime.ts` 注入：
  ```ts
  C_API_ORIGIN ? async input => {
    assertApiOrigin(C_API_ORIGIN, ALLOW_LOCAL_HTTP)
    const result = await Taro.uploadFile({
      url: `${C_API_ORIGIN}/api/v1/merchant/marketing/assets`,
      filePath: input.filePath, name: 'file',
      header: { Authorization: input.authorization, 'X-Request-Id': input.requestId },
      timeout: 60000,
    })
    return { statusCode: result.statusCode, data: result.data }
  } : undefined
  ```
- 页面层复用 `privateUploadFiles()` 的 `choose/save/inspect/remove` 选择与校验；上传成功后删除本地副本；失败保留可重试。不要把文件字节存进 storage。

#### 4.5.3 页面/路由

`frontend-miniapp/src/app.config.ts` merchant 子包增加：

```ts
'pages/marketing/platform/index',
'pages/marketing/campaigns/index',
'pages/marketing/campaigns/edit',
```

每个页面目录必须有对应的 `index.config.ts`（或 `edit.config.ts`），导航标题与现有一级/二级页规范一致；同步更新 `frontend-miniapp/src/merchant/tests/tab-bar-nav.test.ts` 中与 marketing 路由相关的断言。

文件：

1. `merchant/pages/marketing/index.tsx` → hub（`12:7982`）
   - 顶部概览卡：`total/active/pending/enrolled` 四个计数；失败显示「—」。
   - 6 个工具卡（顺序：优惠券、会员卡、限时秒杀、裂变拉新、赠品活动、节日营销）：
     - 节日营销可用 → `/merchant/pages/marketing/campaigns/index`
     - 其余 `disabled` + 徽标「筹备中」/「暂不开放」；不导航。
   - 平台活动入口：单独一行「平台活动报名 / 我的报名」→ `/merchant/pages/marketing/platform/index`。
   - 底部 `MerchantTabBar active='home'`；返回逻辑沿用 `reLaunch('/merchant/pages/workspace/index')`。
   - 样式沿用 402 设计宽与现有营销页 CSS 变量；图标可从 Figma `12:7982` 导出，不要重绘。
2. `merchant/pages/marketing/platform/index.tsx`
   - 迁移现页 `open/mine` 两 tab 的加载/报名/撤回逻辑；保留 API 与错误文案。
   - 不显示「我的活动」tab（已迁至 campaigns）。
3. `merchant/pages/marketing/campaigns/index.tsx`（`18:11090`）
   - 顶部 banner/统计（活动数/进行中/待审核，来自 overview）。
   - `+ 创建活动` → `campaigns/edit`。
   - 列表：标题、副标题/说明、日期、状态胶囊（草稿/待审核/已驳回/待开始/进行中/已结束/已取消）。
   - `DRAFT/REJECTED` 点击进编辑；其他只读详情（可在 edit 页 `readonly`）。
   - 空态/失败态/加载态。
4. `merchant/pages/marketing/campaigns/edit.tsx`（`18:11706`）
   - 字段：封面（选择/更换/预览）、活动名称（name/title 同值）、节日主题 chips、活动玩法（仅「主题活动」可选；其余置灰「待开放」）、节日主打项目（highlightText）、活动有效期（两个日期 Picker）、活动说明（Textarea maxlength 300 + 计数）。
   - 不显示「优惠力度」。
   - 按钮：「保存草稿」「提交审核」；提交前二次确认文案「提交后由运营审核，审核通过后展示」。
   - `DRAFT/REJECTED` 可编辑；其他状态只读。
   - 上传中禁用提交；失败提示可重试；不得伪造成功。
   - 保存/提交成功后回到列表并刷新。

样式与测试：
- 新页面 CSS 放各自目录或共享 `marketing.css`；复用现有字体/颜色/间距；不引入新 UI 库。
- 更新 `frontend-miniapp/src/merchant/tests/*`：解码、状态映射、路由、上传流程的纯逻辑测试；不要依赖真实网络。
- `npm run typecheck`、`npm test`、`npm run build:weapp` 必须通过。

### 4.6 WP-04：运营后台

`frontend-admin/src/pages/MarketingCenterPage.tsx` + `src/api/marketingAdmin.ts`：

- 活动列表/详情展示 `description/themeCode/themeLabel/highlightText/playType/coverUrl`。
- 详情页可预览封面；发布/驳回按钮不变。
- 若运营编辑表单存在，增加这些字段（可编辑）；不要新增商家发布入口。
- `npm run typecheck`/build 按 admin 包脚本执行（先看 `frontend-admin/package.json`）。

### 4.7 WP-05：测试与验收

后端：
```bash
cd backend
python3 tools/check-persistence-style.py
python3 tools/check-module-deps.py
mvn -q -pl pet-marketing-biz -am test
mvn -q test   # 或至少 pet-marketing-biz + pet-boot 相关测试
```
前端：
```bash
cd frontend-miniapp
npm run typecheck
npm test
npm run build:weapp
```

必须覆盖：
- 创建/编辑：合法字段成功；非法主题/玩法 400；无封面提交 409/400；有封面提交成功。
- 素材：仅本人/本店可用；跨店/平台素材 403/400；文件类型/大小/像素；同 requestId 重放。
- 审核：商家提交后运营可见；运营发布后状态；驳回原因；商家不可发布。
- 概览：4 个计数正确；不含订单/营收字段。
- 平台报名：原流程回归。
- 权限：非 OWNER 403；门店不可运营 409。
- 架构/SQL 风格检查通过。

### 4.8 本地运行（不要 Docker）

按项目既有本地栈：
- 本机 MySQL（WSL 通过 TCP forwarder，常见 `127.0.0.1:13306`）、Redis `127.0.0.1:6379`、隔离 auth Redis `127.0.0.1:16379`。
- 用 `E2eCoreLoopServer`（Spring Boot test-domain launcher）启动后端；营销开关需 `pet.marketing.enabled/admin.http/merchant.http/c.http=true`，封面素材需要 asset base + OSS 或 BLOB 回退。
- 复用已有数据库时先确认表完整；不要在未确认时设置 `E2E_DATABASE_NAME`。
- **不要启动 Docker Desktop/容器**。

### 4.9 Phase 1 完成标准

- 4 个页面可用；平台报名无回归；商家可上传封面、创建/提交审核；运营可审核发布。
- 未开放工具不可进入；统计不伪造；商家不能发布/传任意 URL。
- 后端/前端测试与构建全绿；SQL 风格/架构检查通过。
- 契约/错误码/Schema/设计登记已更新；PR 描述含测试证据与遗留风险。

---

## 5. Phase 2 执行规格（限时秒杀）

> 执行前读 `PLAN.md` §7 + §2.2 固定决策。未收到 Phase 2 指令不要开始。

### 5.1 文档

- 新建 `planning/ccr/CCR-SVC-FLASH-PRICE-001.md`（APPROVED，依据 41 号补充 §4）。
- 新建 `docs/04-api/77-Service-Flash-Price-Contract-v0.1.md`。
- 更新 `docs/04-api/38-Atomic-Booking-Create-Contract-v0.1.md`（价格来源/快照）、`11`（订单字段）、`74`（服务卡展示）、`75`（分账基数）。
- 更新 `docs/03-database` 服务价格表与 `12` 错误码。

### 5.2 数据（`pet-service` 自有）

```text
service_flash_price
  id, merchant_id, store_id, service_id,
  flash_price DECIMAL(18,2), list_price_snapshot DECIMAL(18,2),
  start_at, end_at, total_quota INT NULL, per_user_limit INT NULL,
  sold_count INT NOT NULL DEFAULT 0,
  status DRAFT/PENDING_REVIEW/SCHEDULED/ACTIVE/ENDED/REJECTED/CANCELED,
  reject_reason, version, created_by..., created_at, updated_at
  CHECK flash_price > 0 AND flash_price < list_price_snapshot
  CHECK total_quota IS NULL OR total_quota > 0

service_flash_price_order
  id, flash_price_id, order_id, user_id, reserved_price,
  status RESERVED/CONSUMED/RELEASED,
  created_at, updated_at
  UNIQUE(order_id), KEY(flash_price_id,user_id,status)

service_flash_price_audit
service_flash_price_command  -- requestId 幂等
```

### 5.3 规则/接口

- 商家端点：`GET/POST/PUT /api/v1/merchant/services/{serviceId}/flash-prices`、`submit/cancel`。
- 运营端点：`GET /api/v1/admin/service-flash-prices`、`approve/reject/pause/end`。
- C 端服务卡/详情/订单确认返回 `flashPrice/flashStartAt/flashEndAt/remaining/priceSource`。
- 下单事务内 `SELECT ... FOR UPDATE` 锁定秒杀行 → 校验窗口/库存/每人限领 → `sold_count+1` + 预占行 → 返回价格；支付超时/取消释放；支付成功 `CONSUMED`；退款不回补。
- 订单快照新增 `price_source=LIST/FLASH`、`flash_price_id`、`original_price`、`flash_discount`。
- 分账基数按 41 号 §4.6。

### 5.4 前端/测试

- 商家：`marketing/flash/index`（`18:9537`）、`edit`（`18:9694`）；服务选择器仅本店 ACTIVE 服务。
- C 端：服务卡/详情/订单确认展示秒杀价/倒计时/售罄。
- 运营：审核列表/详情。
- 测试：并发最后 1 个名额、价格快照、支付/退款/分账、无秒杀兼容。

---

## 6. Phase 3 执行规格（商家券）

> 前置检查：仓库存在支付中心阶段 2 的 `settlement_order`/`SettlementAdminApi`（`grep -R` 确认）。不存在则停止并报告，不要用 stub。

### 6.1 文档/数据

- 新建 `planning/ccr/CCR-MKT-MERCHANT-COUPON-001.md`。
- 新建 `docs/04-api/79-Merchant-Coupon-Campaign-Contract-v0.1.md`（或 72 号 v0.2）。
- 更新 61（模板归属，不改生命周期）、75（分账基数）、12。
- `coupon_template` 增 `owner_type/owner_merchant_id/funding_type`；`coupon_campaign` 增 `owner_type/owner_merchant_id/owner_store_id/status` 审核态/`reject_reason/budget_reserved/budget_used`；新增 `merchant_coupon_budget`、审计/幂等表。

### 6.2 规则/接口

- 仅无门槛/满减；商家出资；运营审核；不叠加平台券；每单一张。
- 商家端点：模板只读/创建、券活动 CRUD/提交/取消/统计；运营审核/暂停/结束。
- 结算：商家券面额扣减商家分账基数；退款按 75 重算；日对账。
- 前端：`marketing/coupon/index`（`12:8436`）、`edit`（`12:8575`）；运营审核页。
- 测试：预算并发、重复领取、退款返券、分账、权限、跨店。

---

## 7. Phase 4 执行规格（裂变拉新）

> 前置：P3 可用；否则停止报告。P4b 现金返现/拼团不启动。

### 7.1 文档/数据

- 新建 `planning/ccr/CCR-MKT-REFERRAL-001.md`、`docs/04-api/79-Merchant-Referral-Contract-v0.1.md`。
- 表：`merchant_referral_program`、`merchant_referral_code`、`merchant_referral_binding`、`merchant_referral_reward`、审计/幂等。
- 规则按 §2.4；奖励只发商家券（P3）。

### 7.2 接口/前端/测试

- 商家：program CRUD/submit/cancel/stats；C 端绑定/我的邀请；运营审核。
- 前端：`marketing/referral/index`（`18:10180`）、`edit`（`18:10337`）；「返券」文案，不显示「返现/拼团」。
- 事件：`OrderPaidEvent`/`OrderVerifiedEvent.v2`/`RefundSucceededEvent.v1`；7 天窗口用 durable AsyncTask。
- 测试：自邀/重复绑定/已有订单/上限/退款撤销/奖励幂等/并发唯一。

---

## 8. Phase 5 执行规格（会员卡）

> 这是独立资金域。未收到 Phase 5 指令不要开始。先检查：法务/财务书面确认、支付中心阶段 2–6、订单支付方式契约、CCR-MEMBER-CARD-001。任一缺失 → 停止并报告，不要 stub。

### 8.1 文档

- 新建 `planning/ccr/CCR-MEMBER-CARD-001.md`、`docs/04-api/80-Member-Card-Contract-v0.1.md`、`planning/ccr/CCR-PAY-CENTER-002.md`。
- 更新 38/11/40/41/49/50/48/75/12/事件目录。
- 新模块 `pet-membercard-api`/`pet-membercard-biz`；`pet-boot` 装配。

### 8.2 子阶段（一次只做一个）

| 子阶段 | 内容 | 关键验收 |
|---|---|---|
| P5.1 | 卡产品目录 + 会员档案 + 开卡（无资金变动） | 商家可建产品/开卡；无可用余额 |
| P5.2 | 充值支付 + 余额账本 + 充值退款 | 支付成功才入账；重放不重复；对账 |
| P5.3 | 余额支付订单 + 退款回余额 | 订单内部支付；余额不为负；退款可追溯 |
| P5.4 | 次卡/年卡购买/预占/扣次/核销 | 取消释放；退款回补；核销不重复扣 |
| P5.5 | 平台代收结算 + 商家钱包/提现 | 按消费结算；对账一致 |
| P5.6 | 运营工具/报表/合规审计 | 法务/财务验收 |

### 8.3 数据/规则

按 `PLAN.md` §10.2 的表与 `41` 号 §7：
- `member_card_product`、`member_card_account`、`member_card_ledger`、`member_card_recharge_order`、`member_card_refund_order`、`member_card_order_payment`、审计/幂等。
- 余额/次数不为负；账本 append-only；`request_id` 唯一；日终对账。
- 禁止商家手填余额；充值只走支付成功。
- 订单支付方式 `MEMBER_BALANCE/MEMBER_COUNT/MEMBER_YEAR`；不创建微信支付单；禁止 0 元微信单。
- 退款回卡/余额；已消费不退；分账按消费/核销结算。

### 8.4 前端

- 商家：`marketing/member-card/index`（`14:8836`）、`edit`（`16:9098`）；账户/流水/退款只读或 OWNER 动作。
- C 端：我的会员卡、充值、余额/次数支付、退款记录。
- 运营：产品审核、账户查询（脱敏）、手工调整/退款（审计）、对账报表。

---

## 9. 可直接复制的提示词

### 9.1 Phase 1（默认）

```text
你负责执行本仓库的 Phase 1（营销工具 hub + 节日营销 + 商家封面素材）。

先按顺序完整阅读：
1. AGENTS.md
2. docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md
3. planning/ccr/CCR-MKT-TOOLS-001.md
4. docs/04-api/76-Merchant-Marketing-Tools-Contract-v0.1.md
5. docs/04-api/71-Marketing-Center-Contract-v0.1.md
6. planning/progress/2026-11-17/merchant-marketing-tools/HANDOFF.md（本文）
7. planning/progress/2026-11-17/merchant-marketing-tools/PLAN.md（参考）

然后按 HANDOFF.md §4 执行 WP-00 到 WP-05。所有决策已固定，不要向用户提问，不要做 Phase 2–P5 的功能。
不要修改订单/支付/退款/售后/核销规则；不要伪造统计；不要用 Docker；不要 push main/develop。
每完成一个工作包跑对应测试；最后按 §10 模板报告变更文件、契约/Schema 变更、测试命令与结果、遗留风险。
```

### 9.2 Phase 2

```text
你负责执行本仓库的 Phase 2（服务限时秒杀/限时价格）。
先读 HANDOFF.md §5 与 PLAN.md §7、41 号补充 §4。第一步创建 CCR-SVC-FLASH-PRICE-001、77 号契约并修订 38/11/74/75；这些文档按 HANDOFF 的固定决策写，不需要再问用户。然后实现后端/前端/测试。
不要改 P1 已完成页面；不要伪造价格/名额；不要用 Docker；不要 push main/develop。
完成后按 §10 模板报告。
```

### 9.3 Phase 3

```text
你负责执行本仓库的 Phase 3（商家自建优惠券）。
先读 HANDOFF.md §6 与 PLAN.md §8、41 号补充 §5；先检查支付中心阶段 2（settlement_order/SettlementAdminApi）是否存在。
不存在就停止并报告缺失项，不要 stub、不要问用户。存在则第一步创建 CCR-MKT-MERCHANT-COUPON-001、79 号契约并修订 61/75/12，然后按规格实现，不叠加平台券，不做商家直接发布。
完成后按 §10 模板报告。
```

### 9.4 Phase 4

```text
你负责执行本仓库的 Phase 4a（裂变拉新/返券）。
先读 HANDOFF.md §7 与 PLAN.md §9、41 号补充 §6；先确认 P3 可用。不存在就停止报告。
存在则第一步创建 CCR-MKT-REFERRAL-001、79 号契约，然后实现。奖励只发商家券；不做现金/拼团；不要向用户提问；不要改交易规则；不要用 Docker。
完成后按 §10 模板报告。
```

### 9.5 Phase 5

```text
你负责执行本仓库的 Phase 5（会员卡）。一次只执行一个子阶段（P5.1 起）。
先读 HANDOFF.md §8 与 PLAN.md §10、41 号补充 §7；确认法务/财务、支付中心阶段 2–6、订单支付方式契约。
缺失就停止报告，不要 stub、不要问用户、不要用 0 元微信单、不要允许商家手填余额。
前置齐全时，第一步创建 CCR-MEMBER-CARD-001、80 号契约、CCR-PAY-CENTER-002 并修订相关契约，然后实现当前子阶段。
完成后按 §10 模板报告。
```

---

## 10. 完成报告模板

```text
Phase: P?
状态: 完成 / 部分完成 / 阻塞

变更文件:
- ...

契约/Schema/Event 变化:
- ...

测试:
- 命令:
- 结果: 通过/失败（贴关键输出）

未完成/阻塞:
- 缺失的前置（文件/接口/表/审批）
- 需要用户决策的冲突（仅限本文未覆盖的）

遗留风险:
- ...
```

**禁止**：没有测试输出就说完成；把阻塞说成完成；自行改硬规则；伪造页面数据。

---

## 11. 硬停止条件

遇到以下情况立即停止并报告，不要继续、不要问用户「是否继续」：

1. 缺少本阶段门禁文件/接口/表/审批。
2. 现有代码与本文规格冲突，且冲突涉及订单/支付/退款/售后/核销/排期规则。
3. 需要修改 SSOT/PRD 但 41 号补充未覆盖。
4. 测试失败且无法在不改硬规则的前提下修复。
5. 需要真实资金/生产开关/生产数据。
