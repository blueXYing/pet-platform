# AFS 售后工作流与退款、核销集成方案

日期：2026-09-30。基线：PR96 合并提交 `ff983596cad899c7b4e1ce9c8278228787ca7ac6`。状态：**PROPOSED_REQUIRES_REVIEW / NOT IMPLEMENTED**。

用户已授权 CI 通过后按四角色方案开始，并明确停用定时任务。本案把该工作具体化为可审阅的接口、事务、存储、分工和验收；不把开工授权当作未决产品规则或重大契约的批准。待用户回答的产品项见[裁决记录](../../progress/2026-09-30/aftersale-workflow/PRODUCT-DECISIONS.md)。正式同步和依赖实现须依据具体回执进行。

## 1. 本次审阅范围

| 技术包 | 具体结果 | 前置条件 |
|---|---|---|
| A1：真实售后工作流 | 真实本人资格与创建、运营受理、双方证据与商家意见、补证期限恢复、按产品回执撤回、非退款终局；ORDER 同事务维护当前售后事实 | P1～P4 产品回答；真实身份、权限、证据和内容安全适配 |
| A2：可信全额/部分退款来源 | AFS 最终退款裁决与真实退款单同事务；AFS/ORDER/REFUND/PAYMENT/SCHEDULE 贯通类型、金额、原渠道、成功投影和预约释放；旧来源保持全额限制 | A1、真实可退资金资格；缺失时退款型裁决失败关闭 |
| A3：兼容、恢复与真实并发验收 | 拆分历史核销证明和当前工单；真实创建工单参与核销竞争；逐持久点故障、原退款号恢复、旧普通退款/拒单/迟到支付回归 | A1/A2 的正式契约及实际实现；测试替身能力不等同生产能力 |

批准范围建议包含对应 API50 / Storage50 / SQL50、现有 API/Event/Scheduler/Error/测试映射同步、上述后端内部实现、隔离 MySQL/Redis 测试及供审阅 PR。编号50为候选，正式落地前核对仓库未被占用。按 [WORK_EXECUTION_PROTOCOL §4](../../../WORK_EXECUTION_PROTOCOL.md) 的“产品裁决”“Contract 重大变更”进行具体人工审核；新 PR 合并、生产迁移及启用不在该范围内。

本次不扩大为公开 HTTP/前端、员工绑定、服务前 REF-001、资金分账/追回、通知实际送达、券/积分/评价消费者或 RESERVICE 自动二次履约。AFS-001/002、VER-002、QA-004 各按其完整 AC 保留未验范围，不因内部切片通过而全部标 DONE。

## 2. 证据与方案组成

- [合并接续记录](../../progress/2026-09-30/aftersale-workflow/START.md)：PR96 合并后 CI 六项成功；下载121份报告核对852项，失败/错误/跳过均0。此为既有基线，不是 AFS 新验收。
- [领域方案](../../progress/2026-09-30/aftersale-workflow/AFS-DOMAIN-PROPOSAL.md)：重新核对 SSOT、C/M/O 最终 PRD、真实接口及缺口，包含 DTO、状态和证据访问矩阵。
- [资金与核销方案](../../progress/2026-09-30/aftersale-workflow/MONEY-INTEGRATION-PROPOSAL.md)：逐模块阻断、资金来源、事务、SQL CHECK、原号恢复、消费兼容及迁移候选。
- [独立验收矩阵](../../progress/2026-09-30/aftersale-workflow/ACCEPTANCE-MATRIX.md)：37项场景及逐持久点故障，当前均未执行；[契约交叉审查](../../progress/2026-09-30/aftersale-workflow/CONTRACT-REVIEW.md)另列确定缺口。

本总案用于统一选择，不取代 SSOT/正式契约。子方案与本案不一致必须在冻结前修正，不能选择性执行。资料优先级沿 AGENTS。

## 3. 产品回执和已定规则

P1 一单活动工单粒度、P2 撤回后重新申请、P3 普通退款与 AFS 并存已询问，均未收到回答；P4 为终局后新问题识别，交叉审查后补问。推荐仅是候选，不能用默认选项、等待时间或 SQL 现状替代回答。

