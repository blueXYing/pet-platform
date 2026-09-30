# AFS 资金验收映射：Money 12 + SourceIntegrity 1 + Recovery 2

日期：2026-09-30。范围仅为本页列出的 15 个测试方法；其他 QA 套件的覆盖及结果由 root 总报告合并。本页不把关联到矩阵条目解释为该条全部通过，不运行 Maven，不扩展生产代码。

## 1. 实际执行状态

root 初次完整 Money 报告为 `Tests run: 12, Failures: 0, Errors: 3, Skipped: 0`，用时 293.7 秒，日志 `afs-complete-1.log`。9 项通过，3 项在 `PaymentRefundResultFactsApiImpl.requireVerified` 遗留全额金额条件处报错。修复将结果边界改为 `0 < refundAmount <= originalPaidAmount`，渠道回执仍精确匹配 dispatch 金额。初次逐方法结果已核对 XML。

root 后续已复测上述 3 项：`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`，用时 79.36 秒；SourceIntegrity 1 项亦通过，用时 17.68 秒。已核对当前 [Money 文本报告](../../../../backend/pet-boot/target/surefire-reports/com.petplatform.boot.booking.AfterSaleMoneyAcceptanceTest.txt)、[Money XML](../../../../backend/pet-boot/target/surefire-reports/TEST-com.petplatform.boot.booking.AfterSaleMoneyAcceptanceTest.xml) 和 [SourceIntegrity 报告](../../../../backend/pet-boot/target/surefire-reports/com.petplatform.boot.booking.AfterSaleSourceIntegrityAcceptanceTest.txt)。因此 Money 12 项有初次 9 PASS + 修复复测 3 PASS 的组合证据，不能表述为修复后整套 12 项同次复跑。Recovery 2 项已由 root 实跑通过：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，用时 33.26 秒；已核对 [Recovery Surefire 报告](../../../../backend/pet-boot/target/surefire-reports/com.petplatform.boot.booking.AfterSaleRefundRecoveryAcceptanceTest.txt)，root 的 `afs-recovery-regression.log` 为 BUILD SUCCESS。同批 PAYMENT 18、ADMIN 12、Architecture 22 全通过，仅由 root 总报告计数，不加入本页 15 项。root 同时报告 Supplement 4 PASS，但不计入本页资金用例数。target 报告会被后续执行覆盖，正式报告应由 root 保存对应运行证据。

测试源码：[AfterSaleMoneyAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSaleMoneyAcceptanceTest.java)、[AfterSaleSourceIntegrityAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSaleSourceIntegrityAcceptanceTest.java)、[AfterSaleRefundRecoveryAcceptanceTest](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSaleRefundRecoveryAcceptanceTest.java)。验收基准：[ACCEPTANCE-MATRIX](ACCEPTANCE-MATRIX.md)。

## 2. 逐方法覆盖矩阵

