# 运营治理台二期 HTTP 契约 v0.2（百科 / 券 / 台账 / 系统设置 / 券发放，A-005）

状态：IMPLEMENTED_INTERNAL_DEFAULT_OFF，最终以实现 PR 的 CI 为准。v0.1 2026-10-16；**v0.2 2026-10-16 增补 §4.3 券发放渠道**（用户裁决 2026-10-16："做券发放渠道——此前券只能数据库造数，运营台只能管模板，'发券给用户'缺失导致券功能空转"；最小实现按 [CCR-CPN-GRANT-001](../../planning/ccr/CCR-CPN-GRANT-001.md) 冻结）；**v0.3 2026-10-16 增补 §4.4 券作废渠道**（用户裁决 2026-10-16 ⑥"治理写动作最小授权"两项之一，[36 号](../01-prd/36-治理写动作最小授权裁决补充-v1.0.md)；最小实现按 [CCR-CPN-VOID-001](../../planning/ccr/CCR-CPN-VOID-001.md) 冻结）；**v0.4 2026-10-19 修订 §4.3 发放渠道强化批**（用户裁决 2026-10-19 "继续扩展CCR"：持有上限/发放频控/total_stock 库存扣减/目标用户存在性校验/批量发放五项工程扩展，规则按行业惯例最小实现，[CCR-CPN-GRANT-001](../../planning/ccr/CCR-CPN-GRANT-001.md) §1 逐项登记）。文件名沿用 v0.1 不改，x-contract 钉不变。
来源：运营端 PRD `docs/01-prd/04-PRD-运营端-V1.0-最终基线.docx` §6.4（优惠券管理）/§6.10（宠物百科与品种库）/§6.5（积分与任务管理：积分流水）/§6.11（基础台账表）/§6.13（系统设置）；HTTP10 §5.8 优惠券/积分/审计草案与权限矩阵（OD-W0-003）行 13/14/15/21/23；[61 号](61-Coupon-Lifecycle-Contract-v0.1.md)（券生命周期与 rule_json 冻结结构）、[62 号](62-Baike-Content-Contract-v0.1.md)（百科读侧，其"运营编辑/发布/下线写侧与权限面（A-005）"遗留由本契约收口）；SSOT §9（券规则唯一口径）、§24（单运营无双人复核）、§25（无 MFA 门禁）；[63 号](63-Admin-Trade-Query-Contract-v0.1.md)/[64 号](64-Admin-Governance-Contract-v0.1.md) 鉴权与边界先例。动作码提案矩阵行 13/14/15/21/23 的连字符拼写按 AUTH 词法（`AdminActionCheckQuery`，小写点分段）落地为点分拼写（`service.force.offline`/`refund.channel.query` 同款实现期拼写更正，PR 披露）。

本契约为运营治理台二期（券模板管理、券实例台账、券发放渠道、百科分类/品种维护与上下线、积分台账、系统设置运行时开关视图）唯一契约文档（65 号预分配位）。**社区治理（HTTP10 矩阵行 20）用户已裁决不在本切片**，一行不做。

## 1. 范围与产品边界

四块范围与其 PRD 依据：

1. **百科内容治理**（PRD §6.10，权限矩阵行 21）：分类停用/启用/编辑/排序、品种上下线/编辑维护 + 运营读侧（全状态列表/详情）。收口 62 号遗留"分类停用、内容上下线暂无运营面（A-005 前只能改库）"。
2. **券治理**（PRD §6.4.1 券模板管理 + §6.4.3 券实例台账，权限矩阵行 13/14；**v0.2 增 §4.3 发放渠道**，用户裁决 2026-10-16；**v0.3 增 §4.4 作废渠道**，用户裁决 2026-10-16 最小授权 36 号；**v0.4 强化 §4.3 发放渠道**，用户裁决 2026-10-19）：券模板读/建/编/启用/停用/试算 + 券实例台账读面（列表/详情/状态时间轴）+ 发放渠道（单发/批量≤100 按 ACTIVE 模板发 AVAILABLE 实例，持有上限/频控/库存扣减/用户存在性守卫，CCR-CPN-GRANT-001）+ 作废渠道最小实现（作废指定单张 AVAILABLE 实例，非 AVAILABLE 一律 409，CCR-CPN-VOID-001）。券规则唯一口径为 SSOT §9（退款返券/部分退款不返），本契约不新增任何券状态词表、不改变 61 号冻结语义（模板 `ACTIVE` 是冻结前置④，模板停用后新订单不可再用该模板券——61 号既有事实，本面只是给出运营杠杆，PR 披露）。
3. **台账**（PRD §6.5 积分流水 + §6.11，权限矩阵行 15）：积分台账读面（HTTP10 §5.8 草案 `GET /admin/points/ledger`）。商家台账（64 号已交付）、券实例台账（本契约第 2 块）不重做。
4. **系统设置**（PRD §6.11.1 系统参数台账 + §6.13，权限矩阵行 23）：系统设置运行时开关视图（只读）——把 21 号《部署开关组合清单》登记的 `pet.*` 装配开关及其当前实际状态作为只读面输出，**不新造配置机制**（系统参数运行时写入不在 V1；PRD"系统参数台账·双人复核"已被 SSOT §24 单运营裁决覆盖为无需第二人，但运行时写配置仍无授权，见 §6 CCR）。