| 项目 | 推荐及其实现分支 |
|---|---|
| P1 | 一单一活动工单；其他新问题追加当前工单。若允许多活动，必须重做 ORDER 单指针、唯一 active 和核销失效集合协议 |
| P2 | 终局前可撤回；仍符合原资格且在原7天内可新建，不重置时限、不重开旧记录 |
| P3 | 普通 PENDING_MERCHANT/APPROVED/AUTO_APPROVED 未建单与活动 AFS 双向互斥；拒绝/撤回等结束后按原资格切换；已存在退款单不可另建 |
| P4 | 已有非退款终局后，可在原窗口提出新问题说明，运营受理前核对历史；重复问题关闭并引用原终局，新问题才受理。不用描述哈希或问题类别等价判断 |

P4 若批准，`Create` 在存在既往非退款终局时必带 `newProblemStatement`，服务端记录此前终局工单集合及说明摘要；`Accept` 必带非空 `newProblemAssessment` 并绑定核对时的终局集合版本；重复问题使用 `CloseDuplicate(context, caseId, expectedVersion, priorFinalCaseId, reason)`，仅 PENDING → CLOSED，原因码 DUPLICATE_FINAL_PROBLEM，不建立新的决定或退款。`priorFinalCaseId` 必须是真实同单终局，且不能引用 WITHDRAWN/INVALIDATED 冒充最终决定。新问题结论由运营负责，系统只验证真实授权与关联，不宣称自动证明问题同一性。若用户选择禁止同阶段再次受理，则该候选入口不实现，按批准范围的阶段规则阻断。此分支目前不得编码。

已定规则不重问：

1. 已核销7天从真实 verifiedAt 起；无需先申请普通退款或被拒。未核销但预约开始后，须有真实商家拒绝退款，7天从 appointmentStart 起，拒绝不重置期限。
2. [Scheduler09 §33](../../../docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md) 已明确 `now <= anchor + 7d`。沿用数据库 UTC 毫秒、`anchor <= now <= anchor+7×24h`，等号允许、下一毫秒拒绝新建。曾讨论的半开候选撤回，不改变已有正式边界。7天仅限制发起，不强制在7天内裁决。
3. 未履约 AFS 申请本身不禁核销。核销先提交使当时活动的旧未履约 AFS 失效；核销后的合法新问题按已核销资格处理。退款单先创建即禁止后续核销，即使渠道 UNKNOWN/FAILED。
4. 每单一次实际退款；PARTIAL 仅真实运营最终裁决；一旦部分退款不再申请 AFS；无复审、MFA或内部两人审批。普通被拒重申请的24h规则保持 PR96。

## 4. A1 工作流与真实边界

### 4.1 准入和状态

ORDER 提供真实 owner/merchant/store/city/scopeVersion、原付款、appointmentStart、不可变 verifiedAt/verificationId、当前 AFS 与普通退款引用。REFUND 通过本域公共事实 API 按订单查询并核验真实历史 REJECTED 和当前申请/退款；不接受客户端 rejected=true，也不以最新申请状态代替完整拒绝来源。

create 在共享 store guard 和 ORDER 锁内重验身份、窗口、P1～P4、退款存在性及素材；同事务写 AFS/来源快照、ORDER 指针、日志、Outbox 和首回执。任意真实退款来源/状态存在均不能新建；依赖损坏或查询失败不当成无记录。

