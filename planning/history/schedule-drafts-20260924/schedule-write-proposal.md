# CCR-W2-API-001 排期写入方契约提案（schedule-write）

> **⚠️ DRAFT v0.1（2026-09-24）——未获批，不得据此实现。**
> 本文档为 SCH-004 准备阶段草案（角色：排期写入方后端）。所有 SCHW-D1～D10 均为**建议+备选**，须经人工 Contract Owner 裁决回执（对齐 [schedule-availability-decisions.md](schedule-availability-decisions.md) 先例）后方可同步权威文档并派发实现。与已批 SCH-D1～D11、SVCW-*、27 号裁决冲突之处一律以已批裁决为准。

规范版本：0.1（DRAFT），日期：2026-09-24。提出方/唯一编辑者：Backend Core（SCH-004 规范阶段）。
关联 Issue：SCH-004 / EPIC-05 / ST-SCH-01（**ISSUE_CATALOG 现无 SCH-004 行，批准时由 Owner 补登**）；上游：SCH-001（PR#77）、SVC-001、MER-001；下游：M-002（排期/员工页）、C-003（预约页）、SCH-003/TX-001（占用消费方）、A-002（监管页，延后）。
基线：develop `0c2d7ae`；分支 `codex/sch002-prep-20260924`。
配套核验：[SCOPE-VERIFY.md](../../issues/wave-2/SCH-004-prep/SCOPE-VERIFY.md)（S-A～S-D 事实盘点）、[TEST-PLAN.md](../../issues/wave-2/SCH-004-prep/TEST-PLAN.md)（W2-SCHW-*）。

## 修订记录

| 版本 | 变更 |
|---|---|
| 0.1（2026-09-24） | 初稿，SCHW-D1～D10：范围拆分（员工六接口归 MER）、window_kind 词表/默认/读侧增列、窗口 CRUD-lite+DELETE 处置+重叠策略、已占用守卫、临时停业批量关窗、排班命令与门禁、能力批量替换、监管路由延后、审计表、错误码增补 |

## 人工 CTO 一页阅读指南

**用通俗话说：现在数据库里的"可预约时段"和"员工排班"只能靠测试脚本手工插行，商家在小程序里没法开窗、没法排班——所以"商家开窗→消费者查到→预约"整条链走不通，这也是 SCH-001 裁决里反复强调的缺口。这个提案要把"商家管排期"的写接口字段、规则、错误写成书面约定。要裁的事情有四件：一是范围——员工档案的六个接口已批但归商家域，建议另派实现，本切片只管时段窗口、排班、能力三张排期域的表；二是上门接送型服务要分"上门窗"和"送回窗"（数据库要补一列），词表建议 PICKUP/RETURN/GENERAL，到店型只能开通用窗；三是"已有订单占用的时段不得关闭或降容量"这条产品规则怎么拦（按临时锁定+已确认的重叠计数拦截）；四是"临时停业"做成一键批量关窗（门店级，不动商家域的门店表）。另外运营的只读监管接口建议延后——它要看"已锁定/超时释放/利用率"，这些数据要到 SCH-003 才有生产者，现在做是空转。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐操作字段/校验/错误/鉴权映射、状态机与乐观锁规则、window_kind Schema 增补草案、重叠与占用守卫规则、审计与测试映射、决策点+推荐+备选 |
| 你负责 | 裁决 SCHW-D1～D10（重点 D1 范围、D2 window_kind、D5 临时停业、D8 监管延后）；批准后 Owner 补登 ISSUE_CATALOG/READY_QUEUE、同步 06/07/10/11/12/34 号再派实现 |
| 不变 | SCH-D1～D11 已批读侧语义（尤其 D6 失败关闭框架、D4"不得宣称双时段排期已完成"）；SSOT §12 分钟粒度/无循环模板/min 公式/120 分钟约束；不实现 hold/swap/指派（SCH-003）、员工六接口（MER 域）、订单/支付/改期 |

## 待裁决决定（SCHW-D1～D10）

1. **SCHW-D1 范围拆分（本提案第一决策）**：**推荐**本切片=①服务时段窗口写入（新增/编辑/启停+临时停业批量关窗）+window_kind；②人员排班写入（staff_availability_window）；③人员能力写入（staff_service_capability，最小路由族）；④商家端工作台读（§4.8/§4.9 GET 语义化，含细分状态/version——SCH-D10 已裁归本域）；⑤C 端读侧 kind 向后兼容增列（SCHW-D2 附则）。**员工六接口（27 号 §6.1 已批）不纳入**——merchant_staff 属 MER 域，纳入即 pet-schedule-* 跨模块写商家表（违反 AGENTS 架构禁令与 27号:27 责任边界），且扩大 Issue Scope；建议 Owner 另派 MER 域实现切片（契约已批零 CCR，可与本切片并行；disable 维持 IMPLEMENTATION_BLOCKED，27号:115）。订单人员指派（10号:1064 的 staff-assignment / 07号 §6.2 assignStaff）归 SCH-003/订单域，本提案不动。
   - 理由：(a) 三张表均在 SCH 域（06 号块 1/2），读组合已由 SCH-001/002 划定归排期域；(b) M-002 排期页与 SCH-002 容量链路共同需要排班/能力有真实写入方，否则 available=true 永远只能靠 SQL 播种；(c) 员工写入与窗口写入零耦合，并行不互相阻塞。
   - 备选 A：员工六接口并入本切片——跨模块违规，不推荐（除非人工明示放宽模块边界，需另立 ACR）。备选 B：能力写入延后（仅窗口+排班）——SCH-002 计数链路缺能力写入方，available=true 的真实路径继续缺一环，不推荐。
