# CCR-MKT-001：营销活动中心（活动位/活动会场/商家促销/商家报名/券活动）

状态：APPROVED / IMPLEMENTING。2026-10-27 用户裁决：一次性完整交付，不分期；买一送一不做；
设计稿团购套餐改为单次服务列表；活动详情复用门店详情；商家报名、商家发起活动、券活动/批次与
C 端领取全部实现。授权来源：[38 号裁决补充](../../docs/01-prd/38-营销活动中心裁决补充-v1.0.md)。

## 1. 问题与已有依据

工程此前把营销位登记为「无 V1 后端」并从首页/服务页裁剪，但 PRD 已有明确底座：

- C 端 5.1.2 首页：活动 Banner 1-3 个、有效期与落地页校验、失效自动隐藏；
- C 端 5.1.14 商家详情：活动角标 + 商家/运营配置促销文案；
- 商家端 5.7 店铺资料：促销等级 1-9 折、促销文案标题/文案 0-100 字（后端字段与商家端编辑已存在，
  C 端未投影）；
- 运营端 6.11.5：Banner 与活动位（首页 Banner/首页活动位/商家列表置顶，跳转活动页/商家/服务/
  小程序路径/无跳转，生效期、排序、启停，首页 Banner 最多 3 个）；
- 运营端 6.4.2：券活动/批次（新用户券、每人限领、总上限、预算、发券期、状态机、主动领取/被动发放）；
- C 端 5.1.21：我的优惠券、活动券领取上限；
- Figma `689:1190` 首页-活动钜惠、`110:480` 服务-商家钜惠、`96:3328` 优惠活动、
  `96:4355` 优惠活动详情（本质为门店详情）。

因此本 CCR 不新增产品规则，只补齐 PRD 已明确但未实现的营销读侧/写侧；商家报名、商家发起活动、
活动会场结构属于 38 号裁决授权的实现口径。

## 2. 领域模型（最小变更）

新增模块 `pet-marketing-api` / `pet-marketing-biz`，活动域只负责内容、招商、投放与页面 Schema；
券实例仍由 `pet-coupon` 负责；订单/支付/退款不反向依赖营销。

| 对象 | 表 | 职责 |
|---|---|---|
| 活动 | `marketing_campaign` | 平台/商家发起、类型、参与方式、时间、城市/分类范围、页面 Schema 版本 |
| 活动门店 | `marketing_campaign_store` | 参与门店、来源（平台选择/商家报名/商家自有）、优惠角标/文案、排序 |
| 活动服务 | `marketing_campaign_service` | 可选服务范围；C 端仍只展示单次服务 |
| 页面版本 | `marketing_page_version` | 发布快照，支持回滚 |
| 投放位 | `marketing_placement` | 首页活动位/商家列表置顶/首页 Banner、城市、排序、生效期、启停 |
| 商家报名 | `marketing_enrollment` | 商家报名活动、审核状态、材料/优惠文案、名额 |
| 活动审计 | `marketing_campaign_audit` | 活动/页面/投放写动作 append-only 审计 |
| 报名审计 | `marketing_enrollment_audit` | 报名/审核写动作 append-only 审计 |
| 幂等 | `marketing_command_idempotency` | 全部营销写动作 requestId 幂等 |
| 券活动 | `coupon_campaign` | 关联既有券模板、发放对象、限领、总上限、预算、发券期、状态 |
| 券领取 | `coupon_claim` | 用户领取记录，`(campaign_id,user_id)` 唯一 |

商家促销复用 `merchant_store_profile.promo_level/promo_title/promo_text`，不新增表；
C 端门店详情/门店列表新增可选 `promotion` 投影，服务 tab 商家钜惠横排读 `promoted=true` 门店列表。

## 3. 状态机

- 活动：`DRAFT → PENDING_REVIEW（仅商家发起）→ SCHEDULED → ONLINE → PAUSED → ENDED`；
  `DRAFT/PENDING_REVIEW/SCHEDULED/PAUSED → CANCELED`；`PENDING_REVIEW → REJECTED`。
  平台活动由运营直接 `DRAFT → SCHEDULED/ONLINE`（SSOT §24 单运营，无双人复核）。
