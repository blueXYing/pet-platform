# SCH-002 准备阶段 — 可用人员事实盘点与范围核验（S-A～S-D）

日期：2026-09-24。分支 `codex/sch002-prep-20260924`（基于 develop `0c2d7ae`）。角色：排期域后端（Backend Core，SCH 规范阶段）。
本切片为**准备/规范阶段文档**：不实现代码、不修改任何权威文档（00-ssot/01-prd/02-architecture/03-database/04-api/05-events）、backend/ 代码与共享台账（含 WAVE_2_TEST_ACCEPTANCE.md / READY_QUEUE / WORK_STATE / CCR-W2-API-001 主索引）；获人工批准后由 Owner 同步权威契约再派发实现。

配套文档：CCR 草案 [schedule-capacity-proposal.md（DRAFT v0.1，SCH2-D1～D7，**未获批不得实现**）](../../../ccr/CCR-W2-API-001/schedule-capacity-proposal.md)、[TEST-PLAN.md](TEST-PLAN.md)。
前置：SCH-001 已批（PR#77 合入 develop；[提案 v0.2](../../../ccr/CCR-W2-API-001/schedule-availability-proposal.md)、[决定回执](../../../ccr/CCR-W2-API-001/schedule-availability-decisions.md)，SCH-D6 失败关闭为裁决修改版）。

引用约定：PRD 为 docx，引用按章节号（提取自 word/document.xml 纯文本，行号为提取文本行号，仅供复核定位）；仓库内文档与代码引用为 文件:行号。

---

## 0. 范围登记核验（本切片从哪里来）

| 登记点 | 原文 | 位置 |
|---|---|---|
| 依赖图 | `SVC-001 → SCH-001 → SCH-002 → SCH-003 → TX-001` | planning/DEPENDENCY_GRAPH.md:21 |
| Issue Catalog | SCH-002「effectiveCapacity=min(configured,availableStaff)」P0，依赖 SCH-001，AllowedModules `backend/pet-schedule-* backend/pet-merchant-api`，Tests `ORD-004,ORD-005,CON-001,CON-002`，BLOCKED（完整 Issue 门禁） | planning/ISSUE_CATALOG.csv:13 |
| Wave2 附加门禁 | C003 真实查询 → `MER+SVC+SCH001/002`；M002 真实范围 → `AUTH+MER+SVC+SCH及签约` | planning/DEPENDENCY_GRAPH.md:54 |
| READY_QUEUE | 「下一段依赖链：核对可预约事实与契约缺口→SCH-001→SCH-002→SCH-003→TX-001」 | planning/READY_QUEUE_WAVE_2.md:3 |
| SCH-001 已批预留 | `QualifiedStaffFactsPort` 为 SCH-002 预留端口（真实装配未注册，可见服务失败关闭 503）；「真实实现=商家域第六查询∩SCH 域能力/时间窗，随 SCH-002 另行走 CCR——本切片不实现、不定义其内部语义」 | schedule-availability-proposal.md §3（v0.2）；QualifiedStaffFactsPort.java:5-11 |
| SCH-001 前瞻登记 | S4.1：pet-merchant-api 无「按门店列在职可服务员工」内部查询（现有五查询均不覆盖），列 SCH-002 前置缺口 | SCH-001-prep/SCOPE-VERIFY.md:161 |
| D6 裁决预告 | 「面向用户启用完整可约判断须等 SCH-002 人员容量及后续占用事实接通」——SCH-002 即解除 503 的切片 | schedule-availability-decisions.md:23 |

**结论**：SCH-002 归属清晰——把 SSOT §12.2 公式的第二项（可用人员数）从"端口占位+失败关闭"升级为"真实事实源接通"，落点两块：`backend/pet-merchant-api`（第六内部查询，Issue Catalog 已明列该模块）+ `backend/pet-schedule-biz`（提供器真实装配）。不实现 SCH-003 hold/swap、不实现写入方（SCH-004/M-002 已裁决归属，见 D7）。

---

## S-A. "可用服务人员"事实定义核验（本切片核心）

SSOT §12.2（docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md:603-621）：`实际可预约容量 = min(商家配置容量, 当前可用服务人员数)`；§13（:625-629）：用户不选人、商家指派、容量受可用人员数限制。**SSOT 未定义"可用"的判定构成**——定义细节在商家端 PRD §5.6。逐层核对如下。

