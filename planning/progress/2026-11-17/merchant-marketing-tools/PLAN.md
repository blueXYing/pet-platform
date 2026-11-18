# 商家端营销工具与会员卡改造方案

> **执行入口**：交给执行智能体时请使用同目录的 `HANDOFF.md`（含固定决策、Phase 1 规格与可复制提示词）。
> 产品决策依据：`docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md`。
> 本文保留完整的背景、差距分析和后续阶段设计，供执行时参考。

- 状态：**DRAFT / 分析版**（2026-11-17；执行版见 `HANDOFF.md`）
- 范围：`frontend-miniapp` 商家端营销工具；`pet-marketing`、`pet-coupon`、`pet-service`、`pet-payment`、`pet-order`、`pet-verification` 及新增会员卡模块
- 目标：让商家端营销页与 Figma 商家端 14 张设计稿对齐，同时保留现有「报名平台活动」能力，并新增商家自建活动、商家自建优惠券、限时秒杀、裂变拉新、完整会员卡（储值/次卡/年卡）
- 执行对象：后续智能体按本文 §12 工作包拆分执行；**每个阶段独立 CCR/PR，不得一次性合入**

---

## 0. 结论（先读）

### 0.1 14 张 Figma 稿的判定

| # | Figma frame | node-id | 当前状态 | 判定 | 阶段 |
|---|---|---|---|---|---|
| 1 | 首页-营销工具 | `12:7982` | 无；现页是营销活动三 tab | 新增营销工具 hub | **P1** |
| 2 | 首页-营销工具-节日营销 | `18:11090` | 无 | 用 71 号 `DISPLAY` 活动承载，保留运营审核 | **P1** |
| 3 | 首页-营销工具-节日营销-创建活动 | `18:11706` | 无 | 扩展商家活动字段；`立即上架`改为`提交审核` | **P1** |
| 4 | 首页-营销工具-优惠券 | `12:8436` | 无 | 商家自建券；依赖结算与规则变更 | P3 |
| 5 | 首页-营销工具-优惠券-创建优惠券活动 | `12:8575` | 无 | 同上 | P3 |
| 6 | 首页-营销工具-限时秒杀 | `18:9537` | 无 | 服务限时价格；依赖价格快照契约 | P2 |
| 7 | 首页-营销工具-限时秒杀-创建秒杀 | `18:9694` | 无 | 同上 | P2 |
| 8 | 首页-营销工具-裂变拉新 | `18:10180` | 无 | 先做邀请返券/返积分；返现、拼团阻塞 | P4 |
| 9 | 首页-营销工具-裂变拉新-创建拉新活动 | `18:10337` | 无 | 同上 | P4 |
| 10 | 首页-营销工具-赠品活动 | `18:10697` | 无 | 本轮未选；V1 明确不做 | 不做 |
| 11 | 首页-营销工具-赠品活动-创建活动 | `18:10938` | 无 | 同上 | 不做 |
| 12 | 首页-营销工具-会员卡 | `14:8836` | 无 | 完整储值/次卡/年卡；强依赖支付中心阶段 2–6 与合规 | P5 |
| 13 | 首页-营销工具-会员卡-添加会员信息 | `16:9098` | 无 | 同上；储值金额不得由商家凭空填写 | P5 |
| 14 | 首页-员工管理-核销记录 | `12:7426` | **已实现** `merchant/pages/staff-verify/index`（CCR-M-VERIFY-RECORD-001） | 不改；美团/抖音/点评来源不支持 | — |

### 0.2 三个关键事实

1. **「商家自建活动」后端已经存在**：71 号契约 §5.3 与 `MarketingMerchantService` 已支持商家创建 `DISPLAY` 活动、编辑、提交审核、取消；前端 `merchant/pages/marketing/index` 的「我的活动」也已有最小表单。当前问题不是「没有能力」，而是**字段/页面与 Figma 差距大**（无封面、无主题/玩法、无说明、无列表详情页）。
2. **会员卡、商家券、秒杀、裂变目前都是 V1 明确不做**：`docs/01-prd/38-营销活动中心裁决补充-v1.0.md` §3 与 71 号契约 §8 明列「秒杀/拼团/裂变/会员卡」「商家自主发券」不做；SSOT §19 明确「只做单次服务；套餐、次卡、普通商品均不做」。因此本方案不是「补页面」，而是**V1 范围变更**，必须先有 41 号裁决补充、SSOT 修订和逐项 CCR。
3. **支付中心目前只有阶段 1**：`CCR-PAY-CENTER-001` 已批准，但结算单、分账、回退、商家钱包、提现属于阶段 2–6，尚未交付；当前收款商户是平台唯一商户，营销不得修改 `pay_amount`。**会员卡储值、商家出资券、裂变返现都卡在这条依赖上**，不能先做页面后补账。

### 0.3 推荐交付顺序

| 阶段 | 内容 | 是否可近期执行 | 前置门禁 |
|---|---|---|---|
| P0 | 41 号裁决补充 + 各 CCR/契约 | 立即 | 用户签署本方案 |
| P1 | 营销工具 hub + 节日营销（自建活动） | 是 | CCR-MKT-TOOLS-001 批准 |
| P2 | 限时秒杀（服务限时价格） | 中期 | CCR-SVC-FLASH-PRICE-001 批准；订单价快照改造 |
| P3 | 商家自建优惠券 | 受阻塞 | 支付中心阶段 2+；CCR-MKT-MERCHANT-COUPON-001 |
| P4 | 裂变拉新（先返券/积分） | 受 P3 阻塞 | P3 可用；返现需支付中心阶段 5 |
| P5 | 会员卡（储值/次卡/年卡） | 受强阻塞 | SSOT 修订 + 法律/合规 + 支付中心阶段 2–6 + 订单支付方式扩展 |
| P6 | 营销归因与统计 | 可选 | 新裁决；71 号 §8 当前禁止 |

**不要**把 P2–P5 的页面先做成「能点但无真实规则」的空壳；Figma 中的折扣、余额、返现数字必须有后端事实支撑，禁止伪造。

---

## 1. 现状盘点

### 1.1 前端现状

- 页面：`frontend-miniapp/src/merchant/pages/marketing/index.tsx` + `index.css` + `index.config.ts`
- API：`frontend-miniapp/src/merchant/marketing/api.ts`
- 入口：
  - 工作台 8 宫格「营销工具」→ `openMarketing()` → `/merchant/pages/marketing/index`
  - 我的 → 「营销活动」→ 同页
- 当前页面三 tab：
  1. `可报名活动`：平台招商活动列表、报名（offerTag/offerTitle/offerCopy）
  2. `我的报名`：报名状态、撤回
  3. `我的活动`：商家创建 `DISPLAY` 草稿、编辑、提交审核、取消
- 路由注册：`frontend-miniapp/src/app.config.ts` merchant 子包 `pages/marketing/index`
- 视觉：402 设计宽、`--mmk-unit`、底部 `MerchantTabBar`；无封面/主题/说明字段；无列表/详情分页。
- 商家端已有私有素材上传基础设施：`CPrivateAssetController` 的 `SERVICE_COVER`/`STORE_PHOTO` purpose，`privateUploadFiles`、`ServiceCoverUpload`；营销封面可复用同一管线，但需新增营销 purpose 与归属校验。

### 1.2 后端现状

- 契约：`docs/04-api/71-Marketing-Center-Contract-v0.1.md`（标题 v0.3）
- 商家接口：
  - `GET /api/v1/merchant/marketing/campaigns`
  - `POST /api/v1/merchant/marketing/campaigns/{id}/enrollments`
  - `GET /api/v1/merchant/marketing/enrollments`
  - `POST /api/v1/merchant/marketing/enrollments/{id}/withdraw`
  - `GET/POST /api/v1/merchant/marketing/my-campaigns`
  - `PUT /api/v1/merchant/marketing/my-campaigns/{id}`
  - `POST /api/v1/merchant/marketing/my-campaigns/{id}/submit|cancel`
