# CCR-W2-IDEMP-001：公共约定与持久化幂等提案

> 2026-09-16 状态同步：PR10/11/13/16及USR的PR24已合入相关公共约定、组件与领域幂等落地；生产ID启用、完整公共幂等及各业务范围继续按原条款核验。CCR不整体RESOLVED。 下文保留提案/交付时点的记录，本段只更新当前阶段事实，不改技术约定。

## 当前S2组件阶段

**COMPONENT_REVIEW_CANDIDATE / PRODUCTION_ENABLEMENT_BLOCKED。** PR13及Hutool/适配包已经批准合入develop6a559bc，当前已交付最小可注入Hutool＋MySQL协调组件候选；不重复SDK/适配包审批。最新ab351ee基线上42项目/154 JUnit、13架构Python及80契约回归通过；代码/Schema候选、Owner、实际验证与生产限制见[组件一页交接](../../backend/pet-id-core/HANDOFF.md)，权威23号仅增加§10实施映射。新PR仍待人工合并。

本阶段root注册pet-id-core、6个main类与QA独占测试/3个CI环境变量、仅节点技术表SQL25；common/task-core/boot及默认Flyway目录没有改动。真实宿主退出验证默认拒绝，生产初始化/写谱系/恢复/告警和装配仍未完成；全Issue非DONE、CCR非RESOLVED，通用业务幂等表/旧key迁移未扩大进来。以下为已接受方案与原审批/规划历史，当前组件交付状态以上述HANDOFF为准。

## 当前批准回执：Hutool新增适配方案已接受

**当前状态：SDK_SELECTED / ADAPTER_ACCEPTED / PENDING_CONTRACT_SYNC。** 2026-09-14人工明确接受Hutool新增适配方案，批准对象严格为PR13提交 **e81c6bcaba360a192001167823c30f2afa24a92a** 中的节点协调、候选发布、1秒超时失效、确认旧JVM实际终止后恢复及一页指南初始参数包；由根任务01a08e29-e8c2-70d0-94ba-18df3e14d948明确转交。SDK5.8.47及原两项已批，不再请求相同批准。

本次仅行政回执，原技术方案/参数/例子保持不变；原ADAPTER_PROPOSED及“待审”措辞在下方保留为批准前历史。**此批准不是PR13合并批准，不是权威Contract已同步、DDL/部署已审或实现验收完成。** 实际宿主退出确认能力、DB持久/恢复前提仍需落实验证，通用业务幂等表/旧key迁移仍待后续设计；CCR不RESOLVED，完整PLAT002/004不DONE。当前剩余人工步骤是文档PR13合并审批。

已在本专用分支集成最新develop **643f05cd3a9357772bb3029ff97b750afeebb1ca**，保留AUTH及取消MFA产品变更；没有编辑这些文件或根dirty台账。新head与该新base的CI须重新核验，不以旧base bcb269c2的绿灯代替。最终固定提交/CI在PR13正文记录。

## 以下为已批准版本原文（含当时待审状态，保留追溯）

## 当前新增阶段：S2 Hutool生产适配设计

**状态：SDK_SELECTED / ADAPTER_PROPOSED。** 人工已选择cn.hutool:hutool-core:5.8.47，不重复询问SDK选型。原d5acc3a自研发号整包未批准，保留Git历史；PR13当前改为Hutool承担算法、薄适配器承担必要协调/候选发布/超时失败关闭的待审方案。S1/PLAT004组件已合入，不是重复S1或开始S2代码。

人工先读[S2一页评审指南](CCR-W2-IDEMP-001/s2-review-guide.md)，只审新增适配/失效恢复包，原两项与Hutool选择均不重问；详细方案见[s2-id-provider-design.md](CCR-W2-IDEMP-001/s2-id-provider-design.md)，例子见[s2-examples.md](CCR-W2-IDEMP-001/s2-examples.md)，证据见[s2-review-evidence.md](CCR-W2-IDEMP-001/s2-review-evidence.md)。保留原1秒对外预算与重启唯一性，不以Future超时冒称SDK线程停止；底层截止差距明确披露。本次只5份Markdown，不改权威docs/pom/代码/DDL/环境或AUTH/产品文件。通用业务幂等物理表与旧key迁移另待设计，CCR非RESOLVED，完整Issue非DONE。

## S1及原审批历史（以下已接受正文保持原样）

状态：**CONTRACT_SYNCED_IN_PR / S1_REVIEW_READY / S2_PENDING_DESIGN**。规范版本：0.1，日期：2026-09-14；本阶段同步已接受内容并交付S1，不关闭完整CCR/Issue。
提出方/唯一编辑者：Backend Core。关联既有 Issue：PLAT-002 / EPIC-01 / ST-PLAT-02。只读审阅：Transaction Backend、QA、C-End、Merchant、Admin。批准人：人工 CTO / Contract Owner **blueXYing**。