2. **SCHW-D2 window_kind 词表、默认值与读侧影响（SCH-D4 承接）**：
   - **推荐词表 `PICKUP / RETURN / GENERAL`**：与既有权威词汇对齐——07 号 §6.2 ReservationHoldCommand 字段即 `pickupStart/returnStart`（07号:476-477）、12 号 `SCHEDULE_PICKUP_RETURN_INTERVAL_INVALID`（12号:67）、schedule_reservation 列 `pickup_start_at/return_start_at`（06号:206-207）；「上门候选/送回候选」为中文语义。`GENERAL`=通用窗（到店型服务唯一合法类型；亦兼容存量行为）。
   - **存储**：`schedule_availability_window` 增列 `window_kind VARCHAR(16) ascii_bin NOT NULL DEFAULT 'GENERAL'` + CHECK（IN ('PICKUP','RETURN','GENERAL')）；存量行回填 GENERAL（SCH-001 阶段窗口本就同集呈现，回填不改变任何已批读侧行为）。DDL 见 §5（34 号新文档+隔离迁移，33 号先例）。
   - **写入校验（应用层，按服务履约方式）**：到店型（IN_STORE）服务仅允许 GENERAL；上门接送型（PICKUP_DELIVERY）仅允许 PICKUP/RETURN；违规 400 `COMMON_INVALID_ARGUMENT`（details 指明 windowKind 与 fulfillmentType）。履约方式事实经 pet-service-api 读取（服务归属+履约方式+storeId 一致性一并校验，见 §3 门禁）。存量 GENERAL 行不迁移不改写——校验只约束新写入。
   - **读侧影响（对 SCH-001 已批契约，确认性结论）**：C 端 `GET /c/services/{serviceId}/availability` **既有字段与语义零变化**；本切片做两点向后兼容增补（即 SCH-001 §6 风险预告的"届时 CCR"）：①Query 增**可选** `kind` 参数（PICKUP|RETURN，缺省=不过滤，行为与今日完全一致）；②items 每窗增**可选** `kind` 字段。无 kind 参数时 GENERAL-only 商家与 C 端消费方无任何感知。备选：读侧零改动（kind 仅约束写入）——则 M-002/C-003 无法分别渲染上门/送回候选列表，window_kind 投资无读侧出口，不推荐；备选：kind 过滤命中含 GENERAL（PICKUP 过滤同时返回 GENERAL 窗）——重新引入混用，与"分别开窗"裁决意图相悖，不推荐。
   - **120 分钟约束**：维持 SCH-D4 已裁口径——查询侧不做（C 端置灰）、**写入侧同样不做组合级校验**（PRD §5.4"商家端排期不得开放违反该约束的组合"提取文本 :563 解释为页面引导义务+服务端预约时最终校验归 SCH-003 hold/订单侧；组合级校验需对 PICKUP×RETURN 全组合判定，且关窗/改窗的任意组合变化都可能导致"无合法组合"，写入侧拦截既不可行也无产品先例）。登记解释，不实现。
3. **SCHW-D3 窗口命令族形状与重叠策略**：
   - **推荐 CRUD-lite（对齐服务写入先例：不自动加删除）**：`POST`（新增，201）、`PUT`（全量替换编辑：时间/kind/容量）、`POST .../{windowId}/close`、`POST .../{windowId}/open`（启停子路由，对齐 §4.10 online/offline 先例；expectedVersion 放 body）。**DELETE 不实现**：物理删除与 SVCW-D9 硬删除同属未决（占用历史追溯需要行存在；PRD §6.2 状态机无"已删除"态，终态即已关闭）；10 号 §4.8 的 DELETE 路由壳以勘误节登记"由 close 承接，V1 不注册该路由"。备选：DELETE=语义关窗（幂等 close 别名）——多一条同义路由徒增歧义，不推荐。
   - **重叠策略（推荐拒绝）**：同一 `(store_id, service_id, window_kind)` 的 **OPEN** 窗口之间时间区间不得重叠（半开区间相交即违规）→ 409 新码 `SCHEDULE_WINDOW_OVERLAP`（SCHW-D10）。CLOSED 窗口不参与判定（重开时再校验）；不同 kind、不同 service 互不影响。理由：C 端 items 按窗口逐行呈现，重叠窗口会产生重复候选时段与重复占用计数口径歧义；06 号无唯一约束，应用层在写入事务内以 `SELECT ... FOR UPDATE` 区间检查实现。
   - 备选：允许重叠（容量叠加语义）——展示歧义+占用计数双计，需连读侧一起改，不推荐。附则：PUT 改时段与"重叠检查+占用守卫"同事务执行；跨天窗口（寄养/过夜）合法，窗口时长无上限（31 天是查询跨度不是窗口长度，登记）。