### S-A.1 人员事实五层盘点（PRD 概念 → Schema 载体 → 写入方 → 读侧现状）

| # | PRD 概念（商家端 §5.6，提取文本行号） | Schema 载体（06 号） | 写入方现状 | 读侧现状 |
|---|---|---|---|---|
| L1 在职 | 在职状态 在职/离职；「离职后不展示、不可派单」（845-850） | `merchant_staff.employment_status ACTIVE/INACTIVE`（06号:100，注释两值） | **无**：27 号 §6.1 六接口已批未实现（backend/pet-boot 无 `/merchant/staff` 控制器，全量检索确认；disable 另有 IMPLEMENTATION_BLOCKED，27号:115）；仅测试库播种（MySqlMerchantDomainTestDatabase） | `getStaff` 单查（所有者前提 merchantId+storeId+staffId+ownerUserId，MerchantQueryService.java:68-90）；无按门店列表/计数查询 |
| L2 在岗 | 在岗状态 在岗/休假/停排；「当前是否可接单；C 端仅展示在岗人员」（851-856） | `merchant_staff.service_enabled TINYINT(1)`（06号:101）——**PRD 三值被 Schema 折叠为布尔**（enable/disable 仅改此列，27号:111） | 同上 | 同上 |
| L3 门店归属 | 员工账号管理「设置可访问的门店范围」；人员×门店（§5.6 核心功能，796） | `merchant_staff.store_id`（每行一店，06号:99；idx_staff_store_status(store_id,employment_status)） | 同上 | selectOwnedStaff 以 join 校验归属（MerchantReadMapper.xml:80-101） |
| L4 可服务类目 | 可服务类目 枚举数组、平台统一服务字典、「影响预约可选人员」（869-874） | `staff_service_capability(staff_id,service_id,status ENABLED, uk(staff_id,service_id), idx(service_id,status))`（06号:149-158）——**粒度为服务项（service_id）而非 PRD 的类目（category）**，偏差登记（见 SCH2-D3） | **无**：员工六接口明确不含能力维护（27号:113「岗位/资质/擅长项扩展不是六接口已覆盖能力」）；写入归 SCH-004/后续员工扩展 | **无任何查询契约** |
| L5 排班（人员可约时间） | 排班管理「按人员 × 营业时段设置可约时间」（797）；「人员可约时间按具体日期和分钟级时段维护，**与门店可预约时段联动**；本期不提供循环模板」（808） | `staff_availability_window(staff_id,store_id,start_at,end_at,status AVAILABLE/CLOSED)`（06号:182-198） | **无**：10 号 §4.9 仅路由壳（10号:1051-1057）；**SCH-D7 已裁决归 SCH-004（后端）+M-002（页面）**，SCH-002 时点仍无写入方 | **无任何查询契约** |

补充事实（SCH 域消费侧）：`QualifiedStaffFactsPort.countQualifiedAvailableStaff(storeId, serviceId, from, to)`（QualifiedStaffFactsPort.java:20-21）已随 PR#77 合入；其 javadoc 已锁定语义「qualified for the service and **available over the whole [from, to) range**，实现不得虚构计数、事实源不可用必须 503」（:14-19）。`AvailabilityQueryService` 逐窗口调用该端口并取 `min(configuredCapacity, staffCount)`（AvailabilityQueryService.java:114-128），提供器缺席/异常/负数一律 503（:71-72, :119-124）。

### S-A.2 关键核对点：排班事实与"可用"的关系（决策点 SCH2-D1）

**事实**：L5 排班表无写入方（SCH-004/M-002 未交付），生产恒空；L4 能力表同样无写入方，生产恒空；L1-L3 员工表无 HTTP 写入方（六接口未实现），生产恒空。三者都空时任何口径下计数均为 0。

三个可选口径（详见 CCR 草案 SCH2-D1）：