| 命令 | 状态变化 | 必须约束 |
|---|---|---|
| create | 无 → PENDING | 本人真实会话；不可变 sourceStage/anchor/deadline 和核销或拒绝来源；P4说明按回执 |
| accept | PENDING → PROCESSING | 当前 aftersale.handle；存在终局历史时按 P4 审核新问题；单运营允许自己受理并终裁 |
| requestSupplement | PROCESSING → WAITING_SUPPLEMENT | USER 或 MERCHANT 单方、非空原因、运营指定未来 deadline、唯一当前 round；不发明固定24h或次数上限 |
| submitEvidence / opinion | PENDING/PROCESSING 保持；对应方满足当前补证轮时 WAITING_SUPPLEMENT → PROCESSING | 追加不可变批次；另一方追加不自动满足指定方请求；商家意见不改变资金权限 |
| supplementTimeout | WAITING_SUPPLEMENT → PROCESSING | now>=deadline、同一请求/round；提前 RetryAt，旧轮次或终态 NOOP；不自动裁决 |
| withdraw | 活动态 → WITHDRAWN | 按 P2；保留旧证据及首回执，重新申请使用新ID和原期限 |
| closeDuplicate | PENDING → CLOSED | 仅 P4 推荐获批后可用；运营核对同单既有终局；不重新裁决 |
| decide | PROCESSING → RESOLVED | FULL_REFUND/PARTIAL_REFUND/REJECT/RESERVICE/OTHER；不可二次决定；等待补证先回 PROCESSING |
| verify | 当前活动 UNVERIFIED_POST_START → INVALIDATED | 沿 Contract48 同事务；不失效核销后新 VERIFIED 工单 |

活动只含 PENDING/PROCESSING/WAITING_SUPPLEMENT。终裁统一 RESOLVED，端上显示“已处理/已关闭”属于读侧文案，不追加自动 RESOLVED→CLOSED 写操作。OTHER 与旧 SQL OTHER_NON_REFUND 需正式显式映射和历史检查。

补证截止的技术候选独立于申请七天窗口：满足当前轮的提交须数据库 `now < deadline`，截止等号及之后即使超时worker尚未运行也不得满足该轮；返回该轮已过期，不自动伪记及时补证。timeout以 `now >= deadline` 恢复PROCESSING；之后的一般新证据按当前状态追加，不能改写原轮超时事实。WAITING时必须带当前请求ID，不能省略ID绕过截止；重复已成功提交仍按原首回执重放。并发以同一锁内数据库时点判定，不能由调度延迟决定是否过期。

### 4.2 权限与证据

本人/本店 OWNER/运营读取真实卷宗和双方入卷说明、图片；列表不发附件 grant。商家不获得手机号明文、内部敏感备注或渠道数据。第一切片仅文本及每次申请/补证最多6张图片；C/M 描述、商家说明10～500字，用户诉求金额不授权出款。证据图片总历史不覆盖；该批次解释须在正式契约说明，不能默默把“6张”扩大成任意文件上传。

新增 typed `AFTERSALE_EVIDENCE` 用途和 case/batch 资源引用；不能把 caseId 塞入现有 merchant applicationId/revisionId。上传者、用途、READY、扫描/内容安全、对象hash和版本在入卷事务再次验证。资产绑定批次、敏感正文和规范幂等参数加密存储，日志/Outbox无自由文本或裸 URL。

证据读取 grant 绑定真实 session/generation/actor/case/batch/asset/用途/对象版本；签发与消费均当前权限、资产隔离状态和审计检查。OWNER 使用真实门店 authority；ADMIN 注册既有清单的 aftersale.read/handle/decide 及集合数据范围，最终写在本事务复验 RBAC。已核实的真实会话/权限 adapter 在 boot 组合，AFS 不依赖其他 biz。STAFF、冻结商家特殊写权限仍是明确未验范围。

### 4.3 幂等和接口

沿23号二进制五元组，scope 使用 ORDER:id 或 AFTERSALE:id；独立 Admission 持久加密规范参数，业务失败仍保留绑定。每个命令返回固定的 commandId/orderId/caseId/status/version/occurredAt 与本次 evidenceBatchId/supplementId/decisionId/refundId（可空）；不随后续状态改写首回执。相同 key 异参冲突，重放仍重验当前资源读取权限。

内部 ID 和版本使用十进制 String、金额 BigDecimal/两位小数、时间 UTC 毫秒；可信上下文由服务端身份边界生成。具体 `Create/Accept/RequestSupplement/SubmitEvidence/SubmitMerchantOpinion/Withdraw/Decide/GetCase/CheckEligibility/HandleSupplementTimeout` 候选见领域方案§7；本案 P4 明确增加条件字段及 CloseDuplicate。创建不接受客户端 sourceStage、实付金额、核销时间或商家归属。