4. **SCHW-D4 已占用守卫（PRD §5.4:560/571 落地）**：**推荐**——写入命令执行时同事务读 `schedule_reservation`（SCH 域自有 Mapper）：与该窗口 `(store_id, service_id)` 时间重叠且 `status ∈ {TEMP_LOCKED, CONFIRMED}` 的计数>0 时，**close、configured_capacity 下调、时间范围变更（无论收窄/平移/扩大）一律 409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED`**；上调容量、CLOSED→OPEN 放行（PRD §6.2"商家开放时段"条件"实际容量>0"是展示公式非写入前置）。RELEASED/EXPIRED 不构成占用（与 SCH-001 occupiedCount 同口径）。
   - 理由：PRD 明文「已有订单占用的时段不得关闭或降容量」（:560）、「临时停业或关闭时段时若存在已占用订单，系统拦截并提示先处理订单」（:571）；时间变更一并拦截是"不得变相腾挪"的保守解释（改时段可能把在途预约挤出窗口覆盖，预约无 window_id 关联、仅按重叠计数，挤出后权威复核在 SCH-003，但写入侧能拦则拦）。
   - 备选：仅拦 close/降容、放行时间变更（PRD 字面最小集）——留"改时段甩掉占用"的绕行口，需依赖 SCH-003 兜底，不推荐；若人工选此备选，TEST-PLAN 对应反例断言随之调整。
5. **SCHW-D5 临时停业粒度（门店级 vs 时段级）**：**推荐门店级=批量关窗命令**：`POST /api/v1/merchant/stores/{storeId}/availability-windows/batch-close`，body `{merchantId, fromDate, toDate}`（平台业务时区日历日，语义=关闭与 `[fromDate 00:00, toDate+1 00:00)` 相交的**全部服务的全部 OPEN 窗口**）。执行=逐窗 CAS 置 CLOSED；**被占用窗口不拦截整单**，进响应 `blockedWindows[]`（含占用计数），成功者进 `closedWindows[]`——与 PRD"拦截并提示先处理订单"的单窗语义一致（单窗 close 仍 409），批量场景以明细报告承接。不做时间裁切（部分相交整窗关闭，v1 简化，登记）。幂等：requestId 重放返回原回执。**不加门店级停业标志列**——临时停业事实载体=窗口 CLOSED（SCH-001 核验既定结论：无独立载体，等价于不开窗/关窗）；门店标志列在 merchant_store（MER 域）属跨模块，且会引入"标志与窗口状态双事实源"漂移。
   - 备选 A：门店停业标志列（merchant_store 加列或新表）+读侧过滤——跨模块+双事实源，不推荐。备选 B：不做批量命令（商家逐窗关闭）——PRD §5.4 明列"临时停业"功能（:553），逐窗关闭在多服务门店不可操作，不推荐。附则：解除停业=逐窗 open 或按需另裁批量 open（本轮不给，登记）。
6. **SCHW-D6 排班（staff_availability_window）命令、状态机与门禁**：**推荐**与窗口同款 CRUD-lite：`POST`/`PUT`/`close`/`open`（DELETE 同 SCHW-D3 不实现）；状态机 `AVAILABLE ↔ CLOSED`（06号:188 既有两值；PRD §6.2 排期槽位状态机的"可约/临时锁定/已占用/已完成"是**预约槽位**生命周期=reservation 域，归 SCH-003——排班条目不承载锁定，**本切片不含 10 分钟锁定/超时释放**）。门禁：①会话主账号+admission（SCHW 门禁见 §3）；②员工事实经 pet-merchant-api `getStaff`（所有者前提三归属一致，27号:60）确认存在且 `employment_status=ACTIVE`（INACTIVE → 409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED` 复用，或 404 防枚举视归属判定先后，见 §2 错误表）；③同 staff 的 AVAILABLE 窗口不得重叠（409 `SCHEDULE_WINDOW_OVERLAP` 同码复用——目标列不同，code 语义"排期窗口重叠"通用）。**排班写入无占用守卫**：预约不关联排班行，人员变化→容量动态重算是 PRD §6.2 明文（「可用人员变化→可约/已占满，实际容量按 min 动态计算」），关闭排班不拦。窗口 kind 不适用于排班（人员可约时间与履约方向无关）。员工六接口未交付前，集成测试员工行 SQL 播种（SCH-D3 披露模式，如实登记不冒充真实链路）。
7. **SCHW-D7 能力（staff_service_capability）写入形状与粒度**：**推荐最小路由族**：`GET /api/v1/merchant/staff/{staffId}/service-capabilities`（query merchantId、storeId）+ `PUT`（body `{merchantId, storeId, serviceIds:[...]}`，**全量替换**语义：差集停用/新增 ENABLED 行，uk(staff_id,service_id) 保证幂等落库；X-Request-Id 幂等）。粒度=**服务项级（表粒度）**：PRD §5.6"可服务类目"（类目枚举数组，:869-874）与表粒度的偏差已由 SCH2-D3 登记给写入方——**推荐后端不造"类目→服务项"展开规则**（涉及字典归属与门店服务范围语义，无产品依据），M-002 页面按类目分组呈现、提交服务项清单；PRD"类目"措辞登记解释偏差（类目=页面上服务项的分组视图）。校验：serviceIds 去重、每个服务须存在且归属同 store（经 pet-service-api；非本店/不存在 → 400 details 指明）、上限建议 ≤200 项/人（防误操作全量勾选，宽松值仅护栏）。无 version 列→不乐观锁，last-write-wins+requestId 幂等（23 号框架内）。门禁同 SCHW-D6（主账号+getStaff ACTIVE）。
   - 备选：逐条 enable/disable 路由（对齐员工 enable/disable 风格）——M-002 表单是勾选集交互，全量替换一次成型，逐条多次请求易留中间态，不推荐。备选：接受 categoryId 由后端展开——无展开规则依据，不推荐。