## 当前 S1 交付与下一步

人工已批准两项方案和PR10合并（实际develop合并`c5a184736c58ddfbaf60c5064b48a97bfb7eff3f`），随后明确启动本Issue权威公共契约同步及S1接口交接。本阶段权威条款见[23号补充](../../docs/04-api/23-公共接口与幂等契约补充-v0.1.md)，一页阅读指南/逐文件Owner/测试证据/下游边界见[S1交接](CCR-W2-IDEMP-001/s1-handoff.md)。

S1已提供纯Snowflake接口、ID/金额接口及确定性转换、时间/requestId基础检查和测试专用替身；不是生产Snowflake或数据库幂等实现。权威同步位于本阶段新PR、尚未合并，旧Schema兼容、具体物理表/迁移/存储/fencing等S2仍待定。完整PLAT-002非DONE，CCR非RESOLVED，不自行启动PLAT-004。当前需审阅本阶段新PR合并，不重复询问两项方案或PR10批准。

## 历史批准回执（PR10合并前，保留当时下一步）

2026-09-14，人工明确回复 **“接受两项建议”**。根任务 `01a08e29-e8c2-70d0-94ba-18df3e14d948` 转交批准并记录于根 WORK_STATE“PLAT002方案人工批准回执”。批准对象严格为已交付提交 **`a9856c14fd596304611496cf794c935ec4d1243e`** 的0.1版主文两项决定及其原有边界：

1. **已接受**数据库幂等记录、事务方案及公共 ID/Snowflake、Clock、金额接口提案。
2. **已接受**失败与旧回执技术澄清：固定第一次成功回执、未成功同参可重试、已绑定键不可改参数、去重事实不自动删除、旧回执仍受当前权限约束。

本次没有增加技术决定，也没有批准原文明确留待后续设计/审查的具体实现选项。**方案接受不等于权威Contract已同步、具体DDL/迁移已审、组件已实现/已测试或Issue DONE。** CCR尚不能RESOLVED，完整PLAT-002实现门禁仍有效；根Work继续负责调度台账。当前剩余人工步骤是**文档PR10合并审批**，该合并尚未获授权；不再询问相同两项方案批准。本回执不启动PLAT-004/AUTH等后续任务，不写代码/DDL或修改权威docs。

## 历史提案快照（a9856c14，保留原审批前措辞）

以下正文与附属文件技术内容沿用原0.1版。原“PROPOSED / PENDING_REVIEW”“未获具体方案批准”“待批”等属于首次交付时状态；两项建议的当前批准状态以上方回执为准。原文列出的Contract同步、具体迁移/Provider/身份/实现设计门禁仍有效，不被本批准自动解除。

基线：develop `e882dc3c2cadd6474ad52ca5f6301e7e80df2743`（fetch后核实）；分支 `codex/plat-002-spec`；任务 `01a09de1-d112-7f20-9beb-f6df6e5fd41d`；专用工作区 `C:/Users/Administrator/.codex/worktrees/9dd6/宠物平台V1.0`。根 WORK_STATE 1.9 和最新执行记录只读。本次人工启动授权覆盖历史“未启动”措辞，仅放行本规范阶段；完整 Issue 仍 **BLOCKED**，本 CCR 未获具体方案批准，非 RESOLVED、非 Issue DONE。

## 人工 CTO 一页阅读指南

**推荐：数据库保存“谁提交了什么操作，以及第一次成功回执”；相同操作重试不重复执行，改参数会冲突；取旧回执时仍检查当前权限。** 优先采用独立幂等记录，同时保留业务表“一单一次退款”等约束。Redis只作加速，断网、重启或缓存丢失都依据数据库恢复。

| 分工 | 内容 |
|---|---|
| 我负责 | 数据库/事务选型、参数比较、失败时序、三端重试、旧约束迁移和实现期验证设计；技术细节不交给你猜 |
| 你负责 | 审核以下两项建议；实现启动、保护Contract修改与PR合并仍按既定流程授权 |
| 必读 | 本页“建议决定”；需要看断网/撤权效果时读[评审例子](CCR-W2-IDEMP-001/examples.md) E03～E09 |
| 技术备查 | [技术提案](CCR-W2-IDEMP-001/idempotency-design.md)、[公共ID/Clock及交接](CCR-W2-IDEMP-001/public-contracts.md)、[来源与审阅](CCR-W2-IDEMP-001/review-evidence.md) |

**建议决定（两项，当前均待批准）：**