- 服务：`MarketingMerchantService`；商家活动固定 `campaignType=DISPLAY`、`participationMode=PLATFORM_SELECTED`、`ownerType=MERCHANT`、只绑定本人门店；`DRAFT/REJECTED` 可编辑；提交后 `PENDING_REVIEW`；运营 `publish/reject`。
- 存储：`docs/03-database/63-Marketing-Storage-v0.1.sql`；`marketing_campaign.campaign_type` CHECK 仅 `DISPLAY/COUPON/MERCHANT_ENROLL`；`marketing_asset` 无归属字段，仅运营上传。
- 商家活动没有封面/说明/主题字段；`coverUrl` 后端支持但前端未采集，且商家不能上传素材。

### 1.3 文档与范围现状

- `docs/01-prd/03-PRD-商家端-V1.0-最终基线.docx`：**「营销工具/营销活动/会员卡/秒杀/裂变/赠品/节日」命中 0 次**；5.7 店铺资料仅促销等级 1–9 折、促销标题/文案。
- `docs/01-prd/38-营销活动中心裁决补充-v1.0.md`：
  - §1.2 商家端：可报名、商家发起活动（审核后展示）、不得自建券/发券；
  - §2 设计冲突：团购套餐、买一送一、折扣券不做；
  - §3 硬规则：不实现「秒杀/拼团/裂变/会员卡/直播」；商家活动/报名必须审核。
- `docs/04-api/71-Marketing-Center-Contract-v0.1.md` §8：同样列明不做上述能力；营销不得创建订单、不得修改 `pay_amount`。
- `docs/04-api/72-Coupon-Campaign-Contract-v0.1.md` §7：商家自主发券不做。
- `docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md` §19：只做单次服务；套餐、次卡、普通商品不做。
- Figma 覆盖审计 `planning/progress/2026-10-19/figma-coverage-audit/REPORT.md` §5.3：营销工具族 13 帧全部 **C2/V1 范围外**。
- 支付：`docs/04-api/75-Payment-Center-Contract-v0.1.md` §1 明确阶段 1 不做结算单、分账、回退、接收方、钱包、提现、对账。
- 券：`61` 号冻结/消费/释放/返券链路完整；`72` 号券活动仅运营创建，券面额由平台承担，无商家资金结算。
- 服务价格：下单时由 service-api 返回价格快照（38 号契约），营销当前不得改价。
- 核销：`48` 号与 `CCR-M-VERIFY-RECORD-001` 已实现商家端核销记录页（Figma `12:7426` 卡语言），无第三方平台来源。

---

## 2. 已确认的产品决策（2026-11-17）

| 决策 | 用户选择 | 方案含义 |
|---|---|---|
| 会员卡范围 | **完整储值卡/次卡/年卡** | 新增会员卡资金域、充值/余额/次数账本、消费/退款/核销联动、结算对账；修改 SSOT「只做单次服务/不做次卡」 |
| 商家自建活动上架 | **保留运营审核** | Figma 的「立即上架」改为「提交审核」；状态机 `DRAFT → PENDING_REVIEW → 运营发布` 不变 |
| 额外营销工具 | **优惠券（商家自建）、限时秒杀、裂变拉新** | 三项均需新契约/规则变更；赠品活动本轮不做 |
| 平台活动报名 | **保留** | 现有「可报名活动/我的报名」能力不删除，迁入 hub 或独立页 |

**推论：**
- Figma 中的「立即上架」开关、商家自主发布、商家自定券/秒杀/返现余额，都不能照抄；必须按审核与资金规则改写文案和流程。
- 会员卡「完整储值」不是营销模块内的一张表，而是一个**受监管的预付资金业务**；技术上可做，但排在支付中心结算能力之后。
- 优惠券、裂变返现、会员卡储值都需要「商家出资/资金归属」的产品裁决；没有结算能力时只能做平台出资试点，不能声称是商家自建。

---

## 3. 不可突破的既有约束

- 30 分钟商家确认；单次服务、必须预约；分钟级排期；接送返程 ≥ 上门 +120 分钟；改期最多一次；服务前退款自动全额；服务后退款商家 24 小时处理；`refund_order` 创建后禁止核销；核销成功使未履约售后失效；部分退款仅运营裁决；迟到支付按真实支付金额全额原路退款。
- 营销不得创建订单、不得新建支付/退款路径、不得修改订单状态机。
- 所有写操作 `X-Request-Id` 幂等；乐观 `version`；append-only 审计。
- `biz` 不依赖 `biz`；跨模块只经 `*-api`；不跨模块访问 Repository/Mapper/DO/Entity。
- 持久层统一 MyBatis：SQL 只在本模块 Mapper XML；禁止 `JdbcTemplate`/原生 JDBC/SQL 注解/Java 内联 SQL；通过 `backend/tools/check-persistence-style.py`。
- 金额 `BigDecimal` / `DECIMAL(18,2)`；ID Snowflake BIGINT，HTTP/JSON String。
- 新增能力开关默认 `false`；迁移 opt-in；不授权生产启用。
- 不伪造数据、不展示无法兑现的优惠、不用设计稿里的数字直接当接口返回值。

---

## 4. 目标架构

### 4.1 模块归属

| 能力 | 归属模块 | 说明 |
|---|---|---|
| 营销工具 hub、商家自建活动 | `pet-marketing` | 扩展现有 `marketing_campaign`；不新建营销资金表 |
| 商家自建优惠券 | `pet-coupon` | 券模板/券活动/券实例生命周期仍归 coupon；新增商家归属与预算 |
| 限时秒杀 | `pet-service` | 服务价格属于 service/order；营销只做入口与审核展示，不改 `pay_amount` 权威 |
| 裂变拉新 | `pet-marketing`（P4a，返券/积分）或新 `pet-referral`（P4b） | P4a 复用 coupon/points；返现需支付中心钱包 |
| 会员卡 | **新增 `pet-membercard-api/biz`** | 资金账本独立域，禁止塞进 marketing |
| 充值支付/结算 | `pet-payment` | `PaymentCenterApi` 扩展 `bizType=MEMBER_RECHARGE`；结算/分账/钱包沿用阶段 2–6 |
| 订单支付方式/价快照 | `pet-order` | 新增内部支付方式（余额/次卡/年卡）与价格来源快照 |
| 核销联动 | `pet-verification` → 事件 | 通过 `OrderVerifiedEvent.v2` 消费，不反向依赖 |

### 4.2 依赖方向

```
pet-marketing ──api──> pet-merchant / pet-service(读) / pet-coupon(读)
pet-coupon ────api──> pet-user / pet-merchant / pet-payment(结算事实，P3)
pet-service ───api──> pet-merchant
pet-order ─────api──> pet-service / pet-coupon / pet-payment / pet-membercard
pet-membercard ─api──> pet-payment / pet-user / pet-merchant
pet-verification ─事件──> pet-membercard（消费/年卡使用记录）
pet-payment ───事件──> pet-membercard（充值成功）/ pet-order（内部支付）
```

禁止：`pet-marketing-biz` 直接依赖 `pet-membercard-biz`；`pet-coupon-biz` 直接读会员卡表；任何模块直接读别模块 Mapper。

### 4.3 横切要求

- 命令幂等：每模块自有 `*_command_idempotency` 行；同 requestId 同参返回首回执，异参 409。
- 审计：资金类写动作必须有 append-only 审计（操作者、requestId、traceId、前后状态、金额）。
- 权限：商家资金/发卡/退款仅 OWNER；员工无权限；运营动作码按 SSOT §24 单运营 + 数据范围；失败关闭。
- 事件：Transactional Outbox；durable AsyncTask 处理到期/对账/补偿；事件仅由 Owner 模块生产。
- 上线：每阶段独立开关；默认 false；迁移脚本 opt-in；不直接 push main/develop。

---

## 5. P0：裁决与契约（先决门禁，不写业务代码）

### 5.1 必须新增/修订的文档