公共职责统一候选：ORDER `OrderAfterSaleFactsApi` 负责 locate/requireCurrentEligible/current；`OrderAfterSaleCommitApi` 负责 bindCreated/projectTransition/acquireRefund/requirePending/commitCreated/requireCreated；`OrderAfterSaleRefundFactsApi` 负责已提交的退款来源。AFS `AfterSaleRefundFactsApi` 负责 requirePendingDecision/requireCreated，分别为当前事务活命令叶子证明和提交后不可变来源。领域草案单一OrderAfterSaleApi/AfterSaleDecisionFactsApi命名由此替代，避免不同模块各建相似事实源。

AFS当前叶子事实单列 `AfterSaleCaseFactsApi.requireCurrent(orderId, storeId, expectedCurrentCaseId, context, DataSource)`，返回 NONE/当前活动/当前历史、状态和版本，验证本域正反关联；不回调ORDER.current/checkEligibility，防止跨域相互读取递归。expectedCurrentCaseId只是来自ORDER的待核对引用，不是客户端授权。ORDER负责把自己的指针与该叶子事实组合成可信当前事实。

## 5. A2 原子裁决和资金来源

退款型决定用同 DataSource、可写 READ_COMMITTED 短事务，锁序统一为命令 → shared store guard → ORDER → 本域来源行/AFS/REFUND。跨模块持久事实仅通过 api，所有写者先同 guard；ADMIN/资产当前授权核验须合审具体锁序，禁止授权回调形成 OSS→AFS 与 AFS→OSS 反序。锁内不访问外部审核、存储或支付网络。

1. ORDER 获取 CREATE_REFUND token，绑定本事务、命令、真实身份、orderVersion、case/version/sourceStage/decisionId、原付款与决策时核销事实；重验退款不存在及资金资格。
2. AFS 写不可变最终决定内容；未绑定态只存在当前未提交事务。REFUND 读取当前活命令/ORDER token/AFS来源以及自身可核实付款，不接受裸决定行授权。
3. REFUND 写唯一 refund_order、不可变 execution、创建 Outbox 和原号渠道 task；来源 `AFTERSALE_DECISION`，sourceBizId=caseId/sourceDecisionId=decisionId，sourceEventId/lateEventId 为空；AFS来源不冒充普通退款。
4. AFS 写退款绑定并 RESOLVED，ORDER 核对本域和 AFS/REFUND 叶子证明后 CAS 退款引用/售后终态、消费 token；保存审计、决定 Outbox 和首回执。各 Owner beforeCommit 校验闭环，叶子事实读取不互相递归。
5. 以上任何失败全回滚；独立 Admission 保留。渠道发送只在 commit 后。不存在合法“已最终裁决退款、却未创建退款单”的异步等待阶段，不新增 AFS 裁决后 CREATE_REFUND 任务。

因此：VERIFY先提交则旧工单 INVALIDATED，旧退款决定不提交；AFS先提交则真实退款单已存在，后核销失败。普通退款49号的先批准后建单语义保持独立。

FULL严格=原渠道实付；PARTIAL仅AFS且0<amount<原实付。原实付必须分别等于 ORDER/PAYMENT真实付款事实；不能把全部现有 amount=paid 放松成<=。refundType固定在执行事实，不能从已舍入ratio猜测。ratio沿DECIMAL精度作为派生展示，不授权出款；具体精度核验见资金方案。

真实可退资金资格单列硬依赖：运营 PRD 要求核对是否已分账，而 [OD-W0-001](../../OPEN_DECISIONS.md) 的资金权威基线仍缺失。候选 `AfterSaleRefundabilityPort.requireCurrent` 返回受信任 proofId/orderId/paymentId/storeId/eligibleAmount/currency/fundsVersion/checkedAt/有效性引用，AFS最终决定和ORDER来源持久绑定该证明；首次发送按资金协议复验，已发送后恢复沿原号查询，不因资格后来变化盲重发。该 port 不是客户端“未分账”声明，不允许生产 constant true、PAID推定可退或查无分账行推定未分账。权威 Provider、资金锁/保留和有效期只能随资金基线定稿；当前不得伪造可用适配。测试 Provider 明确 test-only，生产缺失失败关闭，A2完整上线仍阻断。