1. **排班纳入（覆盖语义）**：可用 = L1∧L2∧L3∧L4∧（L5 AVAILABLE 窗口并集完全覆盖该窗口 [from,to)）。与已批端口 javadoc 的"whole range"语义一致；无写入方时计数=0——**读空权威表=事实**（D6 裁决对 occupiedCount 已确立同款先例：「读权威表的事实，不是占位假设」，schedule-availability-proposal.md:33）；结果向"不可约"方向失败，绝不虚增可约。生产在写入方交付前自然全 0（窗口表本身也无写入方，对外表现为空 items，三种口径生产行为一致，**差异是语义锁定与测试口径**）。
2. **简化（在职在岗即可用）**：可用 = L1∧L2∧L3∧L4，不消费 L5。缺排班事实时也计为可用——把"无排班事实"当"排班不设限"，属于向"可约"方向的乐观假设，与 D6「不得拿占位冒充已核实事实」的精神存在张力；且需修订已批端口 javadoc 的"whole range"措辞。
3. **排班缺失 503 失败关闭（D6 同精神的字面延伸）**：把"排班表无该店任何行"当"事实源缺失"报 503。与 occupiedCount=0 先例冲突（空权威表≠依赖不可用）；且所有店都无排班写入方，等于 SCH-002 交付后路由仍恒 503，与 D6「启用完整可约判断须等 SCH-002 接通」的预告相悖——**不推荐**。

**推荐口径 1**（理由与备选详见提案 SCH2-D1；时间维度：口径 1 下可用性随窗口变化——不同窗口 [from,to) 覆盖判定不同，与端口签名自然吻合；口径 2 下计数时不变）。

### S-A.3 员工"在职在岗"事实表现状核对（如实披露）

- `merchant_staff` 表结构完整（employment_status/service_enabled/store_id/version 齐备，27 号存储映射已批语义：**「service_enabled 不是子账号启停；staff 人数不是可约容量」**，docs/03-database/27-Merchant-Domain-Storage-v0.1.md:13——容量计算在 SCH 域，MER 域只供事实）。
- 27 号 §2 边界已批：「**schedule 拥有时间冲突和可用人数**」「MER 不查询订单表，也不从服务人员总人数推算可预约容量」（27号:27-28）——第六查询只供"门店在职在岗员工"明细事实，min 组合在 SCH 域。
- 员工六接口（GET 列表/单查/POST/PUT/enable/disable，27号 §6.1）已批**未实现**：SCH-002 的员工事实在实现切片测试中只能 SQL 播种（与窗口/预约同款 SCH-D3 披露）；不因此提前实现员工写入。
- 在岗三值（在岗/休假/停排）→ 布尔折叠已由 27 号 §6.1（enable/disable 语义）隐含裁决，本切片沿用，不再立项。

### S-A.4 汇总表

| # | 可用性事实 | 权威事实源 | 写入方 | 内部查询契约 | SCH-002 可用性 |
|---|---|---|---|---|---|
| F5a | 在职∧在岗∧归属门店 | merchant_staff（MER 域） | 缺失（六接口未实现） | **缺**（本切片新增第六查询，S-B） | 本切片定义+实现 |
| F5b | 可服务该服务 | staff_service_capability（SCH 域自有 Mapper，06号块1 但按 SCH-001 已批口径由 SCH 域消费） | 缺失（SCH-004/员工扩展） | 缺（SCH 域内自有 Mapper 读取，无跨模块问题） | 本切片实现（组合） |
| F5c | 排班在场（时间维） | staff_availability_window（SCH 域，06号块2） | 缺失（SCH-004+M-002 已裁决） | 缺（同上） | **决策点**（SCH2-D1：纳入覆盖语义 vs 延后） |
| F5 组合 | qualifiedAvailableStaffCount | 第六查询∩F5b(∩F5c) | — | QualifiedStaffFactsPort（已批形状） | 本切片真实装配 |

---

## S-B. 第六内部查询草案核对（27 号风格）

**现状（已核验）**：pet-merchant-api 五查询=①`getStore` ②`checkOrderEligibility` ③`getStaff`（以上 07 号 §4.1，所有者授权面，MerchantQueryApi.java）④`checkDisplayEligibility`（SVC-D5，§4.2，独立接口 MerchantDisplayEligibilityApi）⑤`pageDisplayStores`/`getDisplayStore`（STR-D6，§4.3，独立接口 MerchantStoreDisplayApi）。**均不覆盖"按门店列在职在岗员工"**——`getStaff` 是单查且带所有者前提（merchantId+storeId+staffId+ownerUserId 三归属一致+owner 校验），不能充当无所有者前提的容量事实源。

**草案形状（详见提案 §2）**：独立接口 `MerchantStoreStaffFactsApi.listActiveStoreStaffFacts(StoreStaffFactsQuery)` → `StoreStaffFactsDTO`：