**不在本切片（CCR 登记，一行不做）**：券实例写动作（批量作废/风控冻结/解冻/手动返还——SSOT §9 未授权直接改用户券状态；单张作废已按 2026-10-16 用户裁决最小授权 36 号放开为 §4.4）；券活动/批次与"导入路径"（发放渠道已按用户裁决以最小实现放开为 §4.3，批量/持有上限/频控/库存扣减等扩展项仍 CCR-CPN-GRANT-001 §2 待裁；券实例仅运营侧数据的导入路径 V1 不做）；券模板"领取后 N 天"有效期模式（需 Schema/61 号冻结语义扩展）；~~百科分类/品种新增与删除、导出~~【**v0.4 2026-10-19 用户裁决"继续扩展CCR"已放开：分类/品种新增（同款守卫）与 CSV 导出（有界），见 §3；删除仍不做**】；资金台账专项面（PRD §6.11.1 明示详见《资金管理 V1.4》，该文件不在仓，字段级口径缺失，不发明）；统一审计日志面 `GET /admin/audit-logs`（审计行分散于各内核处置表 + 文件 sink，统一存储未决）；后台账号/角色管理写面（权限矩阵行 26，AUTH owner 专项，V1 单运营种子账号，无授权矩阵登记）；消息公告/通知模板面（矩阵行 24，无契约无事件）。
**不在本切片（CCR 登记，一行不做）**：券实例写动作（批量作废/风控冻结/解冻/手动返还——SSOT §9 未授权直接改用户券状态；单张作废已按 2026-10-16 用户裁决最小授权 36 号放开为 §4.4）；券活动/批次与"导入路径"（发放渠道已按用户裁决以最小实现放开为 §4.3，批量/持有上限/频控/库存扣减/用户存在性校验五项已按 2026-10-19 裁决落地为 §4.3 v0.4，CCR-CPN-GRANT-001 §1.1；券实例仅运营侧数据的导入路径 V1 不做）；券模板"领取后 N 天"有效期模式（需 Schema/61 号冻结语义扩展）；百科分类/品种新增与删除、导出（分类枚举五值 PRD 不可自定义新增；品种新增需封面图上传通道，V1 运营侧无素材通道；导出不在 V1 已批准范围，63 号 v0.2 同口径）；资金台账专项面（PRD §6.11.1 明示详见《资金管理 V1.4》，该文件不在仓，字段级口径缺失，不发明）；统一审计日志面 `GET /admin/audit-logs`（审计行分散于各内核处置表 + 文件 sink，统一存储未决）；后台账号/角色管理写面（权限矩阵行 26，AUTH owner 专项，V1 单运营种子账号，无授权矩阵登记）；消息公告/通知模板面（矩阵行 24，无契约无事件）。

## 2. 鉴权、开关与边界（沿 63/64 号先例）

- 新装配开关 `pet.admin.ops.http.enabled`（默认 **false**），与 `pet.auth.admin.enabled` 双条件装配全部内核与控制器（63 号 `pet.admin.trade.http.enabled` 同款：http=true 而内核 bean 缺失直接启动失败）；HTTP 置 true 而内核关闭直接装配失败。21 号清单同步登记。**v0.2 增第二装配开关 `pet.coupon.grant.enabled`（默认 false）**：仅门控 §4.3 发放内核（`CouponGrantConfiguration`，另需 `pet.auth.admin.enabled`）；**v0.3 增第三装配开关 `pet.coupon.void.enabled`（默认 false）**：仅门控 §4.4 作废内核（`CouponVoidConfiguration`，另需 `pet.auth.admin.enabled`）。开关关闭而 ops 面开启时，对应路由以 503 `COMMON_DEPENDENCY_UNAVAILABLE` 失败关闭（61 号"提供器未装配"语义），不随面整体装配失败。默认值翻转随 35 号开关放开裁决统一处理。21 号清单同步登记三行。
- 全部路由真实 ADMIN_WEB Bearer 会话（`AdminBearerAuthenticationFilter`），控制器做会话快照动作码 + `ALL` 数据范围门禁（本面全部资源为全平台字典/配置/台账，CITY/MERCHANT fail-closed 403，CCR-PERM-001 §3 同口径）；每个内核经注入的模块本地授权端口在事务/读取前重验会话、动作码与范围（快照永非授权证据）。
- `Cache-Control: no-store` 全路由；信封 `{code,message,data,traceId}`；未知 query 参数整单 400；分页 `page` 缺省 1（1..10000）、`pageSize` 缺省 20（1..100）；ID 全部 Snowflake 十进制 String；金额两位小数字符串；时间 ISO-8601 UTC 毫秒。
- 写路由（POST/PUT）一律终结性 `X-Request-Id`（23 号幂等契约）：幂等绑定在本内核 `command_idempotency`（14 号共享物理表）append-only 行内完成，同 ID 异参重放 409 `IDEMPOTENCY_KEY_CONFLICT`，同 ID 同参重放返回首次成功回执；状态冲突 409 `COMMON_CONFLICT`；资源不存在/不可见 404 `COMMON_NOT_FOUND`（防枚举）。
- 展示态零推导（ARCH-005 词法纪律）：控制器只透传内核投影事实；无敏感明文（本面无手机号/地址/密文字段；券实例用户仅 Snowflake String）。
- biz 不依赖 biz；SQL 只进本模块 Mapper XML（community/coupon/points 各自 `CommunityMybatis`/`CouponMybatis`/`PointsMybatis` 命名空间）；无 JdbcTemplate/注解 SQL/内联 SQL，过 `backend/tools/check-persistence-style.py`。
- 无事件 by design：本面全部为配置/内容维护与台账读，无业务事实跨域消费方（消费方读权威状态事实，同 63 号 v0.2 商家下线先例）——PR 披露。无 Outbox/AsyncTask 新增。

## 3. 百科治理（pet-community-api/biz `baike` 子域）

