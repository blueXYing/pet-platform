# REF-001 服务开始前自动全额退款（默认关闭）实施记录

日期 2026-10-02；分支 `codex/ref001-auto-refund-20261002`（基于 origin/develop @ `e3b846e`，PR#98 合并点）。Draft PR 不授权合并、生产迁移或启用。

## 接管背景与前代理遗留审查结论

本记录前半部分（依赖核对、规则引用、实现方式、变更文件、待裁决、风险清单）由前代理完成；该代理中途失活，遗留**未提交的 5 文件实现 + 本文档草稿**，且"本地验证"一节未回填、无 commit、无 PR。接管代理逐行审查全部遗留改动后**全部保留，未发现方向性错误**：

- 默认关闭开关（boot `@Value` 注入 + 12 参默认构造器 false + 装配测试断言默认关/显式开/显式关）成立；
- 实现确为围绕 PR96 `RefundApplicationService.apply()` 扩展"服务前自动全额"路径：同事务即时 SYSTEM 批准（专用准入绑定）、不入队商家超时任务、建单仍走既有 durable `REFUND_APPLICATION_CREATE`，执行/渠道/释放机制零新增；
- 证明判定 `decidedAt==createdAt` 与超时路径（`decidedAt>=createdAt+24h`，Schema CHECK 强制 deadline=createdAt+24h）数学不相交，分派校验分支可靠；ORDER 侧提交态不变式（服务前未核销不得停留 PENDING_MERCHANT）与 REFUND 侧开关互补。
- 接管代理的补完仅为验证与环境：搭建隔离验证环境完成全部门禁并回填本文档；清理了本 worktree 内前代理遗留的僵尸 mvn 测试进程（其 classpath 已被并发安装破坏，结果不可信）。

## 接管后验证环境（隔离，避免与他人在途工作互踩）

- Java 21（Eclipse Adoptium 21.0.11）；MySQL 8.4.9 **独立实例** 127.0.0.1:3321（数据目录在 D 盘，`--initialize-insecure`，AUTH_MYSQL_* 注入，每用例独立随机 schema）；Redis 7.4（Docker 容器，`--save "" --appendonly no`，AFS 验收夹具要求关闭持久化）；**隔离 Maven 本地仓库**（`-Dmaven.repo.local`，规避共享仓库被其他在途构建并发写入导致的 jar 撕裂读）。
- 共享 3306 实例上另有他 worktree 在途测试运行，本切片未触碰；该实例上遗留的历史 qa_booking_* 泄漏 schema 已部分清理（前次运行崩溃残留，非本切片产物）。

## 依赖核对结论（原 ISSUE_CATALOG 状态已过期）

ISSUE_CATALOG 将 REF-001 标为 BLOCKED，依赖 `TX-001,REF-003`。核实 develop 实际状态：**依赖均已就绪，目录未更新**。

| 目录依赖 | 目录状态 | develop 实际对应物（已合入） | 结论 |
|---|---|---|---|
| TX-001 创建预约订单 | BLOCKED | pet-order-biz OrderCreationApi 全链路 + PR#86 booking-create 合入，验收测试 BookingCreateAcceptanceTest 在 CI 运行 | 已就绪 |
| REF-003 RefundOrder/渠道退款核心 | BLOCKED | refund_order/refund_execution + LateRefundService/RefundExecutionService（PR#90 late-refund 合入，含迟到支付自动全额原路退款先例）；PaymentRefundService 渠道 worker（PR#88/#89） | 已就绪 |
| （目录未列）普通退款申请 | — | PR#96 契约49：`RefundApplicationService` 申请/决定/24h 超时自动批准/建单，且 `apply()` 内预置桩 `REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED`，正是本切片要接通的缺口 | 已就绪 |
| （目录未列）核销互斥 | — | PR#94/#95 核销凭证与完成；`refund_order` 创建后禁核销、PENDING/已批准未建单可核销语义已在 ORDER 侧实现并有验收 | 已就绪 |
| durable AsyncTask | BLOCKED | pet-task-core `JdbcAsyncTaskSubmitter`/`JdbcAsyncTaskRecoverer`/`AsyncTaskWorker` | 已就绪 |

REF-001~REF-003 测试矩阵（docs/07-testing/14 §81-83）要求的"自动批准并创建 FULL refund_order；创建后禁止核销；渠道最终成功才释放预约"全部可基于既有机制实现，无需新增 Schema、事件或资金 Provider。

## 产品规则依据（原文引用）

