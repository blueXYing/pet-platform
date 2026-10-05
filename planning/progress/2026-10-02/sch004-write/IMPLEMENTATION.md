# SCH-004 排期写侧实施记录

日期：2026-10-02。分支 `codex/sch004-write-20261002`（基于 origin/develop `e3b846e`，PR#98 合并点）。工作树 `C:\Users\Administrator\Desktop\wt-sch004-write`；主目录与其他在途 worktree（aftersale-pages/ntf-inbox/staff-identity/services-class-variants）未动。

## 接管背景（2026-10-02 三次接管，最终交付）

前两个代理先后失活（根因：C 盘 100% 满 + Maven 构建写 C 失败 + 僵尸 mvn 进程；僵尸已在接管前清理）。三次接管代理在**全程 D 盘隔离**（`-Dmaven.repo.local=D:\m2-sch004\repository`、`-Djava.io.tmpdir=D:\Temp\sch004-tmp`，长命令一律后台+轮询）下完成复核与交付：

1. 逐文件复审前代理全部改动（10 M + 24 untracked），**独立复核二次接管代理的关键声明**：①OpenAPI 11 号大 diff 纯增量（`sort -u` 全集比对，旧内容 0 行丢失）；②SQL52 与 V29 迁移主体自第 8 行起逐字节一致；③`requireCurrentVersion` 409 回归与迁移 bean try-with-resources 两处修复在代码中核验成立；④新增 12 项 MySQL 测试与 PRD29/CCR 逐条对应。结论不变：**无发明 PRD29/CCR 之外的产品规则**。
2. 环境复查：确认无残留构建进程（现存 java 为 xinzitong 别项目，未动）；发现本机 MySQL **再次积累 52 个泄漏 `qa_booking_*` 测试库**（约 360MB/6470 表，C 盘曾回满），已全部 DROP 回收（C 盘余量 1.2G→2.4G）。
3. 门禁全部以接管代理身份独立重跑（数字见下文"验收证据"补记），非沿用前代理记录；Docker daemon 本机未运行，测试按二次接管先例改用本机 MySQL 8.4.9（127.0.0.1:3306，root；测试逐例随机建库、用后 DROP），并通过 `PLAT004_/PLAT002_ID_/AUTH_/SCH004_/SCH003_/SCH002_/MER001__MYSQL_URL/USER/PASSWORD` 环境变量把 pet-task-core/pet-id-core 等依赖模块的测试（默认端口 33440/33442 本机不存在）同向指向。
4. 补齐 PR 交付链（前两代理均无 commit）：提交、推送、draft PR（见分支 `codex/sch004-write-20261002`）。

## 接管背景（2026-10-02 二次接管）

本 worktree 有一份前代理中断后遗留的**未提交**写侧实现（10 个已跟踪文件修改 + 24 个新增文件 + `.tmp-pr-body.md` 草稿 + 本文档初版，无任何 commit）。接管代理逐文件审查全部改动、对照 PRD29/CCR 依据逐条核对后**全量接手**（未删除任何遗留文件），并修复 2 处遗留缺陷、补 1 项回归测试后完成门禁与交付。审查结论：

**保留（前代理遗留改动经核验真实且与依据一致）**：

- 前代理自查修复的 9 项（异常 helper 返回未抛出、409 码未映射 HTTP、履约方式枚举比较恒假、BIGINT 列强转 CCE、员工窗重放恒 201、版本溢出拒绝、yml 仅 LF 追加 12 行、能力 GET 放宽 X-Request-Id、proveReductionSafe 补窗-商家一致性循环）在代码中逐条核验成立，全部保留。
- 实现与 PRD29 十条裁决逐条对得上：kind×履约方式 400 矩阵（不遍历组合）、占用窗禁关/降/移+升容放行、批量关窗部分成功+未关闭明示+跨天整窗、减员=ORDER 指派保护+全店可行性复核失败关闭、关闭/停业/减员 reason 必填+append-only 审计、能力集合过期 409 提示重读、明细无头 LEGACY_UNVERSIONED 503 隔离（SCHC-4 补记）、无 200 项上限、无物理删除、无禁周模板字段。**未发现发明 PRD29/CCR 之外的产品规则**；`MAX_CONFIGURED_CAPACITY=10^9` 仅是 06号 INT 列技术边界（Contract52 §7 已披露），非业务上限。
- 范围遵守分工：MER 员工六接口、C 端 kind 增列/过滤（34号 §1 须与 SCH-003 联合交付）、运营只读监管、M-002 页面均未越界实现。前端无改动：PRD29 明确「页面由M-002承接」，本切片不涉及 frontend-miniapp。
- OpenAPI 11号 6048/4173 行大 diff 经核验为**纯增量**：旧文件每一行（sort -u 全集比对）都仍在，删除行只是插入新路径块的 diff 错位；10号/12号为小步正式化。

