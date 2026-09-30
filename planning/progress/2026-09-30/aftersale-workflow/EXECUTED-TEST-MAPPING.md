# AFS 实现范围与验收方法映射

日期：2026-09-30；角色：独立 QA。对应 [历史矩阵](ACCEPTANCE-MATRIX.md) AFS-01–37。P1–P4、A1–A3 已获批准；规则以 [正式 Contract50](../../../../docs/04-api/50-AfterSale-Workflow-Contract-v0.1.md)、SSOT §40 和批准记录为准。本文件审计当前代码能证明什么，不重新决定产品规则，不把历史规划的全部要求视为已实现或已验收。

当前执行事实见主执行者维护的 [actual-summary.json](actual-summary.json) / [IMPLEMENTATION.md](IMPLEMENTATION.md)：新增81项已在增量批次和修复复测中通过，包含后补的真实退款worker恢复2项；不是单次同head全量。下面的首轮及“待最终报告”措辞保留为独立QA编写映射时的快照，结果统一由上述实际报告覆盖；矩阵中尚无对应测试的分支继续未验，不因同一行其他用例通过而自动补齐。

## 1. 执行快照与阅读约定

主执行者已报告首轮 `afs-complete-1` 实跑：Money 12（9 pass、3 error），Private 4 pass，Supplement 3 pass，Workflow 17 pass，Config 23 pass，units 14 pass。三项 Money error 涉及 PARTIAL 成功事实仍按全额比较，修复后需要复测；该首轮结果不能证明增强后的源码已通过。本文未自行运行 Maven，也未重新核实首轮每份 XML，因此这里只引用主执行者运行快照，不生成逐行 PASS。

当前源码清点为 Workflow 21 个 `@Test`、Supplement 4、Money 12、Private 4、SourceIntegrity 1；循环中的业务分支不另冒充 JUnit test 数。Config 参数化实际数量须看报告，units 也以实际运行类和报告为准。新增/增强的时间、P4、竞争、Provider 重放、实时 scope、核销取消补证与来源损坏验收仍待本轮最终证据。旧普通退款、迟到支付、拒单退款等完整回归当前尚未运行，不能由首轮 AFS 结果代替。

- **直接覆盖**：指定方法直接构造本行的核心业务和结果断言；只表示代码覆盖关系，不表示已执行通过。仍需阅读边界列，不能扩大到线上依赖。
- **部分覆盖**：只有本行的一部分分支、领域或故障得到直接断言；剩余分支明确列出。
- **未执行**：目前没有对应 AFS 集成验收，或只找到旧基线待回归方法；不得借已有模块单元测试替代 AFS 全链路。
- 每行“当前结果”统一为 **待最终报告**；对没有测试的分支继续保留 **NOT_EXECUTED**，即便同一行的其他方法通过也不自动补齐。

## 2. 测试标识

下表的方法使用 `别名#完整方法名`，别名链接到真实源码。旧基线方法只作为回归定位，不代表本批已运行。

| 别名 | 测试源码 |
|---|---|
| W | [AfterSaleWorkflowAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSaleWorkflowAcceptanceTest.java) |
| M | [AfterSaleMoneyAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSaleMoneyAcceptanceTest.java) |
| P | [AfterSalePrivateEvidenceAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSalePrivateEvidenceAcceptanceTest.java) |
| S | [AfterSaleSupplementAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSaleSupplementAcceptanceTest.java) |
| I | [AfterSaleSourceIntegrityAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSaleSourceIntegrityAcceptanceTest.java) |
| C | [AfterSaleWorkflowConfigurationTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/config/AfterSaleWorkflowConfigurationTest.java) |
| B | [AfterSaleBoundaryTest](../../../../backend/pet-aftersale-biz/src/test/java/com/petplatform/aftersale/biz/application/AfterSaleBoundaryTest.java) |
| F | [RefundFundingEvidenceChecksTest](../../../../backend/pet-payment-biz/src/test/java/com/petplatform/payment/biz/application/RefundFundingEvidenceChecksTest.java) |
| T | [AfterSaleRefundTaskFamilyTest](../../../../backend/pet-refund-biz/src/test/java/com/petplatform/refund/biz/application/AfterSaleRefundTaskFamilyTest.java) |
| R | [RefundApplicationAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/RefundApplicationAcceptanceTest.java) |
| V | [VerificationCompletionAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/VerificationCompletionAcceptanceTest.java) |
| L | [LateRefundAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/LateRefundAcceptanceTest.java) |
| A | [AdminAuthorizationCurrentReadTest](../../../../backend/pet-admin-biz/src/test/java/com/petplatform/admin/biz/auth/AdminAuthorizationCurrentReadTest.java) |
| E | [AfterSaleContractTest](../../../../e2e/test_aftersale_contract.py)，仅 OpenAPI 静态断言 |