| 方法 | 对应矩阵 | 实际操作与断言边界 | 状态 |
|---|---|---|---|
| `unknownFundingCannotCommitDecisionRefundOrCloseTheCase` | AFS-08、21、28、36；A2 | 真实已核销订单→工单→受理；资金公共事实依赖不可用时 FULL 终裁被拒，工单仍 PROCESSING，决定、refund_order、execution 均为零。这里的 unknown 是 provider unavailable，不是所有 settlement/eligibility 枚举的集成遍历。 | 已通过 |
| `partialAndFullBindExactOriginalPaymentAndOnlyOneBusinessRefund` | AFS-08、10、24 | 分别真实创建 FULL 128.00、PARTIAL 32.00，核对 RESOLVED、来源 AFTERSALE_DECISION、类型、金额、case 关联、唯一 refund/execution/Created 事件；预约仍 CONFIRMED；建单后新 AFS 被拒。两组均从 VERIFIED 来源开始，未执行渠道成功后的第二次退款命令。 | 已通过 |
| `invalidPartialAmountsNeverBecomeARefund` | AFS-08、21 | 逐个测试 0.00、-0.01、128.00、128.01、0.001、10000000000000000.00；均不得产生决定和退款，工单保留 PROCESSING。覆盖零、负数、等于/超本金、小数超精度、DECIMAL 整数位溢出；本方法没有 null 金额或 FULL 错金额用例。 | 已通过 |
| `realVerificationWinsAndOldUnfulfilledCaseCannotRefund` | AFS-07、23、25、33、35 | 真实普通拒绝→未履约 AFS 受理→核销先成功，旧工单 INVALIDATED 且不可终裁退款；新 VERIFIED 工单可创建；重放原核销首回执不覆盖新指针；在真实事务/guard 内分别读取 ORDER、AFS 历史核销证明，仍绑定旧 INVALIDATED 工单。没有把旧工单 task 数与渠道计数作为独立 oracle。 | 已通过 |
| `realRefundCreationWinsBeforeAnyChannelCallAndBlocksVerification` | AFS-24、25 | 真实普通拒绝→先发核销码→AFS PARTIAL 终裁和建单先提交→核销拒绝；verification_record 为零，refund_order 为一，预约仍 CONFIRMED。覆盖 CREATED、渠道尚未执行；本方法未将该 AFS 退款推进 UNKNOWN/FAILED 后再次核销。 | 已通过 |
| `independentKeysForRefundAndRealVerificationHaveOnlyOneWinner` | AFS-22、25 | 两个独立 requestId、CountDownLatch 同时启动真实核销与 PARTIAL 决定/建单；恰一项 ApiException，verification_record + refund_order 恰为一，工单终态与赢家一致。一次双向竞争，不是多轮压力测试，也不是普通退款/AFS/核销三方竞争。 | 已通过 |
| `everyRefundCommitPointRollsBackAndKeepsRequestParameterBinding` | AFS-19、20、21、25 | 在同一真实 PROCESSING 工单的退款原子事务内逐个注入 17 个数据库写故障；每次核对 PROCESSING、零决定、零 refund_order/execution。故障移除后同 key 改金额 64.00 冲突，原参数 32.00 可恢复建单。已遍历写点，不等于已逐表断言所有指针/版本/任务/Outbox/审计行。 | 已通过 |
| `committedAckLossRecoversSameFinalReceiptAndSingleRefund` | AFS-19、20、21 | 在 beforeCompletion 后选定实际业务 JDBC commit，commit 已返回成功再抛 ACK 异常；随后同 key 恢复并重复读取相同终局回执，决定和 refund_order 均只有一行。不是提交前异常；仍在同一 fixture 内重试，没有进程重启。 | 已通过 |
| `firstSendRechecksFundingAndNeverDispatchesUnknownAuthority` | AFS-28、31、36；A2 | 真实 PARTIAL 建单后关闭资金 provider；首发拒绝、submit=0、MAY_HAVE_SENT=0、退款仍 CREATED；恢复 provider 后同一 refund 执行一次成功。 | 修复后复测通过 |
| `partialChannelAckLossQueriesSameNumberWithoutNewFundingOrSecondSend` | AFS-28、31、32 | 错误或缺失任务来源拒绝且 submit=0；正确来源 submit 后 QA 渠道抛 ACK 异常，退款 UNKNOWN、预约未释放；时间推进两分钟并关闭新资金 provider，只 query 原 refundNo，submit=1/query=1、SUCCESS、事件 PARTIAL、金额 32.00。此方法未覆盖真实 worker 重启/lease 丢失或付款状态反转。 | 修复后复测通过 |
| `fullAndPartialSuccessProjectExactAmountOnceAndOnlyThenReleaseReservation` | AFS-08、10、21、32、34、37 | VERIFIED/UNVERIFIED_POST_START × FULL/PARTIAL 四组；渠道成功后、消费者前 ORDER 退款金额仍为零且预约 CONFIRMED；注入 SCHEDULE UPDATE 故障后金额和预约回滚；恢复后重复消费成功事件只释放一次，保留 verified_at/verification_status；三个旧退款消费者遇 AFS 事件安全跳过。未断言 DisplayOrderStatus、评价或券/积分效果。 | 修复后复测通过 |
| `historicalWithdrawnCaseDoesNotCancelOrdinaryApprovalAfterRealVerification` | AFS-26、33、35 | 真实未履约 AFS 撤回→普通退款申请/同意→真实核销→普通已批退款建单；已批状态阻止新 AFS；旧 AFS 保持 WITHDRAWN；退款来源 MERCHANT_APPROVED、金额 128.00，AFS 决定为零。证明普通已批承诺不被核销/历史工单误作失效；本方法不是普通/AFS 并发，也未执行普通渠道。 | 已通过 |
| `eachIndependentSourceProofMustAgreeBeforeAnyFirstSend` | AFS-27（部分）、28；A3 | 真实 API 创建 PARTIAL 32.00 退款后，依次破坏 AFS 决定密文、把 ORDER commit 金额错绑 64.00、把 REFUND proof JSON 金额错绑 64.00；每项要求 DEPENDENCY_UNAVAILABLE、submit/query=0、全部 dispatch/funding-proof=0、无成功事件、refund 保持 CREATED 且金额/数量不变。每次 finally 恢复从真实业务保存的原值；最后同一 refund 成功一次。金额错绑是代表例，不含跨订单/买家等全字段排列，也未测恢复扫描 issue 记录。 | 已通过 |
| `missingSubmitTaskRecoversAndUnknownQueriesOriginalNumberWithFundingDisabled` | AFS-24、29、31 | 真实未履约 PARTIAL 建单后删除 submit task；真实源扫描按原 key/type/bizId/version/payload 恢复；正式 `LateRefundConfiguration.registration` 经真实 worker claim/attempt 执行，QA ACK 丢失后退款 UNKNOWN、核销拒绝；query 到期前 worker EMPTY；到期后新 runtime 不安装 funding Provider，worker 只查原号一次并完成同一退款。 | 已通过 |
| `deadSubmitRecoveryPreservesAttemptAndMissingQueryRecoversItsOriginalSchedule` | AFS-24、29、31 | 先由真实 worker 在资金依赖不可用时产生 RETRY attempt，再故障置 DEAD；恢复同 taskId、version/fence+1、保留原 attempt；真实 worker 二次投递 submit 产生 UNKNOWN 并拒核销；删除原 query task 后新 runtime 依据真实 first_query_at 恢复原计划，无 funding Provider 的 worker 只 query 原号。未使用 SQL 制造退款成功或来源证明。 | 已通过 |