| 文档 | 类型 | 内容 |
|---|---|---|
| `docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md` | 产品裁决 | 范围变更、会员卡资金/次卡规则、商家券出资、秒杀价格、裂变奖励；修改 38 号 §3、SSOT §19 |
| `planning/ccr/CCR-MKT-TOOLS-001.md` | CCR | P1：营销工具 hub、商家自建活动字段、封面素材、审核流程、统计口径 |
| `planning/ccr/CCR-SVC-FLASH-PRICE-001.md` | CCR | P2：服务限时价格、配额、订单价快照、退款规则 |
| `planning/ccr/CCR-MKT-MERCHANT-COUPON-001.md` | CCR | P3：商家券模板归属、预算、审核、结算 |
| `planning/ccr/CCR-MKT-REFERRAL-001.md` | CCR | P4：邀请绑定、奖励、防刷；返现/拼团边界 |
| `planning/ccr/CCR-MEMBER-CARD-001.md` | CCR | P5：会员卡产品、账户、账本、充值、消费、退款、结算、合规 |
| `planning/ccr/CCR-PAY-CENTER-002.md` | CCR | 支付中心：`MEMBER_RECHARGE` bizType、内部支付方式、充值资金结算/对账 |
| `docs/04-api/76-Merchant-Marketing-Tools-Contract-v0.1.md` | 契约 | P1 API/Schema/审核/错误码 |
| `docs/04-api/77-Service-Flash-Price-Contract-v0.1.md` | 契约 | P2 |
| `docs/04-api/78-Merchant-Coupon-Campaign-Contract-v0.1.md` | 契约 | P3（或 72 号 v0.2） |
| `docs/04-api/79-Merchant-Referral-Contract-v0.1.md` | 契约 | P4 |
| `docs/04-api/80-Member-Card-Contract-v0.1.md` | 契约 | P5 |
| 更新 `71` | 契约 v0.4 | P1 字段；明确商家不可发布；新增 `playType` 白名单 |
| 更新 `72` | 契约 v0.2 | P3 商家券 |
| 更新 `38`、`SSOT`、`12` 错误码、事件目录 | 文档 | 范围/错误码/事件登记 |

### 5.2 41 号裁决补充必须回答的问题

1. 会员卡资金归属：平台代收后按消费结算给商家，还是商家直接收款？（推荐平台代收 + 按消费/核销结算，需支付中心）
2. 储值卡未消费余额退款规则、有效期、过期处理、转让/继承、发票。
3. 次卡/年卡的退款、过期、部分使用、停业/闭店处理。
4. 商家自建券的出资方与预算不足处理；平台券与商家券能否叠加。
5. 秒杀价与优惠券叠加规则；秒杀价是否计入商家出资优惠、如何参与分账。
6. 裂变奖励类型（券/积分/现金）、出资方、结算时点、防刷与上限。
7. 是否允许商家为线下现金收款「补录」储值余额（推荐禁止；只允许 C 端支付成功的充值）。
8. 统计口径：哪些数字可展示（活动数/报名数/发券数/核销数/带动订单），哪些必须等归因系统（曝光/点击/GMV）。

**在 5.2 问题未回答前，P3/P4/P5 不得进入编码。**

---

## 6. P1：营销工具 hub + 节日营销（自建活动）

### 6.1 范围

- Figma：`12:7982`（hub）、`18:11090`（节日营销列表）、`18:11706`（创建节日活动）
- 保留：现有平台活动报名/我的报名
- 不做：优惠券/会员卡/秒杀/裂变/赠品的真实创建能力；这些入口显示「筹备中/暂不开放」，不得可点击进入空壳表单
- 不做：商家直接发布；保留运营审核
- 不做：Figma 统计卡中的「带来订单/营收/转化率」等归因数字（无事实来源）

### 6.2 页面与路由

| 路由 | 文件 | Figma | 说明 |
|---|---|---|---|
| `/merchant/pages/marketing/index` | `merchant/pages/marketing/index.tsx` | `12:7982` | 营销工具 hub：顶部概览 + 工具宫格 + 平台活动入口 |
| `/merchant/pages/marketing/platform/index` | `merchant/pages/marketing/platform/index.tsx` | 无（复用现有） | 平台可报名活动 + 我的报名（原两 tab） |
| `/merchant/pages/marketing/campaigns/index` | `merchant/pages/marketing/campaigns/index.tsx` | `18:11090` | 我的主题/节日活动列表 + 创建入口 |
| `/merchant/pages/marketing/campaigns/edit` | `merchant/pages/marketing/campaigns/edit.tsx` | `18:11706` | 创建/编辑商家活动 |

路由需登记到 `frontend-miniapp/src/app.config.ts` merchant 子包；每页 `index.config.ts` 命名与导航栏保持一致。Hub 底部保留 `MerchantTabBar active='home'`；二级页按现有二级页规范（返回 + 标题 + 底部 tab）。

### 6.3 Hub 内容（`12:7982`）

- 顶部概览卡：只展示可证明事实：
  - `activeCampaignCount`（进行中）
  - `pendingReviewCount`（待审核）
  - `totalCampaignCount`（全部）
  - 或文案「管理你的营销活动与工具」，不展示订单/营收/转化率。
- 工具宫格 6 项：优惠券、会员卡、限时秒杀、裂变拉新、赠品活动、节日营销。
  - 节日营销：可点击 → `/marketing/campaigns/index`
  - 其余：展示「筹备中」/「暂不开放」徽标，不可导航；不得伪造可点击状态。
- 平台活动入口：单独一行「平台活动报名 / 我的报名」→ `/marketing/platform/index`，保留现有能力。
- 空态/失败态：加载失败整块显示重试，不阻塞底部 tab。

### 6.4 节日营销列表（`18:11090`）

- 顶部 banner：标题「节日营销」、说明「节日主题套餐，蹭热点促销量」——若「套餐」与 SSOT 冲突，改为「节日主题活动，围绕节日做门店曝光」。
- 统计卡：活动数、进行中、待审核（不展示订单/营收）。
- `+ 创建活动` → `campaigns/edit`。
- 列表卡：标题、副标题/说明、开始日期、状态胶囊（草稿/待审核/已驳回/待开始/进行中/已结束/已取消）。
- 点击卡片：`DRAFT/REJECTED` → 编辑；其他状态 → 只读详情（可在编辑页只读渲染）。
- 空态：「还没有节日活动，创建第一个」。

### 6.5 创建节日活动（`18:11706`）

字段映射：

| Figma 字段 | 契约字段 | 规则 |
|---|---|---|
| 活动头图 | `coverAssetId` → 服务端解析 `coverUrl` | 商家必须通过营销素材上传端点上传；PNG/JPEG ≤2MiB、≤2000×2000；归属本人 |
| 活动名称 | `name`（2–100） | 与 `title` 可同值；前端不重复收集 |
| 节日主题 | `themeCode` | 白名单：`SPRING_FESTIVAL/MID_AUTUMN/NATIONAL_DAY/CHRISTMAS/NEW_YEAR/DOUBLE_11/OTHER`；展示名由前端映射 |
| 活动玩法 | `playType` | P1 仅 `THEME`（主题活动）；P2 增 `FLASH_PRICE`，P3 增 `MERCHANT_COUPON`，P4 增 `REFERRAL`；其余选项隐藏或置灰「暂不支持」 |
| 节日主打项目 | `highlightText`（≤100） | 纯文本，不得暗示未配置的折扣/赠品 |
| 优惠力度（元） | — | P1 不采集；没有真实价格/券规则时禁止展示 |
| 活动有效期 | `startAt/endAt` | `startAt < endAt`；日期选择器；时区按北京时间提交 ISO UTC |
| 活动说明 | `description`（≤300 码点） | Figma 0/300 计数 |
| 立即上架 | — | 改为「提交后由运营审核，审核通过后展示」；提交动作 `submit`，不提供商家发布 |
| 创建按钮 | — | 「保存草稿」+「提交审核」两个动作（与状态机匹配） |

前端校验：必填名称/主题/有效期；封面可选或必填由契约定（建议必填，与 Figma 一致）；说明不超 300；提交前二次确认审核规则。后端必须重复校验全部规则，不信任前端。

### 6.6 契约 71 v0.4 增量

#### 6.6.1 `marketing_campaign` 新字段