动作码：`encyclopedia.read`（读字典/列表 + **v0.4 CSV 导出**）、`encyclopedia.manage`（分类维护写 + **v0.4 分类新增**）、`encyclopedia.publish`（品种上下线）、`breed.read`（品种详情读）、`breed.manage`（品种编辑维护 + **v0.4 品种新增**）——矩阵行 21 全行登记部署（编 D，获权编辑可直发，SSOT §24 无第二人）。零新增动作码。

**v0.4 增补（2026-10-19 用户裁决"继续扩展CCR"，百科节）**：分类/品种**新增**（字典枚举不再封闭）与 CSV 导出。同款守卫=与编辑一致的 14 号 command_idempotency 幂等绑定（同参重放首回执/异参 409）+ 模块本地授权端口重验 + 乐观/唯一键守卫；无删除路由。

| 方法/路径 | 动作码 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|---|
| GET `/api/v1/admin/encyclopedia/categories` | encyclopedia.read | page/pageSize | 全状态分类分页（`categoryCode/categoryName/status/sortOrder/breedCount/publishedBreedCount/version/createdAt/updatedAt`） | 400/401/403 |
| POST `/api/v1/admin/encyclopedia/categories` | encyclopedia.manage | `{categoryCode(词法 2..16：A-Z 数字下划线), categoryName(2..10码点), sortOrder(0..999)}`；终结性 X-Request-Id | `{categoryCode,categoryName,status:"ENABLED",sortOrder,version:0,occurredAt}`（幂等） | 400（词法）/401/403/409（编码重复）/503 |
| PUT `/api/v1/admin/encyclopedia/categories/{categoryCode}` | encyclopedia.manage | `{categoryName(2..10码点), sortOrder(0..999), expectedVersion}` | `{categoryCode,categoryName,sortOrder,status,version}` | 400/401/403/404/409 |
| POST `/api/v1/admin/encyclopedia/categories/{categoryCode}/enable` | encyclopedia.manage | 无 body | `{categoryCode,status:"ENABLED",version}` | 404/409（已启用） |
| POST `/api/v1/admin/encyclopedia/categories/{categoryCode}/disable` | encyclopedia.manage | 无 body | `{categoryCode,status:"DISABLED",version}` | 404/409（已停用） |
| GET `/api/v1/admin/encyclopedia/breeds` | encyclopedia.read | page/pageSize/status?(PUBLISHED\|OFFLINE)/categoryCode?/keyword? | 全状态品种分页卡（C 端 BreedCardView 字段 + `status/sortOrder/version/updatedAt`） | 400/401/403 |
| POST `/api/v1/admin/encyclopedia/breeds` | breed.manage | `{categoryCode(必须存在且 ENABLED), name(1..30码点，同类别唯一), summary(1..120), coverUrl?(≤512，URL 维护), bodyType?(≤32), aliases?(≤5×50), tags?(≤10×30), sortOrder(0..9999)}`；终结性 X-Request-Id | `{breedId,name,status:"OFFLINE",sortOrder,version:0,occurredAt}`（幂等） | 400/401/403/404（分类不存在）/409（重名或分类停用）/503 |
| GET `/api/v1/admin/encyclopedia/breeds/{breedId}` | breed.read | — | 全字段详情（C 端 BreedDetailView 字段 + `status/sortOrder/version/createdAt/updatedAt`），OFFLINE 行同应答 | 400/401/403/404 |
| PUT `/api/v1/admin/encyclopedia/breeds/{breedId}` | breed.manage | `{summary(≤120), coverUrl(≤512), bodyType(≤32), aliases[](≤5,单个≤50), tags[](≤10,单个≤30), sortOrder(0..9999), expectedVersion}` | `{breedId,status,sortOrder,version}` | 400/401/403/404/409 |
| POST `/api/v1/admin/encyclopedia/breeds/{breedId}/publish` | encyclopedia.publish | 无 body | `{breedId,status:"PUBLISHED",version}` | 404/409（已发布） |
| POST `/api/v1/admin/encyclopedia/breeds/{breedId}/offline` | encyclopedia.publish | 无 body | `{breedId,status:"OFFLINE",version}` | 404/409（已下线） |
| GET `/api/v1/admin/encyclopedia/export` | encyclopedia.read | — | `text/csv` 双段文档（分类段含计数 + 品种段清单卡字段；富文本列不入导出；RFC 4180，UTF-8；`Content-Disposition` 文件名 `encyclopedia-export-{UTC时间戳}.csv`） | 400/401/403/**413（ENCYCLOPEDIA_EXPORT_TOO_LARGE）**/503 |

