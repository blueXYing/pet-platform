# SCH-004 准备阶段 — 排期写入方范围核验（S-V）

日期：2026-09-24。分支 `codex/sch002-prep-20260924`（基于 develop `0c2d7ae`，其上已有 SCH-002 准备 docs 提交 `eb8dbe5`，本切片不改动 SCH-002 文档）。角色：排期写入方后端（Backend Core，SCH-004 规范阶段）。
本切片为**准备/规范阶段文档**：不实现代码、不修改任何权威文档（00-ssot/01-prd/02-architecture/03-database/04-api/05-events）、backend/ 代码与共享台账（含 WAVE_2_TEST_ACCEPTANCE.md / READY_QUEUE / WORK_STATE / ISSUE_CATALOG / CCR-W2-API-001 既有文件）；获人工批准后由 Owner 同步权威契约再派发实现。

配套文档：CCR 草案 [schedule-write-proposal.md（DRAFT v0.1，SCHW-D1～D10，**未获批不得实现**）](../../../ccr/CCR-W2-API-001/schedule-write-proposal.md)、[TEST-PLAN.md](TEST-PLAN.md)（W2-SCHW-*）。
前置：SCH-001 已批合入（PR#77，[提案 v0.2](../../../ccr/CCR-W2-API-001/schedule-availability-proposal.md)、[决定回执](../../../ccr/CCR-W2-API-001/schedule-availability-decisions.md)，SCH-D4/D7 关键）；SCH-002 草案同分支（[schedule-capacity-proposal.md v0.1](../../../ccr/CCR-W2-API-001/schedule-capacity-proposal.md)，**SCH2-D1 排班口径未裁，本切片按两口径兼容设计**）；服务写入方先例（[service-write-proposal.md v0.2 已批](../../../ccr/CCR-W2-API-001/service-write-proposal.md)、33 号、V27/V28 迁移）。

引用约定：PRD 为 docx，引用按章节号（提取自 word/document.xml 纯文本，行号为提取文本行号，仅供复核定位）；仓库内文档与代码引用为 文件:行号。

---

## 0. 范围登记核验（本切片从哪里来）

| 登记点 | 原文 | 位置 |
|---|---|---|
| SCH-D7 裁决（归属定稿） | 「后端写入方=SCH-004 承接（§4.8 四路由命令+§4.9 人员时间+window_kind 增补+临时停业拦截+排期负责人 RBAC+运营只读监管路由）；商家排期管理页=M-002 承接。真实"开窗→查询→预约"验收前必须交付写入方」 | schedule-availability-proposal.md:38 |
| SCH-D4 裁决 | 「上门/送回分别开窗须在写入方交付时解决 window_kind（06 号增补届时走 CCR），不得把同一组窗口宣称为已完成双时段排期」 | schedule-availability-proposal.md:30 |
| SCH-001 承接登记表 | 排期后端写入方（§4.8/§4.9 命令+window_kind+临时停业拦截+排期负责人 RBAC+运营只读监管）→ SCH-004；商家排期管理页 → M-002 | schedule-availability-proposal.md:157 |
| SCH-001 风险预告 | 「SCH-D4 若 SCH-004 裁决分 kind，本查询响应可能增列（向后兼容，届时 CCR）」 | schedule-availability-proposal.md:168 |
| SCH-D3 披露 | 窗口表无任何写路径，真实"商家开窗→消费者查到→预约"链路在写入方交付前保留未完成 | schedule-availability-proposal.md:29 |
| Issue Catalog | M-002「商家登录/首页/店铺/服务/员工/排期」P0（页面消费方） | planning/ISSUE_CATALOG.csv:52 |
| **台账缺口（如实报告）** | ISSUE_CATALOG.csv **无 SCH-004 行**；SCH-D7 裁决原文「批准时 Owner 登记 READY_QUEUE」——该登记属人工 Owner 动作，至今未落；依赖图亦无 SCH-004 节点 | planning/ISSUE_CATALOG.csv（全量核验）；planning/DEPENDENCY_GRAPH.md:21-26 |
| 运营端 PRD 边界 | 「不代商家维护排期」（§3.3）；「只读监管，不得代商家修改排期」（§6.2.5） | /tmp/ops.txt:68、:1021（提取文本） |

**结论**：SCH-004 的承接内容已被 SCH-D4/D7 书面锁定（窗口+人员时间+window_kind+临时停业+排期负责人 RBAC+运营只读监管），但"员工六接口/能力写入是否属于本切片"未在裁决文字中显式区分（SCH-D7 只写了"§4.9 人员时间"）——范围拆分是本核验的第一个决策点（SCHW-D1，见 S-B）。