**接管后新修复（本次）**：

1. **窗口/员工排班过期 expectedVersion 误报 503**——6 个命令（window/staff window 的 update/close/open）依赖 SQL CAS+`one(0)` 把过期版本落成 `COMMON_DEPENDENCY_UNAVAILABLE`，与能力路径（显式比较→409 `COMMON_CONFLICT`）及 ServiceCommandService.requireEditable 仓内先例不一致；客户端会被误导"稍后重试"而不是"重读后重提"。已在锁行读取后补 `requireCurrentVersion` 比较（409 COMMON_CONFLICT），并新增回归测试 `staleExpectedVersionIsAConflictNotADependencyFailure`（证明版本检查先于指派事实查询、行与审计不受污染）。
2. **`scheduleWriteMigration` bean 连接泄漏**——启用路径第一个 `source.getConnection()`（catalog 校验用）从不关闭；重构为单一 try-with-resources（默认关闭路径不受影响）。

## 产品依据（原文引用）

本切片是「排期写侧」实现切片，首要输入为 develop 上已批准的：

> [PRD29 排期人员容量与维护人工裁决补充](../../../../docs/01-prd/29-排期人员容量与维护人工裁决补充-v1.0.md)（= SSOT §29，docs/00-ssot/01-SSOT §1175-1189）：
> - 「可用人员须在职在岗、属于该店、具备具体服务能力，且排班无空档覆盖整个时段。无排班/能力的确定事实不计人；读取故障不能当0或默认可约。」
> - 「能力按具体服务项授权，类目只作分组展示，新服务不自动继承。」
> - 「上门/送回时段分别维护，到店为通用时段。页面引导合法组合，预约服务端最终强校验送回开始>=上门开始+120分钟；开窗接口不遍历组合校验。」
> - 「同店同服务同类型的开放窗不重叠，同员工有效排班不重叠，相邻可以衔接；关闭/重开保留历史，不物理删除。」
> - 「临时锁位/确认预约占用的时段不能关闭、降容量或改时间；升容量仍按权限等既有守卫执行。」
> - 「临时停业批量关窗允许部分关闭，未关闭时段必须明示，不能把部分成功当作全店停业。跨天相交整窗处理，恢复逐窗进行；本轮不是持续性门店停业开关。」
> - 「减少排班可用性或撤销服务能力必须保护已有预约容量及已指派订单；保护契约缺失时相关写入不得放行。不得沿用"人员变化仅动态重算所以无需守卫"的解释。」
> - 「关闭、临时停业及减少人员可用性的操作必填原因，并留操作人/时间/动作审计。能力集合编辑冲突必须提示重读，不静默覆盖。」
> - 「员工档案由MER原模块、排期后端由SCH-004、页面由M-002承接；运营只读监管延后至占用数据齐备后交付，不取消。」
> - 「本裁决不批准未提出的精确保护算法、200项人员能力上限、Schema/API细节、PR合并或生产发布。实现前按CCR完成关键保护及并发契约冻结。」

CCR 冻结依据（均已批准、随 develop 在库）：[联合审阅回执](../../../ccr/CCR-W2-API-001/schedule-review-decisions.md)（SCHW-D1~D10 业务方向）、[写入提案 v0.2](../../../ccr/CCR-W2-API-001/schedule-write-proposal.md)、[四项技术裁决回执](../../../ccr/CCR-W2-API-001/schedule-write-completion-decisions.md)（SCHC-1~4 已批）与 [G1-G3 契约补齐提案](../../../ccr/CCR-W2-API-001/schedule-write-completion-proposal.md)、权威契约 [34号 API](../../../../docs/04-api/34-Schedule-Protection-Contract-v0.1.md)/[34号存储](../../../../docs/03-database/34-Schedule-Protection-Storage-v0.1.md)、[36号 ROC-1~6](../../../../docs/04-api/36-Reservation-Order-Protection-Contract-v0.1.md)。**关键发现：develop 已合入预约保护基础（37号 schema、共同门店闸门 `ScheduleCapacityGuardApi`、`ScheduleProtectionFactsApi`、claim 表、ORDER `OrderProtectionFactsApi.getCurrentAssignments`、MER `MerchantCurrentStaffFactsApi`、`CapacityFeasibilitySolver`）**，因此 PRD29「保护契约缺失时相关写入不得放行」在本批可以按已批契约真实执行，而不是继续阻塞。

## 已实现表面（默认关闭）