- 报名：`SUBMITTED → UNDER_REVIEW → APPROVED / REJECTED`；`SUBMITTED/UNDER_REVIEW → WITHDRAWN`。
- 投放位：`DRAFT → ENABLED → DISABLED`。
- 券活动：`DRAFT → SCHEDULED → ACTIVE → PAUSED → ENDED`。
- 全部状态转换使用乐观 `version` + CAS；同 requestId 同参重放返回首回执，异参 409。

## 4. 热更新（新活动不改小程序代码）

- 活动页是**一个通用页面** `consumer/pages/marketing/campaign`，按后台下发的 `pageSchema` 渲染。
- `pageSchema.schemaVersion=1`，组件白名单：`hero`、`coupon`、`storeList`、`richText`、
  `notice`、`buttonGroup`、`spacer`、`divider`；未知组件跳过并继续渲染其余组件。
- 后台可编辑 Section 列表（增删/上下移/属性），发布时服务端校验；发布快照入
  `marketing_page_version`，可回滚到历史版本。
- 首页活动位、服务 tab 商家钜惠横排由 `marketing_placement`/商家促销读侧驱动；
  新活动只新增数据行，不改小程序代码。
- 只有新增一种组件类型或新增一个原生页面才需要发版；该边界写入契约 71，不承诺“任意页面零发版”。

## 5. 权限、事务、幂等

- 运营动作码：`marketing.campaign.read|manage|publish`、`marketing.placement.read|manage`、
  `marketing.enrollment.read|review`、`coupon.campaign.read|manage|publish`；
  沿用 ADMIN_WEB Bearer、会话快照 + 资源范围复核；平台配置默认 `ALL`，城市范围账号按
  `cityCode` 校验；fail-closed。
- 商家动作：C 端小程序会话 + 门店 OWNER 事实；报名/发起活动只允许绑定本人门店；
  审计记录操作者、requestId、traceId；商家不得自建券/发券。
- C 端公共读匿名；券领取需要 C 会话，防枚举统一 404；领取按 `(campaign,user)` 唯一 +
  requestId 幂等；库存/预算条件更新防超发。
- 所有写动作 requestId 必填；SQL 仅进本模块 Mapper XML；biz 不依赖另一个 biz；
  `pet-marketing-biz` 仅依赖 `pet-merchant-api` 查询接口或 boot 适配端口。

## 6. 接口与页面

- 运营端：活动 CRUD/发布/暂停/结束/取消、参与门店替换、页面草稿/发布/回滚、投放位 CRUD/启停、
  报名审核、商家活动审核、券活动 CRUD/发布/暂停/结束/领取记录。
- 商家端：可报名活动、报名/撤回/状态、我的活动、创建/编辑/提交/取消、店铺促销字段（既有）。
- C 端：活动位、活动聚合页、活动标签、活动详情（Schema）、门店详情活动横幅/促销、
  商家钜惠横排、券活动详情/领取、我的优惠券（既有）。
- 完整字段、错误码、分页/信封/缓存规则见
  [71 号营销活动中心契约](../../docs/04-api/71-Marketing-Center-Contract-v0.1.md) 与
  [72 号券活动契约](../../docs/04-api/72-Coupon-Campaign-Contract-v0.1.md)。

## 7. 不做/边界

- 买一送一、团购套餐/次卡/普通商品、折扣券类型、商家自主发券、商家资金结算/分摊、
  秒杀/拼团/裂变/会员卡/直播、个性化推荐、活动曝光/成交归因报表。
- 被动发券不新增注册触发消费者；本期实现 C 端主动领取，运营仍可用既有发放渠道。
- 不改变订单/支付/退款/核销/售后状态机；不新增订单类型；券实例生命周期仍按 61 号。

## 8. 实施与验收

- 后端：新增模块、Schema 迁移、Mapper XML、服务、HTTP 控制器、开关、动作码、审计；
  `check-persistence-style.py`、架构测试通过。
- 前端：运营工作台营销中心、商家端营销活动、C 端活动位/活动页/商家钜惠/券领取；
  typecheck/build/单测通过。
- 验收：运营配置新活动 → C 端无需发版可见；商家报名/发起活动 → 运营审核 → C 端展示；
  用户领券 → 下单抵扣；过期/暂停/落地页失效自动隐藏；未知 Schema 组件不白屏。
- 默认装配开关关闭，迁移 opt-in；不授权生产部署。