---

## S-A. 排期域写入对象盘点（六项，逐项核验写入方/契约/Schema 现状）

| # | 写入对象 | Schema 载体（06 号） | HTTP 契约现状 | 内部契约现状 | 实现现状 | 归属判定 |
|---|---|---|---|---|---|---|
| W1a | 服务时段窗口（含容量/启停/window_kind） | `schedule_availability_window`（06号:165-180：merchant/store/service/start_at/end_at/configured_capacity/status OPEN-CLOSED/version；**无 window_kind 列**） | 10 号 §4.8 四路由壳（GET/POST/PUT/DELETE，10号:1019-1047）+请求示例+禁周模板字段；**无语义/校验/错误/幂等/鉴权定义**；DELETE 语义未定 | 无（pet-schedule-api 仅读侧 §6.1.1） | 无控制器（pet-boot 全量检索无 availability-windows 路由） | **SCH-004 核心**（SCH-D7 明列） |
| W1b | 临时停业（门店级拦截） | **无独立载体**——SCH-001 核验已定调：「临时停业/休息日等价于不开窗或把窗口置 CLOSED；拦截已占用订单是写入方校验」 | §4.8 未单列路由（PRD §5.4 核心功能「支持设置营业时间、休息日、临时停业和手动开放或关闭时段」，提取文本 553） | 无 | 无 | **SCH-004**（SCH-D7 明列「临时停业拦截」） |
| W2 | 人员排班（人员可约时间） | `staff_availability_window`（06号:182-195：store/staff/start_at/end_at/status AVAILABLE-CLOSED/version） | 10 号 §4.9 四路由壳（10号:1051-1057）；另挂 staff-assignment（见 W6） | 无（SCH-002 草案仅读组合） | 无 | **SCH-004**（SCH-D7 明列「§4.9 人员时间」） |
| W3 | 人员能力（可服务该服务） | `staff_service_capability`（06号:149-158：staff/service/status ENABLED，uk(staff_id,service_id)；**无 version 列**） | **无任何路由**（10 号 §4.9 不含能力维护） | 无 | 无 | **建议纳入 SCH-004**（SCH-002 草案已把能力表消费划给 SCH 域并提供读组合，写入方缺口登记「SCH-004/员工扩展」，SCH-002-prep/SCOPE-VERIFY.md:69；粒度偏差已登记给写入方裁决，schedule-capacity-proposal.md:55） |
| W4 | 员工档案（在职/在岗/姓名/手机） | `merchant_staff`（06号:102-116） | **27 号 §6.1 六接口已批**（GET 列表/单查/POST/PUT/enable/disable，27号:99-115；disable 另有 IMPLEMENTATION_BLOCKED 前置，27号:115） | 27 号 §7 已批命令名 merchant.staff.create/update/enable/disable | **未实现**（pet-boot 无 `/merchant/staff` 控制器，adapter/web/merchant/ 全量核验） | **不属 SCH-004**——MER 域（表/路由/命令/主账号门禁全在商家域；SCH-004 建议模块为 pet-schedule-*，纳入即跨模块写 MER 表，违反 AGENTS 架构禁令） |
| W5 | 运营只读监管路由 | 消费 W1a/W2 + `schedule_reservation`（锁定/占用/利用率） | 10 号 §5 无排期监管路由（属补缺） | 无 | 无 | SCH-D7 归 SCH-004；**本轮是否交付=决策点 SCHW-D8**（监管维度依赖 SCH-003 占用生产者，见 S-C.5） |
| W6 | 订单人员指派 | `schedule_reservation`/订单域 | 10 号 §4.9 挂 `POST /merchant/orders/{orderId}/staff-assignment`（10号:1064-1069） | 07 号 §6.2 `assignStaff`（**归 SCH-003 命令族**，07号:448-462 交付状态注记） | 无 | **不属 SCH-004**——指派/改派属订单与预约命令域（27号:27「order 拥有订单快照、指派与存量责任」；07号 §6.2 随 SCH-003 交付） |

补充核验（读侧承接）：

- **商家端读**（M-002 日历消费）：10 号 §4.8 GET / §4.9 GET 两路由同样只有壳；SCH-D10 已裁「商家端日历的细分状态归 SCH-004」（schedule-availability-proposal.md:41）——工作台窗口/排班列表读（含 CLOSED 窗口、version、细分状态）随写入方一并交付，否则 M-002 无事实源。
- **C 端读侧 kind 影响**：SCH-001 已按"窗口不分 kind"交付；window_kind 增补后 C 端查询是否需要 kind 过滤/呈现=决策点（SCHW-D2 附则，SCH-001 风险预告的"向后兼容增列"落地口径）。