- **服务时段窗口**：创建（kind×履约方式 400 矩阵：IN_STORE→GENERAL、PICKUP_DELIVERY→PICKUP/RETURN）、编辑（仅 OPEN，CAS 版本；占用时改时间/降容量 409、升容量放行）、close（reason 必填；TEMP_LOCKED/CONFIRMED 原窗 claim 占用 409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED`）、open（重开核验同店同服务同 kind 重叠 409 `SCHEDULE_WINDOW_OVERLAP`，相邻半开允许）、batch-close（Asia/Shanghai 日历日范围、closedWindows/blockedWindows 部分成功、基础设施故障整笔 503 回滚）；身份三字段创建后固定，无 DELETE/物理删除；禁周模板字段不引入。所有 CAS 命令过期版本 409 `COMMON_CONFLICT`。
- **员工排班**：创建/编辑/close/open；写入前提=员工属店且在职 ACTIVE（MER 当前事实，失败关闭 503）；同员工 AVAILABLE 不重叠。**减员保护（SCHW-D6/PRD29）**：先查 ORDER 当前指派（`protectRequired` → 409 先改派），通过后同事务应用变更，再以既有 `CapacityFeasibilitySolver.solveAll` 对全店活跃预约做整体可行性复核（不可行 409，预算/事实故障 503 整笔回滚）——即 34号 §2.3 的「已知冲突 409、依赖故障 503」两态。
- **员工能力（SCHC-2 全量落地）**：GET/PUT `/merchant/staff/{staffId}/service-capabilities`；`staff_capability_set` BIGINT 单调集合头（SQL52/V29 新表，HTTP 十进制 String 版本）；首空集合 GET version "0"，首 PUT expectedVersion "0" 原子建头；后续 CAS 全量替换、过期 409 COMMON_CONFLICT、重复 serviceIds 400、移除必填 reason、撤销走同款减员保护；明细有头无/有明细无头=LEGACY_UNVERSIONED 503 隔离；不做 200 项上限。
- **商家工作台读**：GET 窗口列表（含 CLOSED、version、updatedAt、kind/status 过滤）与排班列表——M-002 的事实源（SCH-D10 细分状态归 SCH-004）。
- **横切**：23号 幂等（`command_idempotency` request-key-v1、admit/execute 两段事务、同参重放先重验 OWNER 准入、异参 409、CommitUnknown 恢复）；OWNER 门禁 `ScheduleAdmissionGate`（getAdmission 四态合取、非 OWNER 404 防枚举）；每店闸门同顶层事务（SCHC-1）；append-only `schedule_write_action` 审计（目标/requestId/版本前后/reason，关闭与减员 reason 必填由应用+DB CHECK 双重约束）；MINIAPP Bearer 过滤器覆盖新路由；HTTP 状态映射 `SCHEDULE_WINDOW_OVERLAP`/`SCHEDULE_WINDOW_STATE_NOT_ALLOWED`→409。
- **契约/Schema 同步**：新增 [Contract52](../../../../docs/04-api/52-Schedule-Write-Contract-v0.1.md)、[SQL52](../../../../docs/03-database/52-Schedule-Write-Schema-v0.1.sql)+`schedule-migration/V29__schedule_write.sql`（两者逐字节一致；仅显式 `schw001_*` 隔离库可执行，默认 `migration-enabled=false`，不运行生产迁移、不回填）；10号 §4.8/§4.9 由草案壳改为正式实现摘要、12号 §4 收录两个新 409 码、11号 OpenAPI 增 10 个路径/13 个操作（旧内容逐行保留）。开关分层默认关闭：`pet.schedule.protection.enabled` / `pet.schedule.command.enabled` / `pet.schedule.command.http.enabled` / `migration-enabled` 全 false。

## 验收证据（2026-10-02 接管代理实测）

### 三次接管独立重跑（2026-10-02，D 盘隔离构建）

- `mvn -pl pet-schedule-api,pet-schedule-biz -am test`（JDK21 Temurin 21.0.11、maven.repo.local=D:\m2-sch004、java.io.tmpdir=D:\Temp\sch004-tmp、本机 MySQL 8.4.9@3306 环境变量注入）：**BUILD SUCCESS**——pet-common **80/80**、pet-task-core **47/47**（首轮因 PLAT004 默认端口 33440 不存在 46 错，环境变量注入后全绿，代码零改动）、pet-merchant-api **1/1**、pet-schedule-biz **39/39**（`ScheduleMerchantCommandMySqlTest` 12/12 + 既有预约保持/选择/Solver 套件）。与二次接管记录数字一致。
- `mvn -pl pet-boot -am -Dtest=ScheduleWriteDisabledTest -Dsurefire.failIfNoSpecifiedTests=false test`：**BUILD SUCCESS**，boot 全依赖链编译通过 + 默认关闭装配 1/1。**未跑 pet-boot 全量 66 测试类套件（以 CI 为准）**。
- `tools/check-persistence-style.py` PASS（41 模块）；`tools/check-module-deps.py` PASS（41 reactor 模块、17 biz 无 biz→biz 依赖）。
- 环境事故补记：本机 MySQL 再次积累 52 个泄漏 `qa_booking_*` 测试库（6470 表/约 360MB），已全部 DROP；容器化隔离 MySQL 因 Docker daemon 未运行改用本机 3306 实例（逐例随机建库、用后 DROP，未触碰任何既有业务库）。

### 二次接管实测（保留存档）

本地 MySQL 8.4（127.0.0.1:3306，root）真实执行；测试库由 06/14/37/38/52 号脚本逐例随机建库、用后 DROP。环境插曲：MySQL 数据盘（C:）曾被历史 QA 遗留的 122 个 `qa_booking_*` 测试库占满（磁盘 100%），已全部 DROP 回收后重跑；前代理时代 SCH-003 用的 33457/33450 等容器端口本机不存在，改以 `SCH003_/SCH002_/SCH_SELECTION_/AUTH_MYSQL_URL` 环境变量指向本机 3306 执行同一批测试。

- `pet-schedule-biz` 全模块 `mvn test`：**Tests run: 39, Failures: 0, Errors: 0, BUILD SUCCESS**——`ScheduleMerchantCommandMySqlTest` 12/12（幂等重放/异参冲突、重叠与相邻、占用窗 close/降容/改时拒绝+升容放行、reason 必填、**过期版本 409 回归**、批量部分关闭与重放原明细、INACTIVE 员工 409、指派保护 409、全店复核不可行 409+可行放行、能力首空集合/CAS/冲突/移除保护/LEGACY 503）；既有预约保持套件（ReservationHold 6、ReservationConfirm 1、ScheduleProtectionFoundation 5、ScheduleQualifiedStaffFacts 2、ScheduleSelectionQuery 4）与 `CapacityFeasibilitySolverTest` 9 全绿——含容量守卫与写侧一致性（W2-SCHW-001/003/004/005/006/007/008/012 核心行）。
- `pet-common` 全模块：**Tests run: 80, Failures: 0, BUILD SUCCESS**（含 S1ContractMappingTest 5/5，新 OpenAPI 引用全解析）。
- `pet-boot` 全模块：结果见 PR 验证小节（含新增 `ScheduleWriteDisabledTest` 默认关闭验证）。
- 架构门禁：`check-persistence-style.py` PASS（41 模块扫描）、`check-module-deps.py` PASS（17 biz 无 biz→biz 依赖）、`test_persistence_style.py`+`test_architecture_gates.py` 自测 13 项 OK。

## 未交付/边界（不冒认）

C 端 availability 的 `windowId/kind` 增列与 kind 过滤（34号 §1 明确须与 SCH-003 hold/swap 联合交付并同步 07/10/11）；M-002 页面（PRD29：页面由 M-002 承接，本切片无前端改动）；MER 员工六接口联动与 W2-SCHW-013 全链路；真实 MySQL 多连接并发演练（现有并发保护由每店闸门+锁行 CAS+同事务复核表达，未做多连接竞争压测）；生产迁移/开关。

## 阻塞与待裁决（未实现，只登记）

1. **B5 售罄自动/手动规则**：PRD29/SSOT §29 未定义容量耗尽后窗口自动置 CLOSED 或「售罄」手工语义；历史记录「B5 售罄宜在 SCH-002 前裁」。当前仅由读侧 min 公式与预约守卫表达耗尽。影响：商家无"一键售罄"语义、前端只能以读侧余量表达；建议选项：a) 新裁决引入窗口状态 SOLD_OUT（自动置入+预约释放联动，需 SCH-003 协同）；b) 维持现状（读侧表达），页面以"余量 0"呈现。
2. **C 端 kind 过滤与 windowId/kind 增列**：须与 SCH-003 双选窗 hold/swap 联合交付（34号 §1），本批未改 C 端契约。
3. **跨日/节假日**：仅已批批量关窗跨天整窗+逐窗恢复；持续性停业开关本轮明确不做，扩展需新裁决。
4. **存量 GENERAL 盘点（SCHC-4）**：上线前盘点/隔离/回填/回滚脚本未交付；V29 不回填旧行。影响：存量 `window_kind='GENERAL'` 的接送旧窗不能被当作双时段供给；建议在启用开关前交付盘点快照+隔离脚本。
5. **200 项人员能力上限**：未批准，不实现。
6. **「排期负责人」子账号角色**：随 AUTH/MER 成员绑定交付；V1 写入=主账号 OWNER。
7. **环境（非本 PR 范围）**：本机 MySQL 数据盘长期处于满盘边缘（C: 100%），历史 QA 套件存在测试库泄漏（本次已清理 122 个 `qa_booking_*`）；建议后续为集成测试套件统一加"失败也 DROP"的保底与定期清理任务。另有一个半删除的 `qa_booking_997f…` 僵尸目录（文件已失、元数据残留），MySQL 重启后自愈。
