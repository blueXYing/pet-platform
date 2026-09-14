# 来源、只读协作与检查回执

状态：**PROPOSAL_ACCEPTED / PENDING_CONTRACT_SYNC**。2026-09-14人工已接受`a9856c14fd596304611496cf794c935ec4d1243e`版两项建议，回执见§5及[主文](../CCR-W2-IDEMP-001.md)。基线develop `e882dc3c2cadd6474ad52ca5f6301e7e80df2743`。行号指此基线，章节语义优先；相对链接可在PR直接打开。主文与附属文件唯一编辑者Backend Core；下面岗位仅发送只读意见，未写文件，未创建额外用户任务。

历史状态：PROPOSED / PENDING_REVIEW。§1～4保留首次交付的来源、审阅与当时未批准记录；这些只读审阅本身仍不构成批准，人工批准另据§5登记。具体Contract同步/DDL/迁移/实现门禁不因方案接受自动解除。

## 1. 权威来源索引

| 编号 | 文件及章节/基线行 | 支撑内容 |
|---|---|---|
| SSOT | [01业务基线](../../../docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md) §七/八/十五/二十二/二十四，L350～431、L1076～1085、L1119～1125 | 核销退款互斥/当前未履约售后失效、一单一次退款、商家下线存量例外、迟到支付、单运营和当前鉴权脱敏审计 |
| P22 | [22权限补充](../../../docs/01-prd/22-运营权限人工裁决补充-v1.0.md) §1～3，L7～45 | 单运营、无第二人审批、实际RBAC/数据范围、超管仅已批准V1权限、原因用途脱敏仍有效 |
| T05 | [05技术基线](../../../docs/02-architecture/05-技术基线-v0.6.md) §1/3/8，L98～141、L474～489、L529～539 | ID/时间/金额/删除/并发基础；业务互斥不能泛化成核销后所有退款禁止 |
| I07 | [07内部API](../../../docs/04-api/07-内部API-Contract-v0.6.md) §1/2，L9～75；§20 L1192～1223；§21 L1240～1264 | 模块边界、五字段Context、String、禁止跨模块大事务、DB幂等、首次成功与异参冲突 |
| H10 | [10 HTTP](../../../docs/04-api/10-HTTP-API-Contract-v0.4.md) §2 L52～156；商家核销L866～874；§9～11 L1414～1458 | Bearer/UUID、首次处理结果、ID时间金额、可信staffId、当前actions/鉴权、状态码/安全 |
| ERR12 | [12错误码](../../../docs/04-api/12-Error-Code-Registry-v0.5.md) §2 L25～36 | 400/401/403/409/503既有码，未登记专用处理中/过期码 |
| D06 | [06核心SQL](../../../docs/03-database/06-核心数据库Schema-v0.1.sql) L226～269、L315～320、L374～387、L410～414、L442～455、L461～490、L594～640 | 业务唯一键、request_id普通索引、复合/全局唯一及64长度、默认排序规则，公共生命周期缺口 |
| D13 | [13异步SQL](../../../docs/03-database/13-Async-Infra-Schema-v0.1.sql) async_task L5～43、attempt L45～62，末尾claim示例 | Snowflake主键/task_no、task_key唯一、attempt唯一、DB NOW(3)领取示例 |
| E08 | [08事件](../../../docs/05-events/08-Integration-Event-Catalog-v0.6.md) §1、§14～17，§15 L369～388 | eventId+consumerName、消费日志同业务事务、版本兼容、迟到支付事实 |
| S09 | [09 Scheduler](../../../docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md) §4 L147、§7 L241～252、§18/19 L719～785、§21 L833～872、§35～39 L1338～1487 | 业务幂等/CAS兜底、claim短事务、Provider UNKNOWN原单查询/有条件重提、本地意图同事务、任务确定key、敏感日志边界 |
| T14 | [14全链路测试](../../../docs/07-testing/14-全链路测试矩阵-v0.1.md) L54/L56 | ORD-011大ID String、ORD-013异参409原定义 |
| T15 | [15并发故障](../../../docs/07-testing/15-并发故障测试矩阵-v0.1.md) L44～56、L99～101 | deadlock/事务崩溃/Provider未知、Outbox/消费者ACK故障及request/event/task不变量 |
| W2 | [PLAT-002](../../issues/wave-2/PLAT-002.md)、[计划](../../WAVE_2_PLAN.md)、[队列](../../READY_QUEUE_WAVE_2.md)、[验收](../../WAVE_2_TEST_ACCEPTANCE.md) | 仅规范阶段、唯一Writer、公共交接、W2-IDEM-001～005、未运行不能PASS |

资料优先级与授权另读[AGENTS](../../../AGENTS.md)、[执行协议](../../../WORK_EXECUTION_PROTOCOL.md)、本分支WORK_STATE及根工作区最新WORK_STATE/执行记录。根状态的后置授权覆盖旧未启动条款，仅本次范围；不编辑根调度/Catalog。HTTP和Internal的两处文字差异按共同约束提出澄清，仍由Contract Owner批准，不借文字优先级自动结案。

## 2. 实际代码/样本证据