SSOT《最终业务基线》四、退款状态机 §4.1 预约开始时间之前（docs/00-ssot/01 §"4.1"）：

> 条件：当前时间 < 预约开始时间
> 流程：买家申请退款 → 无需商家确认 → 创建退款单成功 → 立即禁止核销 → 退款中 → 拉卡拉退款成功 → 已退款
> 规则：
> - 服务开始前属于系统自动退款。
> - 不进入 `退款待确认`。
> - 退款单创建成功后立即禁止核销。
> - 槽位只在支付渠道退款最终成功后释放。

SSOT §20 已封板规则含"服务前自动退款""退款单创建成功后禁止核销"。SSOT §39（2026-09-30 批准）明确：

> 本批接通 §4.2 的已到预约开始但未核销全额普通退款，以及 §4.3 的已核销全额普通退款。……**服务前未核销自动退款**、AFS 创建/裁决、部分退款和 STAFF 授权另行交付。

契约49（docs/04-api/49）同样声明"服务前 REF-001……不属本批"，即当前 `apply()` 对 `now<appointmentStart` 抛 `REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED` 是**已知的待接通桩**，不是产品拒绝。

触发点语义按原文写死：**买家申请退款**即触发（不是定时扫描、不是取消订单）；全额=真实实付金额（与契约49 `FULL=真实本金` 一致）；原路=既有拉卡拉退款通道；与商家 24h 处理的关系=**服务前完全没有商家处理环节**（§19.2 商家端 PRD："服务开始前不需要商家处理"）。

## 实现方式（最大化复用既有原路退款先例）

复用 PR96 普通退款申请链路与 PR90 迟到支付先例的全部执行机制（同一 refund_order/执行绑定、同一 `APPLICATION_REFUND_SUBMIT`→PAYMENT `REFUND_SUBMIT` 渠道任务、同一 `RefundOrderCreatedEvent.v1`/`RefundSucceededEvent.v1`、同一 Outbox/幂等/审计、同一"最终成功才原子释放预约"投影），**不新增来源类型、不新增 Schema/事件/任务类型**：

- 新增开关 `pet.refund.pre-service-auto-refund.enabled`（默认 false，boot `@Value` 注入 `RefundApplicationService` 新构造参数；默认构造器保持 false）。关闭时维持原桩错误码 `REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED`，行为与基线完全一致。
- `RefundApplicationService.apply()`：买家申请时 `now<appointmentStart` 且未核销 → 开关开启时同一事务内：
  1. 发布 `RefundApplicationCreatedEvent.v1`（applicationStatus 按提交真实终态 `AUTO_APPROVED`，字段集与契约49一致）；
  2. 以专属 SYSTEM 准入绑定（namespace `refund.application.pre-service-auto`、scope `REFUND_APPLICATION:{id}`、requestId `REFUND_PRE_SERVICE_AUTO:{id}`）写入不可变 `AUTO_APPROVED` 决定（DB CHECK 亦强制 SYSTEM/无操作者/无理由密文）；
  3. `writeDecision` 既有路径发布 `RefundApplicationDecidedEvent.v1`、ORDER 投影/审计、入队既有 `REFUND_APPLICATION_CREATE:{id}` durable 任务；
  4. 不入队 `REFUND_MERCHANT_TIMEOUT`（服务前无商家处理窗口）；
  5. 申请绑定与决定绑定各自以同一首回执 SUCCEEDED，重放幂等。
- 建单仍由既有 durable 任务 `createApproved` 完成：获得本次 ORDER 版本 CREATE_REFUND 能力、`commitCreated`（此后核销被拒）、`RefundOrderCreatedEvent.v1`、渠道提交任务。渠道最终成功前预约保持占用；成功后既有 `OrderApplicationRefundProjectionConsumer` 同事务回写金额、记消费、释放预约——即测试矩阵 REF-002/REF-003 语义，零新增代码。
- 来源沿用 `MERCHANT_TIMEOUT_AUTO`（PAYMENT `validateApproval` 对其校验为 SYSTEM 决定、决定时间≥申请创建时间、建单时间≥决定时间——服务前即时批准全部满足；渠道 worker、ORDER `requireApprovedRefund`、迟到支付先例的 `verifyRow` applicationSource 分支全部零改动）。
- 证明判定：`decidedAt==application.createdAt`（同事务即时决定）为服务前决定；与超时路径（`decidedAt>=merchantDeadline=createdAt+24h`）数学上不相交，`requireDecision`/ORDER `recordDecision` 按此分派证明校验分支。
- ORDER 侧 `normal()` 的服务前拒绝改为仅 `forAftersale` 路径保留（AFS 未履约窗口起算预约开始的既有语义不变）；`bindApplication` 移除绑定时点门槛（绑定发生在同事务决定之前，时点本身不再是资格判据）；`requireApplicationBound` 新增不变式：**服务开始前未核销订单的申请提交时不得停留于 PENDING_MERCHANT**（必须是已决定的系统全额退款）。