## S-B. 范围拆分建议（W1 核验任务；决策点 SCHW-D1）

**推荐拆分**（详见提案 SCHW-D1）：

| 切片 | 内容 | 依赖 |
|---|---|---|
| **SCH-004（本提案）** | ① 服务时段窗口写入（新增/编辑/启停/临时停业批量关窗）+window_kind 增补；② 人员排班写入（§4.9 四路由语义化）；③ 人员能力写入（新增最小路由族）；④ 商家端工作台读（§4.8/§4.9 GET 语义化，细分状态）；⑤ C 端读侧 kind 向后兼容增列 | 窗口写入：仅依赖既有 admission 门禁+service 域归属校验，可独立交付；排班/能力写入：事实前提=merchant_staff 行存在（MER 员工写入或测试 SQL 播种） |
| **MER 员工六接口实现切片**（建议新立/并入 MER-001 S3 续段） | 27 号 §6.1 已批六接口纯实现（create/update/enable；disable 维持 IMPLEMENTATION_BLOCKED，解除需 order 域在途指派查询+并发协调契约） | 契约已批零 CCR，纯实现；被 M-002 员工页与 SCH-002 员工事实的真实数据路径共同依赖 |
| **M-002（页面）** | 商家排期管理页+员工页（消费 SCH-004+MER 员工接口） | SCH-004+员工写入方交付后真实联调 |
| **运营只读监管（建议延后，SCHW-D8）** | 门店排期监管读 API+页面 | SCH-003（占用/锁定/超时释放记录、利用率分子）交付后才有监管实效 |

**依赖顺序建议**：MER 员工六接口与 SCH-004 **可并行**（窗口写入互不依赖）；真实"开窗→查询→预约"E2E 的完整事实链=员工录入（MER）+能力/排班录入（SCH-004）+开窗（SCH-004）+查询（SCH-001/002）+hold/下单（SCH-003/TX-001）——员工写入方未交付前，SCH-004 的排班/能力集成测试按 SCH-D3 披露模式以 SQL 播种员工行（不冒充真实链路）。

**不纳入的理由（对照禁止令）**：

- 员工六接口入 SCH-004 = 跨模块写 `merchant_staff`（pet-schedule-* 写 MER 表），违反「不跨模块访问 Repository」与模块责任边界（27号:27）；且扩大 Issue Scope。
- 指派/改派（W6）= SCH-003/订单域既有归属，07 号 §6.2 一字不动。
- 监管路由（W5）若本轮强做，其"已锁定/超时释放记录/排期利用率"维度在 SCH-003 前无生产者——只读空转，建议延后（SCHW-D8）。

## S-C. 权威文档与代码缺口核验

### S-C.1 Schema（06 号）

- `schedule_availability_window` 无 `window_kind` 列（06号:165-180，SCH-D4 已登记缺口）——增补 DDL 走 33 号先例（新文档 34 号+隔离迁移，见提案 §5）。
- `schedule_reservation` **无 window_id 列**（06号:197-221）——占用与窗口的关联是**时间区间重叠**（SCH-001 已按重叠计数消费）→「已有订单占用的时段不得关闭或降容量」（PRD §5.4，提取文本 560）的写入侧守卫按"TEMP_LOCKED/CONFIRMED 与该窗口重叠计数>0"实现，同域自有 Mapper，无跨模块问题。
- 无排期写入审计表（PRD §6 总则「任何状态变更必须可追溯操作人、操作时间与原因」，提取文本 1720——审计载体=决策点 SCHW-D9）。
- `staff_service_capability` 无 version 列→能力写入不可乐观锁，幂等仅靠 requestId（23 号）+整批替换语义（提案 SCHW-D1 附则）。

### S-C.2 HTTP10 §4.8/§4.9 列名与语义核对

- §4.8 请求示例字段=`serviceId/startAt/endAt/configuredCapacity`（10号:1030-1037）——与本提案命令字段一致（增 windowKind）；禁周模板三字段（dayOfWeek/repeatWeekly/copyNextWeek，10号:1041-1047）为 V1 禁用项，写入命令**不得引入**（对齐 SSOT §12.1「不做循环模板」）。
- §4.8/§4.9 均列 DELETE 路由但无语义；服务写入先例「不自动加删除」（SVCW-D9 硬删除未决）→窗口 DELETE 处置=决策点（SCHW-D3 附则，推荐不实现物理删除，以 close 承接）。
- §4.9 staff-assignment 属订单域（S-B/W6），本提案不动该路由。

### S-C.3 内部契约（07 号）

