# AfterSale Workflow Contract v0.1

状态：APPROVED / IMPLEMENTATION_IN_PROGRESS，2026-09-30。用户原话“批准四项推荐规则及 A1–A3”。产品权威：[SSOT §40](../00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)、[PRD31](../01-prd/31-售后流程人工裁决补充-v1.0.md)；技术批准：[总案](../../planning/ccr/CCR-W2-API-001/aftersale-workflow-proposal.md)。本契约不表示代码已通过验收，不授权生产迁移/开启开关或合并PR。

## 1. Scope / ownership

A1建立真实内部资格、申请、受理、证据/意见、补证及超时、撤回、重复问题关闭、五类最终决定；A2建立AFS最终退款决定与业务退款单的原子来源及FULL/PARTIAL执行链；A3兼容历史核销、普通退款与真实工单核销并发。AFS/ORDER/REFUND/PAYMENT/SCHEDULE/ADMIN/THIRD_PARTY各自持有本域表，boot只组合公共API及本域port，禁止biz→biz或跨域持久化访问。

后续公开非出款HTTP由[Contract51](51-AfterSale-Http-Contract-v0.1.md)独立交付；50号本身的未交付范围：STAFF真实绑定、小程序/运营页面、REF-001、实际资金分账/追回、通知送达和券/积分/评价消费者。RESERVICE只记录人工处理安排，不新增订单/预约或重复核销。全部新开关默认false；真实资金Provider缺失时退款型决定/首次出款失败关闭，隔离测试的资金资格不允许进入生产配置。

## 2. Eligibility / product decisions

- P1一单只一活动工单；活动状态为PENDING/PROCESSING/WAITING_SUPPLEMENT，新增问题追加当前工单。
- P2上述终局前状态本人可撤回，仍满足原资格/原七天可新建，新ID且不重置期限。
- P3普通PENDING_MERCHANT/APPROVED/AUTO_APPROVED未建退款与活动AFS双向互斥；各自已提交承诺不被另一方静默取消。普通拒绝/AFS撤回或非退款结束后按原资格切换；任意真实refund_order存在均阻止重新申请或建第二退款。
- P4已有非退款正式终局时，Create必须携newProblemStatement；服务端保存真实历史终局集合与版本，Accept必须携newProblemAssessment，当前运营核对后才受理。重复问题只能CloseDuplicate，PENDING→CLOSED并引用真实同单非退款终局，原因DUPLICATE_FINAL_PROBLEM，无新的decision/refund；WITHDRAWN/INVALIDATED/无决定CLOSED不能作为旧正式终局。描述hash/问题类别不自动证明同一性。
- VERIFIED：真实verifiedAt起七天，无商家先拒绝前置。UNVERIFIED_POST_START：已到appointmentStart、且存在同单同主体的真实普通REJECTED决定，appointmentStart起七天。REFUND按订单提供真实拒绝历史及活动申请，AFS不跨查表、不信客户端标志。
- 七天沿Scheduler09 §33采用数据库UTC毫秒闭区间 `anchor <= now <= anchor+7*24h`；+1ms拒绝新建，拒绝/撤回不换anchor。七天只限制发起，旧工单可在七天后处理。当前资格快照不授权随后写入，写事务必须重验。

## 3. Commands / DTOs

Java公共ID/版本均十进制String；内部金额BigDecimal，精确两位小数/DECIMAL(18,2)范围；时间UTC毫秒。CommandContext必须来自可信边界，不能用body身份授权。所有写操作遵守23号二进制五元组requestId。

`AfterSaleCommandApi`：Create、Accept、RequestSupplement、SubmitEvidence、SubmitMerchantOpinion、Withdraw、CloseDuplicate、Decide。`AfterSaleQueryApi`：资格和按权限裁剪卷宗。`AfterSaleSupplementTimeoutApi`：持久任务处理与到期恢复。精确Java records为对应api模块中本契约实现表面，字段不得超出本契约批准语义；内部命名调整需在实施记录披露。