8. **SCHW-D8 运营只读监管路由：延后**。**推荐本轮不交付**（SCH-D7 的归属登记不推翻，兑现时点延后）：监管维度=「可约/已锁定/已占用/已关闭分布、排期利用率、临时锁定超时释放记录」（运营端 §6.2.5，提取文本 :1021-1025）——已锁定/超时释放/利用率分子全部依赖 `schedule_reservation` 生产者（SCH-003 hold/confirm/release 与超时任务）未交付，本轮只能空转；A-002 排期监管页未排期。建议：SCH-003 交付后以 SCH-004 后续切片（或并入 ADM 排期监管切片）走新 CCR，路由形态预留 `GET /api/v1/admin/merchants/{merchantId}/stores/{storeId}/availability-windows`（真实 admin 会话+动作码 `schedule.supervision.read`，SVCW-D7 先例）。
   - 备选：本轮交付最小只读（仅窗口分布，无锁定/利用率）——运营在 SCH-003 前看到的监管页无纠纷核查价值（§6.2.5 定位即"纠纷核查时查看占用与锁定记录"），不推荐；若人工倾向早给，范围限窗口列表只读。
9. **SCHW-D9 写入审计载体**：**推荐新增 append-only 审计表 `schedule_write_action`**（§5 DDL）：逐动作一行（target_type=SERVICE_WINDOW/STAFF_WINDOW/STAFF_CAPABILITY；action_type=CREATE/UPDATE/CLOSE/OPEN/BATCH_CLOSE/REPLACE；操作人会话 userId、request_id、trace_id、acted_at；UNIQUE(request_id,target_id)——批量关窗一命令多行共享 requestId）。理由：商家端 PRD §6 总则「任何状态变更必须可追溯操作人、操作时间与原因」（提取文本 :1720）是状态机章的普适要求；command_idempotency 表参数按保留策略可清理且无业务语义列，不能充当审计。本轮不强制"原因"字段（PRD 未给关窗/排期变更定义必填原因，不造规则；预留 `reason VARCHAR(200) NULL` 列，批量关窗建议前端传"临时停业"类说明，后端可空）。
   - 备选：不建表，依赖 command_idempotency+行 version/updated_at——不满足 §6 总则可追溯要求，不推荐。备选：reason 必填（关窗类）——PRD 无依据，不推荐（登记）。
10. **SCHW-D10 错误码**：**推荐新增两码（12 号 §4 增补）**：`SCHEDULE_WINDOW_OVERLAP`(409，同目标 OPEN/AVAILABLE 窗口时间重叠——窗口与排班通用)、`SCHEDULE_WINDOW_STATE_NOT_ALLOWED`(409，占用守卫拦截 close/降容/改时段；员工 INACTIVE 时排班/能力写入拒绝；命名对齐 `ORDER_STATE_NOT_ALLOWED`/`SERVICE_STATE_NOT_ALLOWED` 先例)。**准入门禁失败（商家/门店不可经营）复用 `SERVICE_STATE_NOT_ALLOWED`**——§4.10.1 已批工作台写命令同语义先例（「409（不可经营 SERVICE_STATE_NOT_ALLOWED）」，10号:1102），排期写命令同属商家工作台面，保持一致。其余全沿用：400 `COMMON_INVALID_ARGUMENT`（kind/时间/容量/参数词法）、401 `COMMON_UNAUTHORIZED`、404 `COMMON_NOT_FOUND`（窗口/员工/门店不存在或越权归属，防枚举同响应，27号 §3）、409 `COMMON_CONFLICT`（expectedVersion CAS 失配/争锁忙）、409 `IDEMPOTENCY_KEY_CONFLICT`（同 key 异参）、503 `COMMON_DEPENDENCY_UNAVAILABLE`（事实源故障）。
    - 备选：不新增，重叠/守卫统一 COMMON_CONFLICT——丢失域语义与前端可判别性（SVCW-D8 同理由先例），不推荐。

---

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| PRD 商家端 §5.4 | 排期管理全字段/规则证实（提取文本 542-660）：分钟自定义、无周模板、临时停业、占用守卫 :560/:571、120 分钟组合约束 :563（解释见 SCHW-D2）、字段表（上门时段/送回时段条件必填 :604-610、配置容量 ≥1 :612-614、锁定时长 10 分钟=预约侧） |
| PRD 商家端 §6.2 | 排期槽位状态机（:1799-1820）=预约槽位生命周期（锁定/占用/改期/退款释放），**归 SCH-003**；窗口侧仅取「可约↔已关闭」两态与"可用人员变化动态计算"行 |
| PRD 商家端 §5.6 | 排班管理「按人员×营业时段设置可约时间」:797、联动解释 :833、可服务类目 :869-874；员工档案字段（岗位/资质/擅长项）=27 号六接口之外扩展，本轮不做 |
| PRD 运营端 §3.3/§6.2.5 | 只读监管边界（:68/:1021）；监管维度依赖 SCH-003（SCHW-D8） |
| SSOT §12/§13 | 分钟粒度、无循环模板、min 公式、120 分钟、用户不选人——写入命令不引入周模板字段（10号:1041-1047 禁用项延续） |
| Schema 06 | 三表齐备（165-180/182-195/149-158）；缺 window_kind（SCH-D4 登记）；schedule_reservation 无 window_id（占用=重叠计数）；能力表无 version |
| HTTP10 §4.8/§4.9 | 路由壳+请求示例字段（serviceId/startAt/endAt/configuredCapacity）；无语义/校验/错误/幂等/鉴权；DELETE 未定；staff-assignment 归订单域不动 |
| 07 号 §6 | command 包空壳；§6.2 五命令随 SCH-003（:414 注记）——本提案新增独立命令接口不触碰 §6.2 |
| 12 号 | SCHEDULE_* 8 码留 SCH-003（SCH-D8）；本提案增两码（SCHW-D10） |
| 11 号 | 无任何排期写入操作（grep 核验）——新操作 ACCEPTED_CONTRACT_NOT_IMPLEMENTED 起步 |
| 23 号 | 写命令幂等全规则已批（RESERVED→业务事务→复核→同提交；首次 201/重放 200；同 key 异参 409）；command_idempotency 表已备 |
| 27 号 §3/§5/§7 | 商家写命令执行序/错误映射/版本 CAS 先例；getFacts 已批**未实现**——按 SVCW-D5 先例复用已实现 `getAdmission`（四态合取+owner 前件+失败关闭），getFacts 落地后适配可切换（scope-verify S-C.5） |
| 33 号+V27/V28 | 写入方切片结构范式（新 Schema 文档+隔离迁移+注释勘误）；本提案照此出 34 号+schedule-migration/V29（编号实现阶段核定） |
| 代码现状（PR#77 后） | pet-schedule-biz 写侧零文件（ScheduleReadStore/ReadMapper/AvailabilityQueryService 均读侧）；pet-boot 无 availability-windows/staff 路由；商家写装配先例 `pet.service.command.enabled` |