17 个故障点的实际来源为 [AfterSaleFixture.refundFailurePoints](../../../../backend/pet-boot/src/test/java/com/petplatform/boot/booking/AfterSaleFixture.java)：`order_operation_guard:INSERT`、`aftersale_decision:INSERT`、`refund_order:INSERT`、`refund_execution:INSERT`、`refund_aftersale_proof:INSERT`、`async_task:INSERT`、`aftersale_decision:UPDATE`、`aftersale_case:UPDATE`、`aftersale_transition:INSERT`、`aftersale_status_log:INSERT`、`pet_order:UPDATE`、`order_aftersale_source_proof:UPDATE`、`order_aftersale_refund_commit:INSERT`、`order_operation_guard:UPDATE`、`order_status_log:INSERT`、`integration_event_outbox:INSERT`、`aftersale_command:UPDATE`。最后一个故障只针对命令状态写 SUCCEEDED，不误伤前置 Admission。

## 3. 本 15 项仍未覆盖或不能独立证明的范围

| 矩阵条目 | 本范围缺口；不得从已有断言推定完成 |
|---|---|
| AFS-08 / A2 | null 金额、FULL 错金额、生产结算状态/额度/时效/并发 fence 的端到端验证；单独的 `RefundFundingEvidenceChecksTest` 是辅助协议单测，不能替代真实资金 Provider 验收。 |
| AFS-10 | 成功之后再次执行退款/AFS 的完整两来源矩阵；评价资格、30 天期限、不计星级未实现消费者验收。建单时的新工单拒绝不等于该条全部完成。 |
| AFS-21 | 已测 17 个故障位置的核心金额/决定回滚，仍缺每位置逐表独立核对双方指针、版本、任务、Outbox、审计、令牌和预约，以及每个不同事务阶段的 commit 后 ACK 丢失。 |
| AFS-24～26 | UNKNOWN 后核销拒绝已由 Recovery 2 实跑通过；FAILED 未覆盖：当前 PAYMENT 未配置官方终局失败保证，渠道 FAILED 仍保持 QUERY_PENDING/UNKNOWN，不能 seed FAILED 冒充真实渠道路径。普通批准/超时与 AFS 创建/裁决并发、三方竞争仍非本页覆盖。协议已选择终裁+建单同一事务，不存在合法“终裁已提交但建单未提交”两阶段窗口；进程故障仍要按原子事务核验。 |
| AFS-27 | 直接调用无 guard、错误 DataSource、只读/错误隔离级别、伪/跨事务/旧 token、孤立单域提交及查询故障。SourceIntegrity 测的是既有三域来源被破坏后的首发拒绝，不是 CREATE capability 的全部边界。 |
| AFS-28 | 来源主体、order/payment/merchant/store/user/核销/拒绝/决定/task payload 的逐字段交换；本次只取密文破坏和金额错绑代表例；不覆盖自动扫描 OPEN/RESOLVED 审计。 |
| AFS-29、30 | Recovery 2 已实跑通过缺失/DEAD submit、缺失 query、保留 attempt/fence 与重建 runtime/worker。CANCELED/SUCCEEDED 终态恢复、RUNNING 不偷租约、进程级重启、扫描坏首条隔离/后续合法行继续、修复重扫、数据库整体不可用均未覆盖；其他补证或普通退款恢复用例不能替代。 |
| AFS-31 | 真实 HTTP 渠道协议与验签、错误实际金额/交易号、付款反转后的 AFS query、dispatch commit ACK 丢失、worker lease 丢失/进程重启未覆盖。Recovery 2 已实跑通过正式任务注册器与真实 worker 的 submit/query（含无资金 Provider 的新 runtime），未冒称进程崩溃或生产网络测试。 |
| AFS-32 | 任意乱序/伪造成功事件、每个成功投影持久点故障和 DisplayOrderStatus HTTP/DTO；当前消费者失败点是 SCHEDULE UPDATE，重复同一个真实成功事件，不是乱序事件全集。 |
| AFS-33、34 | Money 只覆盖普通已批历史分支以及旧消费者跳过 AFS。迟到支付、商家拒单、普通超时/恢复/新轮期限完整回归需 root 引用对应旧套件实际结果；本页未重新执行。 |
| AFS-36、37 | 生产开关/依赖完整装配、真实结算 Owner、真实出款、站内消息送达、券/积分/评价消费者、HTTP/前端/小程序真机均不能由本 15 项证明。缺权威生产资金 Provider 时真实首发保持关闭。 |