1. **接受数据库记录、事务方案及公共 ID/Clock/金额接口提案。** Snowflake、金额精度、权限和产品规则保持。新增的是实现约定；旧表作用域约束须逐模块迁移审核，不能以新增记录掩盖兼容问题。
2. **接受失败与旧回执的技术澄清。** 推荐固定第一次成功回执；未成功的同参请求可以重试，但已受理requestId不能改参数。暂不自动删除去重事实；旧回执按当前权限脱敏或拒绝，绝不重新执行业务。解决下述“第一次处理结果/第一次成功结果”差异。

接受提案后，团队仍需将条款映射至受保护Contract、具体迁移和逐文件Owner，由根Work派发实现及验证。**本次不授权应用代码、测试实现、DDL/迁移、PLAT-004/AUTH等其他Issue、合并或发布。** 不重裁单运营、接单、退款、核销等产品规则。未发现需要新增产品裁决的冲突；AUTH、Outbox及交易既有CCR仍约束对应实现。

## 1. 既有要求与真正待决项

“既有”来自当前权威文档；附属技术提案中的新增选择全部待批，不能当现成Contract。精确来源见[证据表](CCR-W2-IDEMP-001/review-evidence.md)。

| 事项 | 既有要求 | 此次待批补充 |
|---|---|---|
| ID/时间/金额 | 技术05 §3：Snowflake BIGINT/Long、API String；DATETIME(3)、Instant/OffsetDateTime、配置业务时区；BigDecimal/DECIMAL(18,2)，禁止浮点资金计算；HTTP10 §2.7 JSON金额String | 提供器与worker/回拨策略、Clock注入、严格无舍入校验、接口草图与装配 |
| CommandContext | Internal07 §2.1及现有Java只有requestId/traceId/operatorType/operatorId/source五字段 | 作用域从可信上下文派生，不扩五字段DTO、不建新身份体系 |
| 幂等 | Internal07 §21：所有写requestId、DB唯一键或表、不得仅Redis、同参成功重放、异参冲突 | 命令/主体作用域、摘要、并发、原子结果、失败/重试、保留和敏感结果 |
| 文字差异 | Internal07 §21：“返回第一次成功结果”；HTTP10 §2.3：“返回第一次处理结果” | 建议按成功结果固定、失败可重试；不假装失败缓存语义已定。结合两文共同约束提出技术澄清，待Contract Owner批准后统一措辞 |
| Schema | SQL06分散业务唯一键、五处全局request_id唯一约束；SQL13 task_key/attempt | 两种可选持久化方案都要映射，不能断言零迁移 |
| 事务/异步 | Event08 §15消费日志与业务同事务；Scheduler09 §35本地事实+必要task/outbox，跨模块不靠全局事务 | 本地成功结果同commit；Provider另守渠道幂等，不宣称分布式恰一次 |

## 2. 方案结论及影响索引

推荐独立数据库绑定记录：先在短事务绑定原参数（没有业务副作用），再持记录行锁，在本模块同一事务提交业务事实与成功回执。失败回滚业务、绑定保留，因此同参可重试、异参不能覆盖。RESERVED仅表示绑定，不能被当作运行租约。已有业务唯一键、CAS和退款/核销互斥始终保留。

替代方案是原业务唯一键加模块内结果适配器：只要能证明作用域、不可变参数、首次结果、失败绑定与原子性全部满足，就不强制新表。Redis-only不满足既有要求；单独提交RUNNING再业务提交会新增接管/fencing缺口，不作为同步命令默认方案。完整取舍、逻辑字段、迁移影响见[技术提案§1～8](CCR-W2-IDEMP-001/idempotency-design.md)。

Schema/API/Event影响均为提案：五处全局request_id唯一键须按Owner迁移评审；CommandContext不扩字段；HTTP需要明确busy/失败/重放/版本，现有事件与任务状态不改。本阶段无可执行DDL、Java文件或提供器实现。PLAT-004只能收到Markdown草图，不能据此交付完整Worker。

## 3. 验收与阶段出口

[规范样例](CCR-W2-IDEMP-001/examples.md)覆盖ORD-011/013及W2-IDEM-001～005；只核对文档结构、样例字节及规范推演，**不表示组件、真实MySQL并发/故障或业务E2E PASS**。现有common/task接口只是工程契约，前端fixture不是真实服务证明。

Transaction/QA与三端意见由Backend Core统一写入[审阅记录](CCR-W2-IDEMP-001/review-evidence.md)。不新增Issue或用户任务，不并写CCR。检查后固定commit并推送草稿PR，base develop，reviewer blueXYing；如作者自审请求422，在正文登记，不自审approve、不merge。最终head CI仅证明既有基线未被文档变更破坏，不能宣布新组件已测试或Issue DONE。
