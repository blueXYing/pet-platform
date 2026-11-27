# CCR-MKT-MERCHANT-COUPON-001：商家自建优惠券（P3）

- 状态：**ACCEPTED / 2026-11-27**（依据 [41 号裁决补充](../../docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md) §5，用户 2026-11-17 批准；本文固化技术决策，不再向用户提问）
- 关联契约：[79 号商家自建优惠券契约](../../docs/04-api/79-Merchant-Coupon-Campaign-Contract-v0.1.md)；修订 [61 号](../../docs/04-api/61-Coupon-Lifecycle-Contract-v0.1.md)、[72 号](../../docs/04-api/72-Coupon-Campaign-Contract-v0.1.md)、[75 号](../../docs/04-api/75-Payment-Center-Contract-v0.1.md)、[78 号](../../docs/04-api/78-Payment-Settlement-Contract-v0.1.md)、[12 号](../../docs/04-api/12-Error-Code-Registry-v0.5.md)
- 关联设计：Figma `12:8436`（优惠券列表）、`12:8575`（创建优惠券活动）
- 执行包：[HANDOFF](../progress/2026-11-17/merchant-marketing-tools/HANDOFF.md) §2.3/§6、[PLAN](../progress/2026-11-17/merchant-marketing-tools/PLAN.md) §8
- 是否涉及产品裁决：**否**（41 号 §5 已批）。本文有几处技术选择与执行包的草图不同，逐条写在 §3.4，随 PR 披露

## 1. 问题

1. 现有券的写入口（模板/活动/发券/作废）全部只对平台运营开放；券模板没有出资方，结算把全部券抵扣记为平台补贴（`SettlementEventConsumers` 把 `merchant_discount_amount` 写死为 0）。
2. 券的适用范围没有任何校验：任何券都能在任何门店下单时冻结。商家出资的券一旦出现，必须限定在本商家的订单上使用。
3. 现有券活动把每人限领固定为 1（服务强制 + `coupon_claim(campaign_id,user_id)` 唯一键），活动名全局唯一，没有审核态。

## 2. 范围

### 2.1 做

- 商家创建/编辑/提交/取消券活动（仅无门槛券、满减券）；运营审核（通过/驳回/暂停/结束）。每个活动绑定一个专属商家券模板，模板与活动整体审核。
- 商家列表、详情、统计（发券数、本月发券、核销率、带动订单数）。
- 复用 72 号的 C 端领取：商家券活动每人限领 1..10；预算或总量耗尽即停止领取。新增按商家列出可领活动的接口。
- 冻结闸门：商家券只能用于本商家的订单。
- 订单快照记录券出资方与商家券面额；结算单按真实出资方拆分商家出资优惠与平台补贴。
- 独立的预算账本（与券台账分离），记 `RESERVE/USE/UNUSE` 三类流水。逐笔日对账**不在本 PR**，作为紧随其后的独立 PR 交付（见 §3.4）。
- 小程序商家端两个页面、营销 hub 入口、运营后台审核页。

### 2.2 不做

折扣券、兑换券、券包；平台券与商家券叠加；商家向指定用户发券；活动头图上传；新客专享；商家直接发布；暂停后恢复；曝光/点击/净 GMV；C 端领取界面（入口待产品拍板，后端接口已就绪）；41 号 §3.1 提到的商家活动玩法 `MERCHANT_COUPON`（执行包没有它的规格）。

## 3. 决策

### 3.1 业务形态

1. 商家券 = 商家出资的无门槛券/满减券；每个活动 1:1 一个 `coupon_template`（`owner_type=MERCHANT`、`funding_type=MERCHANT`），有效期同活动窗口。
2. `DRAFT → PENDING_REVIEW → SCHEDULED/ACTIVE`；`REJECTED`、`CANCELED`；运营可 `PAUSED`、`ENDED`。商家不可发布。
3. `amountOff > 0`；`budgetLimit ≥ amountOff`；`totalLimit × perUserLimit` 沿用既有列约束（1..1,000,000 / 1..10）。
4. 领取、冻结、消费、释放、返券全部沿用 61/72 号；不新增券实例状态。
5. 每单最多一张券由既有结构保证；平台券与商家券不叠加因此自动成立。
6. 分账口径（78 号 §3）：`base0 = original_price − flash_discount_amount − merchant_discount_amount`。

### 3.2 适用范围

`CouponFreezeCommand` 增加 `merchantId`。模板 `funding_type=MERCHANT` 且 `owner_merchant_id ≠ 订单 merchantId` → 409 `COUPON_SCOPE_MISMATCH`（12 号既有码，启用并登记 409）。平台券行为不变。

### 3.3 订单与结算

- `pet_order` 增加 `coupon_funding_type`、`merchant_coupon_discount`。存量订单迁移后为 `NULL` / `0.00`，结算结果不变。
- `OrderSettlementFact` 增加 `merchantCouponDiscount`；结算单注册时 `merchant_discount_amount` 取该值，`platform_subsidy_amount = max(0, discountAmount − merchantCouponDiscount)`。
- 计算器、退款重算、分账回退均不改：它们读的是结算单快照。

### 3.4 与执行包草图不同的技术选择（随 PR 披露）