- 入参 `storeId + QueryContext`（三字段不扩展，07 号 §2.2）；**serviceId 不入参**——能力表（F5b）归 SCH 域读取组合，商家域不读 SCH 域事实（27号:27 边界 + SCH-001 已批「商家域第六查询∩SCH 域能力/时间窗」措辞，proposal §3）。
- 返回**明细（staffId 列表）而非计数**——计数形无法在 SCH 域与能力/排班表求交（组合必须按 staffId 交集）。
- 过滤 `employment_status=ACTIVE ∧ service_enabled=1`（查询语义内固定）；**未知枚举值不静默排除**——按 STR-D6 先例（「无 status 谓词……策略对未知状态失败关闭而不是 SQL 过滤隐藏」，MerchantReadMapper.xml:107-109 注记；实现侧 requireKnown 先例 MerchantQueryService.java:131-143），未知 employment_status → 503。
- **无所有者前提**（对齐 SVC-D5/STR-D6 第四/第五查询先例）：仅供 SCH 域容量事实消费，不授予任何商家操作权限；QueryContext 仅承载链路信息，不构成 Principal。
- **错误两分（同既有，不得混同）**：确认门店不存在 → NOT_FOUND（调用方投影 404，与可见性先判一致性见提案 SCH2-D2 附则）；事实源故障/读取失败/状态未知 → DEPENDENCY_UNAVAILABLE（调用方 503）；**空列表=确认该门店在职在岗员工为 0（正常事实，非错误）**——对齐「确认开放城市无可见门店=正常空页」先例。
- 命名对齐先例：SVC-D5=第四查询、STR-D6=第五查询 → 本查询=**第六查询**，`MerchantStoreStaffFactsApi`（任务建议名，提案采用；备选并入 MerchantQueryApi 不推荐——所有者面与无所有者前提面不混）。
- 索引支撑：`idx_staff_store_status(store_id, employment_status)`（06号:113）现成，无需 Schema 变更。

---

## S-C. SCH-002 实现范围核验（获批后派发的切片内容）

1. **pet-merchant-api/biz**：第六查询接口+DTO+query record（api 模块）；真实现（biz 模块，自有 MerchantReadMapper 增一无所有者前提的门店员工行读取，读后校验+过滤）。
2. **pet-schedule-biz**：`infrastructure/provider/`（package-info 已预留）落地 `QualifiedStaffFactsPort` 真实适配器——第六查询结果 ∩ `staff_service_capability`（自有 Mapper，ENABLED∧service_id 匹配）[∩ `staff_availability_window`（若 SCH2-D1 裁决纳入）] → 计数；任何读失败/未知状态/负数 → 503（端口契约既定）。`AvailabilityQueryService` 的 min 公式**零改动**（PR#77 已实现 min+失败关闭，提供器接通即生效——`Math.min(configuredCapacity, staffCount)`，AvailabilityQueryService.java:128）。
3. **pet-boot**：`ScheduleQueryConfiguration`（ScheduleQueryConfiguration.java:22-30）从 `ObjectProvider.getIfAvailable()`（缺席→null→503）改为注入真实提供器 bean；开关 `pet.schedule.query.enabled` **维持默认关闭**。可见服务的 availability 由"恒 503（提供器缺席）"转为真实计算——生产当前窗口表亦无写入方，表现为 200 空 items；503 仅剩真实事实源故障。
4. **occupiedCount**：仍读 `schedule_reservation`（TEMP_LOCKED/CONFIRMED 重叠计数），无生产者恒 0——**维持 SCH-001 披露口径不变**（读权威表的事实；生产者 SCH-003/TX-001 未交付）。
5. **快照语义**：提供器在 `store.read`（SCH 域 repeatable-read 快照）内被逐窗口调用——能力/排班读可与窗口/占用共享 SCH 快照；第六查询在 MER 域开自己的快照。组合后为**三个连续快照**（service 资格 / SCH 窗口+占用+能力+排班 / MER 员工），漂移仍为展示级，权威复核归 SCH-003 hold（SCH-001 两快照注记的自然扩展，随实现 PR 同步 07 号注记）。
6. **不实现**：员工六接口 HTTP、能力/排班写入、hold/swap（SCH-003）、window_kind（SCH-004）、运营监管路由。

---