- pet-schedule-api `command` 包为空壳（仅 package-info.java）；§6.2 ScheduleCommandApi 五命令=预约生命周期（hold/confirm/release/swap/assignStaff），**交付状态注记明确随 SCH-003**（07号:414）——本提案新增**独立命令接口**（窗口/排班/能力写入），不触碰 §6.2 一字。
- 商家端工作台读需新增查询形状（§4.8 GET 的列表/详情 DTO）。

### S-C.4 OpenAPI/错误码（11/12 号）

- 11 号无任何 availability-windows/staff-windows 操作（grep 核验仅 `/c/services/{serviceId}/availability` 与 `/c/orders/{orderId}/reschedule`）——新操作以 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步收录（MER/SVC 先例）。
- 12 号 SCHEDULE_* 8 码（12号:62-69）按 SCH-D8「留 SCH-003 命令侧」——写入侧新增码=决策点 SCHW-D10（候选：重叠、状态守卫）。

### S-C.5 鉴权与门禁事实（27 号 §5；**与任务指示的一处事实修正**）

- 27 号 §5 批准的 `MerchantMembershipQueryApi.getFacts`（membershipKind/四态/authzVersion/获授动作）**未实现**——backend 全量检索无该接口（pet-merchant-api 现有 MerchantAdmissionQueryApi=listMemberships+getAdmission，MerchantAdmissionQueryApi.java:9-11）。
- 已实现且已批的所有者视角等价门禁=`MerchantAdmissionQueryApi.getAdmission(userId, merchantId, storeId)`：四态合取（application=APPROVED ∧ signing=SIGNED ∧ merchant=ACTIVE ∧ store=ACTIVE，含 owner 归属前件与失败关闭）——**SVCW-D5 先例即"getFacts 未落地前复用 getAdmission，落地后端口适配可切换"**（service-write-proposal.md:54）。
- 「排期负责人 RBAC」（SCH-D7 措辞）：PRD §5.6 角色权限矩阵=主账号有排期维护权、核销员无；V1 无 STAFF 成员绑定（27号 §5 保留、SVCW-D5 P3 同结论）→**V1 写入门禁=主账号（OWNER）**，「排期负责人」子账号角色细分随成员绑定交付，本轮仅登记不实现。
- 运营只读监管（若本轮做）=真实 admin 会话+动作码先例（SVCW-D7 / MerchantApplicationAdminController）。

### S-C.6 代码现状（PR#77 后）

- pet-schedule-biz：ScheduleReadStore（repeatable-read 只读快照）、ScheduleReadMapper（窗口+占用读）、AvailabilityQueryService（min+失败关闭）、QualifiedStaffFactsPort（SCH-002 占位）——**写侧零文件**（无 WriteMapper/命令服务）；`infrastructure/provider` 包预留（package-info）。
- pet-boot：CScheduleController（C 端读，`pet.schedule.query.enabled` 门控）；`CBearerSessionFilter.protectedPath` 前缀 `/api/v1/merchant/` 已强制商家会话（MerchantServiceController 同链先例）；商家写命令装配先例=`pet.service.command.enabled`（MerchantServiceController.java:54）。
- Flyway 隔离迁移先例：service-migration/V27、V28（33号:5-6 注记）→排期写侧建议 `schedule-migration/V29__schedule_write.sql`（编号实现阶段核定）。

## S-D. 决策点汇总（SCHW-D1～D10，详见 CCR 草案；每条含推荐+备选）