公共事实名称/精确字段以资金方案§2.4的 `RefundFundingEligibilityFactsApi.requireForDecision / requireForFirstSend`、FundingCheck/FundingEvidence为准。上述AfterSaleRefundabilityPort只是在boot组合该公共事实的AFS本域端口，不成为第二权威来源；proofId/eligibleAmount等解释性简称正式统一为evidenceId/authorizedRefundAmount。接口位置候选pet-payment-api仅是facade，不授权PAYMENT拥有尚未定稿的结算账本。首次发送再次查询仍必须有权威契约保证查验到实际出款间的有效性/竞争；本地store guard锁不住远端资金，无法证明该保证即继续阻断，不靠两次快照自称已解决。

REJECT/RESERVICE/OTHER同事务结束工单/ORDER投影/决定/事件/首回执，不创建退款或释放预约。RESERVICE记录人工安排，未交付自动新订单/预约/重复核销。若真实资金依据未就绪，可独立验收A1及非退款终局，并测试A2拒绝缺依据，但不称真实退款闭环完成。

## 6. A3 兼容、存储与恢复

历史证明必须与当前售后事实拆开：核销提交前仍严格断言同步失效完成；历史读取核对不可变核销/命令/事件/时间及当时失效证明，允许后来的合法新工单。当前AFS反向关联独立检查。已发现 ORDER/AFS requireCommitted 和普通退款资格把历史指针与当前指针相等作为条件，需要修正；现有 VER 成功重放用自身 receipt，不能声称它现在已经调用上述接口而失败。新增回归仍覆盖新AFS和终裁后的旧核销首回执不变。

| Owner | 正式增量候选 |
|---|---|
| AFS | aftersale_command、decision、refund_commit、不可变证据batch/items、supplement request/round；case来源锚点、原付款/核销或拒绝引用、加密字段、最终决定与退款绑定；P4终局关联按回执 |
| ORDER | order_aftersale_source_proof、order_aftersale_refund_commit；CAS当前AFS投影、AFS专用事实和CREATE_REFUND能力；成功投影与DisplayOrderStatus由本域统一计算 |
| REFUND | public AFS create/createdFacts；execution固定refundType；扩精确来源形状；SQL42全局amount=paid CHECK必须替换为来源+类型联合CHECK，旧来源仍FULL且=paid，NULL显式拒绝 |
| PAYMENT/SCHEDULE | 首次发送校验AFS/ORDER/REFUND真实来源；保留MAY_HAVE_SENT先持久、后仅原号查单；可信FULL/PARTIAL成功才释放原预约 |
| ADMIN/THIRD_PARTY/boot | 真实AFS动作与范围、typed evidence/grants/授权组合；默认关闭配置和缺依赖验证 |

生产 SQL 全在各 Owner MyBatis XML；不跨域查 Mapper/DO，不新增内联JDBC。SQL50有历史前置核对及分步失败说明；不删旧核销/普通退款/幂等事实、不凭旧 AFS status补造决定。MySQL DDL非全事务，不许宣称失败自动回滚。

`RefundOrderCreatedEvent.v1`/`RefundSucceededEvent.v1`沿严格字段集，只扩认可的AFS来源及合法FULL/PARTIAL类型；新case/decision来源通过可信公共事实读取。旧 ORDER消费者必须先通用验证和来源分派、再对自己来源检查FULL，否则合法AFS PARTIAL会在旧消费者失败；未知来源仍拒绝。新增ORDER_AFTERSALE_REFUND只处理AFS，claim、成功证明、金额投影和SCH释放同事务；重复核对后NOOP，UNKNOWN不释放。