语义与硬规则：
- **v0.4 字典开放**：分类枚举不再封闭五值——新增分类词法 2..16（A-Z 数字下划线，uk 防重 409），born `ENABLED` version 0；既有编辑/上下线路由按 `categoryCode` 寻址（词法校验 + DB 存在性 404），C 端 62 号读侧按启用字典表自然展示新分类（读侧零改动）。**删除路由仍不存在**（字典行只停用不删除）。
- **v0.4 品种新增**：宿主分类必须存在且 `ENABLED`（404/409）；name 同类别唯一（uk 防重 409）；born `OFFLINE`（先维护再发布——publish 路由既有语义衔接）；富文本列（description/careAdvice/healthTips）本面不可写（既有披露），发布空正文品种由运营自行判断（C 端有占位回退）；封面仍为 URL 维护（无上传通道，既有口径）。
- **v0.4 CSV 导出**：只读字典数据、无任何隐私物料（无用户字段）；内核有界生成 ≤1MiB（`ENCYCLOPEDIA_EXPORT_TOO_LARGE` 413 新码，12 号登记），超限在任何响应体提交前失败关闭；流式拼装逐行计数，不构建无界缓冲。
- 分类枚举五值（DOG/CAT/REPTILE/BIRD/INSECT）为 62 号种子初始字典；路径以 `categoryCode` 寻址，`categoryName` 为展示名（PRD 2-10 字）；停用分类对 C 端立即不可见（读侧既有 `status='ENABLED'` 过滤），重新启用立即恢复——62 号读侧零改动。
- 品种状态词表沿用 62 号 `PUBLISHED/OFFLINE`：下线即 C 端列表消失、详情 404 `BREED_NOT_FOUND`（既有读侧语义）；上下线不删除/不改写任何内容列。PRD"停用品种时提示是否一并下线已发布词条"不适用：62 号模型中品种即词条本体（单一内容行），无第二内容对象——PR 披露。PRD"档案引用数校验"不适用：宠物档案品种为自由文本无外键（62 号 §7 偏差 7）——PR 披露。
- 品种编辑为白名单字段维护：富文本列本面**不可编辑**（既有披露）；文本/标签/排序/封面 URL 维护按上表边界校验（长度/个数/码点，超界 400）。
- 版本：全部写动作带 `expectedVersion` 乐观锁（失配 409 `COMMON_CONFLICT`）；新增动作为 version 0 起点无 expectedVersion（uk 防重并发安全）。
- C 端读侧（62 号三条路由）过滤事实零改动；新增字典行按既有过滤自然生效。

## 4. 券治理（pet-coupon-api/biz）

动作码：`coupon.template.read` / `coupon.template.manage`（矩阵行 13 提案 `coupon-template.read/manage` 的点分落地；行 13 的 campaign 族 V1 无业务不登记）、`coupon.instance.read`（矩阵行 14 提案 `coupon-instance.read` 的点分落地）、`coupon.instance.grant`（v0.2，发放渠道，用户裁决 2026-10-16，CCR-CPN-GRANT-001 §3）、`coupon.instance.void`（v0.3，作废渠道，用户裁决 2026-10-16 最小授权 36 号，行 14 提案 `coupon-instance.void` 的点分落地，CCR-CPN-VOID-001 §3；行 14 的 return 写码**不登记**，见 §6 CCR）。

### 4.1 券模板管理

| 方法/路径 | 动作码 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|---|
| GET `/api/v1/admin/coupon-templates` | coupon.template.read | page/pageSize/status?(DRAFT\|ACTIVE\|OFFLINE) | 模板分页（`templateId/name/status/typeLabel/amountOff/thresholdAmount/validStartAt/validEndAt/totalStock/issuedCount/version/createdAt/updatedAt`） | 400/401/403 |
| GET `/api/v1/admin/coupon-templates/{templateId}` | coupon.template.read | — | 单模板全字段 + `ruleJson` 原文四键 | 400/401/403/404 |
| GET `/api/v1/admin/coupon-templates/{templateId}/trial-calculation` | coupon.template.read | orderAmount（必填，>0 两位小数） | `{orderAmount,thresholdAmount,discountAmount,payAmount,eligible,ineligibleReason}` | 400/401/403/404 |
| POST `/api/v1/admin/coupon-templates` | coupon.template.manage | `{name(2..50码点), amountOff(>0 两位小数), thresholdAmount?(≥0 两位小数，缺省无门槛), validStartAt, validEndAt, scopeSummary?(≤64), typeLabel?}` | `{templateId,status:"DRAFT",version,createdAt}`（X-Request-Id 幂等） | 400/401/403/409 |
| PUT `/api/v1/admin/coupon-templates/{templateId}` | coupon.template.manage | 同 POST 字段 + `expectedVersion` | `{templateId,status,version,updatedAt}` | 400/401/403/404/409 |
| POST `/api/v1/admin/coupon-templates/{templateId}/enable` | coupon.template.manage | 无 body | `{templateId,status:"ACTIVE",version}` | 404/409 |
| POST `/api/v1/admin/coupon-templates/{templateId}/offline` | coupon.template.manage | 无 body | `{templateId,status:"OFFLINE",version}` | 404/409 |

语义与硬规则：
- 券类型 V1 仅无门槛券/满减券两类（PRD §6.4.1，折扣券 1.0 不实现）：`thresholdAmount` 缺省/null 即无门槛券（>0 即满减券）；`typeLabel` 为展示文本（缺省服务端按门槛推导"满减券"/"无门槛券"），不参与资格判定（61 号 §2 冻结口径）。
- 满减券门槛必须 > 面额（PRD §6.4.1），否则 400；有效期固定日期模式（`validStartAt < validEndAt`）；"领取后 N 天"模式不在本面（§6 CCR）。
- rule_json 落库为 61 号 §2 冻结四键结构（`amountOff/thresholdAmount/scopeSummary/typeLabel`），金额十进制字符串两位小数；模板启用（ACTIVE）后即满足 61 号冻结前置④，与既有 C 端下单选券链路零改动衔接。
- 编辑守卫（PRD §6.4.1"一经被活动引用，关键字段不可修改，需停用后新建"的 V1 无活动口径）：`issuedCount > 0` 的模板不可编辑任何字段（409）；OFFLINE 模板不可编辑（409，需新建）；DRAFT/ACTIVE 且未发放时可编辑规则与期限字段（同参重放按幂等回执返回）。
- 状态机：DRAFT → ACTIVE（enable）→ OFFLINE（offline）；OFFLINE/ACTIVE 不可再 enable（409）；停用**不修改、不作废任何已发放券实例**（PRD §6.4.1）；模板删除不存在（无路由）。
- 试算复用 61 号冻结资格语义的纯读投影：`eligible=false` 时 `ineligibleReason=BELOW_THRESHOLD`（订单金额 < 门槛）或 `NOT_YET_VALID`/`EXPIRED`（模板期限）或 `NOT_ACTIVE`（状态非 ACTIVE）；抵扣=`amountOff`，应付=订单金额−抵扣（不投影折后 ≤0 的拒单分支，试算对 `amountOff >= orderAmount` 返回 `eligible=false, ineligibleReason=DISCOUNT_EXCEEDS_AMOUNT`，与 61 号资格⑦一致）。