## 3. 37 行逐项映射

| 历史 ID | 覆盖分类 | 当前真实测试方法 | 能证明的范围与未覆盖部分 |
|---|---|---|---|
| AFS-01 | 直接覆盖 | `W#realRejectionCreatesOnePrivateCaseWithoutRefundOrReservationRelease`；`M#realVerificationWinsAndOldUnfulfilledCaseCannotRefund` | 真实拒绝/上传/AFS 创建及无退款、无预约释放；实际后续核销成功。各归属篡改排列不在此方法内，见 28。 |
| AFS-02 | 部分覆盖 | `W#verifiedOrderNeedsNoPriorMerchantRejection`；`W#createRacingRealVerificationPreservesTheWinningSourceStage` | 真实核销后创建、无普通拒绝前置、竞争来源阶段；没有专门伪造裸 COMPLETED/verifiedAt 后尝试创建的 AFS 负例。 |
| AFS-03 | 部分覆盖 | `W#bothSevenDayWindowsIncludeDeadlineAndExcludeNextMillisecond`；`W#withdrawalFromEachActiveStatePreservesOriginalWindowAndCreatesNewId` | 两种真实 anchor 已区分；deadline−1ms/等号/+1ms、撤回与重建推进时间且期限不变。未逐测 t0−1ms/t0，以及锁等待跨截止的当前时间重读。增强版待执行报告。 |
| AFS-04 | 部分覆盖 | `W#unverifiedWithoutRealRejectionAndForeignBuyerCannotCreate`；`W#oneActiveCaseAndOrdinaryApplicationAreMutuallyExclusiveInBothDirections` | 无拒绝、非本人、普通 PENDING 与活动 AFS 互斥；未覆盖全部未到预约、跨商家门店、取消和迟到支付来源组合。 |
| AFS-05 | 部分覆盖 | `W#supplementDeadlineDoesNotDependOnWhetherWorkerAlreadyRan`；`S#fabricatedSystemCommandCannotStandInForDurableWorkerDelivery`；`S#completedSupplementMakesOldTaskAnIdempotentNoOp`；`S#realVerificationCancelsOpenSupplementAndNeverResurrectsItsTimeout` | 用户补证、截止三点、真实 TASK、旧轮任务 NOOP、核销取消轮并绑定真实 proof；不是每个状态/角色/旧版本的全组合，商家补证尚无专门集成方法。 |
| AFS-06 | 部分覆盖 | `W#allNonRefundFinalDecisionsAreIrreversibleAndNeverCreateMoney`；`W#newProblemWithSameCategoryCanBeAcceptedAgainstServerHistory`；`W#finalHistoryRequiresAssessmentAndDuplicateClosureDoesNotCreateAnotherDecision`；`W#competingTerminalDecisionsAtTheSameVersionCommitOnlyOneOutcome` | 首次终局及 P4 两条真实非退款 final、过期 finalSetVersion、重复 CLOSED 无新 decision；商家冒充终裁、另一运营等角色组合未穷尽。 |
| AFS-07 | 部分覆盖 | `M#realVerificationWinsAndOldUnfulfilledCaseCannotRefund`；`W#createRacingRealVerificationPreservesTheWinningSourceStage`；`W#withdrawnOrInvalidatedHistoryCannotBeForgedAsFinalDecision` | 新 VERIFIED 工单后重放旧核销、调用历史 ORDER/AFS requireCommitted；旧创建回执不改指针。没有逐一重放所有旧受理/旧决定组合。 |
| AFS-08 | 部分覆盖 | `M#partialAndFullBindExactOriginalPaymentAndOnlyOneBusinessRefund`；`M#invalidPartialAmountsNeverBecomeARefund`；`B#arbitraryPrecisionMoneyCannotReachPersistenceOrRefunds`；`F#changedAmountCannotReuseEvidenceAndPartialBoundsAreStrict` | 真订单金额、FULL/PARTIAL、0/负数/等本金/超额/超精度/超 DECIMAL；缺失金额等排列未全部直接验。SQL CHECK 存在不等于单独跑过数据库约束绕过测试。 |
| AFS-09 | 直接覆盖 | `W#allNonRefundFinalDecisionsAreIrreversibleAndNeverCreateMoney`；`B#nonRefundDecisionCannotSmuggleAnAmount` | 三种非退款终局无 refund_order、不可二次退款决定，非退款不能夹带金额；RESERVICE 是记录处置，不交付新服务订单/预约流程。 |
| AFS-10 | 部分覆盖 | `M#partialAndFullBindExactOriginalPaymentAndOnlyOneBusinessRefund`；`M#fullAndPartialSuccessProjectExactAmountOnceAndOnlyThenReleaseReservation` | 退款建单后禁止新 AFS、两来源成功金额/预约释放/核销历史保留；未直接验证成功后再次退款全排列，评价资格/30 天/星级消费者不在此切片。 |
| AFS-11 | 部分覆盖 | `W#realUserAndAdminRevocationBlocksOldReceiptReplay`；`W#frozenBuyerRetainsCaseReadingButCannotWriteOrReplayCreate`；`W#unverifiedWithoutRealRejectionAndForeignBuyerCannotCreate` | 真登录/session，退出重放拒绝、冻结只读、错误买家；伪 operatorId 与另一主体复用同 requestId 等完整组合未覆盖。 |
| AFS-12 | 部分覆盖 | `W#retainingReadPermissionDoesNotAuthorizeOldWriteReceiptReplay`；`W#successfulReplaySurvivesProviderOutageButRechecksPayloadAndCurrentAuthority`；`P#revokedSessionCannotIssueReplayOrConsumeOutstandingGrant` | 保留 read 撤销 handle/decide、当前会话、实时 MER scope 持久化；AFS 的 CITY/MERCHANT 范围撤销、generation 变更逐动作矩阵尚未验。 |
| AFS-13 | 未执行（AFS 集成） | 旧基线 `A#outerRepeatableReadSnapshotCannotHideCommittedActionRemoval`、`A#outerRepeatableReadSnapshotCannotHideCommittedScopeRemoval` | 这些只定位 ADMIN 自身证明。AFS 入口通过后/等待期间/提交前并发撤权与 RR 外层快照未做独立集成测试，不能用顺序撤权测试替代。 |
| AFS-14 | 未执行（AFS 商家意见） | 无对应 AFS 意见/商家补证方法；`W#realRejectionCreatesOnePrivateCaseWithoutRefundOrReservationRelease` 的 OWNER 只执行普通拒绝前置 | 真 OWNER 普通操作不是 AFS 商家意见权限证明。STAFF、跨商家、OWNER 变更、OFFLINE/FROZEN 的 AFS 意见/补证矩阵保持 NOT_EXECUTED。 |
| AFS-15 | 部分覆盖 | `W#evidenceMustBeOwnedReadyAndAftersalePurpose`；`P#assetIdAloneDoesNotProveCaseEvidenceMembership`；`B#duplicateAssetIdsAreRejectedBeforeAdmission` | 真上传READY、他人/错purpose、扫描依赖不可用、未绑定资产拒绝；资产隔离及不可变摘要/版本损坏、混合批次部分绑定回滚未全面验。 |
| AFS-16 | 部分覆盖 | `P#tokenCannotBeLentToAnotherUserOrOperatorAndOriginalHolderRetainsIt`；`P#assetIdAloneDoesNotProveCaseEvidenceMembership`；`W#duplicateReferenceMustBelongToTheSameRealOrder` | 买家/运营 grant 转借、证据成员关系、同 schema 真跨单 duplicate 引用拒绝；不是全部角色详情/证据可见矩阵，不覆盖集合列表过滤或其他租户全排列。 |
| AFS-17 | 部分覆盖 | `P#buyerAndCurrentOperatorReceiveOnlySingleUseWatermarkedContent`；`P#revokedSessionCannotIssueReplayOrConsumeOutstandingGrant`；`P#tokenCannotBeLentToAnotherUserOrOperatorAndOriginalHolderRetainsIt` | 单次水印内容、同请求原授权、转借/退出后重放和消费拒绝；未模拟 TTL 等号/过期、generation、证据版本变化、审计每状态及消费 commit ACK。 |
| AFS-18 | 部分覆盖 | `W#realRejectionCreatesOnePrivateCaseWithoutRefundOrReservationRelease`；`W#evidenceMustBeOwnedReadyAndAftersalePurpose`；`W#successfulReplaySurvivesProviderOutageButRechecksPayloadAndCurrentAuthority`；`B#purposeAndIntegrityBindProtectedValues`；`E#test_evidence_references_are_public_ids_and_not_object_urls` | Outbox 不含创建描述、加密 purpose/完整性、Provider 失败与成功回执重放、契约公开ID；未穷举日志/异常/全部敏感字段泄漏，未运行 HTTP cache header 或真对象URL验收。 |
| AFS-19 | 部分覆盖 | `W#realRejectionCreatesOnePrivateCaseWithoutRefundOrReservationRelease`；`W#internalRequestIdsPreserve512Utf8BytesCaseAndTrailingSpace`；`W#successfulReplaySurvivesProviderOutageButRechecksPayloadAndCurrentAuthority`；`F#amountCanonicalizationPreservesSemanticEquality` | 创建/决定异参、证据重放、512字节大小写/尾空格、金额绑定；所有 namespace/actor/scope/证据排序/金额表达的组合未穷尽，unit 规范化不等于每个业务命令验收。 |
| AFS-20 | 部分覆盖 | `M#committedAckLossRecoversSameFinalReceiptAndSingleRefund`；`M#everyRefundCommitPointRollsBackAndKeepsRequestParameterBinding` | 真 JDBC commit 后丢失终裁 ACK、原回执/单退款恢复、业务失败 Admission 绑定保留。不是创建/受理/补证/每阶段 ACK 覆盖，也没有在独立新 JVM 重启逐条恢复。 |
| AFS-21 | 部分覆盖 | `M#everyRefundCommitPointRollsBackAndKeepsRequestParameterBinding`；`M#fullAndPartialSuccessProjectExactAmountOnceAndOnlyThenReleaseReservation` | fixture 枚举17个退款终裁联合事务写点及预约投影失败；断言 PROCESSING、零决定/refund/execution和同参恢复。未逐事务覆盖创建/受理/证据/补证/Admission/核销，未对每点穷尽全部日志/任务/事件/指针。方法名 every 不代表历史§5全表完整。 |
| AFS-22 | 部分覆盖 | `W#differentCreateKeysCannotCommitTwoActiveCases`；`W#competingTerminalDecisionsAtTheSameVersionCommitOnlyOneOutcome` | barrier 真并发不同创建 key、REJECT 与 PARTIAL 同 version 竞争；只有一个终局/事件且退款证明随赢家一致。AFS 同 key 并发和受理↔补证竞争仍未执行。 |
| AFS-23 | 直接覆盖 | `M#realVerificationWinsAndOldUnfulfilledCaseCannotRefund`；`S#realVerificationCancelsOpenSupplementAndNeverResurrectsItsTimeout` | 真实核销先成功、旧未履约AFS失效无退款、新来源及历史证明；WAITING 轮 CANCELED 绑定真实 verification proof，旧任务不复活。 |
| AFS-24 | 直接覆盖 | `M#realRefundCreationWinsBeforeAnyChannelCallAndBlocksVerification` | 真退款单在渠道调用前阻核销，无 verification_record，预约仍 CONFIRMED。已有退款单是门槛，不把渠道成功当作门槛；UNKNOWN/FAILED 的逐状态重复测试未另执行。 |
| AFS-25 | 直接覆盖（批准原子方案） | `M#independentKeysForRefundAndRealVerificationHaveOnlyOneWinner`；`W#createRacingRealVerificationPreservesTheWinningSourceStage`；`M#everyRefundCommitPointRollsBackAndKeepsRequestParameterBinding` | 决定+建单按批准方案同事务，VERIFY↔退款单一赢家；Create↔VERIFY 两操作可都成功，区别合法 VERIFIED 新来源/旧来源 INVALIDATED。不存在获批的“决定已提交、建单未提交”拆分窗口，不能据历史规划增造该流程。 |
| AFS-26 | 部分覆盖 | `W#oneActiveCaseAndOrdinaryApplicationAreMutuallyExclusiveInBothDirections`；`M#historicalWithdrawnCaseDoesNotCancelOrdinaryApprovalAfterRealVerification` | 普通 PENDING 排斥AFS；普通APPROVED未建单→真实VERIFY→原承诺仍可建FULL。未跑普通timeout建单与AFS多方barrier竞争；普通AUTO_APPROVED分支不能用APPROVED代替。 |
| AFS-27 | 部分覆盖 | `M#everyRefundCommitPointRollsBackAndKeepsRequestParameterBinding`；旧基线 `V#verifyCapabilityCannotEscapeOrCommitAnIsolatedAftersale`、`V#readonlyWrongSourceAndForgedTokensCannotAuthorizeAnAftersale` | 新联合事务有回滚验证；同源RC/无guard/错DataSource/只读/跨事务token的旧组件测试尚待当前回归，且不能全部等同新AFS工作流API能力负例。 |
| AFS-28 | 部分覆盖 | `I#eachIndependentSourceProofMustAgreeBeforeAnyFirstSend`；`S#fabricatedSystemCommandCannotStandInForDurableWorkerDelivery`；`M#firstSendRechecksFundingAndNeverDispatchesUnknownAuthority` | 三owner真实来源分别损坏AFS决定密文、ORDER金额、REFUND proof金额→零dispatch/funding proof/渠道；精确恢复原值后原退款成功。不是每个身份/付款/核销/拒绝/任务字段全部篡改；坏来源诊断扫描见30。 |
| AFS-29 | 部分覆盖 | `S#dueTaskRecoversAfterLossAndLateEvidenceDoesNotRewriteExpiredRound`；`S#realVerificationCancelsOpenSupplementAndNeverResurrectsItsTimeout`；`T#aftersaleRecoveryNeverSelectsLegacyTaskFamily` | 真补证任务 MISSING/DEAD恢复和已取消轮不恢复、资金任务family单元检查；AFS退款任务DEAD/CANCELED/SUCCEEDED、RUNNING租约/fence/attempt历史/进程重启全链未全面验。 |
| AFS-30 | 未执行（新 AFS 批扫描） | 旧基线 `R#corruptCandidateDoesNotStarveLaterRealApprovedApplication`、`R#corruptCanonicalAndTaskMetadataProduceDurableIssuesAndResolveAfterRepair`、`R#unavailableOrderStorageIsNotMisclassifiedAsCorruptBusinessProof` | 原普通退款实现有方法可回归，但不能证明新增AFS扫描首坏后好、修复重扫OPEN→RESOLVED、数据库整体故障传播。SourceIntegrity只测首发拒绝，不是扫描隔离。 |
| AFS-31 | 部分覆盖 | `M#partialChannelAckLossQueriesSameNumberWithoutNewFundingOrSecondSend`；`M#firstSendRechecksFundingAndNeverDispatchesUnknownAuthority` | 受控渠道接受后响应丢失→UNKNOWN→原号query，不再取新funding、不二次submit；未覆盖实际进程/worker lease丢失、支付状态变化、渠道验签全协议和全部金额异常。 |
| AFS-32 | 部分覆盖 | `M#fullAndPartialSuccessProjectExactAmountOnceAndOnlyThenReleaseReservation` | 两来源×FULL/PARTIAL成功，重复消费、预约UPDATE故障后回滚重试，精确金额/保留核销历史；没逐点注入全部claim/投影写故障及所有乱序排列，DisplayOrderStatus未独立断言。首轮该方法 error 后修复待复测。 |
| AFS-33 | 部分覆盖；旧回归未执行 | `M#historicalWithdrawnCaseDoesNotCancelOrdinaryApprovalAfterRealVerification`；旧基线 `R#rejectionThenNewApplicationHasFreshDeadlineAndOldRequestsCannotChangeIt`、`R#earlyTimeoutRetriesAndExactDeadlineExcludesMerchant`、`R#bothOrdinarySourcesUseOriginalChannelAndOnlyFinalSuccessReleases` | 新AFS→WITHDRAWN历史指针不取消普通APPROVED真实承诺有直接测试；普通完整24h/恢复/重复申请/普通渠道套件尚待当前修改下回归，不能沿用PR96绿色为本批结果。 |
| AFS-34 | 未执行（旧资金回归） | `L#signedLatePaymentFixtureKeepsTimeoutClosureAndActualPaidAmount`、`L#eventPayloadAloneCannotAuthorizeWrongAmountOrMissingProof`；MerchantOrderAcceptanceTest整类待回归 | 本批新AFS成功测试仅检查旧消费者安全跳过AFS事件，不能证明迟到支付与拒单来源仍可正确创建/执行全额退款。 |
| AFS-35 | 部分覆盖；旧回归未执行 | `M#realVerificationWinsAndOldUnfulfilledCaseCannotRefund`；旧基线 `V#completionCommitAckLossReturnsOriginalReceiptOnRestart`、`V#confirmationVersionCrossStoreAndRevocationDoNotCountCodeFailures`、`V#autoConfirmationAndRescheduledPickupCanComplete` | 真实AFS后的历史核销证明有直接覆盖；原核销/凭证/改期风险计数、迁移等完整旧套件尚待当前回归。 |
| AFS-36 | 部分覆盖 | `C#defaultsDoNotInstantiateWorkflowFundingOrWorkers`、`C#httpCannotBeEnabledEvenWithCompleteInternalDependencies`、`C#missingTrustedSourceHasNoPermissiveReplacement`、`C#openingRefundWithoutAuthoritativeFundingProviderFailsStartup`、`C#offlineA1StartsWithoutFundingAndNeverCallsExternalOrFinancialDependencies`、`C#explicitTestProviderEnablesOnlyInternalCompositionWithoutImplicitExecution` | 默认关闭、缺依赖/缺资金provider失败关闭、内部装配。fixture实际SQL50加载不等于历史脏数据迁移全验；架构/MyBatis/契约检查结果另取最终命令报告，不能由config启动测试替代。 |
| AFS-37 | 部分覆盖 | `M#fullAndPartialSuccessProjectExactAmountOnceAndOnlyThenReleaseReservation`；`T#unknownFamilyDoesNotFallBackToLatePayment`；`E#test_only_approved_final_decisions_are_public` | 旧退款消费者跳过AFS、未知资金任务family拒绝、契约枚举。通知送达/站内消息、评价、券、积分消费者，以及HTTP/前端/真机均未交付或未在本切片验收；Outbox存在不等于这些完成。 |