| # | 问题 | 推荐 | 影响面 |
|---|---|---|---|
| SCHW-D1 | 范围拆分：员工六接口/能力写入是否入本切片 | 窗口+排班+能力三表写入入 SCH-004；员工六接口归 MER 域独立实现切片（可并行）；指派归 SCH-003 | 切片边界、MER/SCH 并行计划、M-002 依赖顺序 |
| SCHW-D2 | window_kind 词表与默认+按履约方式约束+读侧增列口径 | `PICKUP/RETURN/GENERAL`，NOT NULL DEFAULT 'GENERAL'（存量回填）；IN_STORE 仅 GENERAL、PICKUP_DELIVERY 仅 PICKUP/RETURN（写入校验 400）；C 端查询增可选 kind 过滤+items 增可选 kind 字段（无参行为不变） | 34 号 DDL、10 号 §3.4 增补、C-003/M-002 消费 |
| SCHW-D3 | 窗口命令族形状与 DELETE 处置；重叠窗口策略 | CRUD-lite：POST/PUT+close/open 子路由；DELETE 不实现（软关闭承接）；同 store+service+kind 的 OPEN 窗口不得重叠→409 新码 | 10 号 §4.8 细化、12 号、M-002 交互 |
| SCHW-D4 | 已占用守卫口径（不得关闭/降容量/改时段） | TEMP_LOCKED/CONFIRMED 与窗口重叠计数>0 → close/降容量/改时段一律 409；升容量/开窗放行 | PRD §5.4:560/571 落地、与 SCH-003 边界 |
| SCHW-D5 | 临时停业粒度（门店级 vs 时段级） | 门店级=批量关窗命令（范围内 OPEN 窗口逐窗 CAS 置 CLOSED；被占用窗口进 blocked 清单报告）；不加门店标志列（避免跨模块改 merchant_store） | 新路由、PRD §5.4:553/571 |
| SCHW-D6 | 排班写入命令与状态机、员工门禁 | staff 窗口 CRUD-lite+close/open（AVAILABLE/CLOSED，对齐 PRD §6.2 但不含 10 分钟锁定——SCH-003）；写入前提=员工在职（INACTIVE 409）；排班写入无占用守卫（人员变化→容量动态重算，PRD §6.2 末行） | 10 号 §4.9 细化、SCH2-D1 两口径数据源 |
| SCHW-D7 | 能力写入形状与粒度 | 最小路由族：GET+PUT 批量替换 serviceIds（表粒度=服务项级；PRD"类目"措辞沿用 SCH2-D3 已登记偏差，类目→服务项展开不在后端造规则，由 M-002 页面分组呈现）；无 version 列→幂等仅 requestId | 新路由族、SCH-002 计数链路 |
| SCHW-D8 | 运营只读监管路由是否本轮交付 | 延后（监管实效维度依赖 SCH-003 占用生产者与利用率语义；A-002 排期页未排期）；SCH-D7 归属登记不推翻，建议 SCH-004 后续切片或并入 ADM | SCH-D7 兑现时点 |
| SCHW-D9 | 写入审计载体 | 新增 append-only `schedule_write_action` 表（动作/操作人/时间/目标/requestId；PRD §6 总则可追溯要求）；备选=仅依赖 command_idempotency（不满足"原因"可追溯） | 34 号 DDL |
| SCHW-D10 | 错误码增补 | 新增 `SCHEDULE_WINDOW_OVERLAP`(409)、`SCHEDULE_WINDOW_STATE_NOT_ALLOWED`(409)；准入门禁失败复用 `SERVICE_STATE_NOT_ALLOWED`（工作台"不可经营"同语义先例）；其余全沿用 | 12 号 §4 增补 |

---

## 9. 待人工裁决问题清单（汇总）

见上表 S-D（SCHW-D1～D10）——完整推荐理由、备选与影响面在 [schedule-write-proposal.md](../../../ccr/CCR-W2-API-001/schedule-write-proposal.md) §"待裁决决定"。另三项**登记不立项**：①「排期负责人」子账号角色细分（随 AUTH/MER 成员绑定，V1 写入=主账号）；②PRD §5.4"商家端排期不得开放违反 120 分钟约束的组合"（:563）按 SCH-D4 已裁口径解释为页面引导+C 端置灰+服务端最终校验（SCH-003），写入侧不做组合级校验；③PRD §5.6"与门店可预约时段联动"（:833）解释为容量公式联动（min 公式），写入侧不做排班⊆门店窗口的硬校验。

## 10. 本轮未做 / 禁止事项（如实声明）

1. 未修改任何权威文档（00-ssot/01-prd/02-architecture/03-database/04-api/05-events）、backend/ 代码、共享台账（ISSUE_CATALOG/READY_QUEUE/WAVE_2_TEST_ACCEPTANCE/WORK_STATE）与 SCH-002 文档——仅新增本目录文件与 CCR 草案目录内新文件。
2. 未实现任何代码（窗口/排班/能力写入、34 号 DDL、V29 迁移均待 CCR 批准后另派实现切片）。
3. 未提前实现员工六接口（MER 域归属）、指派/hold/swap（SCH-003）、运营监管路由（SCHW-D8 延后建议）、M-002 页面。
4. SCHW-D1～D10 仅为建议，不代产品/人工裁决；已批裁决（SCH-D1～D11、SVCW 全部、27 号 §5/§6.1）原样遵守，不推翻。
5. 测试本轮不跑（准备阶段）；测试计划见 TEST-PLAN.md，执行待实现切片。
6. 与指示不符的事实已如实报告：①`getFacts` 已批未实现，本轮门禁按 SVCW-D5 先例推荐 `getAdmission`（S-C.5）；②ISSUE_CATALOG 无 SCH-004 行（§0 台账缺口），登记归人工 Owner。