AFS Created/Resolved/Invalidated沿已有事件精确载荷，不广播证据。受理/补证/撤回/重复关闭新增候选 `AfterSaleProgressChangedEvent.v1` 精确 payload：afterSaleId/orderId/action/fromStatus/toStatus/caseVersion/supplementRequestId/targetParty/deadline/occurredAt；可空字段仍固定出现，ID/version String，action为 ACCEPTED/SUPPLEMENT_REQUESTED/EVIDENCE_ADDED/MERCHANT_OPINION_ADDED/SUPPLEMENT_TIMED_OUT/WITHDRAWN/DUPLICATE_CLOSED，targetParty仅补证轮有值。每次状态变更由唯一Owner同事务发布，事件不提供资金授权，也不代称通知已送达。

候选 task：`AFTERSALE_SUPPLEMENT_TIMEOUT:{supplementId}:0`，payload afterSaleId/supplementRequestId/storeId/expectedDeadline；校验真实任务与原round。退款侧使用 `AFTERSALE_REFUND_SUBMIT:{refundId}:0` / `AFTERSALE_REFUND_CHANNEL_QUERY:{refundId}:0`，payload refundOrderId/storeId，沿固定executionKey和PAYMENT原号。逐笔来源扫描严格复核后恢复原key，READY/RETRY_WAIT/RUNNING不抢占；参数冲突记录问题不覆盖。禁止裸AFS终态自动补款。

上线顺序：隔离环境验SQL50及旧数据 → 全部来源/消费者兼容代码 → 真实依赖装配和验收 → 单独申请启用。生产开关本次默认关闭；已有AFS事件后不能直接回退到只懂旧来源的消费者。关闭新准入不删除已提交退款/证据/任务，已发款沿原号完成查询。

## 7. 文件归属与执行顺序

| 角色 | 模型/思考档位 | 独占实现边界（正式冻结后） |
|---|---|---|
| 总协调 | 沿当前聊天配置；用户建议Astra/xhigh | 正式契约/Schema/Event/Scheduler/Error及fixtures总协调；ADMIN/THIRD_PARTY/boot适配按逐文件清单；审查/集成/提交PR |
| AFS业务 | GPT-6 Astra/high | pet-aftersale-api/biz及其本域测试；真实准入、状态、证据、裁决、AFS证明；不得改共享契约或他域文件 |
| 资金与核销 | GPT-6 Astra/xhigh | pet-order/refund/payment/schedule 的 api/biz 和本域测试；历史核销兼容与资金链；不改AFS本域实现 |
| 独立QA | GPT-6 Astra/high；复杂审查按需xhigh | pet-boot集成测试、e2e及独占fixture文件；先独立期望再验收，不把seed当真实创建 |

AFS-001/002之外触及ORDER/REFUND/PAYMENT/SCHEDULE/ADMIN/THIRD_PARTY，是其既有Owner对本集成的必要依赖协作，须随本案明确批准 Allowed Modules；不把跨模块代码偷偷归到仅允许AFS的原Issue。此处只定义最小依赖增量，不新增资金Epic。

顺序：先产品回执与正式契约冻结 → 共享DTO/迁移/fixture由单Owner落地 → 业务和资金按文件并行，QA独立准备 → boot真实装配 → 串行Maven及隔离数据库真实并发 → 审阅PR。Maven只能一名协调者持有构建窗口；源文件/fixture归属有交叉先协调，禁止互相覆盖。

## 8. 验收与当前未交付

完整验收按独立矩阵：真实普通拒绝或核销→真实AFS创建→真实运营裁决→可信退款来源→受控渠道/最终投影；核销和AFS分别先提交、并发请求、各写点回滚、旧任务/证明篡改、会话与RBAC撤权、跨店证据、UNKNOWN与重启、旧来源回归、ArchUnit和持久层检查。数据库资金Provider或支付渠道替身必须标清，只证明已冻结协议行为，不能叫生产真实资金闭环。

当前实际完成的是PR96收尾、多角色源码/资料审查、可审阅提案与验收设计；未写AFS业务代码、未跑新增AFS用例、未执行SQL50或生产动作。三角色已启动不等于三个模块已交付。未回答产品项、重大契约批准和OD-W0-001真实资金来源是分层依赖；完成独立工作后仅对具体仍缺内容请求用户决策，不重新索要整体开工许可。