## 2. HTTP 操作契约（获批后同步 10 号 §4.8/§4.9 明细化+新路由族）

通用（27 号 §3/23 号先例）：统一 SUCCESS 信封；ID 十进制 String；时间带偏移 ISO-8601 分钟精度（秒必须 :00）；`Cache-Control: no-store`；写请求 X-Request-Id（UUID，缺失/畸形 400）；首次创建 201、更新/命令/幂等重放 200；MINIAPP Bearer 强制会话（`/api/v1/merchant/` 前缀既有过滤器）；expectedVersion 非负 Long String 放 body；装配开关 `pet.schedule.command.enabled` 默认关闭（对齐 `pet.service.command.enabled` 先例）。

### 2.1 服务时段窗口（§4.8 细化+启停/批量新增）

| 方法/路径 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|
| GET `/api/v1/merchant/stores/{storeId}/availability-windows` | query merchantId、serviceId?、kind?、status?(OPEN/CLOSED)、startDate、endDate（≤31 天）、page/pageSize | 分页 `{items:[{windowId,serviceId,windowKind,startAt,endAt,configuredCapacity,status,version,updatedAt}]}`（全状态含 CLOSED——工作台细分状态，SCH-D10） | 400/401/404（无归属防枚举） |
| POST `/api/v1/merchant/stores/{storeId}/availability-windows` | body merchantId、serviceId、windowKind、startAt、endAt、configuredCapacity；X-Request-Id | 201 `{windowId,status:"OPEN",version:0,...}` | 400（词法/kind×履约方式冲突/容量<1）、409 不可经营 SERVICE_STATE_NOT_ALLOWED、409 重叠 SCHEDULE_WINDOW_OVERLAP、409 异参 IDEMPOTENCY_KEY_CONFLICT |
| PUT `/api/v1/merchant/stores/{storeId}/availability-windows/{windowId}` | body 同上业务字段+expectedVersion；X-Request-Id | `{windowId,status,version,...}` | 404（非本店同响应）、409 占用守卫 SCHEDULE_WINDOW_STATE_NOT_ALLOWED、409 重叠、409 版本 COMMON_CONFLICT |
| POST `.../{windowId}/close` | body merchantId、expectedVersion；X-Request-Id | `{windowId,status:"CLOSED",version}` | 409 占用守卫（TEMP_LOCKED/CONFIRMED 重叠>0）、409 版本 |
| POST `.../{windowId}/open` | body merchantId、expectedVersion；X-Request-Id | `{windowId,status:"OPEN",version}` | 409 版本；（CLOSED→OPEN 无占用守卫） |
| POST `.../batch-close` | body merchantId、fromDate、toDate；X-Request-Id | `{closedWindows:[...], blockedWindows:[{windowId,occupiedCount}...]}` | 400（日期词法/跨度）、409 不可经营 |
| DELETE `.../{windowId}` | — | — | **V1 不注册**（10 号勘误节：由 close 承接） |

### 2.2 人员排班（§4.9 细化）

| 方法/路径 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|
| GET `/api/v1/merchant/staff/{staffId}/availability-windows` | query merchantId、storeId、startDate、endDate、page/pageSize | 分页（含 status/version 全状态） | 400/401/404（员工不存在或三归属不一致防枚举） |
| POST `/api/v1/merchant/staff/{staffId}/availability-windows` | body merchantId、storeId、startAt、endAt；X-Request-Id | 201 `{windowId,status:"AVAILABLE",version:0,...}` | 409 不可经营、409 员工 INACTIVE SCHEDULE_WINDOW_STATE_NOT_ALLOWED、409 重叠 |
| PUT `.../{windowId}` | body merchantId、storeId、startAt、endAt、expectedVersion；X-Request-Id | `{windowId,status,version}` | 404/409 重叠/409 版本 |
| POST `.../{windowId}/close` / `.../{windowId}/open` | body merchantId、storeId、expectedVersion；X-Request-Id | `{windowId,status,version}` | 404/409 版本（**无占用守卫**，SCHW-D6） |
| DELETE | — | — | V1 不注册（同 2.1） |