### 4.2 券实例台账（只读）

| 方法/路径 | 动作码 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|---|
| GET `/api/v1/admin/coupon-instances` | coupon.instance.read | page/pageSize/templateId?/status?(AVAILABLE\|FROZEN\|USED\|EXPIRED\|RISK_FROZEN\|VOID)/orderId?/createdFrom?/createdTo? | 实例分页（`instanceId/templateId/templateName/userId/status/discountAmount/thresholdAmount/originalExpireAt/expireAt/orderId/frozenAt/usedAt/createdAt`） | 400/401/403 |
| GET `/api/v1/admin/coupon-instances/{instanceId}` | coupon.instance.read | — | 实例全字段 + `ledger[]`（`coupon_ledger` 时间轴：`action/fromStatus/toStatus/orderId/remark/createdAt`，`created_at ASC,id ASC`） | 400/401/403/404 |

- 状态词表 v0.1–v0.2 为 61 号既有实例词表（含 `RISK_FROZEN`）；**v0.3 起新增 `VOID` 终态**（仅 §4.4 作废可落，Schema06 `coupon_instance.status` 词表注记同步更新，PR 披露）；面额/门槛为发放时快照（`rule_json` 读模板现值，PRD 快照列 V1 无独立列——PR 披露：61 号模型实例不冗余快照金额，展示取模板当前 rule 值；v0.2 后实例由发放渠道产生，作废不改任何金额字段，无快照漂移风险面）。
- 实例写路由仅 §4.3 发放与 §4.4 作废两条（返还/风控冻结/解冻/批量作废全部 CCR，§6）；`coupon_ledger` 只读透传（v0.3 起时间轴含 `VOID` 行，动作词表注记同步披露）。

### 4.3 券发放渠道（v0.2，用户裁决 2026-10-16；v0.4 2026-10-19 强化批修订）

| 方法/路径 | 动作码 | 装配开关 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|---|---|
| POST `/api/v1/admin/coupon-instances/grant` | coupon.instance.grant | ops 面 + `pet.coupon.grant.enabled`（内核关=503 失败关闭） | 单发 `{templateId, userId}`（双 Snowflake 十进制 String）**或**批量 `{templateId, userIds:[≤100 个十进制 String]}`，二者恰现其一；终结性 X-Request-Id | 单发：`{instanceId, templateId, userId, status:"AVAILABLE", expireAt, occurredAt}`；批量：`{templateId, results:[{userId, status:"GRANTED"\|"REJECTED", instanceId?/expireAt?/occurredAt? \| code?/message?}...], grantedCount, rejectedCount, occurredAt}`（单发 X-Request-Id 幂等同参重放回执；批量逐用户独立幂等，GRANTED 行重放稳定） | 400（词法/批量形态非法/userIds 空·重复·超 100）/401/403（动作码或范围）/404（模板或目标用户不存在，防枚举）/409（模板非 ACTIVE、不在有效期内、库存售罄、持有超限、60 秒频控、幂等异参）/503（内核未装配/授权决断或存在性读不可用） |

语义与硬规则（v0.2 最小实现 + v0.4 强化批按行业惯例最小实现，逐项登记 [CCR-CPN-GRANT-001](../../planning/ccr/CCR-CPN-GRANT-001.md) §1 采用规则，不发明）：