| 字段 | 类型 | 约束 |
|---|---|---|
| `description` | String/null | ≤300 码点 |
| `themeCode` | String/null | `^[A-Z][A-Z0-9_]{1,31}$`，白名单；商家创建必填 |
| `highlightText` | String/null | ≤100 码点 |
| `playType` | String | `THEME`；后续阶段扩展白名单；未知 400 |
| `coverAssetId` | String/null | 必须指向本人/本平台的 ACTIVE 营销素材 |
| `coverUrl` | String/null | 服务端由 `coverAssetId` 解析；商家请求不得直接传任意 URL |

#### 6.6.2 `marketing_asset` 归属扩展

| 字段 | 类型 | 约束 |
|---|---|---|
| `owner_type` | `PLATFORM/MERCHANT` | 默认 `PLATFORM`（迁移存量） |
| `owner_merchant_id` | BIGINT/null | 商家素材必填 |
| `owner_store_id` | BIGINT/null | 可空；用于门店隔离 |
| `purpose` | `CAMPAIGN_COVER` | 商家上传仅此 purpose |

- 新增 `POST /api/v1/merchant/marketing/assets`（multipart `file`，`X-Request-Id`）：
  - 仅 OWNER；校验商家当前可运营；文件类型/大小/像素与运营上传一致；`sha256` 去重可选。
  - 写 `marketing_asset` + `marketing_asset_audit`；返回 `{assetId,url,contentType,bytes,width,height,sha256}`。
  - C 端读取仍走 `GET /api/v1/c/marketing/assets/{assetId}`；URL 稳定。
- 商家创建/更新活动时校验 `coverAssetId` 归属本人；运营可继续使用平台素材。

#### 6.6.3 商家接口增量

`POST/PUT /api/v1/merchant/marketing/my-campaigns` 请求新增：

```json
{
  "name": "中秋宠物月饼礼盒",
  "title": "中秋宠物月饼礼盒",
  "subtitle": "节日限定礼盒",
  "description": "活动说明…",
  "themeCode": "MID_AUTUMN",
  "highlightText": "宠物月饼礼盒",
  "playType": "THEME",
  "coverAssetId": "123456789",
  "startAt": "2026-09-01T00:00:00.000Z",
  "endAt": "2026-09-30T23:59:59.000Z"
}
```

- 响应 `CampaignCard` 增补 `description/themeCode/themeLabel/highlightText/playType/coverAssetId/coverUrl`。
- `create` → `DRAFT`；`submit` → `PENDING_REVIEW`；`cancel` 规则不变。
- 运营 `publish` 前校验：`playType=THEME` 时不得配置券/价格规则；封面素材 ACTIVE；活动窗口有效。

#### 6.6.4 统计接口（只做可证明事实）

`GET /api/v1/merchant/marketing/overview?merchantId=&storeId=`

```json
{
  "totalCampaignCount": 2,
  "activeCampaignCount": 1,
  "pendingReviewCount": 1,
  "enrolledCampaignCount": 3
}
```

- 全部来自 `marketing_campaign`/`marketing_enrollment` 计数；不返回订单数/营收/转化率。
- 查询失败按 503，前端显示「—」或隐藏概览，不伪造 0。

### 6.7 后端任务（P1）

| 工作包 | 文件 | 内容 |
|---|---|---|
| P1-BE-01 | `backend/pet-boot/src/main/resources/db/marketing-migration/V34__merchant_marketing_tools.sql` | 新字段 + `marketing_asset` 归属 + 索引/CHECK；迁移不猜测存量 |
| P1-BE-02 | `MarketingMerchantApi.java` / `MarketingMerchantService.java` | 命令/校验/状态；`playType=THEME`；封面归属 |
| P1-BE-03 | `MarketingMapper.xml` / `MarketingMapper.java` / `MarketingEntities.java` | 字段映射、插入/更新/查询、计数 |
| P1-BE-04 | `MarketingMerchantController.java` | 新端点/请求解析/严格字段校验/错误映射 |
| P1-BE-05 | 素材上传：`MarketingMerchantApi` + `MarketingMerchantController`（或专用 `MerchantMarketingAssetController`） | 复用 `MarketingAssetObjectStore`；新 purpose；审计 |
| P1-BE-06 | `MarketingAdminService` / admin controller / admin API | 运营审核可见/可编辑新字段；发布前校验 |
| P1-BE-07 | 测试 | MySQL 集成：创建/编辑/提交/审核；素材归属；幂等；非法 theme/playType/封面；跨店越权；`check-persistence-style.py`；架构测试 |

**注意**：`marketing_campaign` 的 `campaign_type` CHECK 不需要新增值（P1 仍 `DISPLAY`）；`playType` 是业务子类型，不替代 `campaign_type`。

### 6.8 前端任务（P1）

| 工作包 | 文件 | 内容 |
|---|---|---|
| P1-FE-01 | `merchant/marketing/api.ts` + 新 controller/model | 严格解码新字段；overview；素材上传 |
| P1-FE-02 | `merchant/pages/marketing/index.tsx` + CSS | Hub 12:7982 |
| P1-FE-03 | `merchant/pages/marketing/platform/index.tsx` + CSS | 迁移现有两 tab；保留报名/撤回 |
| P1-FE-04 | `merchant/pages/marketing/campaigns/index.tsx` + CSS | 列表 18:11090 |
| P1-FE-05 | `merchant/pages/marketing/campaigns/edit.tsx` + CSS | 创建/编辑 18:11706；封面上传；主题/玩法；说明；提交审核 |
| P1-FE-06 | `src/app.config.ts` | 注册 4 个新路由 |
| P1-FE-07 | 测试 | 解码单测、状态文案、路由、typecheck、`npm run build:weapp` |

**交互纪律**：
- 「立即上架」不得保留；改为审核提示。
- 未支持的玩法/工具不得可提交；前端隐藏或置灰并显示准确原因。
- 不上传远程 URL；只从本地选择图片。
- 所有金额/份数/统计数字必须来自接口或显示「—」。

### 6.9 运营后台（P1）

- `frontend-admin/src/pages/MarketingCenterPage.tsx`：活动详情/审核卡展示 `description/themeCode/highlightText/playType/封面`。
- 运营发布前能看到商家提交的封面与说明；驳回原因仍 1–500 字。
- 不新增运营动作；沿用 `marketing.campaign.publish`。

### 6.10 验收（P1）

- 商家工作台「营销工具」→ hub，6 个工具卡显示正确状态；节日营销可进入。
- 商家创建节日活动：上传封面、选主题、填说明、提交审核；状态 `DRAFT → PENDING_REVIEW`。
- 运营审核通过并发布后，商家列表显示「进行中/待开始」；驳回显示原因，商家可编辑重提。
- 平台活动报名/撤回与现状一致，无回归。
- 商家不能发布、不能填任意封面 URL、不能提交未支持的玩法。
- 相关 MySQL 测试、架构测试、`check-persistence-style.py`、前端 typecheck/单测/build 全通过。
- 无伪造统计；无未披露的 Contract/Schema/Event 变化。

### 6.11 P1 明确不做/设计偏差

- 不做 Figma 的「立即上架」；改为审核后展示。
- 不做 Figma 的「优惠力度/节日套餐/买赠/抽奖」；这些依赖 P2/P3 或 V1 外能力。
- 不做 hub 的订单/营收/转化率数字；只做可证明计数。
- 不做赠品活动入口的可点击表单。
- 不把 `12:7426` 核销记录的第三方来源加入（47/48 号无此事实源）。

---

## 7. P2：限时秒杀（服务限时价格）

> 前置：`CCR-SVC-FLASH-PRICE-001` 批准；更新 38 号原子下单、11 号订单、74 号服务目录、75 号分账基数口径。**未批准前不得写价格表/改订单。**

### 7.1 产品规则（建议稿）