| 路径 | 实际能力及不能声称的能力 |
|---|---|
| [CommandContext.java](../../../backend/pet-common/src/main/java/com/petplatform/common/CommandContext.java) L3～9 | 五字段record，未扩身份DTO，无真实认证/幂等实现 |
| [TaskExecutionContext.java](../../../backend/pet-task-core/src/main/java/com/petplatform/task/core/TaskExecutionContext.java) L5～10；同目录TaskHandler/TaskExecutionResult | taskId/requestId/traceId/OffsetDateTime now及结果草架，无Worker/ID/Clock提供器 |
| [小程序request.ts](../../../frontend-miniapp/src/shared/request.ts) L18～26；[request.test.ts](../../../frontend-miniapp/src/shared/tests/request.test.ts) L11～26 | caller提供requestId、无隐式重试；注入transport的大ID/金额样本，无网络/订单操作，不等于ORD011/013 |
| [小程序workspace.ts](../../../frontend-miniapp/src/shared/workspace.ts) L20～46 | epoch隔离旧请求；只是客户端缓存/展示保护 |
| [商家workspace.ts](../../../frontend-miniapp/src/merchant/workspace.ts)；[admission.ts](../../../frontend-miniapp/src/merchant/admission.ts) | 工程准入fixture、切店隔离；不代表AUTH或真实签约准入 |
| [Admin request.ts](../../../frontend-admin/src/request.ts) L11～29；[README](../../../frontend-admin/README.md) | 缺省requestId每调用生成UUID；重试须caller保留；401/403清内存和旧响应隔离，无持久化重试/真实鉴权 |

源文件名称/路径已由结构检查实际确认，Admin路径校正为src/request.ts。

## 3. 六岗位同 Issue 只读协作

| 岗位/子代理 | 有界输入 | 已纳入提案 |
|---|---|---|
| Backend Core / 主任务 | 来源阅读、方案整合、唯一编辑、交付PR | 主文CTO指南、两项决定、所有附属规范 |
| Transaction Backend / transaction_review | 事务、失败绑定、Provider、Schema兼容 | Admission不得先业务写；SUCCEEDED+业务同commit；五处全局唯一逐模块迁移；主库未知结果恢复；当前授权与已消耗执行资格分开；渠道UNKNOWN原单查询 |
| QA / qa_review | 原测试定位、并发/崩溃/跨主体/过期/撤权反例 | E01～E10、合法终端UUID、旧版本/金额/null/数组边界、真实DB NOT_EXECUTED声明 |
| C-End / frontend_inputs | 原requestId保存/旧响应/金额String | 同意图原UUID+原参数，不将内部fixture requestId当HTTP格式放宽；切账号废旧请求 |
| Merchant / frontend_inputs | 同shared层、切店/员工真实性、存量例外 | 重试绑定原店上下文；可信scope不由客户端自证；不一律禁冻结/下线存量履约售后 |
| Admin / frontend_inputs | 缺省UUID风险、RBAC/敏感回执/单运营 | 显式保留原UUID；回执当前权限/脱敏；不按角色名字放行，不重新引入双人审批 |

三前端由同一只读子代理分别按三个岗位给出有界意见，未派发C/M/A实现Issue。角色名不是GitHub账号，唯一人工技术审阅归属blueXYing。审阅不是批准；最终完整草案复审回执见下节。

## 4. 本地检查与最终复审

2026-09-14固定提交前检查：5个Markdown文件、38个本地链接全部可解析，代码围栏配对；git diff --check无错误。来源章节/关键行复核，D06五处全局唯一键与实际相符。E02主任务与QA独立核得96 UTF-8 bytes及同一SHA-256，仅证明文档向量一致。

| 最终审阅 | 结果和处置 |
|---|---|
| Transaction | 曾提出3项修改：createPayment需要同步支付参数，不能统一用受理成功；核销码/券码不能从摘要排除；退款/核销例子必须区分来源。已修design §2.2/3/7与E05/E07，审阅者再次核对3项全部关闭，所分配规范范围无剩余阻断 |
| QA | 样例、原测试映射、Admission/崩溃、ID/Clock未完成门禁无阻断；E02独立摘要一致。补强旧绑定按旧schema校验、Money拒绝128.，均已纳入 |
| C-End / Merchant / Admin | 三个岗位分别确认UUID保留、金额/ID String、错误码、切主体隔离、权限重放及不扩身份体系，文档审阅无阻断；未执行测试 |
| 根Work意见 | 共同约束下提出失败语义澄清，不能用文字优先级直接结案；已纳入。根台账/Catalog仅根Work所有，本次未编辑 |

这些结果只支持PROPOSED规范进入人工审阅，不是方案批准。公共组件、真实MySQL、业务API、迁移和Provider均NOT_EXECUTED。最终head CI及远端差异核验回执在PR正文登记，不为转述CI反复改head。

## 5. 人工批准回执（2026-09-14）

人工原话：“接受两项建议”。根任务`01a08e29-e8c2-70d0-94ba-18df3e14d948`明确转交批准，根WORK_STATE“PLAT002方案人工批准回执”与最新执行记录已登记，本任务只读核实，未编辑根台账。

批准对象是已交付`a9856c14fd596304611496cf794c935ec4d1243e`/tree `54625a39bf68695c621b07e41fdad0bba9f1a986`，即主文两项建议：数据库记录/事务和公共ID/Clock/金额提案；失败与旧回执技术澄清。本次仅5份规范Markdown的状态/历史说明/批准回执和PR10描述，不扩新技术决定，不修改原技术正文或样例。

当前为PROPOSAL_ACCEPTED / PENDING_CONTRACT_SYNC。权威Contract未同步、具体DDL/迁移未审、组件未实现/未验证；CCR尚不能RESOLVED，完整Issue实现门禁仍有效，不是DONE。PR10合并未获授权，是当前剩余人工审批；不重复请求相同两项方案接受，不启动后续任务。

原a9856c14的CI run34802737566六job成功保留为历史证据，不冒充本回执新head结果；新的固定head及CI、差异核验在PR10正文登记。CI仍只说明现有基线未被文档变更破坏。