### 2.3 人员能力（新路由族，SCHW-D7）

| 方法/路径 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|
| GET `/api/v1/merchant/staff/{staffId}/service-capabilities` | query merchantId、storeId | `{staffId,serviceIds:[...]}`（ENABLED 集合） | 401/404 |
| PUT `/api/v1/merchant/staff/{staffId}/service-capabilities` | body merchantId、storeId、serviceIds（去重，≤200）；X-Request-Id | `{staffId,serviceIds,updatedAt}` | 400（serviceId 词法/非本店服务）、409 不可经营、409 员工 INACTIVE、409 异参重放 |

### 2.4 C 端读侧增列（SCHW-D2 附则；10 号 §3.4 维护性增补）

`GET /api/v1/c/services/{serviceId}/availability` 增可选 query `kind`（PICKUP|RETURN；缺省不过滤）+ items 增可选字段 `kind`；无 kind 参数时行为与 SCH-001 已批语义逐字段一致（GENERAL-only 商家零感知）。错误码/会话/跨度上限均不变。

## 3. 内部命令与 DTO（pet-schedule-api，获批后同步 07 号 §6 新小节）

```java
/** 排期写入方命令族（SCH-004；不触碰 §6.2 预约命令——其随 SCH-003 交付）。 */
public interface ScheduleWindowCommandApi {
    AvailabilityWindowResult createWindow(CreateAvailabilityWindowCommand c);   // merchant.schedule.window.create
    AvailabilityWindowResult updateWindow(UpdateAvailabilityWindowCommand c);   // merchant.schedule.window.update
    AvailabilityWindowResult closeWindow(CloseAvailabilityWindowCommand c);     // merchant.schedule.window.close
    AvailabilityWindowResult openWindow(OpenAvailabilityWindowCommand c);       // merchant.schedule.window.open
    BatchCloseWindowsResult batchCloseWindows(BatchCloseWindowsCommand c);      // merchant.schedule.window.batchClose
}

public interface StaffScheduleCommandApi {
    StaffWindowResult createStaffWindow(CreateStaffWindowCommand c);            // merchant.staff.schedule.create
    StaffWindowResult updateStaffWindow(UpdateStaffWindowCommand c);            // merchant.staff.schedule.update
    StaffWindowResult closeStaffWindow(CloseStaffWindowCommand c);              // merchant.staff.schedule.close
    StaffWindowResult openStaffWindow(OpenStaffWindowCommand c);                // merchant.staff.schedule.open
}

public interface StaffCapabilityCommandApi {
    StaffCapabilityResult replaceStaffCapabilities(ReplaceStaffCapabilitiesCommand c); // merchant.staff.capability.replace
}
```

命令形状（CommandContext 五字段，23 号/27 号先例；窗口命令带 merchantId/storeId/serviceId/windowKind/startAt/endAt/configuredCapacity，变更类带 windowId+expectedVersion；批量关窗带 fromDate/toDate；排班命令带 merchantId/storeId/staffId/windowId+expectedVersion；能力替换带 staffId+serviceIds）。

执行序（每命令，27 号 §7 先例）：会话 → **admission 门禁**（`MerchantAdmissionQueryApi.getAdmission`：无归属 404 防枚举、事实不可读 503、未批/未签/停用等明确不可经营 → 409 `SERVICE_STATE_NOT_ALLOWED`；getFacts 落地后适配切换，biz 不感知）→ **服务/员工事实**（窗口：pet-service-api 读服务归属+履约方式，storeId 不一致 404 防探测——SCH-D9 同语义；排班/能力：pet-merchant-api `getStaff` 三归属一致+ACTIVE）→ 词法/kind×履约方式/容量校验（400）→ 重叠检查（事务内锁定区间）→ 占用守卫（窗口类，SCHW-D4）→ expectedVersion CAS → 写表+审计行+最小回执，同一事务；幂等经 command_idempotency（独立短事务 RESERVED→业务事务复核，23 号）。

工作台读（§2.1/§2.2 GET）新增 `ScheduleWorkbenchQueryApi`（listWindows/getWindow/listStaffWindows；admission 门禁+全状态投影）。架构：biz 不依赖 biz、不跨模块 Repository（merchant/service 事实经 api 模块）；ARCH001~005 随 CI。

## 4. 状态机与守卫（实现规则）

```
服务窗口：OPEN ──close（无 TEMP_LOCKED/CONFIRMED 重叠占用）──▸ CLOSED
         CLOSED ──open ──▸ OPEN（无前置）
         编辑（PUT）：仅时间/kind/容量全量替换；占用>0 时降容/改时间 409（升容放行）
排班条目：AVAILABLE ──close──▸ CLOSED ──open──▸ AVAILABLE（无占用守卫）
能力集合：ENABLED 行集（全量替换；无状态机）
```

- 改期/取消/锁定/释放/指派**零触碰**（07 号 §6.2 归 SCH-003；订单域）——窗口编辑不修改任何 reservation 行，守卫是只读检查。
- 容量/可约呈现永远是读侧公式（min+占用），写入侧只维护配置事实——「可用人员变化→已占满」类动态迁移不在写入命令内发生。

## 5. Schema DDL 草案（权威同步待批后执行；34 号新文档+隔离迁移 V29，33 号先例）