## 变更文件

- backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/RefundApplicationService.java（开关参数、apply 服务前分支、autoApprovePreService、requireDecision/replay 证明分支）
- backend/pet-order-biz/src/main/java/com/petplatform/order/biz/apiimpl/OrderRefundApplicationApiImpl.java（服务前资格门迁移、committed-state 不变式）
- backend/pet-boot/src/main/java/com/petplatform/boot/config/RefundApplicationConfiguration.java（开关装配）
- backend/pet-boot/src/test/java/com/petplatform/boot/booking/RefundApplicationAcceptanceTest.java（夹具开关 + 4 个新验收方法）
- backend/pet-boot/src/test/java/com/petplatform/boot/config/RefundApplicationConfigurationTest.java（默认关闭/显式开启装配测试）

无 Schema/Contract/Event 文件变更（本批对契约49/SQL49零增量；"服务前申请 Created 事件状态值为 AUTO_APPROVED"是对契约49字段值语义的扩展，见待裁决第3条）。

## 本地验证（接管代理 2026-10-02 实测回填）

环境见上节。结果（全部为隔离环境实测，无失败/错误/跳过除注明外）：

| 范围 | 结果 |
|---|---|
| pet-boot `RefundApplicationAcceptanceTest`（含 4 个新增服务前验收 + 25 个既有普通退款验收回归） | **29/29 通过** |
| pet-boot `RefundApplicationConfigurationTest`（含新增默认关闭/显式开关装配断言） | **34/34 通过** |
| pet-boot `VerificationCompletionAcceptanceTest`（核销互斥回归） | 19/19 通过 |
| pet-boot `VerificationCredentialAcceptanceTest`（核销凭证回归） | 23/23 通过 |
| pet-boot `AfterSaleWorkflowConfigurationTest`（AFS 装配回归） | 24/24 通过 |
| pet-boot `AfterSaleMoneyAcceptanceTest`（AFS 未履约窗口/核销胜出回归，需 Redis） | 12/12 通过（修复 Redis 持久化配置后复跑） |
| pet-order-biz 全模块 | 29/29 通过 |
| pet-refund-biz 全模块 | 4/4 通过 |
| pet-aftersale-biz 全模块 | 8/8 通过 |
| pet-architecture-test 全模块（模块依赖/归属规则） | 31/31 通过 |

新增 4 个服务前验收方法内容：①申请即 AUTO_APPROVED（version=1、SYSTEM 决定、无商家超时任务、Created/Decided 事件入 Outbox）→ 建单 → **建单后核销被拒** → 渠道最终成功前预约保持 CONFIRMED → 成功后 RELEASED 并回写 refunded_amount=128（REF-001~REF-003 测试矩阵全链）；②核销先完成不否决服务前自动全额退款（与 §39/PRD30 语义一致）；③已批准未建单任务恢复慢于服务开始仍按原来源建单；④伪造决定来源/篡改准入绑定不能建单更不能触达渠道。

过程说明（如实记录）：首次尝试因与其他在途工作共享 MySQL/Maven 仓库互相干扰（磁盘满、共享仓库 jar 并发写）失败两轮；切换全隔离环境后一轮全绿。上述数字均为最终隔离环境的单次连续运行。最终完整回归以 PR CI 为准。

## 阻塞与待裁决（不阻塞本默认关闭切片，但启用/合并前需明确）