- 秒杀是**服务项目的限时价格**，不是新订单类型、不是新服务形态；仍必须预约、单次服务。
- 商家创建秒杀 → 运营审核 → 按窗口生效；商家不能直接发布。
- 秒杀价 >0 且 < 服务划线价/日常价；同一服务同一时间最多一个有效秒杀窗口。
- 发放总量/每人限领可选；无总量时不限库存，仅限时价。
- 秒杀价与券叠加：券按秒杀价后的金额计算；折后应付必须 >0（沿用 61 号）。平台券由平台承担；商家券由商家承担（P3）。
- 退款：按订单实付金额原路退；已支付订单的秒杀名额不恢复（避免二次占用），取消/超时未支付释放名额。
- 分账：分账基数 = 服务原价 − 秒杀优惠 − 商家券优惠；平台券不扣商家。更新支付中心 §5 的「商家出资优惠 V1 为 0」。

### 7.2 数据模型（`pet-service` 自有）

```text
service_flash_price
  id, merchant_id, store_id, service_id,
  flash_price DECIMAL(18,2), list_price_snapshot DECIMAL(18,2),
  start_at, end_at, total_quota INT NULL, per_user_limit INT NULL,
  sold_count INT NOT NULL DEFAULT 0,
  status DRAFT/PENDING_REVIEW/SCHEDULED/ACTIVE/ENDED/REJECTED/CANCELED,
  reject_reason, version, created_by..., created_at, updated_at
  UNIQUE(service_id, start_at)  -- 或应用层窗口不重叠校验
  CHECK flash_price > 0 AND flash_price < list_price_snapshot
  CHECK total_quota IS NULL OR total_quota > 0

service_flash_price_order
  id, flash_price_id, order_id, user_id,
  reserved_price DECIMAL(18,2),
  status RESERVED/CONSUMED/RELEASED,
  created_at, updated_at
  UNIQUE(order_id), KEY(flash_price_id,user_id,status)

service_flash_price_audit
service_flash_price_command  -- requestId 幂等
```

- 所有表在本模块 Mapper XML；不跨模块读订单表。
- 配额预占：下单事务内 `SELECT ... FOR UPDATE` 秒杀行 → 校验窗口/库存/每人限领 → `sold_count+1` + 插预占行 → 返回价格快照。
- 支付超时/取消：durable AsyncTask 或订单事件释放预占；已支付置 `CONSUMED`；退款不回补。
- 订单快照新增 `price_source=LIST/FLASH`、`flash_price_id`、`original_price`（服务原价）、`flash_discount`。
- `ServiceQueryApi` / `BookingServiceFacts` 增加生效价与来源；38 号下单内核只信服务域返回值。

### 7.3 接口（草案）

商家端：

```text
GET    /api/v1/merchant/services/{serviceId}/flash-prices
POST   /api/v1/merchant/services/{serviceId}/flash-prices
PUT    /api/v1/merchant/services/{serviceId}/flash-prices/{id}
POST   /api/v1/merchant/services/{serviceId}/flash-prices/{id}/submit
POST   /api/v1/merchant/services/{serviceId}/flash-prices/{id}/cancel
```

运营端：

```text
GET  /api/v1/admin/service-flash-prices
POST /api/v1/admin/service-flash-prices/{id}/approve
POST /api/v1/admin/service-flash-prices/{id}/reject
POST /api/v1/admin/service-flash-prices/{id}/pause
POST /api/v1/admin/service-flash-prices/{id}/end
```

C 端：服务列表/详情返回 `flashPrice`、`flashStartAt/flashEndAt`、`remaining`、`perUserLimit`、`priceSource`；订单确认按生效价；下单请求不得传价格。

错误码新增：`FLASH_PRICE_NOT_FOUND`、`FLASH_PRICE_STATE_NOT_ALLOWED`、`FLASH_PRICE_WINDOW_OVERLAP`、`FLASH_PRICE_QUOTA_EXCEEDED`、`FLASH_PRICE_LIMIT_REACHED`、`FLASH_PRICE_INVALID`。

### 7.4 前端

- 商家：`merchant/pages/marketing/flash/index.tsx`（`18:9537`）、`edit.tsx`（`18:9694`）；服务选择器来自本店 ACTIVE 服务；状态/审核/驳回；不显示距离/资质。
- C 端：服务卡/详情/订单确认展示秒杀价与倒计时；售罄/结束隐藏或恢复原价。
- 运营后台：秒杀审核列表/详情。
- 统计只展示可证明：场次、参与人数（下单用户数）、转化率仅在定义清楚后展示；否则不展示。

### 7.5 测试与风险

- 并发：同一秒杀最后 1 个名额多请求只成功 1 个。
- 价格：下单期间秒杀结束/价格变化，订单以创建事务内快照为准；支付金额与快照一致。
- 退款：全额/部分退款按实付；分账基数口径与支付中心一致。
- 兼容：无秒杀时价格来源 `LIST`，行为与现状一致。
- 风险：改动 38 号下单内核和 75 号分账口径，必须独立 PR + 全链路回归。

---

## 8. P3：商家自建优惠券

> 前置：支付中心阶段 2（结算/分账）可用；`CCR-MKT-MERCHANT-COUPON-001` 批准；72 号 v0.2；61 号只增模板归属，不改冻结/消费/释放。

### 8.1 产品规则

- 商家只能创建**无门槛券/满减券**（沿用 61/72）；不得创建折扣券、兑换券、券包。
- 商家券的优惠由**商家出资**；平台券由平台出资，两者可叠加但需明确顺序与上限。
- 商家券必须运营审核后才能发布；商家不得直接发券给指定用户，只能配置公开领取的券活动。
- 预算：商家设置总预算/总发放量/每人限领/有效期；预算不足时停止领取，不超发。
- 领取/使用/退款返券沿用 61/72；退款不返券/全额退款返券规则不变。
- 结算：分账基数扣除商家券面额；平台券不扣商家。支付中心 §5 的「商家出资优惠」从 0 放开。
- 统计可展示：发券数、领取率、核销率（已使用/已领取）、带动订单数（`coupon_instance.order_id` 去重）、净 GMV（这些订单实付减退款，需定义）；不展示曝光/点击。

### 8.2 数据模型（`pet-coupon` 自有）

```text
coupon_template 增加：
  owner_type PLATFORM/MERCHANT
  owner_merchant_id BIGINT NULL
  funding_type PLATFORM/MERCHANT
  CHECK owner_type='MERCHANT' -> owner_merchant_id NOT NULL AND funding_type='MERCHANT'

coupon_campaign 增加：
  owner_type/owner_merchant_id/owner_store_id
  status 增加 PENDING_REVIEW/REJECTED（或复用独立审核状态）
  reject_reason, review_version
  budget_reserved DECIMAL(18,2), budget_used DECIMAL(18,2)

merchant_coupon_budget
  merchant_id, total_budget, reserved, used, version
  -- 或从 settlement 应付扣减；P3 先做预算账本，结算阶段对接

coupon_campaign_audit / merchant_coupon_command 幂等
```

- 券实例/台账仍由 coupon 模块拥有；`coupon_instance` 增加 `funding_type` 快照（平台/商家）。
- 订单快照增加 `merchant_coupon_discount` / `platform_coupon_discount`（或券实例可回溯）；支付中心分账读取订单快照，不读券表。

### 8.3 接口（草案）

商家端：

```text
GET  /api/v1/merchant/coupon-templates        -- 只读可用模板（平台模板 + 本人商家模板）
POST /api/v1/merchant/coupon-templates        -- 创建商家模板（待审核）
GET  /api/v1/merchant/coupon-campaigns
POST /api/v1/merchant/coupon-campaigns
PUT  /api/v1/merchant/coupon-campaigns/{id}
POST /api/v1/merchant/coupon-campaigns/{id}/submit
POST /api/v1/merchant/coupon-campaigns/{id}/cancel
GET  /api/v1/merchant/coupon-campaigns/{id}/stats
```

运营端：审核模板/活动、暂停/结束、预算异常处理。

C 端：沿用 `GET /api/v1/c/coupon-campaigns/{id}` 与领取端点；响应增加 `merchantName`、`fundingType`、`canStack`。

### 8.4 结算与对账（强依赖）

