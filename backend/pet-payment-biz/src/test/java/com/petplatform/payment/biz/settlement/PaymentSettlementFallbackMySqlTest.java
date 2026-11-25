package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.order.api.dto.OrderLatePaymentFact;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
import com.petplatform.order.api.dto.OrderSettlementFact;
import com.petplatform.order.api.query.OrderLatePaymentFactsApi;
import com.petplatform.order.api.query.OrderMerchantRejectFactsApi;
import com.petplatform.order.api.query.OrderRefundApplicationFactsApi;
import com.petplatform.order.api.query.OrderSettlementFactsApi;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundQuery;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundSubmitCommand;
import com.petplatform.payment.api.dto.PaymentRefundTypes.CoordinationState;
import com.petplatform.payment.api.dto.SettlementTypes.MarkSettlementVerifiedCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RegisterSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RetryFallbackCommand;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementInstructionView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.payment.biz.application.PaymentRefundService;
import com.petplatform.payment.biz.application.SettlementRefundGate.GateDecision;
import com.petplatform.payment.biz.application.SettlementRefundGate.RefundGateRequest;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedFallbackSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedQueryAmt;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSeparateSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ExpectedSplit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackSubmitResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryPlusInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateSubmitResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitDetail;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitStatus;
import com.petplatform.payment.biz.settlement.SettlementOrderBlockerPort.BlockerState;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup.ActiveReceiver;
import com.petplatform.payment.biz.task.SettlementDueTask;
import com.petplatform.payment.biz.task.SettlementQueryTask;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.api.dto.RefundSuccessFact;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Real MySQL integration tests for CCR-PAY-CENTER-001 stage 3 (Contract 78 §8):
 * post-split 2395 fallback before original-route refund, platform-coupon sequential partial refunds,
 * insufficient-balance failure pausing refund + idempotent admin retry, 180-day window expiry,
 * pre-split WAITING/SPLITTING concurrency fences, and unknown 2395 submit idempotency.
 */