Create只有orderId、问题/诉求code、10～500字描述、可选诉求金额、0～6唯一assetId及P4新问题说明；不接受sourceStage/实付/核销时间/merchantId/userId作为业务事实。类型和诉求由批准字典配置/权威目录校验，缺少适配不可开放。

Accept/RequestSupplement/Withdraw/CloseDuplicate/Decide及追加证据均使用expectedVersion作CAS。RequestSupplement由运营指定USER/MERCHANT、非空原因、未来deadline。SubmitEvidence文本/图片至少一项；商家意见带10～500字说明及既有四类意见，不是终局授权。用户诉求金额不是出款依据。敏感文字内容安全通过后加密入卷。

Receipt固定commandId/orderId/afterSaleId/status/version/occurredAt及本次batch/supplement/decision/refund引用，可空字段按实际命令；同请求成功重放保持原内容/时点，仍验证当前读取权和原动作当前权限，包括handle/decide及商家OWNER。输入完整性和同key异参冲突仍须验证；只有持久SUCCEEDED及首回执与原迁移证明一致才走成功重放，不重新调用当前原因目录或内容审核Provider。未成功命令继续正常审核；外部审核仍在业务锁外执行，业务失败保留独立Admission参数绑定。

## 4. State / supplement timeout

| Operation | Transition |
|---|---|
| create | NONE→PENDING |
| accept | PENDING→PROCESSING |
| requestSupplement | PROCESSING→WAITING_SUPPLEMENT |
| evidence/opinion | PENDING/PROCESSING保持；指定方满足当前轮则WAITING_SUPPLEMENT→PROCESSING |
| timeout | WAITING_SUPPLEMENT→PROCESSING；无自动退款/驳回 |
| withdraw | 活动态→WITHDRAWN |
| closeDuplicate | PENDING→CLOSED，仅P4重复问题 |
| decide | PROCESSING→RESOLVED，唯一不可变决定 |
| verification | 当前活动UNVERIFIED_POST_START→INVALIDATED，与真实核销同事务 |

满足当前补证轮必须now<deadline；达到截止即使worker未运行也不能满足该轮。WAITING必须绑定当前supplementId/round；另一方追加不能满足指定方。timeout在now>=deadline执行，提前RetryAt，旧轮次/终态NOOP，损坏真实任务失败隔离。之后PROCESSING一般新证据不改写原轮超时。成功重放仍返回原回执。无补证次数固定上限、无任意固定24h。

核销先成功使WAITING_SUPPLEMENT工单失效时，同一核销事务将当前OPEN轮次置CANCELED、清空工单currentSupplementId，并把completion_verification_id绑定真实核销失效证明；不得伪造AFS completion_command_id。正常补证完成/超时/撤回仍绑定真实completion_command_id，两类完成引用互斥。旧超时任务验证原轮次、真实核销证明与任务来源后NOOP；已取消轮次不再参与到期恢复。

## 5. Public facts and transaction boundaries

ORDER `OrderAfterSaleFactsApi`负责定位、锁内真实资格与当前事实；`OrderAfterSaleCommitApi`负责bindCreated/projectTransition、acquireRefund/requirePending/commitCreated/requireCreated；`OrderAfterSaleRefundFactsApi`负责已提交退款来源。

AFS `AfterSaleCaseFactsApi.requireCurrent`核对expectedCurrentCaseId与本域NONE/活动/历史及反向孤儿；不能回调ORDER.current。`AfterSaleRefundFactsApi.requirePendingDecision/requireCreated`分别是当前事务活命令来源与已提交不可变来源。`AfterSaleEvidenceAccessApi.proveAccess`复验当前业务授权并提供case/batch/asset/owner/hash/version证明。

REFUND按订单申请/拒绝事实接口正式命名 `RefundApplicationHistoryFactsApi.readForOrder`，避免Windows上与已创建资金叶子 `RefundAfterSaleFactsApi`仅大小写不同。`RefundAfterSaleCommandApi.create`只接受case/decision/ORDER token等可信绑定，不接受客户端金额授权。