- **单次一张 / 批量逐用户一张**：一个终端 `X-Request-Id` 的单发命令向一个用户按一个 ACTIVE 模板发放恰好一张实例；批量命令按 `userIds` 顺序逐用户各发一张。批量形态约束：`userIds` 非空、≤100、去重（重复/超限/空一律 400，整体不执行）；批量与单发在同一 POST 路由上以 body 形态区分（`userId` 与 `userIds` 恰现其一）。
- **模板可用性守卫 = 61 号 §3 冻结前置④的"模板可用"事实原样执行**：发放时刻模板 `status='ACTIVE'` 且 `now ∈ [valid_start_at, valid_end_at]`，任一不满足 409（非 ACTIVE / 不在有效期内，同码不同文案）；模板不存在 404 防枚举。守卫在行锁（`SELECT ... FOR UPDATE`）事务内与写入同事务，模板 offline 与发放并发安全。
- **目标用户存在性校验（v0.4）**：内核在事务前经 boot 装配的 user 域只读存在性端口（`UserActiveStatusApi`，券域仍不依赖 user biz——模块本地端口 + boot 适配）证实 `user_account` 行存在（任意状态均算存在，无可用性政策），不存在 404（消灭"无主券"）；存在性读不可用 503 失败关闭。批量按用户逐个证实。
- **单用户同模板持有上限（v0.4）**：同用户同模板 `AVAILABLE+FROZEN` 实例数达到上限后 409（USED/EXPIRED/VOID 不占额）；上限可配 `pet.coupon.grant.max-per-template`，默认 **10**。守卫在模板行锁事务内执行，并发发放对同模板天然串行。
- **发放频控（v0.4）**：同操作者对同用户同模板 60 秒滑动窗内重复发放 409（DB 计数：ISSUE 台账行 remark 的 `operator=` 审计前缀 + 模板 join，不引 Redis）；不同用户/不同操作者互不共享窗口；窗界外（>60s）恢复可发。守卫同样在模板行锁事务内执行。
- **`total_stock` 库存扣减（v0.4）**：模板 `total_stock` 非空时 `issued_count < total_stock` 才可发，售罄 409；`total_stock=NULL` 恒为不限量（v0.2 语义保留）。扣减即既有 `issued_count+1` 语句的条件化（`WHERE ... AND (total_stock IS NULL OR issued_count < total_stock)`），与实例/台账写入同事务同锁，售罄回滚整事务。
- **实例落点**：`status='AVAILABLE'`，`original_expire_at = expire_at = 模板 valid_end_at`（61 号模型无实例级独立有效期；有效期内使用走既有冻结/核销/释放/过期生命周期，本面零改动衔接）。
- **幂等与审计**：23 号幂等契约，绑定在本内核 `command_idempotency`（14 号共享物理表）——单发沿用 v0.2 模板作用域键（同 ID 同参重放返回首次回执、异参 409 `IDEMPOTENCY_KEY_CONFLICT`）；**批量逐用户独立绑定**（键含 `:u:{userId}` 段）：每用户各自同参重放稳定（GRANTED 行的 instanceId/expireAt 不随重放漂移）、被拒用户重放时按当时事实重判；`coupon_ledger` 每实例写 ISSUE 行（`request_id='CPN:GRANT:{instanceId}'` uk，`from_status=NULL`、`to_status='AVAILABLE'`，remark 记 `operator={operatorId};source={channel}`）——61 号既有词表零新增，ISSUE 行即发放审计事实。
- **批量部分成功语义（v0.4）**：逐用户独立事务（服务内分批执行，外层仍单请求）——单用户业务失败（404 用户不存在、409 守卫）只落自身 REJECTED 明细（`code`/`message` 透传既有码），**不回滚已成功用户**；授权失败/存在性读不可用/提交结果未知等基础设施失败终止整个命令（已成功用户保持，重放按逐用户幂等续传）。回执按请求顺序逐用户一行，`grantedCount`/`rejectedCount` 汇总。
- **模板计数**：`issued_count` 同事务 +1（受 total_stock 条件约束，见上）；既有 `issued_count>0` 编辑守卫随之自然生效（已发放模板关键字段不可修改，§4.1 既有规则）。
- **鉴权**：控制器快照校验 `coupon.instance.grant` + ALL 范围（CITY/MERCHANT 403），内核在事务前经模块本地授权端口当前读重验（§2 既有纪律；批量整单一次授权，逐用户不再重复鉴权）。
- **C 端联动零改动**：发放后实例立即可见于既有 `GET /api/v1/c/coupons?status=AVAILABLE`（10 号 §3.15.1）；下单选券冻结走 61 号既有链路。发放不产生任何通知（16 号消息目录无此类型，CCR-CPN-GRANT-001 §2 明确不做）。

### 4.4 券作废渠道（v0.3，用户裁决 2026-10-16 治理写动作最小授权 36 号）

| 方法/路径 | 动作码 | 装配开关 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|---|---|
| POST `/api/v1/admin/coupon-instances/{instanceId}/void` | coupon.instance.void | ops 面 + `pet.coupon.void.enabled`（内核关=503 失败关闭） | body `{reason(10..200 码点，必填)}` + 路径 instanceId；终结性 X-Request-Id | `{instanceId, templateId, userId, status:"VOID", occurredAt}`（X-Request-Id 幂等，同参重放返回首次回执） | 400（词法/reason 缺失或超界）/401/403（动作码或范围）/404（实例不存在，防枚举）/409（非 AVAILABLE 状态、幂等异参）/503（内核未装配/授权决断不可用） |

语义与硬规则（最小实现，扩展项 CCR-CPN-VOID-001 §2 待裁，不发明）：

- **只可作废 AVAILABLE**：作废落点为新终态 `status='VOID'`；`USED`（已核销）/`FROZEN`（被在途订单冻结）/`RISK_FROZEN`/`EXPIRED` 一律 409 `COMMON_CONFLICT`——FROZEN 被订单锁着的券不许作废，防破坏在途单。守卫在行锁（`SELECT ... FOR UPDATE`）事务内与 CAS 写（`WHERE status='AVAILABLE'`）同事务，与下单冻结并发安全（锁序：作废先锁券行，冻结由 38 号订单内核持 guard 后锁券行，二者无交叉多锁）。
- **审计与台账**：23 号幂等契约，绑定本内核 `command_idempotency`（14 号）——同 ID 同参重放返回首次回执、异参 409 `IDEMPOTENCY_KEY_CONFLICT`；`coupon_ledger` 写 `VOID` 行（`request_id='CPN:VOID:{instanceId}'` uk，`from_status='AVAILABLE'`、`to_status='VOID'`，remark 记 `operator={operatorId};source={channel};reason={reason}`，分号/换行消毒防注入解析）。动作词 `VOID` 为 coupon_ledger 既有词表新增（Schema06 注记同步，PR 披露）。
- **不可逆、无补偿**：作废即终态，不存在返还/解冻/补偿路径（"作废后是否补偿"SSOT 未写，CCR-CPN-VOID-001 §2 登记最小实现=不补偿，待用户另裁）。
- **C 端即时不可见**：C 端三桶 AVAILABLE/USED/EXPIRED（10 号 §3.15.1 + 61 号 §7 D2）不含 VOID，列表与详情即时消失（详情 404 `COUPON_NOT_FOUND` 同形）——C 端读侧零改动。
- **既有链路零影响**：冻结 CAS（`WHERE status='AVAILABLE'`）天然拒绝 VOID 行；返券恢复只找 `status='USED'` 行；释放只走 FROZEN；过期判定只作用 AVAILABLE——VOID 行不参与任何自动化链路。
- **鉴权**：控制器快照校验 `coupon.instance.void` + ALL 范围（CITY/MERCHANT 403），内核在事务前经模块本地授权端口当前读重验（§2 既有纪律）。