class PaymentSettlementFallbackMySqlTest {
    private static final String PLATFORM_MCH = "82229005943096D";
    private static final String RECEIVER_NO = "SR202611250001";
    private static final String LOG_NO = "66210306990190";
    private static final LocalDate LOG_DATE = LocalDate.of(2026, 11, 18);
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static KeyPair notifyKeys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        notifyKeys = gen.generateKeyPair();
    }

    @Test
    void postSplitFullRefundTriggersFallbackFirstAndExecutesRefundOnlyAfterFallbackSucceeds() throws Exception {
        try (Fixture f = Fixture.open(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"))) {
            // 1. Complete split to SPLIT_SUCCEEDED (split_amount = 99.40).
            SettlementOrderView splitDone = f.runSplitToSucceeded(9962L);
            assertEquals("SPLIT_SUCCEEDED", splitDone.status());
            assertEquals(new BigDecimal("99.40"), splitDone.splitAmount());

            // 2. PaymentRefundService.submitRefund must trigger 2395 fallback and NOT call Lakala refund yet.
            var first = f.refundService.submitRefund(f.submitRefundCmd());
            assertEquals(CoordinationState.QUERY_PENDING, first.state());
            assertEquals(0, f.refundChannel.submits.get(), "channel refund must NOT run before fallback succeeds");
            assertEquals(1, f.sacsChannel.fallbackInputs.size());
            FallbackInput fbReq = f.sacsChannel.fallbackInputs.getFirst();
            assertEquals(9940L, fbReq.totalAmountCents());
            assertEquals(RECEIVER_NO, fbReq.receiverNo());
            assertEquals("REVERSING", f.admin.getById(splitDone.settlementOrderId(), f.sysQuery()).status());
            assertEquals(0, f.count("SELECT COUNT(*) FROM payment_refund_dispatch"));

            // Find FALLBACK instruction and verify 15s query task enqueued.
            var fbInstr = f.latestFallbackInstruction(splitDone.settlementOrderId());
            assertEquals("PROCESSING", fbInstr.status());
            assertEquals(Fixture.REFUND_ID, fbInstr.refundOrderId());
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task WHERE task_key='SETTLEMENT_CHANNEL_QUERY:"
                    + fbInstr.instructionId() + "'"));

            // 3. While fallback is still PROCESSING, PaymentRefundService.queryRefund keeps returning QUERY_PENDING.
            assertEquals(CoordinationState.QUERY_PENDING, f.refundService.queryRefund(f.queryRefundCmd()).state());
            assertEquals(0, f.refundChannel.submits.get());

            // 4. 1296 query confirms FALLBACK SUCCESS -> settlement_order advances to REVERSED.
            f.sacsChannel.onQuery = (in, exp) -> {
                assertEquals(CmdType.FALLBACK, exp.expectedCmdType());
                return new VerifiedSplitOutcome(new SplitOutcome(
                        fbInstr.separateNo(), fbInstr.outSeparateNo(), CmdType.FALLBACK,
                        LOG_NO, LOG_DATE, "0", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25),
                        9940L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 9940L, 0L, null, List.of()), SHA_B);
            };
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    f.queryCoordinator.execute(Long.parseLong(fbInstr.instructionId())));

            SettlementOrderView reversed = f.admin.getById(splitDone.settlementOrderId(), f.sysQuery());
            assertEquals("REVERSED", reversed.status());
            assertEquals(new BigDecimal("99.40"), reversed.reversedAmount());

            // 5. Now PaymentRefundService.queryRefund passes the gate and executes original-route refund!
            var refundDone = f.refundService.queryRefund(f.queryRefundCmd());
            assertEquals(CoordinationState.VERIFIED_SUCCESS, refundDone.state());
            assertEquals(1, f.refundChannel.submits.get());
            assertEquals(1, f.count("SELECT COUNT(*) FROM payment_refund_dispatch WHERE state='VERIFIED_SUCCESS'"));

            // 6. RefundSucceededEvent.v1 updates post-split refund projection cleanly.
            f.fireRefundSucceeded("FULL", new BigDecimal("100.00"), new BigDecimal("100.00"));
            SettlementOrderView projected = f.admin.getById(splitDone.settlementOrderId(), f.sysQuery());
            assertEquals("REVERSED", projected.status());
            assertEquals(new BigDecimal("100.00"), projected.refundedAmount());
            assertEquals(new BigDecimal("0.00"), projected.platformSubsidyDue());
        }
    }

    @Test
    void platformCouponSequentialPartialRefundsBalanceFallbackAndSubsidyToThePenny() throws Exception {
        // Original 100.00, platform coupon 20.00, paid 80.00, fee 0.6% -> merchantShare=99.40, split=80.00, subsidy=19.40.
        try (Fixture f = Fixture.open(new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"))) {
            SettlementOrderView splitDone = f.runSplitToSucceeded(8000L);
            assertEquals("SPLIT_SUCCEEDED", splitDone.status());
            assertEquals(new BigDecimal("80.00"), splitDone.splitAmount());
            assertEquals(new BigDecimal("19.40"), splitDone.platformSubsidyDue());

            // Partial refund #1: 40.00 -> fallback = floor(40 * 0.994) = 39.76
            long refund1 = 8801001L;
            GateDecision d1 = f.fallbackService.ensureReadyForRefund(new RefundGateRequest(
                    Fixture.PAYMENT_ID_LONG, refund1, new BigDecimal("40.00"), new BigDecimal("80.00"),
                    "AFTERSALE_DECISION"));
            assertTrue(d1 instanceof GateDecision.Wait);
            assertEquals(3976L, f.sacsChannel.fallbackInputs.get(0).totalAmountCents());

            // Confirm via signed 1297 FALLBACK notification!
            var fb1 = f.latestFallbackInstruction(splitDone.settlementOrderId());
            byte[] noticeBody = ("{\"separate_no\":\"" + fb1.separateNo()
                    + "\",\"out_separate_no\":\"" + fb1.outSeparateNo()
                    + "\",\"cmd_type\":\"FALLBACK\",\"log_no\":\"" + LOG_NO
                    + "\",\"log_date\":\"20261118\",\"total_amt\":\"3976\","
                    + "\"status\":\"SUCCESS\",\"final_status\":\"SUCCESS\"}").getBytes(StandardCharsets.UTF_8);
            var rec = f.receiptWriter.receiveNotification(sign1009(noticeBody), noticeBody);
            assertTrue(rec.terminal() && rec.succeeded());

            SettlementOrderView afterFb1 = f.admin.getById(splitDone.settlementOrderId(), f.sysQuery());
            assertEquals("PARTIALLY_REVERSED", afterFb1.status());
            assertEquals(new BigDecimal("39.76"), afterFb1.reversedAmount());

            // Gate now allows refund #1 to proceed.
            assertTrue(f.fallbackService.ensureReadyForRefund(new RefundGateRequest(
                    Fixture.PAYMENT_ID_LONG, refund1, new BigDecimal("40.00"), new BigDecimal("80.00"),
                    "AFTERSALE_DECISION")) instanceof GateDecision.Proceed);

            // Refund #1 succeeds -> platform_subsidy_due = 49.70 - (80.00 - 39.76) = 9.46.
            f.fireRefundSucceeded("PARTIAL", new BigDecimal("40.00"), new BigDecimal("40.00"));
            SettlementOrderView afterRef1 = f.admin.getById(splitDone.settlementOrderId(), f.sysQuery());
            assertEquals(new BigDecimal("40.00"), afterRef1.refundedAmount());
            assertEquals(new BigDecimal("49.70"), afterRef1.merchantShare());
            assertEquals(new BigDecimal("9.46"), afterRef1.platformSubsidyDue());

            // Partial refund #2: remaining 40.00 (cumulative 80.00 == paid) -> exact remaining 40.24
            long refund2 = 8801002L;
            GateDecision d2 = f.fallbackService.ensureReadyForRefund(new RefundGateRequest(
                    Fixture.PAYMENT_ID_LONG, refund2, new BigDecimal("40.00"), new BigDecimal("80.00"),
                    "AFTERSALE_DECISION"));
            assertTrue(d2 instanceof GateDecision.Wait);
            assertEquals(4024L, f.sacsChannel.fallbackInputs.get(1).totalAmountCents());

            var fb2 = f.latestFallbackInstruction(splitDone.settlementOrderId());
            f.sacsChannel.onQuery = (in, exp) -> new VerifiedSplitOutcome(new SplitOutcome(
                    fb2.separateNo(), fb2.outSeparateNo(), CmdType.FALLBACK,
                    LOG_NO, LOG_DATE, "0", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25),
                    4024L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 4024L, 0L, null, List.of()), SHA_C);
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    f.queryCoordinator.execute(Long.parseLong(fb2.instructionId())));

            SettlementOrderView afterFb2 = f.admin.getById(splitDone.settlementOrderId(), f.sysQuery());
            assertEquals("REVERSED", afterFb2.status());
            assertEquals(new BigDecimal("80.00"), afterFb2.reversedAmount());

            f.fireRefundSucceeded("PARTIAL", new BigDecimal("40.00"), new BigDecimal("80.00"));
            SettlementOrderView finalView = f.admin.getById(splitDone.settlementOrderId(), f.sysQuery());
            assertEquals(new BigDecimal("80.00"), finalView.refundedAmount());
            assertEquals(new BigDecimal("0.00"), finalView.merchantShare());
            assertEquals(new BigDecimal("0.00"), finalView.platformSubsidyDue());
        }
    }

    @Test
    void fallbackFailurePausesRefundWithoutPlatformAdvanceAndAdminRetryRecovers() throws Exception {
        try (Fixture f = Fixture.open(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"))) {
            SettlementOrderView splitDone = f.runSplitToSucceeded(9962L);

            assertEquals(CoordinationState.QUERY_PENDING, f.refundService.submitRefund(f.submitRefundCmd()).state());
            assertEquals(0, f.refundChannel.submits.get());
            var fb1 = f.latestFallbackInstruction(splitDone.settlementOrderId());

            // 1296 returns official insufficient balance FAIL.
            f.sacsChannel.onQuery = (in, exp) -> new VerifiedSplitOutcome(new SplitOutcome(
                    fb1.separateNo(), fb1.outSeparateNo(), CmdType.FALLBACK,
                    LOG_NO, LOG_DATE, "0", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25),
                    9940L, "FAIL", "FAIL", SplitStatus.FAIL, 9940L, 0L,
                    "账户端处理失败[可用余额不足]", List.of()), SHA_B);
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    f.queryCoordinator.execute(Long.parseLong(fb1.instructionId())));

            SettlementOrderView failed = f.admin.getById(splitDone.settlementOrderId(), f.sysQuery());
            assertEquals("REVERSE_FAILED", failed.status());
            assertEquals("CHANNEL_FALLBACK_FAILED", failed.lastErrorCode());
            assertEquals("账户端处理失败[可用余额不足]", failed.lastErrorMessage());

            // Refund query must pause (RECONCILIATION_REQUIRED) and NEVER invoke Lakala refund!
            var paused = f.refundService.queryRefund(f.queryRefundCmd());
            assertEquals(CoordinationState.RECONCILIATION_REQUIRED, paused.state());
            assertEquals(0, f.refundChannel.submits.get(), "platform must NEVER advance refund when fallback failed");

            // Ops retries failed fallback after merchant replenishes balance (requestId idempotent).
            CommandContext retryCtx = new CommandContext("req-retry-fb-1", "t",
                    OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN");
            SettlementOrderView retrying = f.admin.retryFailedFallback(new RetryFallbackCommand(
                    retryCtx, splitDone.settlementOrderId(), Fixture.REFUND_ID, "merchant replenished"));
            assertEquals("REVERSING", retrying.status());
            assertEquals(2, f.sacsChannel.fallbackInputs.size());

            // Same requestId replays idempotently without creating a 3rd fallback attempt.
            SettlementOrderView replayed = f.admin.retryFailedFallback(new RetryFallbackCommand(
                    retryCtx, splitDone.settlementOrderId(), Fixture.REFUND_ID, "merchant replenished"));
            assertEquals("REVERSING", replayed.status());
            assertEquals(2, f.sacsChannel.fallbackInputs.size());

            // Same requestId with different refundOrderId triggers IDEMPOTENCY_KEY_CONFLICT.
            ApiException conflict = assertThrows(ApiException.class, () -> f.admin.retryFailedFallback(
                    new RetryFallbackCommand(retryCtx, splitDone.settlementOrderId(), "9999999", "wrong")));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());

            // 2nd fallback attempt succeeds -> refund query now executes original-route refund!
            var fb2 = f.latestFallbackInstruction(splitDone.settlementOrderId());
            assertEquals(2, fb2.attemptNo());
            f.sacsChannel.onQuery = (in, exp) -> new VerifiedSplitOutcome(new SplitOutcome(
                    fb2.separateNo(), fb2.outSeparateNo(), CmdType.FALLBACK,
                    LOG_NO, LOG_DATE, "0", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25),
                    9940L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 9940L, 0L, null, List.of()), SHA_C);
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    f.queryCoordinator.execute(Long.parseLong(fb2.instructionId())));

            var recovered = f.refundService.queryRefund(f.queryRefundCmd());
            assertEquals(CoordinationState.VERIFIED_SUCCESS, recovered.state());
            assertEquals(1, f.refundChannel.submits.get());
        }
    }

    @Test
    void expired180DayFallbackWindowPausesRefundWithoutChannelCall() throws Exception {
        try (Fixture f = Fixture.open(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"))) {
            SettlementOrderView splitDone = f.runSplitToSucceeded(9962L);

            // Backdate split completion to 181 days before current UTC DB time.
            f.executeSql("UPDATE settlement_instruction SET terminal_at=UTC_TIMESTAMP(3)-INTERVAL 181 DAY "
                    + "WHERE settlement_order_id=" + splitDone.settlementOrderId() + " AND instruction_type='SPLIT'");

            var paused = f.refundService.submitRefund(f.submitRefundCmd());
            assertEquals(CoordinationState.RECONCILIATION_REQUIRED, paused.state());
            assertEquals(0, f.sacsChannel.fallbackInputs.size());
            assertEquals(0, f.refundChannel.submits.get());

            SettlementOrderView expired = f.admin.getById(splitDone.settlementOrderId(), f.sysQuery());
            assertEquals("REVERSE_FAILED", expired.status());
            assertEquals("FALLBACK_WINDOW_EXPIRED", expired.lastErrorCode());
        }
    }

    @Test
    void preSplitWaitingIsHeldSynchronouslyAndSplittingWaitsBeforeRefunding() throws Exception {
        // Case A: WAITING -> submitRefund atomically marks ON_HOLD(REFUND_IN_PROGRESS) and executes refund directly.
        try (Fixture f = Fixture.open(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"))) {
            SettlementOrderView waiting = f.registerAndVerifyDueNow();
            assertEquals("WAITING", waiting.status());

            var refunded = f.refundService.submitRefund(f.submitRefundCmd());
            assertEquals(CoordinationState.VERIFIED_SUCCESS, refunded.state());
            assertEquals(1, f.refundChannel.submits.get());
            assertEquals(0, f.sacsChannel.fallbackInputs.size());

            SettlementOrderView held = f.admin.getById(waiting.settlementOrderId(), f.sysQuery());
            assertEquals("ON_HOLD", held.status());
            assertEquals("REFUND_IN_PROGRESS", held.holdReason());

            // Concurrent SETTLEMENT_DUE sees ON_HOLD and refuses to split!
            assertTrue(f.executionService.executeDue(Long.parseLong(waiting.settlementOrderId()), 1)
                    instanceof SettlementDueTask.Outcome.Done);
            assertEquals(0, f.count("SELECT COUNT(*) FROM settlement_instruction WHERE instruction_type='SPLIT'"));
        }

        // Case B: SPLITTING in flight -> submitRefund waits; once split FAILS, next queryRefund proceeds directly.
        try (Fixture f = Fixture.open(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"))) {
            SettlementOrderView waiting = f.registerAndVerifyDueNow();
            assertTrue(f.executionService.executeDue(Long.parseLong(waiting.settlementOrderId()), 1)
                    instanceof SettlementDueTask.Outcome.Done);
            assertEquals("SPLITTING", f.admin.getById(waiting.settlementOrderId(), f.sysQuery()).status());

            // Refund must wait while split is in flight.
            assertEquals(CoordinationState.QUERY_PENDING, f.refundService.submitRefund(f.submitRefundCmd()).state());
            assertEquals(0, f.refundChannel.submits.get());
            assertEquals(0, f.sacsChannel.fallbackInputs.size());

            // Split confirms FAIL -> no fallback needed, refund proceeds immediately on next queryRefund.
            var splitInstr = f.admin.listInstructions(waiting.settlementOrderId(), f.sysQuery()).getFirst();
            f.sacsChannel.onQuery = (in, exp) -> new VerifiedSplitOutcome(new SplitOutcome(
                    splitInstr.separateNo(), splitInstr.outSeparateNo(), CmdType.SEPARATE,
                    LOG_NO, LOG_DATE, "0", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25),
                    9962L, "FAIL", "FAIL", SplitStatus.FAIL, null, null, "channel err", List.of()), SHA_B);
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    f.queryCoordinator.execute(Long.parseLong(splitInstr.instructionId())));
            assertEquals("SPLIT_FAILED", f.admin.getById(waiting.settlementOrderId(), f.sysQuery()).status());

            assertEquals(CoordinationState.VERIFIED_SUCCESS, f.refundService.queryRefund(f.queryRefundCmd()).state());
            assertEquals(1, f.refundChannel.submits.get());
            assertEquals(0, f.sacsChannel.fallbackInputs.size());
        }
    }

    @Test
    void unknownFallbackSubmissionStaysSentAndNeverDuplicatesOnReplay() throws Exception {
        try (Fixture f = Fixture.open(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"))) {
            SettlementOrderView splitDone = f.runSplitToSucceeded(9962L);

            f.sacsChannel.fallbackThrowsUnknown = true;
            assertEquals(CoordinationState.QUERY_PENDING, f.refundService.submitRefund(f.submitRefundCmd()).state());
            assertEquals(1, f.sacsChannel.fallbackInputs.size());

            var fbInstr = f.latestFallbackInstruction(splitDone.settlementOrderId());
            assertEquals("SENT", fbInstr.status());

            // Replayed submitRefund & queryRefund MUST NOT create a second fallback instruction.
            assertEquals(CoordinationState.QUERY_PENDING, f.refundService.submitRefund(f.submitRefundCmd()).state());
            assertEquals(CoordinationState.QUERY_PENDING, f.refundService.queryRefund(f.queryRefundCmd()).state());
            assertEquals(1, f.sacsChannel.fallbackInputs.size());
            assertEquals(1, f.count("SELECT COUNT(*) FROM settlement_instruction WHERE instruction_type='FALLBACK'"));

            // 1296 confirms by original out_separate_no -> refund succeeds!
            f.sacsChannel.onQuery = (in, exp) -> {
                assertEquals(fbInstr.outSeparateNo(), in.outSeparateNo());
                return new VerifiedSplitOutcome(new SplitOutcome(
                        "FB_SEP_RECOVERY", fbInstr.outSeparateNo(), CmdType.FALLBACK,
                        LOG_NO, LOG_DATE, "0", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25),
                        9940L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 9940L, 0L, null, List.of()), SHA_C);
            };
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    f.queryCoordinator.execute(Long.parseLong(fbInstr.instructionId())));

            assertEquals(CoordinationState.VERIFIED_SUCCESS, f.refundService.queryRefund(f.queryRefundCmd()).state());
            assertEquals(1, f.refundChannel.submits.get());
        }
    }

    // ---- Test Fixture ----------------------------------------------------------------------

    private static Map<String, String> sign1009(byte[] body) throws Exception {
        String time = "1764040000", nonce = "AbCd12345678";
        byte[] prefix = (time + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[prefix.length + body.length + 1];
        System.arraycopy(prefix, 0, msg, 0, prefix.length);
        System.arraycopy(body, 0, msg, prefix.length, body.length);
        msg[msg.length - 1] = '\n';
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(notifyKeys.getPrivate());
        signer.update(msg);
        String sig = Base64.getEncoder().encodeToString(signer.sign());
        return Map.of("Authorization", "LKLAPI-SHA256withRSA timestamp=\"" + time
                + "\", nonce_str=\"" + nonce + "\", signature=\"" + sig + "\"");
    }

    private static final class Fixture implements AutoCloseable {
        static final String ORDER_ID = "7701001";
        static final long PAYMENT_ID_LONG = 7701002L;
        static final String PAYMENT_ID = "7701002";
        static final String PAYMENT_NO = "7701003";
        static final String REFUND_ID = "7701004";
        static final String REFUND_NO = "7701005";
        static final String STORE_ID = "7701006";
        static final String MERCHANT_ID = "7701007";
        static final String USER_ID = "7701008";
        static final String EVENT_ID = "7701009";
        static final String APPLICATION_ID = "7701020";
        static final String DECISION_ID = "7701021";
        static final String TRADE_NO = "20261118110113130266210306990190";

        private static final List<String> SCHEMAS = List.of(
                "06-核心数据库Schema-v0.1.sql",
                "13-Async-Infra-Schema-v0.1.sql",
                "40-Payment-Foundation-Schema-v0.1.sql",
                "41-Payment-Dispatch-Schema-v0.1.sql",
                "43-Payment-Refund-Dispatch-Schema-v0.1.sql",
                "75-Payment-Center-Schema-v0.1.sql",
                "78-Payment-Settlement-Schema-v0.1.sql",
                "79-Payment-Settlement-Fallback-Schema-v0.1.sql");

        final String dbName;
        final String baseUrl;
        final String user;
        final String password;
        final DataSource source;
        final AtomicLong seq = new AtomicLong(600_000_000L);
        final SnowflakeIdGenerator ids = seq::incrementAndGet;
        final BigDecimal originalPrice;
        final BigDecimal platformCoupon;
        final BigDecimal paidAmount;
        final StubSacsChannel sacsChannel = new StubSacsChannel();
        final FakeRefundChannel refundChannel;
        final SettlementFallbackService fallbackService;
        final SettlementAdminService admin;
        final SettlementExecutionService executionService;
        final SettlementReceiptWriter receiptWriter;
        final SettlementQueryCoordinator queryCoordinator;
        final PaymentRefundService refundService;
        BigDecimal latestOrderRefunded = BigDecimal.ZERO.setScale(2);

        private Fixture(String dbName, String baseUrl, String user, String password, DataSource source,
                BigDecimal originalPrice, BigDecimal platformCoupon, BigDecimal paidAmount) throws Exception {
            this.dbName = dbName;
            this.baseUrl = baseUrl;
            this.user = user;
            this.password = password;
            this.source = source;
            this.originalPrice = originalPrice;
            this.platformCoupon = platformCoupon;
            this.paidAmount = paidAmount;

            OffsetDateTime now = dbNow();
            OffsetDateTime paidAt = now.minusDays(10).truncatedTo(ChronoUnit.MILLIS);
            insertPaidPayment(paidAt);

            JdbcAsyncTaskSubmitter submitter = new JdbcAsyncTaskSubmitter(source, ids);
            Clock fixedClock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
            this.fallbackService = new SettlementFallbackService(
                    source, ids, sacsChannel, submitter, ZoneId.of("Asia/Shanghai"), fixedClock);
            this.admin = new SettlementAdminService(source, ids, submitter, fallbackService::retryFailedFallback);
            this.executionService = new SettlementExecutionService(
                    source, ids, sacsChannel,
                    m -> Optional.of(new ActiveReceiver(m, RECEIVER_NO)),
                    o -> new BlockerState(latestOrderRefunded, false, false),
                    submitter,
                    new SettlementExecutionService.Settings(PLATFORM_MCH, ZoneId.of("Asia/Shanghai"),
                            BigDecimal.ZERO, "1950836", "https://api.example.com/callbacks/lakala/separate"),
                    fixedClock);
            this.receiptWriter = new SettlementReceiptWriter(source, ids,
                    SettlementReceiptWriter.NotificationVerifier.fromPublicKey(notifyKeys.getPublic()),
                    new TransactionalOutboxPublisher(source, ids, new com.fasterxml.jackson.databind.ObjectMapper()));
            this.queryCoordinator = new SettlementQueryCoordinator(
                    source, sacsChannel, receiptWriter, ZoneId.of("Asia/Shanghai"), fixedClock);
            this.refundChannel = new FakeRefundChannel(paidAmount);

            ScheduleCapacityGuardApi guard = new ScheduleCapacityGuardApi() {
                @Override public void acquire(List<String> stores, QueryContext context) {}
                @Override public void requireHeld(String store, DataSource caller) {}
            };
            OffsetDateTime appliedAt = paidAt.plusHours(1);
            OffsetDateTime decidedAt = appliedAt.plusSeconds(1);
            OffsetDateTime createdAt = decidedAt.plusSeconds(1);
            OrderLatePaymentFactsApi lateOrders = new OrderLatePaymentFactsApi() {
                @Override public String locateStore(String orderId, QueryContext ctx) { return STORE_ID; }
                @Override public OrderLatePaymentFact requireLatePayment(String o, String p, String s, QueryContext c) {
                    throw new UnsupportedOperationException();
                }
            };
            OrderMerchantRejectFactsApi rejectOrders = new OrderMerchantRejectFactsApi() {
                @Override public String locateStore(String orderId, QueryContext ctx) { return STORE_ID; }
                @Override public OrderRefundOriginFact requireRejected(String o, String p, String s, QueryContext c) {
                    throw new UnsupportedOperationException();
                }
            };
            OrderRefundApplicationFactsApi appOrders = (o, p, s, c) -> new OrderRefundOriginFact(
                    ORDER_ID, STORE_ID, MERCHANT_ID, USER_ID, "7701010", PAYMENT_ID, EVENT_ID,
                    TRADE_NO, paidAmount, paidAt, "MERCHANT_APPROVED", null, REFUND_ID,
                    APPLICATION_ID, DECISION_ID);
            RefundApplicationApprovalFactsApi approvals = new RefundApplicationApprovalFactsApi() {
                @Override public ApprovalFact requireApproved(String app, String decision, String store, QueryContext c) {
                    var application = new ApplicationFact(APPLICATION_ID, ORDER_ID, STORE_ID, MERCHANT_ID,
                            USER_ID, "7701010", PAYMENT_ID, PAYMENT_NO, EVENT_ID, TRADE_NO, paidAmount,
                            paidAt, "APPROVED", 1, appliedAt, appliedAt.plusHours(24), "7701022",
                            DECISION_ID, REFUND_ID);
                    return new ApprovalFact(application, DECISION_ID, "MERCHANT_APPROVED", "USER", USER_ID,
                            decidedAt, "7701023", "7701024");
                }
                @Override public ApplicationFact requireApplication(String a, String s, QueryContext c) { throw new UnsupportedOperationException(); }
                @Override public DecisionFact requireDecision(String a, String d, String s, QueryContext c) { throw new UnsupportedOperationException(); }
                @Override public CreatedFact requireCreated(String a, String d, String r, String s, QueryContext c) { throw new UnsupportedOperationException(); }
            };
            RefundExecutionFactsApi refundFacts = new RefundExecutionFactsApi() {
                @Override public RefundExecutionFact requireForChannel(String r, String s, QueryContext c) {
                    return new RefundExecutionFact(REFUND_ID, REFUND_NO, ORDER_ID, PAYMENT_ID, PAYMENT_NO,
                            STORE_ID, MERCHANT_ID, USER_ID, EVENT_ID, null, TRADE_NO, paidAmount, paidAmount,
                            paidAt, "CNY", "CREATED", 0, "7701012", createdAt, "MERCHANT_APPROVED",
                            null, APPLICATION_ID, DECISION_ID);
                }
                @Override public RefundSuccessFact requireSucceeded(String r, String o, String s, QueryContext c) {
                    throw new UnsupportedOperationException();
                }
            };
            this.refundService = new PaymentRefundService(
                    source, ids, guard, lateOrders,
                    new PaymentSuccessFactsApiImpl(source, guard),
                    refundFacts, refundChannel,
                    new PaymentRefundService.Settings("127.0.0.1", null, ZoneId.of("UTC")),
                    fixedClock, rejectOrders, appOrders, approvals,
                    null, null, null, null, fallbackService);
        }

        static Fixture open(BigDecimal originalPrice, BigDecimal platformCoupon, BigDecimal paidAmount) throws Exception {
            String url = System.getenv("PAYMENT_CENTER_MYSQL_URL");
            if (url == null || url.isBlank()) url = System.getenv("PAYMENT_REFUND_MYSQL_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "MySQL URL env not set");
            String user = env("PAYMENT_CENTER_MYSQL_USER", env("PAYMENT_REFUND_MYSQL_USER", "root"));
            String pass = env("PAYMENT_CENTER_MYSQL_PASSWORD", env("PAYMENT_REFUND_MYSQL_PASSWORD", "root"));
            String dbName = "payfb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String normalized = url.endsWith("/") ? url : url + "/";

            MysqlDataSource adminDs = new MysqlDataSource();
            adminDs.setURL(normalized + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            adminDs.setUser(user);
            adminDs.setPassword(pass);
            try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }

            MysqlDataSource ds = new MysqlDataSource();
            ds.setURL(normalized + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&allowMultiQueries=true");
            ds.setUser(user);
            ds.setPassword(pass);

            Path root = repoRoot().resolve("docs/03-database");
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                for (String file : SCHEMAS) {
                    ScriptUtils.executeSqlScript(c, new EncodedResource(
                            new FileSystemResource(root.resolve(file)), StandardCharsets.UTF_8));
                }
                s.execute("ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
            }
            return new Fixture(dbName, normalized, user, pass, ds, originalPrice, platformCoupon, paidAmount);
        }

        SettlementOrderView registerAndVerifyDueNow() throws Exception {
            CommandContext sys = new CommandContext("reg-" + seq.incrementAndGet(), "t",
                    OperatorType.SYSTEM, null, "TEST");
            SettlementOrderView reg = admin.registerForPayment(new RegisterSettlementCommand(
                    sys, PAYMENT_ID, ORDER_ID, "BOOKING_ORDER", ORDER_ID, MERCHANT_ID, STORE_ID,
                    originalPrice, BigDecimal.ZERO, BigDecimal.ZERO, platformCoupon, paidAmount, EVENT_ID));
            OffsetDateTime verifiedAt = dbNow().minusDays(8).truncatedTo(ChronoUnit.MILLIS);
            return admin.markVerified(new MarkSettlementVerifiedCommand(
                    sys, reg.settlementOrderId(), verifiedAt, Long.toString(seq.incrementAndGet())));
        }

        SettlementOrderView runSplitToSucceeded(long canSeparateCents) throws Exception {
            SettlementOrderView waiting = registerAndVerifyDueNow();
            sacsChannel.canSeparateCents = canSeparateCents;
            assertTrue(executionService.executeDue(Long.parseLong(waiting.settlementOrderId()), 1)
                    instanceof SettlementDueTask.Outcome.Done);
            var splitInstr = admin.listInstructions(waiting.settlementOrderId(), sysQuery()).getFirst();
            long merchantCents = splitInstr.merchantAmount().movePointRight(2).longValueExact();
            sacsChannel.onQuery = (in, exp) -> new VerifiedSplitOutcome(new SplitOutcome(
                    splitInstr.separateNo(), splitInstr.outSeparateNo(), CmdType.SEPARATE,
                    LOG_NO, LOG_DATE, "0", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25),
                    canSeparateCents, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS,
                    canSeparateCents, 0L, null,
                    List.of(new SplitDetail(null, RECEIVER_NO, merchantCents, merchantCents, 0L, null))), SHA_A);
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    queryCoordinator.execute(Long.parseLong(splitInstr.instructionId())));
            return admin.getById(waiting.settlementOrderId(), sysQuery());
        }

        void fireRefundSucceeded(String refundType, BigDecimal refundAmount, BigDecimal cumulativeRefunded) {
            this.latestOrderRefunded = cumulativeRefunded.setScale(2);
            OrderSettlementFactsApi factsApi = (oid, ctx) -> new OrderSettlementFact(
                    ORDER_ID, MERCHANT_ID, STORE_ID, false, true,
                    originalPrice, BigDecimal.ZERO, platformCoupon, paidAmount,
                    cumulativeRefunded, null, null, null, dbNow().minusDays(8));
            var consumer = new SettlementEventConsumers.OnRefundSucceeded(
                    source, new JdbcOutboxConsumeGuard(source, ids), factsApi, admin);
            String eventId = Long.toString(seq.incrementAndGet());
            String payload = "{\"refundOrderId\":\"" + REFUND_ID + "\",\"orderId\":\"" + ORDER_ID
                    + "\",\"refundType\":\"" + refundType + "\",\"refundAmount\":" + refundAmount + "}";
            consumer.consume(new DispatchedEvent(eventId, "RefundSucceededEvent.v1", 1,
                    dbNow(), "REFUND", Long.parseLong(REFUND_ID), "trace", payload));
        }

        SettlementInstructionView latestFallbackInstruction(String settlementOrderId) {
            List<SettlementInstructionView> all = admin.listInstructions(settlementOrderId, sysQuery());
            return all.stream()
                    .filter(i -> "FALLBACK".equals(i.instructionType()))
                    .reduce((a, b) -> b)
                    .orElseThrow();
        }

        ChannelRefundSubmitCommand submitRefundCmd() {
            return new ChannelRefundSubmitCommand(
                    new CommandContext("TASK:REFUND_SUBMIT:" + REFUND_ID + ":0", "QA",
                            OperatorType.SYSTEM, null, "ASYNC_TASK"),
                    REFUND_ID, REFUND_NO, PAYMENT_ID, STORE_ID, 0);
        }

        ChannelRefundQuery queryRefundCmd() {
            return new ChannelRefundQuery(
                    new CommandContext("TASK:REFUND_CHANNEL_QUERY:" + REFUND_ID + ":0", "QA",
                            OperatorType.SYSTEM, null, "ASYNC_TASK"),
                    REFUND_ID, REFUND_NO, PAYMENT_ID, STORE_ID, 0);
        }

        QueryContext sysQuery() { return new QueryContext("t", OperatorType.SYSTEM, null); }

        private void insertPaidPayment(OffsetDateTime paidAt) throws Exception {
            String sql = "INSERT INTO payment_order(id,payment_no,order_id,store_id,merchant_id,user_id,"
                    + "amount,status,channel,expire_at,merchant_no,term_no,sub_appid,currency,dispatch_state,"
                    + "channel_trade_no,channel_paid_amount,paid_at,success_event_id,settle_type,log_no,log_date,"
                    + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PAID','LAKALA_WECHAT',UTC_TIMESTAMP(3),"
                    + "?,'TERM1','wxapp','CNY','OBSERVED',?,?,?,?, '1',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))";
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, Long.parseLong(PAYMENT_ID));
                ps.setLong(2, Long.parseLong(PAYMENT_NO));
                ps.setLong(3, Long.parseLong(ORDER_ID));
                ps.setLong(4, Long.parseLong(STORE_ID));
                ps.setLong(5, Long.parseLong(MERCHANT_ID));
                ps.setLong(6, Long.parseLong(USER_ID));
                ps.setBigDecimal(7, paidAmount);
                ps.setString(8, PLATFORM_MCH);
                ps.setString(9, TRADE_NO);
                ps.setBigDecimal(10, paidAmount);
                ps.setObject(11, paidAt.toLocalDateTime());
                ps.setLong(12, Long.parseLong(EVENT_ID));
                ps.setString(13, LOG_NO);
                ps.setObject(14, LOG_DATE);
                ps.executeUpdate();
            }
            String receiptSql = "INSERT INTO payment_channel_receipt(id,payment_id,receipt_sha256,"
                    + "channel_trade_no,channel_status,total_amount,paid_amount,paid_at,received_at) "
                    + "VALUES(?,?,?,?,'SUCCESS',?,?,?,UTC_TIMESTAMP(3))";
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(receiptSql)) {
                ps.setLong(1, seq.incrementAndGet());
                ps.setLong(2, Long.parseLong(PAYMENT_ID));
                ps.setString(3, SHA_A);
                ps.setString(4, TRADE_NO);
                ps.setBigDecimal(5, paidAmount);
                ps.setBigDecimal(6, paidAmount);
                ps.setObject(7, paidAt.toLocalDateTime());
                ps.executeUpdate();
            }
        }

        OffsetDateTime dbNow() {
            try (Connection c = source.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT UTC_TIMESTAMP(3)")) {
                rs.next();
                return rs.getObject(1, LocalDateTime.class).atOffset(ZoneOffset.UTC);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        void executeSql(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate(sql);
            }
        }

        int count(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            }
        }

        @Override
        public void close() throws Exception {
            MysqlDataSource adminDs = new MysqlDataSource();
            adminDs.setURL(baseUrl + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            adminDs.setUser(user);
            adminDs.setPassword(password);
            try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + dbName + "`");
            }
        }

        private static String env(String key, String fallback) {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : v;
        }

        private static Path repoRoot() {
            Path p = Path.of("").toAbsolutePath();
            while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
            return java.util.Objects.requireNonNull(p);
        }
    }

    private static final class StubSacsChannel implements SettlementChannel {
        long canSeparateCents = 9962L;
        boolean fallbackThrowsUnknown;
        final List<FallbackInput> fallbackInputs = new ArrayList<>();
        java.util.function.BiFunction<QueryPlusInput, ExpectedSplit, VerifiedSplitOutcome> onQuery;

        @Override
        public VerifiedQueryAmt querySeparableAmount(QueryAmtInput input) {
            return new VerifiedQueryAmt(new QueryAmtResult(
                    input.merchantNo(), input.logNo(), input.logDate(), canSeparateCents, canSeparateCents), SHA_A);
        }

        @Override
        public VerifiedSeparateSubmit submitSeparate(SeparateInput input) {
            return new VerifiedSeparateSubmit(new SeparateSubmitResult(
                    input.outSeparateNo(), "SEP_" + input.outSeparateNo(), SplitStatus.PROCESSING,
                    "PROCESSING", input.logNo(), input.logDate(), input.totalAmountCents()), SHA_A);
        }

        @Override
        public VerifiedFallbackSubmit submitFallback(FallbackInput input) {
            fallbackInputs.add(input);
            if (fallbackThrowsUnknown) {
                throw unknownIo();
            }
            return new VerifiedFallbackSubmit(new FallbackSubmitResult(
                    input.outSeparateNo(), "FBSEP_" + input.outSeparateNo(),
                    input.originSeparateNo(), input.originOutSeparateNo(),
                    input.totalAmountCents(), SplitStatus.PROCESSING, "PROCESSING"), SHA_A);
        }

        private static ChannelUnknownException unknownIo() {
            try {
                var ctor = ChannelUnknownException.class.getDeclaredConstructor(LakalaSacsHttpClient.Reason.class);
                ctor.setAccessible(true);
                return ctor.newInstance(LakalaSacsHttpClient.Reason.IO);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public VerifiedSplitOutcome querySeparateOutcome(QueryPlusInput input, ExpectedSplit expected) {
            if (onQuery != null) return onQuery.apply(input, expected);
            throw new AssertionError("unexpected query");
        }
    }

    private static final class FakeRefundChannel implements PaymentRefundChannel {
        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        final long amountCents;

        FakeRefundChannel(BigDecimal amount) {
            this.amountCents = amount.movePointRight(2).longValueExact();
        }

        @Override
        public VerifiedResult submit(RefundRequest request) {
            submits.incrementAndGet();
            return new VerifiedResult("SUCCESS", request.refundNo(), "CH_REF_" + request.refundNo(),
                    amountCents, amountCents,
                    LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1).truncatedTo(ChronoUnit.SECONDS),
                    SHA_B);
        }

        @Override
        public VerifiedResult query(RefundRequest request) {
            queries.incrementAndGet();
            return submit(request);
        }
    }
}