上述“未覆盖”限定于本页 15 项，root 应合并其他独立 QA 用例及其实际报告；没有报告的条目保持待验。

## 4. 控制替身与真实边界

| 层次 | 使用方式与限制 |
|---|---|
| MySQL 与事务 | 每 fixture 隔离随机真实 MySQL schema，执行 SQL50；真实公共 API、MyBatis、跨域共享门店 guard、事务、幂等、Outbox 和 async_task 写入。静态身份/目录/排期可预置；正向 AFS、退款、普通拒绝、核销和来源证明由真实命令产生。时间由受控 DataSource/MySQL 会话时钟推进；触发器和真实 commit 后异常是故障注入。 |
| 付款与订单前置 | 继承真实预约创建→支付准备→离线测试密钥签名的付款通知→真实验签/持久化→ORDER 支付投影→商家确认。证明应用支付事实链，不是拉卡拉实网支付或清结算。 |
| 用户/运营身份 | `AfterSaleIdentityFixture` 使用真实 USER/ADMIN 登录、权限查询、MySQL 会话和 Redis；仅微信身份/手机号交换受控。用 `MockHttpServletRequest` 设置实际登录所得 bearer，再调用 service API，不是 AFS HTTP 路由或前端验收。初始化商家确认的继承 fixture 含测试会话控制，不把它扩称为全链路 HTTP 身份验收。 |
| 资金资格 | `AfterSaleFixture.funding` 是明确的 `QA_ONLY_AUTHORITY`/`QA_ONLY:` 两阶段证据替身，构造请求绑定摘要、UNSETTLED/ALLOWED、短有效期和 QA fence；可用开关模拟不可用。它不代表真实结算状态，不注册为生产 Provider，不证明资金可退。 |
| 退款渠道 | `AfterSaleChannelFixture` 直接实现 `PaymentRefundChannel`，返回确定性 `VerifiedResult` 和测试摘要，或模拟 ACK 丢失；计数 submit/query、记录原 refundNo/金额。真实 PAYMENT/REFUND 协调和数据库写入保留，退款网络与退款报文密码验签不在本 15 项内；没有真实资金操作。Recovery fixture 的三参构造可不注入 funding Provider，旧两参构造保持原行为。 |
| 原因、审核、加密与资产 | 原因字典/内容审核是明确 QA provider；密文实际使用 AES，但密钥固定测试值。fixture 配置内存对象存储和受控扫描器；Money/SourceIntegrity 正向创建传空证据列表，不能据此宣称真实 OSS、附件授权或扫描验收。 |
| 任务与事件 | Money 渠道用例直接调用 `RefundExecutionService.execute`，投影直接 consume 数据库真实 Outbox 事件。新增 Recovery 用例使用正式注册器 + `AsyncTaskWorker.runOne`，实际执行数据库 claim/lease/attempt/handler/complete，限定 AFS 两退款类型，未启动后台轮询；它仍不证明 Spring 全量开关装配、事件 dispatcher 重启或外部消息送达。 |