```sql
-- 建议新文档：docs/03-database/34-Schedule-Write-Schema-v0.1.sql（评审产物，非自动迁移）
-- 已开通环境等价 Flyway 增量：backend/pet-boot/src/main/resources/db/schedule-migration/V29__schedule_write.sql（编号实现阶段核定）
ALTER TABLE schedule_availability_window
    ADD COLUMN window_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'GENERAL'
        COMMENT '窗口类型：PICKUP=上门候选 RETURN=送回候选 GENERAL=通用（到店型唯一合法；按服务履约方式应用层校验）'
        AFTER service_id,
    ADD CONSTRAINT chk_schedule_window_kind CHECK (window_kind IN ('PICKUP', 'RETURN', 'GENERAL'));

-- 写入审计（append-only；PRD 商家端 §6 总则可追溯要求，SCHW-D9）
CREATE TABLE schedule_write_action (
    id              BIGINT NOT NULL,
    target_type     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id       BIGINT NOT NULL,
    store_id        BIGINT NOT NULL,
    action_type     VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason          VARCHAR(200) NULL,
    acted_by_user_id BIGINT NOT NULL,
    acted_at        DATETIME(3) NOT NULL,
    request_id      VARBINARY(512) NOT NULL,
    trace_id        VARCHAR(128) NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sched_action_request_target (request_id, target_id),
    KEY idx_sched_action_target_time (target_type, target_id, acted_at),
    KEY idx_sched_action_store_time (store_id, acted_at),
    CONSTRAINT chk_sched_action_target CHECK (target_type IN ('SERVICE_WINDOW','STAFF_WINDOW','STAFF_CAPABILITY')),
    CONSTRAINT chk_sched_action_type CHECK (action_type IN
        ('CREATE','UPDATE','CLOSE','OPEN','BATCH_CLOSE','REPLACE')),
    CONSTRAINT chk_sched_action_ids CHECK (id > 0 AND target_id > 0 AND store_id > 0 AND acted_by_user_id > 0),
    CONSTRAINT chk_sched_action_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_sched_action_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='排期写入审计（append-only；批量关窗一命令多行共享 request_id）';
```

存量兼容：窗口存量行回填 GENERAL，读写行为与 SCH-001 已批语义一致（回填不改变任何已批断言）；无其他回填。**staff_availability_window / staff_service_capability 无 Schema 变更**（列已满足：status/version、uk(staff_id,service_id)）。

## 6. Schema / Event / OpenAPI 影响汇总（随实现 PR 权威同步，获批后执行）

| 文件 | 变更 | Writer |
|---|---|---|
| 06 号 | schedule_availability_window 注释增 window_kind 指向 34 号（值域权威在 34 号+应用层，33 号同款分工） | 本切片 |
| 34 号（新） | window_kind ALTER+CHECK+回填注记+schedule_write_action 表 | 本切片 |
| backend schedule-migration/V29 | 上述增量（隔离目录先例） | 本切片 |
| 07 号 §6 | 新增 §6.4 写入命令族三接口+工作台读形状+门禁/执行序；§6.2 一字不动（注记维持"随 SCH-003"） | 本切片 |
| 10 号 §4.8/§4.9 | 全语义细化（会话/校验/错误/幂等）+close/open/batch-close 新路由+§2.3 能力路由族+DELETE 勘误节；§3.4 kind 增补说明 | 本切片 |
| 11 号 | 新操作 ACCEPTED_CONTRACT_NOT_IMPLEMENTED 起步（merchantListAvailabilityWindows/merchantCreateAvailabilityWindow/merchantUpdateAvailabilityWindow/merchantCloseAvailabilityWindow/merchantOpenAvailabilityWindow/merchantBatchCloseAvailabilityWindows/merchantListStaffAvailabilityWindows/merchantCreateStaffAvailabilityWindow/merchantUpdateStaffAvailabilityWindow/merchantCloseStaffAvailabilityWindow/merchantOpenStaffAvailabilityWindow/merchantListStaffServiceCapabilities/merchantReplaceStaffServiceCapabilities）+cGetServiceAvailability kind 参数/字段维护性增补 | 本切片 |
| 12 号 §4 | 新增 SCHEDULE_WINDOW_OVERLAP / SCHEDULE_WINDOW_STATE_NOT_ALLOWED | 本切片 |
| Event08 | **无变化**（写入方不发事件：排期变更无既定下游事件需求；SCH-003/订单域事件不在本轮） | — |
| pet-boot 共享文件 | CSessionSecurityConfiguration/MerchantHttpExceptionHandler 行级追加（排期写路由登记+SCHEDULE_* 两码映射）；CBearerSessionFilter 零改动 | 本切片 |

## 7. 与 SCH2-D1 两口径的兼容说明（W4）

SCH-002 草案 SCH2-D1（排班是否纳入"可用"）**未裁**，本提案对两口径均兼容，不依赖其结论：