- `payment_center` 分账基数：`order.list_amount − flash_discount − merchant_coupon_discount`；平台券不扣商家。
- 退款：按支付中心 §5 重算/回退；券返还不改变已发生的分账回退。
- 日对账：商家券发放/使用与订单/结算逐笔核对；差异告警，不自动改账。
- **支付中心阶段 2 未交付前，P3 不得上线；最多开发到默认关闭的 DRAFT 状态。**

### 8.5 前端与测试

- 商家：`marketing/coupon/index.tsx`（`12:8436`）、`edit.tsx`（`12:8575`）；券类型仅无门槛/满减；预算/总量/限领/有效期/说明；提交审核。
- 运营：券模板/活动审核页。
- 测试：预算并发不超发、重复领取、退款返券、分账基数、权限（非 OWNER 拒绝）、跨店模板不可用。
- 风险：券出资与结算口径是财务核心，必须与支付中心同批联调。

---

## 9. P4：裂变拉新

### 9.1 范围与边界

- **P4a（先做）**：邀请绑定 + 奖励为**商家券（P3）或积分（58 号）**；不做现金返现、不做拼团。
- **P4b（阻塞）**：现金返现需支付中心钱包/提现/结算（阶段 5+）与商家出资规则；`CCR-PKT-REFERRAL-002` 另立。
- Figma 的「返现」在 P4a 显示为「返券/返积分」；「拼团」不支持，登记设计偏差。
- 奖励时点：被邀请人完成**首笔已支付订单**且过退款/售后窗口（建议 T+7）后发放；退款/拒付则不发或扣回。
- 防刷：自邀禁止；被邀请人必须是该商家新客（无已支付订单）；每个被邀请人终身只能绑定一个商家/邀请人；每邀请人月度上限；同一设备/手机号/支付工具等风控字段按最小化采集，不新增敏感明文。

### 9.2 数据模型（建议 `pet-marketing` 或新 `pet-referral`）

```text
merchant_referral_program
  id, merchant_id, store_id, name,
  reward_type MERCHANT_COUPON/POINTS,
  reward_coupon_template_id / reward_points INT,
  total_budget DECIMAL(18,2), per_inviter_limit INT,
  start_at, end_at, status DRAFT/PENDING_REVIEW/SCHEDULED/ACTIVE/PAUSED/ENDED/REJECTED,
  reject_reason, version, ...

merchant_referral_code
  id, program_id, inviter_user_id, code VARCHAR(32) UNIQUE, status ACTIVE/DISABLED

merchant_referral_binding
  id, program_id, inviter_user_id, invitee_user_id, bound_at,
  first_paid_order_id, status BOUND/QUALIFIED/REWARDED/REVOKED,
  UNIQUE(program_id, invitee_user_id), UNIQUE(program_id, inviter_user_id, invitee_user_id)

merchant_referral_reward
  id, binding_id, reward_type, coupon_instance_id/points_ledger_id,
  amount, status PENDING/ISSUED/REVOKED, issued_at,
  UNIQUE(binding_id)

merchant_referral_audit / merchant_referral_command
```

- 事件消费：`OrderPaidEvent`（首单）→ 绑定置 `QUALIFIED`；退款/售后窗口结束任务 → 发奖；`RefundSucceededEvent` → 撤销未发奖励/扣回（按产品规则）。
- 奖励使用 P3 商家券或 58 号积分 API；不直接写券/积分表。
- 统计：邀请人数、新增客户、奖励支出（券面额/积分）；不展示未发生的现金。

### 9.3 接口（草案）

商家端：`GET/POST/PUT /api/v1/merchant/referral-programs`、`submit/cancel`、`GET /stats`。
C 端：`GET /api/v1/c/referral-programs?merchantId=`、`POST /api/v1/c/referral-bindings`（邀请码）、`GET /api/v1/c/referral-bindings/me`。
运营端：审核/暂停/结束/异常。

### 9.4 测试

- 自邀、重复绑定、已有订单用户、跨商家绑定、超上限、退款撤销、奖励幂等、并发绑定唯一。
- 奖励发放与券/积分台账一致；不产生现金流水。
- 返现/拼团未实现时接口和页面不得暗示可用。

---

## 10. P5：会员卡（储值/次卡/年卡，完整版）

> **这是独立资金域项目，不建议与 P1 同批。** 前置：
> 1. 41 号裁决补充批准，SSOT 修订「只做单次服务/不做次卡」；
> 2. 法律/财务/合规评审（单用途预付卡、资金存管、退款、发票、过期）；
> 3. 支付中心阶段 2–6（结算、分账、回退、钱包、提现、对账）可用；
> 4. 订单支付方式扩展（余额/次卡/年卡内部支付）契约批准；
> 5. `CCR-MEMBER-CARD-001` 批准。

### 10.1 卡类型与规则（需 41 号裁决确认）

| 类型 | 购买 | 使用 | 退款/过期 |
|---|---|---|---|
| 储值卡 `STORED_VALUE` | C 端支付充值（微信） | 下单时余额支付；可部分使用 | 未消费余额按规则退；过期/闭店规则需裁决 |
| 次卡 `COUNT` | C 端支付购买 N 次 | 下单/核销扣次；每次服务消耗 1 次 | 未使用次数按规则退；过期需裁决 |
| 年卡 `YEAR` | C 端支付购买有效期 | 有效期内按次预约；可设公平使用上限 | 未使用不退或按规则；需裁决 |

**禁止**：商家在「添加会员信息」里直接填写一笔储值金额并当作可用余额（无支付事实、无对账、可被滥用）。储值金额必须来自支付成功的充值单；线下现金收款若要做，必须另立「线下收款登记/对账」裁决，不能混入本卡余额。

### 10.2 领域模型（新模块 `pet-membercard-api/biz`）

```text
member_card_product
  id, merchant_id, store_id, name, card_type STORED_VALUE/COUNT/YEAR,
  face_value DECIMAL(18,2) NULL,        -- 储值卡建议充值面额/档位
  count_total INT NULL,                 -- 次卡总次数
  valid_days INT NULL / valid_from,valid_to,
  price DECIMAL(18,2),                  -- 购买价
  refund_policy, status DRAFT/PENDING_REVIEW/ONLINE/OFFLINE/REJECTED,
  version, ...

member_card_account
  id, card_no, user_id, merchant_id, store_id, product_id, card_type,
  balance DECIMAL(18,2) NOT NULL DEFAULT 0, remaining_count INT NULL,
  valid_from, valid_to, status ACTIVE/FROZEN/EXPIRED/CLOSED/REFUNDED,
  version, created_at, updated_at

member_card_ledger
  id, account_id, biz_type RECHARGE/CONSUME/REFUND/ADJUST/EXPIRE/REVERSE,
  amount_delta DECIMAL(18,2), count_delta INT,
  balance_after DECIMAL(18,2), count_after INT,
  order_id, payment_id, verification_id, request_id, remark,
  created_at, UNIQUE(request_id)  -- 资金幂等

member_card_recharge_order
  id, account_id, product_id, user_id, amount,
  payment_id, status CREATED/PAYING/PAID/FAILED/CLOSED/REFUNDED,
  paid_at, version, ...

member_card_refund_order
  id, account_id, order_id/payment_id, amount/count,
  reason, status APPLIED/APPROVED/REJECTED/SUCCEEDED/FAILED,
  operator_id, version, ...

member_card_order_payment
  id, account_id, order_id, mode BALANCE/COUNT/YEAR,
  amount/count, status RESERVED/CONSUMED/RELEASED/REFUNDED,
  UNIQUE(order_id), ...

member_card_audit / member_card_command
```

- 账本 append-only；余额/次数以账本 + 账户当前值双校验；`balance >= 0`、`remaining_count >= 0`。
- 充值：`PaymentCenterApi.create(bizType=MEMBER_RECHARGE)` → 成功事件 → 账本 `RECHARGE`；失败/关闭不记账。
- 消费：订单创建事务内调 `MemberCardPaymentApi.reserve` → 订单内部支付成功 `consume`；取消/超时 `release`；退款 `REFUND` 回账户。
- 次卡：订单按次预占，支付成功扣次；退款回补；核销只记录使用事实，不重复扣次。
- 年卡：订单创建校验有效期与公平使用上限；核销记录使用；退款规则按裁决。
- 所有资金写动作 requestId 幂等 + 版本 CAS + 审计；日终与支付中心对账。