## 5. 积分台账与系统设置

| 方法/路径 | 动作码 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|---|
| GET `/api/v1/admin/points/ledger` | points.ledger.read | page/pageSize/userId?/bizType?(SIGN_IN\|INVITE\|TASK\|ORDER_REWARD\|REFUND_CLAWBACK)/createdFrom?/createdTo? | 流水分页（`ledgerId/userId/bizType/delta/balanceAfter/orderId/createdAt`，`created_at DESC,id ASC`） | 400/401/403 |
| GET `/api/v1/admin/settings/runtime` | system.parameter.read | — | `{switches:[{key,enabled,phase}], updatedAt}` | 400/401/403 |

- 积分台账：PRD §6.5 积分流水五字段（用户/来源/数量/余额/时间）+ `orderId`（订单奖励/扣回关联）；`bizType` 为 `points_ledger` 既有词表（SSOT §10：只赚取/扣回，不消费）。只读，无任何调整/冻结/扣回写路由（PRD"运营调整需审批"在 V1 无审批语义载体 → §6 CCR）。
- 系统设置运行时视图：`switches` 为 21 号《部署开关组合清单》登记的 `pet.*` 装配开关系（服务端静态登记表 + `Environment` 实时取值），`phase` 为该开关清单归属域（如 `ADMIN`/`C`/`ORDER`/`REFUND`）。只读、无表、无新配置机制；不做任何运行时写配置（§6 CCR）。PRD 系统设置的账号/角色管理（矩阵行 26）与消息公告（行 24）不在本面（§6 CCR）；MFA 已由 SSOT §25 取消，无面。

## 6. CCR 登记清单（本切片不做，待用户裁决）

登记文件：`planning/ccr/CCR-ADM-A005-OPS-001.md`。SSOT/已批裁决未授权的运营动作，本切片一律不实现：

1. **券实例写动作**（矩阵行 14 return 提案码不登记）：~~作废~~/批量作废/风控冻结/解冻/手动返还——SSOT §9 仅封板退款返券自动化规则，无运营直改用户券状态授权；PRD §7.3 运营分支（作废需通知用户、风控冻结）需通知域与风控事实契约，均不在仓。**【2026-10-16 更新】单张作废已按用户裁决"治理写动作最小授权"（36 号）放开为 §4.4 最小实现（不通知、无补偿，[CCR-CPN-VOID-001](../../planning/ccr/CCR-CPN-VOID-001.md) §2 登记待裁项）；批量作废/风控冻结/解冻/手动返还维持不做。**发放渠道（§4.3）不属于本项：其唯一写效果是新增 AVAILABLE 实例，不改任何既有实例状态。
2. **券活动/批次与发放渠道扩展项**：61 号 V1 边界"发放渠道 V1 不做"已被 2026-10-16 用户裁决部分放开为 §4.3 最小实现；批量发放/单用户同模板持有上限/发放频控/`total_stock` 库存扣减/目标用户存在性校验五项已按 2026-10-19 用户裁决"继续扩展CCR"以行业惯例最小实现落地为 §4.3（v0.4，[CCR-CPN-GRANT-001](../../planning/ccr/CCR-CPN-GRANT-001.md) §1 逐项登记采用规则）；发放通知维持不做（16 号消息目录无此类型）。券活动/批次（campaign 族）与导入路径维持不做。
3. **券模板"领取后 N 天"有效期模式**：需 Schema 新列与 61 号实例到期语义扩展。
4. **百科分类/品种新增与删除、导出**：~~分类枚举五值 PRD 不可自定义新增；品种新增需运营侧封面图上传通道（V1 私有素材通道仅售后证据）；导出不在 V1 已批准范围（63 号 v0.2 同口径）~~【**2026-10-19 用户裁决"继续扩展CCR"放开：分类/品种新增（同款守卫，字典枚举开放、born ENABLED/OFFLINE）与有界 CSV 导出（413 上限新码）已落地为 §3 v0.4；品种删除仍不做（无删除路由）；封面仍 URL 维护**】。
5. **资金台账专项面**：PRD §6.11.1 明示详见《资金管理 V1.4》（不在仓），字段级口径缺失；既有资金事实读侧由 63 号（退款执行事实）与订单金额投影覆盖。
6. **统一审计日志面**（HTTP10 §5.8 `GET /admin/audit-logs`）：审计行分散于各内核处置表（63 号 v0.2）与文件 sink（AUTH），统一存储/口径未决。
7. **后台账号/角色管理写面**（矩阵行 26 全族）与 **消息公告/通知模板面**（行 24）：AUTH/通知 owner 专项契约，V1 单运营种子账号，无事件契约。
8. **系统参数运行时写配置**（矩阵行 23 configure 码不登记）：V1 配置仅部署期 env（21 号清单），无运行时配置中心。

## 7. 验收