## 5. 旧来源 FULL 校验独立复核

结论：本次成功结果边界修复未把旧业务来源开放为 PARTIAL。`PaymentRefundResultFactsApiImpl.requireVerified` 是 PAYMENT 自域签名结果事实叶子，只证明已持久 dispatch 的真实回执及精确金额；它不承担业务来源裁决。不能用该事实单独授权业务退款成功。

| 证据文件 / 方法 | 仍在生效的约束 |
|---|---|
| [RefundFundingEvidenceChecks.amount](../../../../backend/pet-payment-api/src/main/java/com/petplatform/payment/api/query/RefundFundingEvidenceChecks.java:37) | FULL 必须 amount=paid；PARTIAL 必须 0<amount<paid；金额 scale/DECIMAL 位数、类型和正数校验。 |
| [LateRefundService.verifyRow](../../../../backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/LateRefundService.java:224) | 先校验来源形状，再调用 `amount`；只要不是 AFTERSALE_DECISION，refundType 必须 FULL；同时对账 refund_order/execution 的来源、类型、金额、ratio、request key 和身份。 |
| [LateRefundService.requireForChannel](../../../../backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/LateRefundService.java:163) | 所有来源事实入口先 `verifyRow`；普通来源另读真实申请批准/建单证明。`requireSucceeded` 也先经过此入口，历史成功查询不能绕开 FULL。`origin` 分派保留迟到事件、真实拒单事件和普通决定的独立绑定。 |
| [PaymentRefundService.validateBinding](../../../../backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/application/PaymentRefundService.java:482) | PAYMENT 首发 prepare/preflight 再次调用 `amount` 并显式要求非 AFS 为 FULL；正常付款、ORDER 来源与 REFUND 金额/原支付信息必须一致。query 通过真实 `refundFacts.requireForChannel` 与 dispatch 精确比较，不把金额范围校验当来源授权。 |
| [RefundExecutionService.finish](../../../../backend/pet-refund-biz/src/main/java/com/petplatform/refund/biz/application/RefundExecutionService.java:97) | 在业务成功事务内重读 ORDER 来源、PAYMENT 已验证结果、REFUND `requireForChannel`；回执金额必须等于受业务授权的 f.refundAmount，并核原支付金额/交易号/事件。旧来源部分金额即使存在 PAYMENT 结果 DTO 也不能据此成功。 |
| [PaymentRefundResultFactsApiImpl.requireVerified](../../../../backend/pet-payment-biz/src/main/java/com/petplatform/payment/biz/apiimpl/PaymentRefundResultFactsApiImpl.java:29) | 修复只将“结果金额=原本金”改为正数且不超原本金；仍要求 VERIFIED_SUCCESS、终局 receipt 存在、receipt amount=dispatch refundAmount、交易号/请求号/时间/摘要/来源一致。 |
| [SQL50 金额约束](../../../../docs/03-database/50-AfterSale-Workflow-Schema-v0.1.sql:131) | refund_execution：非 AFS（含旧 null source 历史形状）仍 refund_amount=channel_paid_amount；refund_order：非 AFS 仍 FULL 且 ratio=1。只有新 AFS 来源有部分金额通道。 |

这部分是当前源码及 DDL 的静态复核，不冒充旧来源回归的本轮执行结果。真实资金资格 Owner 未获准不因协议测试或 PARTIAL 修复而解锁。