所有复合写使用同DataSource、可写READ_COMMITTED短事务；命令→共享store guard→ORDER→各Owner当前行，ADMIN/AFS/资产回调遵循业务授权先于资产锁的方向，避免OSS先锁资产再回调AFS。生产SQL本域MyBatis XML。网络/内容审核/对象读取/渠道调用不在业务锁内。

创建和状态迁移同事务写本域、ORDER投影、日志、Outbox和首回执。退款决定还必须同事务包含：ORDER CREATE_REFUND事务能力→AFS不可变授权→REFUND唯一业务退款/execution/创建事件/渠道task→AFS退款绑定及终态→ORDER绑定/CAS/消费token→决定事件/首回执。各Owner提交前校验叶子证明完整性，不反向递归。任一步失败整体回滚，独立Admission保留。不得新增AFS终裁后异步CREATE_REFUND阶段。

VERIFY先提交，旧未履约工单INVALIDATED且无退款授权；退款创建先提交，后核销禁止，包括UNKNOWN/FAILED。普通49号批准后异步建单仍合法且不受核销否决。不可把VERIFIED全局当禁退款。

历史核销的提交瞬间断言与之后不可变证明查询分离：历史查询不要求当前售后指针永远等于当时快照，但保留当时关联/失效证明。当前关系另核对，不能删孤儿检测。VER本身成功重放走原receipt，始终保留旧回执/真实verifiedAt。

## 6. Decisions / money

FULL_REFUND必须等于正常原渠道实付；PARTIAL_REFUND仅运营且0<amount<实付；REJECT/RESERVICE/OTHER金额必须null、无退款。最终状态统一RESOLVED；旧OTHER_NON_REFUND只可经明确历史映射为OTHER。金额和类型来自真实最终决定/执行绑定，不由事件、requestedAmount或ratio猜测。ratio=amount/paid按现有DECIMAL(10,6)六位HALF_UP派生，0或1的舍入不改变PARTIAL。

AFS执行来源统一AFTERSALE_DECISION；sourceBizId=caseId、sourceDecisionId=decisionId、sourceEventId/lateEventId为空；refund_order.aftersale_id是真实case、refund_application_id为空，initiator为真实运营。旧迟到/拒单/普通来源仅FULL且=其原真实实付。SQL来源×类型×金额联合CHECK与Java同验，显式防NULL三值放过。

PAYMENT首发独立核实REFUND执行、AFS决定、ORDER提交证明、PAYMENT自己原付款及真实资金资格；MAY_HAVE_SENT先持久，后续只原refundNo查询，不换号盲重发。退款创建不等于成功，UNKNOWN不释放；真正成功后ORDER消费claim/金额类型/成功证明与SCH原预约释放同事务，保留核销历史。只有ORDER计算DisplayOrderStatus。

资金公共边界 `RefundFundingEligibilityFactsApi.requireForDecision/requireForFirstSend` 使用FundingCheck/FundingEvidence，绑定真实来源身份、case/decision/command/金额/币种及权威evidenceId、版本、policy、checkedAt/validUntil、并发有效性引用。PAID或无分账行不证明可退。权威Owner/Provider及查验至出款间保证尚依OD-W0-001；缺失/UNKNOWN/过期/错绑定不得终裁或首发，不注册生产常量替身。已建单而首发资格失败保留原承诺并记录待核查，恢复仅原来源；MAY_HAVE_SENT后不能用后来资格变化抹除原号查询/真实成功。

## 7. Private evidence / current authorization

AFTERSALE_EVIDENCE是独立私有用途，沿真实上传、扫描、图片规范化和不可变对象hash/version，不混用商家applicationId/revisionId。入卷前和最终事务重验owner/purpose/READY/hash/version，证据批次不可覆盖，描述/原因/参数/回执密文，Outbox无自由文本或裸URL。图片每次申请/补证最多6张，不支持任意文件。