真实回环 HTTP（隔离 MySQL 33440/Redis 16379，env 同 CI 名）：百科分类/品种全状态读写回环、C 端读侧联动（停用分类消失/品种下线 404）、券模板全生命周期（建→编→启→停→编辑守卫）+ 试算资格矩阵、券实例台账与时间轴、积分台账过滤与排序、设置开关视图、未授权动作 403、CITY/MERCHANT 范围 403、幂等同参重放 200/异参 409、乐观锁 409、防枚举 404。**v0.2 增**：发放内核真实 MySQL 回环（真实 MySQL 隔离库：ACTIVE 在窗模板发放→AVAILABLE 实例+ISSUE 台账+issued_count 消耗、同参幂等重放回执/异参 409、DRAFT/OFFLINE/过窗/未生效/未知模板守卫 409/404、越权 fail-closed 403/503、C 端 AVAILABLE 桶立即可见且他人不可见——`CouponGrantMySqlTest`）。`check-persistence-style.py`、architecture-test、OpenAPI11 e2e 计数钉（184→205；**v0.3 起再增作废路由 205→206**）、前端 typecheck/build/playwright 全绿。**v0.3 增**：作废内核真实 MySQL 回环（隔离库：AVAILABLE 实例作废→VOID 行+VOID 台账（`CPN:VOID:{instanceId}`+operator/reason remark）、同参幂等重放回执/异参 409、USED/FROZEN/EXPIRED/未知实例守卫 409/404、越权 fail-closed 403/503、C 端三桶即时不可见且他人无感——`CouponVoidMySqlTest`）。**v0.4 增（百科节，本 PR）**：新增内核真实 MySQL 回环（分类新增 born ENABLED/重复 409/词法 400/新码可编辑；品种新增 born OFFLINE/重名 409/宿主停用 409；创建幂等同参重放；导出双段 CSV 内容+1MiB 有界+文件名钉——`AdminEncyclopediaMySqlTest` 扩展）；OpenAPI 计数钉本批 **+9**（+2 注销冷静期 +4 反馈运营面 +3 百科节；分支合并后全量钉以 e2e `test_contract_smoke` 为准）；运营台前端 sortOrder 请求键修正（既有缺陷随批修复，见 §8.10）。

## 8. 偏差与披露

1. 权限矩阵行 13/14 提案连字符拼写（`coupon-template.*`/`coupon-instance.*`）按 AUTH 词法落地为点分（`coupon.template.*`/`coupon.instance.*`）；行 21/23 提案拼写本就合规，原样登记。**v0.2**：`coupon.instance.grant` 在提案矩阵无提案码，按同一点分词法新造并登记部署（CCR-CPN-GRANT-001 §3），PR 披露。
2. 21 号清单增 `pet.admin.ops.http.enabled` 一行（默认 false，软依赖 `pet.auth.admin.enabled`）。**v0.2 再增 `pet.coupon.grant.enabled` 一行（默认 false，硬依赖 `pet.auth.admin.enabled`）；默认值翻转随 35 号开关放开裁决统一处理，本契约不自行翻转。**
3. 券模板停用改变新订单冻结资格（61 号前置④既有事实）与本面组合后的产品行为，PR 描述披露；已 FROZEN 实例的既有释放/核销链路不受影响（读的是实例状态非模板状态）。
4. 券实例金额快照取模板现值（61 号模型无实例级快照列）；v0.1 在无实例写动作的前提下无漂移面，**v0.2 起实例由发放渠道产生且面额/门槛展示仍读模板现值（61 号模型不冗余快照列）——发放后编辑模板被 `issued_count>0` 守卫阻止，Active 模板发放前后 rule 不变的窗口内无漂移，PR 披露**。
5. PRD §6.10"停用分类需过敏感词校验/品种联动校验影响条数"在本模型的落地偏差（枚举封闭/无外键引用）见 §3 披露。
6. 错误码复用 12 号既有通用码（400/401/403/404/409/500/503），**零新增错误码**，12 号不改（发放守卫复用 `COMMON_CONFLICT`/`COMMON_NOT_FOUND`/`COMMON_DEPENDENCY_UNAVAILABLE`）。
7. **v0.2**：61 号 §1"无券模板管理/发放渠道"边界的发放半边由 2026-10-16 用户裁决放开（最小实现 CCR-CPN-GRANT-001），61 号契约文档本身零改动（其冻结/消费/释放/返券四链路与 rule_json 冻结结构不受影响；发放产生的模板 ACTIVE 即满足冻结前置④的既有事实）；事件族零新增（发放为同步内核写，不经 Outbox/AsyncTask，与 63 号 v0.2"无事件 by design"先例同口径——PR 披露）。
8. **v0.3**：实例状态词表新增 `VOID` 终态、coupon_ledger 动作词表新增 `VOID`（Schema06 注记同步，纯词表注记无 DDL）；作废不通知用户（PRD §7.3"作废需通知"的通知域不在仓，CCR-CPN-VOID-001 §2 待裁）；作废无补偿（CCR-CPN-VOID-001 §2 最小实现=不补偿）；作废旧券不释放模板计数（`issued_count` 只增不减——发放事实历史，作废不改发放流水）；事件族零新增（作废为同步内核写，同披露 7 口径）。
9. **v0.3**：`reason` 为必填审计字段（10..200 码点，merchant.disable 10..500 口径的券域收紧版——ledger remark 500 上限约束），落 `coupon_ledger.remark`，不做独立处置审计表（与 63 号 merchant_governance_action 表不同形，PR 披露）。
10. **v0.4（百科节，2026-10-19"继续扩展CCR"）**：①分类/品种新增为同步内核写（无事件 by design 同 §2 口径）；新增分类/品种 born ENABLED/OFFLINE、uk 防重（重复 409）、无删除路由；②导出为只读字典数据（无用户/隐私字段），413 `ENCYCLOPEDIA_EXPORT_TOO_LARGE` 为 12 号新登记码（本切片此前零新增码口径就此打破，PR 披露）；③**既有缺陷随批修复**：运营台控制器 PUT 分类/品种与新增路由原读请求键 `sortNo`，而契约/运营台前端发 `sortOrder`（真实请求会 400）——实现已对齐 `sortOrder`，分类行响应投影键同步修正；④OpenAPI 字典 categoryCode 枚举钉（五值）开放为词法 pattern（`^[A-Z][A-Z0-9_]{1,15}$`），C 端 62 号读侧过滤事实零改动。