## S-D. 决策点汇总（SCH2-D1～D7，详见 CCR 草案；每条含推荐+备选）

| # | 问题 | 推荐 | 影响面 |
|---|---|---|---|
| SCH2-D1 | "可用服务人员"定义与排班关系：①排班纳入（覆盖语义，空表=0）②在职在岗即可用（排班延后）③排班缺失 503 | **①**：L1∧L2∧L3∧L4∧排班 AVAILABLE 并集覆盖 [from,to)；空表=0 为正常事实（occupiedCount=0 同先例）；③不推荐（与先例冲突且 SCH-002 无解锁实效） | 提供器语义、测试播种、SCH-004 到货后是否复裁 |
| SCH2-D2 | 第六查询归属与命名：独立 `MerchantStoreStaffFactsApi`（明细形）vs 并入 MerchantQueryApi vs 计数形 | 独立接口+明细（staffId 列表）；serviceId 不入参；无所有者前提；错误两分；空列表=0 | 27号 §4/07号 §4 增补、merchant-biz 实现 |
| SCH2-D3 | 能力表语义消费：粒度（表=服务项 vs PRD=类目）、ENABLED 判定、空表/悬挂行/未知状态 | 按表粒度（服务项级，更严）执行，粒度偏差登记给写入方；无行=不具备；悬挂行以员工表交集自然排除；未知 status→503；无写入方披露 | 提供器实现、测试反例、SCH-004 写入方裁决 |
| SCH2-D4 | 员工事实源故障失败关闭口径：提供器缺席/读失败/未知枚举/空表/门店不存在 | 缺席→503（既有分支保留）；读失败/未知枚举/负数→503；空表=0（事实）；门店不存在→404 投影（与可见性两分一致） | 提供器实现、W2-SCH2-005 |
| SCH2-D5 | min 公式启用条件与真实装配 | 提供器 bean 注册即启用（boot 组合第六查询）；开关默认关不变；SCH-001 "恒 503" 有意状态由本切片解除（D6 预告兑现）并披露 | boot 装配、上线说明、11号状态标签 |
| SCH2-D6 | 既有 SCH-001 测试更新口径 | W2-SCH-007 的"提供器缺席失败关闭"用例保留（null 构造仍有效）；聚合断言改走真实提供器+SQL 播种（替身仅保留给缺席构造）；W2-SCH-001~006/DisabledTest 不回退 | ScheduleAvailabilityHttpTest 修订 |
| SCH2-D7 | OpenAPI/权威文档影响 | 响应形状不变：10号 §3.4 无契约变更、12号无新增、06号无 Schema 变更、Event 无；07号 §4 增补第六查询+§6.1.1 注记更新；27号 §4 增补；11号 cGetServiceAvailability 状态标签/注释维护性更新（具体标签人工裁决） | 权威同步清单（随实现 PR） |

---

## 9. 待人工裁决问题清单（汇总）

见上表 S-D（SCH2-D1～D7）——每条的完整推荐理由、备选与影响面在 [schedule-capacity-proposal.md](../../../ccr/CCR-W2-API-001/schedule-capacity-proposal.md) §"待裁决决定"。另有两项**登记不立项**：在岗三值→布尔折叠（27号 §6.1 已隐含裁决，沿用）；PRD 可服务类目 vs 表粒度偏差（登记给写入方，本切片按表粒度）。

---

## 10. 本轮未做 / 禁止事项（如实声明）

1. 未修改任何权威文档（00-ssot/01-prd/02-architecture/03-database/04-api/05-events）、backend/ 代码、WAVE_2_TEST_ACCEPTANCE/READY_QUEUE*/WORK_STATE/CCR-W2-API-001 主索引——仅新增本目录与 CCR 草案目录内文件。
2. 未实现任何代码（第六查询/提供器/boot 装配均待 CCR 批准后另派实现切片）；未实现员工六接口 HTTP、能力/排班写入（无归属授权不抢做）。
3. 未提前实现 SCH-003 hold/swap/TEMP_LOCKED、SCH-004 写入方命令/M-002 页面、运营监管路由。
4. SCH2-D1～D7 仅为建议，不代产品/人工裁决；已批裁决（SCH-D1～D11、SCH-D4/D5/D7/D11 后续口径）原样遵守，不推翻。
5. 测试本轮不跑（准备阶段）；测试计划见 TEST-PLAN.md，执行待实现切片。