### 10.3 订单/支付改造（高风险）

- 订单新增支付方式：`WECHAT`（默认）/`MEMBER_BALANCE`/`MEMBER_COUNT`/`MEMBER_YEAR`。
- 余额/卡支付不创建微信 `payment_order`；订单在内部支付成功后置 `PAID`；**不得用 0 元微信单绕过通道限制**。
- 需要修订 38 号原子下单、11 号订单、40/41 号支付契约、49/50 号售后退款：卡支付订单的退款回到卡/余额，不原路微信。
- 退款优先级：未消费余额 > 次卡次数 > 年卡按规则；已消费部分不退（或按裁决）。
- 分账：充值资金先进入平台/商家？推荐平台代收、按实际消费/核销结算给商家；需支付中心新增结算触发事件与 T+d 规则。
- 发票：充值/消费开票主体与时点需财务裁决。

### 10.4 商家端页面（Figma）

- `14:8836` 会员卡列表：卡产品、会员数、新增、储值余额；数字只来自真实账户/账本汇总。
- `16:9098` 添加会员信息：姓名、手机号（脱敏显示/校验）、宠物昵称/品种、卡类型、储值金额、开卡日期。
  - 储值金额字段：若为「开卡充值」，生成待支付充值单，C 端支付成功后入账；商家不能直接改余额。
  - 手机号/宠物信息属于客户档案，需遵守 69 号隐私红线与授权；不得明文外泄。
- 商家还可查看会员卡账户、余额/次数、流水、退款申请（只读 + 发起退款需裁决）。
- C 端（Figma 未给，但完整能力必需）：我的会员卡、充值、余额/次数支付、退款记录。
- 运营后台：卡产品审核、账户查询（脱敏）、手工调整/退款（审计）、对账报表。

### 10.5 分阶段交付（P5 不能再拆得更少）

| 子阶段 | 内容 | 门禁 |
|---|---|---|
| P5.1 | 卡产品目录 + 会员档案 + 开卡（无资金变动） | 41 号裁决；不含余额可用 |
| P5.2 | 充值支付 + 余额账本 + 充值退款 | 支付中心 `MEMBER_RECHARGE`；对账 |
| P5.3 | 余额支付订单 + 退款回余额 | 订单支付方式契约；38/11/40/41 修订 |
| P5.4 | 次卡/年卡购买、预占/扣次、核销联动 | 48 号事件；退款规则 |
| P5.5 | 商家结算、平台代收资金、T+d/消费结算、钱包提现 | 支付中心阶段 2–6 |
| P5.6 | 运营工具、报表、合规审计 | 法务/财务验收 |

### 10.6 验收（P5）

- 充值：支付成功才入账；重复通知/重放不重复入账；失败/关闭不记账。
- 余额：不足不能支付；并发扣减不出现负余额；退款回余额且账本可追溯。
- 次卡/年卡：预占/消费/释放/退款/核销事件幂等；订单取消不扣次；核销不重复扣次。
- 对账：会员卡账本与支付中心/订单/退款逐笔一致；差异告警不自动改账。
- 权限：仅 OWNER 可发卡/退款/调整；员工不可见资金面；越权 403。
- 合规：预付资金、退款、过期、发票规则经法务确认。

---

## 11. P6：营销归因与统计（可选）

- 当前 71 号 §8 明确不做曝光/成交归因；Figma 的「带来 326 笔订单/较上月提升 18.6%」在 P1 不能实现。
- 若要做：新增事件（曝光/点击/领取/核销/订单归因）、埋点隐私评审、异步聚合、商家报表；单独 CCR 和契约。
- 在 P6 之前，所有页面只能展示可证明计数（活动数/报名数/发券数/领取数/核销数/使用订单数），并标注口径。

---

## 12. 工作包与执行顺序

| 工作包 | 阶段 | 模块 | 主要文件 | 依赖 | 验收 |
|---|---|---|---|---|---|
| WP-00 | P0 | docs | 41 号补充、CCR-*、契约 76–80、71/72/38/SSOT/12 更新 | 用户批准 | 文档评审通过 |
| WP-01 | P1 | marketing | `V34__merchant_marketing_tools.sql`、`MarketingMerchantApi/Service/Mapper/Controller` | WP-00 CCR-MKT-TOOLS-001 | 商家活动字段/审核/素材 |
| WP-02 | P1 | marketing/asset | 商家素材上传端点、`marketing_asset` 归属、审计 | WP-01 | 上传/归属/限额 |
| WP-03 | P1 | miniapp | hub、platform、campaigns list/edit、app.config | WP-01/02 | 14 张中的 3 张 + 保留报名 |
| WP-04 | P1 | admin | 营销中心活动详情/审核展示新字段 | WP-01 | 运营可见 |
| WP-05 | P2 | service/order | 秒杀表、价格快照、订单/退款/分账口径 | CCR-SVC-FLASH-PRICE-001 | 并发/价格/退款 |
| WP-06 | P2 | miniapp/admin | 秒杀列表/创建、C 端展示、运营审核 | WP-05 | 页面 + 真数据 |
| WP-07 | P3 | coupon/payment | 商家模板/券活动/预算/结算 | 支付中心阶段 2 + CCR | 预算/分账/退款 |
| WP-08 | P3 | miniapp/admin | 券列表/创建、运营审核 | WP-07 | 页面 + 真数据 |
| WP-09 | P4a | marketing/coupon/points | 邀请绑定、奖励券/积分、防刷 | WP-07 或 58 号 | 绑定/奖励/撤销 |
| WP-10 | P4b | payment | 现金返现、钱包/提现 | 支付中心阶段 5 | 另立 CCR |
| WP-11 | P5.1 | membercard | 卡产品/会员档案/开卡 | 41 号 + CCR-MEMBER-CARD-001 | 无资金面 |
| WP-12 | P5.2 | membercard/payment | 充值/余额账本/退款 | 支付中心 MEMBER_RECHARGE | 记账/对账 |
| WP-13 | P5.3 | order/membercard | 余额支付订单/退款 | 38/11/40/41 修订 | 内部支付 |
| WP-14 | P5.4 | membercard/verification | 次卡/年卡/核销 | 48 号事件 | 扣次/幂等 |
| WP-15 | P5.5 | payment/membercard | 结算/钱包/对账 | 支付中心阶段 2–6 | 资金一致 |
| WP-16 | P6 | marketing/data | 归因/报表 | 新裁决 | 口径/隐私 |

**执行顺序硬约束：**
- WP-01/02/03/04 可作为一个 P1 迭代，但 WP-01 必须先于 WP-03。
- WP-05 不得与 P1 混 PR；价格与订单改动需独立回归。
- WP-07/09/11–15 不得在支付中心/合规门禁前合入生产；开关默认关闭。
- 任何阶段不得顺手修改交易/退款/售后/核销硬规则。

---

## 13. 风险与门禁

| 风险 | 等级 | 影响 | 缓解 |
|---|---|---|---|
| 把 V1 范围变更当普通页面需求 | 高 | 违反 AGENTS/38/71；返工 | 先 41 号裁决 + CCR；本文 §5 |
| 支付中心阶段 2–6 未交付 | 阻塞 | 商家券/会员卡/返现无法结算 | P3/P5 不启动或只做默认关闭草稿 |
| 储值卡合规/资金归属 | 高 | 预付卡监管、退款、闭店 | 法务/财务评审；平台代收按消费结算 |
| 0 元单/内部支付绕过通道 | 高 | 支付/退款链路破坏 | 新增内部支付方式契约；禁止 0 元微信单 |
| 秒杀改价破坏订单/分账 | 高 | 金额不一致、退款错误 | 价格快照 + 分账口径 + 独立 CCR |
| 商家券预算超发/结算争议 | 高 | 财务损失 | 预算账本 + 行锁/条件更新 + 对账 |
| 裂变刷单/自邀 | 中 | 奖励损失 | 新客校验、窗口、上限、事件撤销 |
| 统计数字伪造 | 中 | 用户决策误导 | 只展示可证明事实；P6 前不展示归因 |
| 商家人工填写余额 | 高 | 无支付事实、对账缺口 | 禁止；只允许支付成功充值 |
| 未支持玩法被展示 | 中 | 无法兑现承诺 | `playType` 白名单；隐藏/置灰 |
| Taro 子包体积/路由 | 低 | 构建失败 | 新页面按子包登记；build 验收 |
| 既有营销页回归 | 中 | 平台报名丢失 | 保留 platform 页 + 回归测试 |