| 事项 | 执行包写法 | 本文选择 | 原因 |
|---|---|---|---|
| 契约编号 | 78 号 | **79 号** | 78、80 已被支付结算契约占用（PR #235） |
| 活动名唯一性 | 未提及 | 全局唯一改为按归属商家、且只约束未终结活动（生成列 `live_name`：`CANCELED/ENDED` 为 NULL） | 多个商家会起同名活动（如"新客立减"）；已取消/已结束的活动应释放名称，否则商家下个月无法复用（72 号原文是"同期不重名"）；平台活动之间仍不重名 |
| 每人限领 | "由商家设置" | 1..10；`coupon_claim` 唯一键改为 `(campaign_id,user_id,claim_no)` | 72 号 §2.2 已写明放开需另开 CCR；平台活动仍由服务层固定为 1 |
| 预算账本 | `coupon_campaign` 增 `budget_reserved/budget_used` + 新表 | 只用独立表 `merchant_coupon_budget(_ledger)`，活动表只保留既有 `budget_limit/issued_amount` | 41 号 §5.4 要求预算账本与券台账分离，不在活动表上再加预算余额列 |
| 日对账 | "日对账" | **本 PR 不含**；后续独立 PR 由券模块自建告警表并挂每日调度 | 实施时发现支付中心差异表 `reconcile_diff` 对业务类型/差异类型有取值约束，挂进去要新增迁移并改动 #235 刚落的对账服务与差异页面，不宜叠在本 PR。本 PR 用自动化测试钉住"结算单金额 = 订单快照 = 券面额"与预算账本不变量 |
| 审核动作码 | 未指定 | 复用 `coupon.campaign.read` / `coupon.campaign.publish` | 不新增权限码，避免三处硬编码权限清单与安全链改动 |
| 活动头图 | 未提及 | 不做 | 头图只在 C 端领取界面展示，而该界面入口尚未拍板 |
| 开关 | "新增开关默认 false" | `pet.coupon.merchant.enabled`：`application.yml` 为 `false`，`dev/test/prod` 各为 `true` | 与 #234 对闪价开关的处理一致（三套环境装配一致的全量能力，隔离单测用裸默认值） |

## 4. Schema / API / Event 变更

- 新增：[SQL84](../../docs/03-database/84-Merchant-Coupon-Schema-v0.1.sql)、`db/marketing-migration/V38__merchant_coupon.sql`、`db/order-migration/V32__order_coupon_funding.sql`、79 号契约、`merchant_coupon_budget` 与 `merchant_coupon_budget_ledger` 两表。
- 修订：[SQL06](../../docs/03-database/06-核心数据库Schema-v0.1.sql)（`coupon_template`、`pet_order`）、[SQL63](../../docs/03-database/63-Marketing-Storage-v0.1.sql)（`coupon_campaign`、`coupon_claim`）、61/72/75/78/12 号、[21 号开关清单](../../docs/08-engineering/21-部署开关组合清单-v1.0.md)、[设计源登记表](../../docs/08-engineering/20-设计源登记表-figma-map.md)。
- 内部 API：`CouponFreezeCommand(+merchantId)`、`CouponFreezeResult(+fundingType)`、`OrderSettlementFact(+merchantCouponDiscount)`；`pet-coupon-api` 新增商家券管理接口（商家端、运营审核端）。
- Event：消费既有 `OrderPaidEvent.v1`（写预算账本 `USE`）；不新增事件类型，`OrderPaidEvent.v1` 载荷键集不变。
- HTTP：商家端 7 个端点、运营端 6 个端点、C 端 1 个新端点（详见 79 号）。新端点不进入 11 号 OpenAPI 与 `e2e/contract_smoke.py` 家族（与 77、80 号一致）。

## 5. 兼容与回滚

- 平台券与平台券活动的既有接口、既有结算结果不变：新列均有默认值，平台路径不读取商家券数据。
- 开关关闭时：商家端与运营端商家券端点不注册，C 端列表不返回商家券活动；已发放的商家券实例冻结闸门照常生效。
- 回滚：关开关即可停止新写入；已落库的订单快照列与结算快照不需要回写。

## 6. 测试

- 券模块 MySQL 集成：商家活动状态机全迁移；名称按商家唯一；每人限领 1 与 3（并发 3 抢 2 只成功 2）；预算耗尽与总量耗尽；预算账本 RESERVE/USE/UNUSE 幂等与不变量；商家券冻结闸门（本商家通过、他商家 409）；平台券活动行为不变（每人限领仍为 1）。
- 订单/结算：商家券订单 `coupon_funding_type=MERCHANT`、`merchant_coupon_discount=面额`；结算单 `merchant_discount_amount=面额`、`platform_subsidy_amount=0`；平台券订单两个快照与现状一致；闪价 + 商家券叠加。
- HTTP：商家端非 OWNER 404（与资源不存在不可区分，沿用服务域准入口径）、跨商家 404；运营端无动作码 403；开关关闭端点不存在。
- 前端：小程序 typecheck/单测/`build:weapp`/`check:package`；运营后台 typecheck/build/`check:boundaries`。
- 门禁：`check-persistence-style.py`、`check-module-deps.py`、架构测试。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 放宽 `coupon_claim` 唯一键削弱平台活动防重 | 平台活动每人限领仍由服务层固定为 1，且在活动行锁内判定；回归测试覆盖平台活动重复领取 |
| 冻结闸门改动触及常开的券生命周期内核 | 只增加一条读取模板列后的判定；平台券路径不变；`CouponLifecycleMySqlTest` 全量回归 |
| 结算写入点改动影响存量订单 | 存量订单 `merchant_coupon_discount` 默认 0，公式等价；结算测试覆盖三种券场景 |
| 商家券无法在 C 端领取（入口未拍板） | 后端接口就绪；界面入口作为独立事项推进，不阻塞本阶段合入 |
| 逐笔日对账未随本 PR 交付 | 不变量由单写入路径保证并有测试覆盖；日对账作为紧随其后的独立 PR，上线前完成 |