1. **契约归属未立项**：服务前自动退款是 SSOT §4.1 封板规则，但契约49明确将其排除在普通申请契约之外；本实现是在契约49机制内的最小延伸，**没有新的正式 Contract/CCR 文档**。按"Contract 缺失走 CCR"，建议补一条 REF-001 实施契约（或 CCR）确认：(a) 复用 `MERCHANT_TIMEOUT_AUTO` 作为服务前退款来源字符串，或另立 `PRE_SERVICE_AUTO` 新来源（后者需改 PAYMENT/ORDER/消费者识别，波及面大）；(b) 服务前申请的事件语义。
2. **决定时间语义**：服务前决定的 `decidedAt==createdAt`（申请即批准），`merchantDeadline` 字段仍按 Schema CHECK 写 `createdAt+24h` 但服务前路径无商家含义，也不入队超时任务。若 CCR 认为 deadline 字段应表达其他含义（如服务前仍可撤销的期限），需另行裁决。
3. **Created 事件状态值**：服务前申请的 `RefundApplicationCreatedEvent.v1.applicationStatus=AUTO_APPROVED`（契约49文本只描述了普通路径 PENDING_MERCHANT 的值；字段集不变）。通知消费者后续交付时按此对齐。
4. **拒绝路径不存在**：服务前申请不可拒绝（系统自动全额），DB CHECK 与应用校验均不允许对该路径产生 REJECTED；若产品后续要"服务前也可拒绝"，是新规则，未实现。
5. 目录 ISSUE_CATALOG/DEPENDENCY_GRAPH 中 REF-001 及其依赖的 BLOCKED 状态未更新，需要目录维护方同步（不在本切片 Scope 内改动）。

## 遗留风险

- 开关打开后，服务前申请在申请事务内即时批准并建退款单承诺；渠道未接真实资金前（OD-W0-001 不具备），一切退款仍只到测试 Provider/离线适配，不代表可出款。
- `preServiceCreationRecoversEvenAfterServiceStart` 覆盖"批准后建单任务恢复慢于服务开始仍按原来源建单"；若产品认为应按开始时点重新裁决，属新规则（未实现，见待裁决2）。
- 未接入 HTTP/小程序入口：`pet.refund.application.http.enabled` 仍默认关闭，C端发起入口后续交付；本切片只打通内部命令链路。
- 通知消费（Created/Decided 事件的实际送达）不在本批，服务前路径的事件将随通知消费者一并验收。

## 2026-10-05 用户裁决附录（第二次接管代理补记）

用户对阻塞第1条裁决：服务前自动退款**新立专门来源 `PRESTART_AUTO`**（沿用 SQL06 `refund_order.source_type` 注释既有预留值），不复用 `MERCHANT_TIMEOUT_AUTO`；上文实现方式中"来源沿用 `MERCHANT_TIMEOUT_AUTO`"的方案自本裁决起废止，以本附录为准。

落地内容（同批提交）：

- SSOT §39（即用户所称契约39的实质条款，见 docs/00-ssot §39；docs/04-api 下的39号文件为选位过期契约，与退款来源无关）与契约49"来源与执行兼容"修订为三类普通来源：`MERCHANT_APPROVED`/`MERCHANT_TIMEOUT_AUTO`/`PRESTART_AUTO`。
- 契约49新增"服务前自动全额退款（REF-001）"实施章节：触发=开关开启时买家申请且 now<预约开始且未核销；全额=实付（refundType=FULL）；原路=既有拉卡拉渠道执行；互斥=既有 refund_order 硬规则；开关 `pet.refund.pre-service-auto-refund.enabled` 默认 false；服务前无拒绝路径；服务前 merchantDeadline=决定时间+24h，与 SQL49 CHECK `merchant_deadline=created_at+INTERVAL 24 HOUR` 一致。
- 事件目录08：RefundApplicationCreatedEvent.v1 的 applicationStatus 普通路径固定 PENDING_MERCHANT、服务前按提交时真实终态 AUTO_APPROVED；RefundOrderCreated/Succeeded 的 source/refundSource 增补 `PRESTART_AUTO`。
- SQL49/SQL50：`refund_execution` 来源 CHECK 增补第三来源分支（仅约束放宽，无数据回填）。
- 代码：`RefundApplicationService.requireApproved` 按决定绑定 namespace `refund.application.pre-service-auto` 判定 `PRESTART_AUTO`，经既有建单/执行链路写入 refund_order/refund_execution 与事件；PAYMENT 审批校验、ORDER 四个投影消费者、SCHEDULE 预约释放、渠道 worker 任务类型/错误码/恢复扫描全链路识别。
- 测试：新增 `preServiceAutoSourceIsDistinctFromMerchantTimeoutSourceOnRefundOrders`（同库两来源可区分）；PAYMENT 来源参数化增补 `PRESTART_AUTO`；`AutoConfirmTaskPreparationAcceptanceTest` 新增按 paymentId 聚合选取事件的过载，修正同夹具多订单时事件选取歧义。
- planning：ISSUE_CATALOG REF-001 → IN_PROGRESS 且依赖补 REF-002；DEPENDENCY_GRAPH 补 REF2→REFB 边与交付注记。