**阶段门禁：**
1. Gate-0：用户批准本方案与 41 号补充。
2. Gate-1：CCR-MKT-TOOLS-001 + 契约 76 批准 → P1。
3. Gate-2：CCR-SVC-FLASH-PRICE-001 + 38/11/74/75 修订批准 → P2。
4. Gate-3：支付中心阶段 2 交付 + CCR-MKT-MERCHANT-COUPON-001 → P3。
5. Gate-4：P3 可用 + CCR-MKT-REFERRAL-001 → P4a；阶段 5 → P4b。
6. Gate-5：41 号 + 法务/财务 + 支付中心阶段 2–6 + 订单支付方式契约 → P5。
7. 每个 PR：相关测试、架构检查、`check-persistence-style.py`、无未披露 Contract/Schema/Event 变化。

---

## 14. Definition of Done（每阶段）

- Acceptance Criteria 满足；P1 见 §6.10，P2 见 §7.5，P3 见 §8.5，P4 见 §9.4，P5 见 §10.6。
- 相关后端单测/MySQL 集成测试、架构测试、前端 typecheck/单测/build 通过。
- `backend/tools/check-persistence-style.py` 通过；SQL 全部在模块 Mapper XML。
- 契约/错误码/事件/Schema 变更已登记；设计源登记表与覆盖审计更新（C2 → 已实现/偏差）。
- 开关默认关闭；迁移 opt-in；未授权生产启用。
- PR 描述含：范围、契约变更、测试证据、设计偏差、遗留风险；不直接 push main/develop。

---

## 附录 A：41 号裁决补充建议目录

1. 范围与生效方式（覆盖 38 号 §3、SSOT §19 的条款）。
2. 商家自建活动：审核流程、可配置字段、禁止项。
3. 商家券：模板归属、出资、预算、审核、结算、退款。
4. 服务限时价格：价格来源、叠加、配额、退款、分账。
5. 裂变拉新：奖励类型、出资、时点、上限、防刷；返现/拼团边界。
6. 会员卡：卡类型、充值、余额/次数、消费、退款、过期、闭店、发票、资金存管、结算。
7. 统计口径：可展示/不可展示。
8. 兼容与回滚：开关、数据迁移、旧活动处理。
9. 验收与责任矩阵。

## 附录 B：Figma node → 路由/文件映射

| node-id | 路由 | 文件 | 阶段 |
|---|---|---|---|
| `12:7982` | `/merchant/pages/marketing/index` | `merchant/pages/marketing/index.tsx` | P1 |
| `18:11090` | `/merchant/pages/marketing/campaigns/index` | `.../campaigns/index.tsx` | P1 |
| `18:11706` | `/merchant/pages/marketing/campaigns/edit` | `.../campaigns/edit.tsx` | P1 |
| `12:8436` | `/merchant/pages/marketing/coupon/index` | `.../coupon/index.tsx` | P3 |
| `12:8575` | `/merchant/pages/marketing/coupon/edit` | `.../coupon/edit.tsx` | P3 |
| `18:9537` | `/merchant/pages/marketing/flash/index` | `.../flash/index.tsx` | P2 |
| `18:9694` | `/merchant/pages/marketing/flash/edit` | `.../flash/edit.tsx` | P2 |
| `18:10180` | `/merchant/pages/marketing/referral/index` | `.../referral/index.tsx` | P4 |
| `18:10337` | `/merchant/pages/marketing/referral/edit` | `.../referral/edit.tsx` | P4 |
| `14:8836` | `/merchant/pages/marketing/member-card/index` | `.../member-card/index.tsx` | P5 |
| `16:9098` | `/merchant/pages/marketing/member-card/edit` | `.../member-card/edit.tsx` | P5 |
| `18:10697` | — | — | 不做 |
| `18:10938` | — | — | 不做 |
| `12:7426` | `/merchant/pages/staff-verify/index` | 已实现 | — |

## 附录 C：契约/错误码/事件登记清单

- 契约：71 v0.4（P1）、76（P1）、77（P2）、72 v0.2/78（P3）、79（P4）、80（P5）；同步 38/11/27/74/75/61/12。
- 错误码（按 12 号登记，命名以最终契约为准）：
  - P1：`MARKETING_ASSET_INVALID`、`MARKETING_ASSET_TOO_LARGE`、`CAMPAIGN_THEME_INVALID`、`CAMPAIGN_PLAY_TYPE_INVALID`、`MARKETING_STORE_NOT_ELIGIBLE`。
  - P2：`FLASH_PRICE_NOT_FOUND`、`FLASH_PRICE_STATE_NOT_ALLOWED`、`FLASH_PRICE_WINDOW_OVERLAP`、`FLASH_PRICE_QUOTA_EXCEEDED`、`FLASH_PRICE_LIMIT_REACHED`、`FLASH_PRICE_INVALID`。
  - P3：`MERCHANT_COUPON_NOT_ELIGIBLE`、`MERCHANT_COUPON_BUDGET_EXCEEDED`、`MERCHANT_COUPON_TEMPLATE_INVALID`。
  - P4：`REFERRAL_CODE_INVALID`、`REFERRAL_ALREADY_BOUND`、`REFERRAL_NOT_ELIGIBLE`、`REFERRAL_LIMIT_REACHED`。
  - P5：`MEMBER_CARD_NOT_FOUND`、`MEMBER_CARD_STATE_NOT_ALLOWED`、`MEMBER_CARD_BALANCE_INSUFFICIENT`、`MEMBER_CARD_COUNT_INSUFFICIENT`、`MEMBER_CARD_EXPIRED`、`MEMBER_CARD_RECHARGE_INVALID`、`MEMBER_CARD_REFUND_NOT_ALLOWED`。
- 事件：
  - P2：`ServiceFlashPriceChangedEvent`（可选，读侧按时间过滤可不需要）。
  - P4：`ReferralBoundEvent`、`ReferralQualifiedEvent`、`ReferralRewardIssuedEvent`。
  - P5：`MemberCardRechargedEvent.v1`、`MemberCardPaymentSucceededEvent.v1`、`MemberCardRefundedEvent.v1`；消费既有 `OrderPaidEvent`、`OrderVerifiedEvent.v2`、`RefundSucceededEvent.v1`。
- 调度：P2 秒杀开始/结束/配额释放；P3 券活动到期；P4 奖励窗口任务；P5 充值/退款查询、卡到期、对账；均走 durable AsyncTask，不新增内存定时器。

---

## 附录 D：给执行智能体的硬性提示

1. 先读 `AGENTS.md`、41 号补充（待建）、对应 CCR 与契约；没有批准文档不要写代码。
2. 不要照抄 Figma 的「立即上架」「返现」「储值金额」「带来订单/营收」；先确认后端事实与规则。
3. 所有金额/余额/次数/统计数字必须来自接口；禁止 mock 成生产数据。
4. 不要改订单/支付/退款/售后/核销硬规则来「适配页面」；需要改契约的，先出 CCR。
5. 不要新增 `JdbcTemplate`/SQL 注解/Java 内联 SQL；Mapper XML 在本模块。
6. 不要跨模块访问 Mapper/DO/Entity；`biz` 只依赖 `*-api`。
7. 每个写命令 requestId + 版本 + 审计；每个阶段独立 PR、开关默认关闭。
8. 完成时按 Definition of Done 提供测试证据与遗留风险，不得只说「已完成」。