本人、本店当前OWNER和获权运营按真实工单关系读双方入卷证据；用户手机号/内部敏感备注/渠道数据不默认公开。AFS typed read grant独立绑定case/batch/asset、当前audience/session/generation/actor、用途和对象版本，签发与消费均当前授权、隔离检查和审计；无永久URL。下载网络在业务事务外，返回前再次校验当前授权与资产事实，保证撤权/隔离不能继续暴露内容。

ADMIN动作aftersale.read/handle/decide注册真实后端入口和集合范围，SUPER_ADMIN按既有全V1权限但不绕过交易规则。当前ADMIN_WEB principal、USER真实MINIAPP会话和OWNER店归属由boot组合；同请求回放亦复验。首切片缺STAFF及冻结特殊写权限不以假authority补齐。

AFS的Authority.requireAdmin返回AdminAuthority(authzVersion,scopeVersion)：前者来自当前ADMIN授权，后者来自当前MER资源范围。终裁将这两个当前版本写入aftersale_decision及其加密DecisionEnvelope，不使用工单创建时的scopeVersion代替终裁时范围。提交前重新授权并要求两版本与本次核准一致，变化则整笔回滚；工单创建范围快照继续保留用于来源核对。

## 8. Events / tasks / deployment

Created/Resolved/Invalidated沿Event08既有v1字段；核销失效事件同核销事务AFS唯一产生。新增AfterSaleProgressChangedEvent.v1字段固定afterSaleId/orderId/action/fromStatus/toStatus/caseVersion/supplementRequestId/targetParty/deadline/occurredAt，可空项仍出现；动作ACCEPTED/SUPPLEMENT_REQUESTED/EVIDENCE_ADDED/MERCHANT_OPINION_ADDED/SUPPLEMENT_TIMED_OUT/WITHDRAWN/DUPLICATE_CLOSED。事件不提供退款授权，可靠意图不等于实际通知送达。

RefundOrderCreatedEvent.v1/RefundSucceededEvent.v1严格字段不加case/decision，新增认可来源AFTERSALE_DECISION及FULL/PARTIAL。旧消费者先通用结构/来源分派再校验本来源FULL；未知来源失败。新ORDER_AFTERSALE_REFUND只消费本来源。上线必须先SQL50及所有消费者兼容，后启用AFS生产。

任务AFTERSALE_SUPPLEMENT_TIMEOUT:{supplementId}:0绑定afterSaleId/supplementRequestId/storeId/expectedDeadline；AFTERSALE_REFUND_SUBMIT:{refundId}:0和AFTERSALE_REFUND_CHANNEL_QUERY:{refundId}:0绑定refundOrderId/storeId及现有PAYMENT原号。真实任务来源/元数据核验，缺失或终态任务按原key耐久恢复，不抢RUNNING租约、不覆盖冲突载荷。无AFS终裁后CREATE任务。

SQL50为显式增量，非启动DDL；历史有不可证实来源必须预检阻止，不清空历史或猜测回填。MySQL DDL非全事务。回退关闭新准入但保留已提交证据/退款/任务并查询原号；产生新来源事件后不能退回只理解旧来源的消费者。验收以真实API工单、隔离MySQL/Redis、双顺序并发、每持久点回滚、权限/证据隔离及旧来源回归为准，不以seed或test-only资金资格宣称生产闭环。


实现补充：CaseView 回显当前补证轮次的 supplementReason（解密后仅已获权参与方可读），让被要求补证方知道所需内容。TASK 通过核心 TaskInvocation 仅在真实 worker dispatch 内建立只读租约上下文；单独构造 SYSTEM 命令不能代替来源证明。开关 pet.aftersale.enabled/refund.enabled/worker.enabled/http.enabled 均默认 false；非出款HTTP表面按[Contract51](51-AfterSale-Http-Contract-v0.1.md)实现并保持默认关闭，公开FULL/PARTIAL始终拒绝；资金开关关闭时即使安装 provider 也不得新做资金裁决。生产必须显式提供审核器、原因目录、密钥及资金权威，仓库不提供恒真替身。