## 4. 真实程度及无法外推的结论

共享 fixture 使用随机 MySQL schema 和独立 Redis 前缀；C/ADMIN 真实登录/session/RBAC 持久链、真实普通 apply/REJECT、真实凭证/核销、AFS命令、ORDER/REFUND证明、真实TASK claim/dispatch及私有素材应用协议。正向 AFS 工单/决定/退款证明不靠 seed；坏来源用既有真实 proof 保存原值→破坏→拒绝→原值恢复，不补造新正向证明。基础静态商家资料/排期、受控时间和故障注入仍明确属于测试设施。

微信上游、资金资格 provider、支付退款渠道、对象存储、扫描/原因审核/加密配置是受控测试配置。其中资金证据显式标记 QA_ONLY/TEST_ONLY；资金状态真实权威 owner 的生产来源缺口仍须按 OD-W0-001 治理，默认关闭和缺 provider 拒启用只能证明 fail-closed，不能宣称已接真实结算/可退资金来源。内存对象存储和受控渠道不能写成生产OSS或真实出款验证。

本轮没有 AFS HTTP adapter 的实际请求验收；OpenAPI 四个 Python 方法只校验契约形状。配置测试刻意拒绝启用 HTTP，不等于用户端售后页面或运营页面交付。静态方法映射也不证明线上的隐私日志、响应头、权限角色全矩阵或下游券/积分/通知完成。

## 5. 后续报告更新规则

主执行者完成当前运行后，以实际报告写 `actual-summary.json` / `IMPLEMENTATION.md`，记录源码版本、所选方法、tests/failures/errors/skipped及命令；失败修复后的方法必须有新结果。尚无测试的方法分支继续 NOT_EXECUTED，不能因同类其他方法通过而改成 PASS。特别保留：全阶段逐持久点、全命令commit ACK、AFS当前权限并发、商家意见/补证、批恢复隔离、旧资金完整回归和外部依赖/下游消费者限制。

本文维护者仅做文档与源码映射检查，没有运行 Maven、数据库或功能用例，没有修改测试代码。该记录不能代替实际执行验收。