- **口径①（排班纳入，覆盖语义）**：SCH-004 交付后，`staff_availability_window` 首次具备真实写入方——商家排班→AVAILABLE 窗口并集覆盖→`qualifiedAvailableStaffCount` 变化→可约性变化，全链真实数据路径打通；写入契约（AVAILABLE/CLOSED、分钟区间、无 kind）与端口 javadoc「available over the whole [from,to) range」的并集覆盖消费语义完全咬合（多段班=多行 AVAILABLE，写入侧无需感知覆盖判定）。
- **口径②（排班不消费）**：写入的排班行对公式惰性（提供器不读），写入契约一字不变；已写行在 SCH-002 复裁回①时即生效，无需返工。
- **结论**：SCH-004 不需要 SCH2-D1 先裁，也不迫使复裁；反之 SCH2-D1 无论裁①②，对本提案零影响（差异全部在读侧提供器，SCH-002 测试计划 W2-SCH2-004 已预置两口径写法）。文件级协调点登记：SCH-002 若先实现将向 `ScheduleReadMapper` 增读方法（能力/排班读），本切片新增 `ScheduleWriteMapper`（独立文件）+复用 ReadMapper 只读——无同文件冲突；两切片实现顺序不分先后。
- window_kind 与 SCH2-D1 正交（kind 属服务窗口维度，人员可用性属排班维度）。

## 8. 测试映射（TEST-PLAN v0.1；编号 W2-SCHW-001～013）

| 验收 | 本提案对应 |
|---|---|
| W2-SCHW-001 窗口命令全路径 | §2.1/§4：create/edit/close/open+状态机+version CAS+DELETE 不注册 |
| W2-SCHW-002 window_kind 矩阵 | SCHW-D2：履约方式×kind 合法/非法、默认回填、C 端 kind 过滤/增列/无参不变 |
| W2-SCHW-003 重叠策略 | SCHW-D3：同 kind 重叠 409、不同 kind/CLOSED 放行、排班重叠 |
| W2-SCHW-004 占用守卫 | SCHW-D4：TEMP_LOCKED/CONFIRMED 拦 close/降容/改时段；RELEASED/EXPIRED/升容/open 放行 |
| W2-SCHW-005 临时停业批量关窗 | SCHW-D5：范围命中、blockedWindows 报告、幂等重放 |
| W2-SCHW-006 排班写入与门禁 | SCHW-D6：CRUD/启停、INACTIVE 409、归属防枚举、无占用守卫 |
| W2-SCHW-007 能力替换 | SCHW-D7：全量替换差集、非本店服务 400、去重上限 |
| W2-SCHW-008 幂等与并发 | 23 号：同参重放回执、异参 409、争锁 409、CAS 冲突 |
| W2-SCHW-009 权限反例矩阵 | admission 门禁（401/404 防枚举/409 不可经营）+子账号理论反例登记 |
| W2-SCHW-010 商家端工作台读 | §2.1/§2.2 GET：全状态/分页/过滤/权限 |
| W2-SCHW-011 C 端读侧回归 | SCH-001 W2-SCH-001～007 复跑不回退+kind 增列断言 |
| W2-SCHW-012 架构守卫 | biz 不依赖 biz、不跨模块 Repository、merchant/service 经 api |
| W2-SCHW-013 写入→查询模块链证据 | 开窗→C 端查询可见（提供器在场，员工/能力/排班经写入方或 SQL 播种）；E2E 边界披露 |
| ARCH001～005 | 随 CI |

## 9. 承接登记与风险

| 事项 | 承接 | 登记位置 |
|---|---|---|
| 员工六接口 HTTP 实现（27 号 §6.1 已批未实现；disable 的在途守卫维持 IMPLEMENTATION_BLOCKED） | **MER 域实现切片**（建议 Owner 另派，可与本切片并行；SCHW-D1） | 本提案+scope-verify S-B |
| 运营只读监管路由（窗口分布/利用率/锁定记录） | SCH-003 交付后的后续切片（SCHW-D8；SCH-D7 归属不推翻） | 本提案 |
| 批量 open（解除停业一键恢复）、临时停业时间裁切 | 未决（本轮不做，按需另裁） | SCHW-D5 附则 |
| 类目→服务项展开规则 | 不造规则（M-002 页面分组呈现；SCH2-D3 偏差登记延续） | SCHW-D7 |
| hold/swap/TEMP_LOCKED/占用生产者/指派 | SCH-003（07 号 §6.2）+订单域 | 既有归属，本提案零触碰 |
| ISSUE_CATALOG/READY_QUEUE 补登 SCH-004 行 | 人工 Owner（批准时） | scope-verify §0 |
| M-002 排期管理页/员工页 | M-002（消费本契约+MER 员工接口） | SCH-D7 |

风险与如实披露：

- **员工写入方未交付前**，排班/能力写入的集成测试员工行 SQL 播种（SCH-D3 披露模式）；「商家录入人员→排班→容量变化→可约」真实链路须 MER 员工切片+本切片+SCH-002 三者齐备，本切片单独不声明该 E2E 完成。
- 临时停业不做时间裁切（部分相交整窗关闭）——商家恢复营业需手工重开或依赖未来的批量 open，交互成本已登记（SCHW-D5 附则）。
- 占用守卫按时间重叠计数（reservation 无 window_id）——与 SCH-003 hold 的权威判定口径必须一致（同为重叠计数），跨切片漂移登记：若 SCH-003 未来引入窗口关联语义，守卫口径随之复核。
- C 端 kind 增列属已批契约的向后兼容修改——若人工选择"读侧零改动"（SCHW-D2 备选），M-002/C-003 的双时段候选渲染将无服务端过滤出口，前端只能全量拉取自行过滤（窗口量大时体验受限）。
- 写入方上线后生产首次出现真实 OPEN 窗口——SCH-002 未交付前 C 端查询仍按 D6 失败关闭（503），"开窗→查到"链路点亮时点受 SCH-002 交付节奏制约（既有披露延续，不因本切片改变）。
