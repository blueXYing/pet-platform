package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.petplatform.order.api.dto.OrderSettlementFact;
import com.petplatform.order.api.query.OrderSettlementFactsApi;
import com.petplatform.payment.api.dto.SettlementTypes.ListSettlementOrdersQuery;
import com.petplatform.payment.api.dto.SettlementTypes.MarkSettlementVerifiedCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RegisterSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.ResumeSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RetrySettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementConfigView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;
import com.petplatform.payment.api.dto.SettlementTypes.UpsertSettlementConfigCommand;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real MySQL integration tests for Contract 78 (CCR-PAY-CENTER-001 stage 2):
 * config audit/idempotency, T+d snapshot isolation, flash-price & platform-coupon math,
 * send discipline, 1296 x 1297 concurrent receipt race, ON_HOLD/resume, SPLIT_FAILED/retry,
 * refund recalculation/cancel, and Outbox event consumers.
 */
class PaymentSettlementMySqlTest {
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
    void configUpsertAuditAndRequestIdIdempotency() throws Exception {
        try (Db db = Db.open()) {
            var admin = db.adminService();
            var qCtx = new QueryContext("t1", OperatorType.PLATFORM_OPERATOR, "1001");
            SettlementConfigView def = admin.getConfig("5001", qCtx);
            assertFalse(def.configured());
            assertEquals(7, def.settleDays());
            assertEquals(new BigDecimal("0.6000"), def.feeRate());
            assertTrue(def.enabled());

            var cmd = new UpsertSettlementConfigCommand(
                    new CommandContext("req-cfg-1", "t1", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                    "5001", 14, new BigDecimal("0.8500"), true, "initial setup");
            SettlementConfigView created = admin.upsertConfig(cmd);
            assertTrue(created.configured());
            assertEquals(14, created.settleDays());
            assertEquals(new BigDecimal("0.8500"), created.feeRate());
            assertEquals(0L, created.version());

            // Same requestId + same params replays cleanly without a second audit row.
            SettlementConfigView replayed = admin.upsertConfig(cmd);
            assertEquals(14, replayed.settleDays());
            assertEquals(1, db.count("SELECT COUNT(*) FROM settlement_config_audit WHERE merchant_id=5001"));

            // Same requestId + different params -> IDEMPOTENCY_KEY_CONFLICT.
            ApiException conflict = assertThrows(ApiException.class, () -> admin.upsertConfig(
                    new UpsertSettlementConfigCommand(
                            new CommandContext("req-cfg-1", "t1", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                            "5001", 15, new BigDecimal("0.8500"), true, "changed")));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
        }
    }

    @Test
    void endToEndSplitFlowSnapshotIsolationFlashPriceAndPlatformSubsidy() throws Exception {
        try (Db db = Db.open()) {
            var admin = db.adminService();
            var aCtx = new CommandContext("req-c1", "t", OperatorType.PLATFORM_OPERATOR, "1", "ADMIN");
            admin.upsertConfig(new UpsertSettlementConfigCommand(aCtx, "5001", 10, new BigDecimal("0.8000"), true, "v1"));

            db.insertPaidPayment(1001L, 2001L, 3001L, 5001L, "1", new BigDecimal("70.00"), LOG_NO, LOG_DATE);

            // Original 100, flash 20, platform coupon 10, paid 70 -> splitBase=80, fee=0.8% -> share=79.36
            SettlementOrderView created = admin.registerForPayment(new RegisterSettlementCommand(
                    new CommandContext("ev-paid-1", "t", OperatorType.SYSTEM, null, "SYS"),
                    "1001", "3001", "BOOKING_ORDER", "3001", "5001", "6001",
                    new BigDecimal("100.00"), new BigDecimal("20.00"), BigDecimal.ZERO,
                    new BigDecimal("10.00"), new BigDecimal("70.00"), "7001"));
            assertEquals("PENDING_TRIGGER", created.status());
            assertEquals(new BigDecimal("80.00"), created.splitBase());
            assertEquals(new BigDecimal("0.8000"), created.feeRateSnapshot());
            assertEquals(new BigDecimal("79.36"), created.merchantShare());

            // Change config AFTER payment: must NOT alter snapshotted feeRate, MUST apply settleDays=15 at verify.
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("req-c2", "t", OperatorType.PLATFORM_OPERATOR, "1", "ADMIN"),
                    "5001", 15, new BigDecimal("1.5000"), true, "v2"));

            OffsetDateTime verifiedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(16);
            SettlementOrderView waiting = admin.markVerified(new MarkSettlementVerifiedCommand(
                    new CommandContext("ev-ver-1", "t", OperatorType.SYSTEM, null, "SYS"),
                    created.settlementOrderId(), verifiedAt, "7002"));
            assertEquals("WAITING", waiting.status());
            assertEquals(15, waiting.settleDaysSnapshot());
            assertEquals(new BigDecimal("0.8000"), waiting.feeRateSnapshot());
            assertEquals(1, db.count("SELECT COUNT(*) FROM async_task WHERE task_type='SETTLEMENT_DUE' AND biz_id="
                    + created.settlementOrderId()));

            // Change config AFTER verification: must NOT alter snapshotted settleDays=15.
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("req-c3", "t", OperatorType.PLATFORM_OPERATOR, "1", "ADMIN"),
                    "5001", 25, new BigDecimal("2.0000"), true, "v3"));

            AtomicReference<SeparateInput> capturedSeparate = new AtomicReference<>();
            StubChannel channel = new StubChannel();
            channel.canSeparateCents = 6973L; // 69.73 yuan available from Lakala 2396
            channel.onSeparate = input -> {
                capturedSeparate.set(input);
                return new VerifiedSeparateSubmit(
                        new SeparateSubmitResult(input.outSeparateNo(), "SEP_0001", SplitStatus.PROCESSING,
                                "PROCESSING", input.logNo(), input.logDate(), input.totalAmountCents()), SHA_A);
            };
            channel.onQuery = (input, expected) -> new VerifiedSplitOutcome(new SplitOutcome(
                    "SEP_0001", expected.outSeparateNo(), CmdType.SEPARATE, LOG_NO, LOG_DATE, "0",
                    LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), 6973L,
                    "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 6971L, 2L, null,
                    List.of(new SplitDetail(null, RECEIVER_NO, 6973L, 6971L, 2L, null))), SHA_B);

            var exec = db.executionService(channel, m -> Optional.of(new ActiveReceiver(m, RECEIVER_NO)),
                    SettlementOrderBlockerPort.none(), BigDecimal.ZERO);
            var dueOut = exec.executeDue(Long.parseLong(created.settlementOrderId()), 1);
            assertTrue(dueOut instanceof SettlementDueTask.Outcome.Done);
            assertNotNull(capturedSeparate.get());
            assertEquals(6973L, capturedSeparate.get().totalAmountCents());
            assertEquals(1, capturedSeparate.get().recvDatas().size());
            assertEquals(RECEIVER_NO, capturedSeparate.get().recvDatas().getFirst().recvNo());

            SettlementOrderView splitting = admin.getById(created.settlementOrderId(),
                    new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals("SPLITTING", splitting.status());
            assertEquals(new BigDecimal("69.73"), splitting.splitAmount());
            assertEquals(new BigDecimal("0.00"), splitting.platformRetainedAmount());
            assertEquals(new BigDecimal("9.63"), splitting.platformSubsidyDue()); // 79.36 - 69.73
            assertNotNull(splitting.activeInstructionId());
            assertEquals(1, db.count("SELECT COUNT(*) FROM async_task WHERE task_type='SETTLEMENT_CHANNEL_QUERY' AND biz_id="
                    + splitting.activeInstructionId()));

            // Run 1296 query coordinator -> confirms SPLIT_SUCCEEDED + 1 Outbox event.
            var writer = db.receiptWriter();
            var queryCoord = db.queryCoordinator(channel, writer);
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    queryCoord.execute(Long.parseLong(splitting.activeInstructionId())));

            SettlementOrderView done = admin.getById(created.settlementOrderId(),
                    new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals("SPLIT_SUCCEEDED", done.status());
            assertEquals(new BigDecimal("69.71"), done.actualMerchantAmount());
            assertEquals(new BigDecimal("0.02"), done.channelSplitFee());
            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='SettlementSplitSucceededEvent.v1'"));
        }
    }

    @Test
    void missingTraceAndInactiveReceiverHoldWithoutCallingChannelThenResume() throws Exception {
        try (Db db = Db.open()) {
            var admin = db.adminService();
            // Payment with NULL log_no / log_date -> must fail closed into ON_HOLD(MISSING_COLLECT_TRACE).
            db.insertPaidPayment(1010L, 2010L, null, 5010L, "1", new BigDecimal("50.00"), null, null);
            SettlementOrderView reg1 = admin.registerForPayment(new RegisterSettlementCommand(
                    new CommandContext("r1", "t", OperatorType.SYSTEM, null, "SYS"),
                    "1010", null, "DEMO", "D1010", "5010", null,
                    new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("50.00"), "8010"));
            admin.markVerified(new MarkSettlementVerifiedCommand(
                    new CommandContext("v1", "t", OperatorType.SYSTEM, null, "SYS"),
                    reg1.settlementOrderId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(8), "8011"));

            AtomicInteger channelCalls = new AtomicInteger();
            StubChannel countingChannel = new StubChannel();
            countingChannel.onQueryAmt = in -> { channelCalls.incrementAndGet(); throw new AssertionError("must not call 2396"); };

            var exec1 = db.executionService(countingChannel, m -> Optional.of(new ActiveReceiver(m, RECEIVER_NO)),
                    SettlementOrderBlockerPort.none(), BigDecimal.ZERO);
            var h1 = exec1.executeDue(Long.parseLong(reg1.settlementOrderId()), 1);
            assertEquals(new SettlementDueTask.Outcome.Held("MISSING_COLLECT_TRACE"), h1);
            assertEquals(0, channelCalls.get());

            // Inactive receiver -> ON_HOLD(RECEIVER_NOT_ACTIVE), then resume -> WAITING.
            db.insertPaidPayment(1011L, 2011L, null, 5011L, "1", new BigDecimal("50.00"), LOG_NO, LOG_DATE);
            SettlementOrderView reg2 = admin.registerForPayment(new RegisterSettlementCommand(
                    new CommandContext("r2", "t", OperatorType.SYSTEM, null, "SYS"),
                    "1011", null, "DEMO", "D1011", "5011", null,
                    new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("50.00"), "8012"));
            admin.markVerified(new MarkSettlementVerifiedCommand(
                    new CommandContext("v2", "t", OperatorType.SYSTEM, null, "SYS"),
                    reg2.settlementOrderId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(8), "8013"));

            var execNoReceiver = db.executionService(countingChannel, SettlementReceiverLookup.failClosed(),
                    SettlementOrderBlockerPort.none(), BigDecimal.ZERO);
            var h2 = execNoReceiver.executeDue(Long.parseLong(reg2.settlementOrderId()), 1);
            assertEquals(new SettlementDueTask.Outcome.Held("RECEIVER_NOT_ACTIVE"), h2);
            assertEquals(0, channelCalls.get());

            SettlementOrderView resumed = admin.resumeHeldOrder(new ResumeSettlementCommand(
                    new CommandContext("req-res-1", "t", OperatorType.PLATFORM_OPERATOR, "9", "ADMIN"),
                    reg2.settlementOrderId(), "receiver active"));
            assertEquals("WAITING", resumed.status());
            assertNull(resumed.holdReason());
            assertEquals(1, db.count("SELECT COUNT(*) FROM async_task WHERE task_key='SETTLEMENT_DUE:"
                    + reg2.settlementOrderId() + ":2'"));
        }
    }

    @Test
    void concurrent1297NotificationAnd1296QueryEmitExactlyOneEvent() throws Exception {
        try (Db db = Db.open()) {
            var admin = db.adminService();
            db.insertPaidPayment(1020L, 2020L, null, 5020L, "1", new BigDecimal("100.00"), LOG_NO, LOG_DATE);
            SettlementOrderView reg = admin.registerForPayment(new RegisterSettlementCommand(
                    new CommandContext("r20", "t", OperatorType.SYSTEM, null, "SYS"),
                    "1020", null, "DEMO", "D1020", "5020", null,
                    new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("100.00"), "8020"));
            admin.markVerified(new MarkSettlementVerifiedCommand(
                    new CommandContext("v20", "t", OperatorType.SYSTEM, null, "SYS"),
                    reg.settlementOrderId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(8), "8021"));

            // Simulate unknown 1293 submit (throws IO): instruction stays SENT, query task is enqueued.
            StubChannel channel = new StubChannel();
            channel.canSeparateCents = 9962L;
            channel.onSeparate = input -> { throw unknownIo(); };

            var exec = db.executionService(channel, m -> Optional.of(new ActiveReceiver(m, RECEIVER_NO)),
                    SettlementOrderBlockerPort.none(), BigDecimal.ZERO);
            assertTrue(exec.executeDue(Long.parseLong(reg.settlementOrderId()), 1) instanceof SettlementDueTask.Outcome.Done);

            var instr = admin.listInstructions(reg.settlementOrderId(),
                    new QueryContext("t", OperatorType.SYSTEM, null)).getFirst();
            assertEquals("SENT", instr.status());
            assertEquals(new BigDecimal("99.40"), instr.merchantAmount());
            assertEquals(new BigDecimal("0.22"), instr.platformAmount());

            // Now race 1297 notification and 1296 query concurrently!
            byte[] noticeBody = ("{\"separate_no\":\"SEP_RACE_1\",\"out_separate_no\":\"" + instr.outSeparateNo()
                    + "\",\"cmd_type\":\"SEPARATE\",\"log_no\":\"" + LOG_NO + "\",\"log_date\":\"20261118\","
                    + "\"total_amt\":\"9962\",\"status\":\"SUCCESS\",\"final_status\":\"SUCCESS\","
                    + "\"detail_datas\":[{\"recv_no\":\"" + RECEIVER_NO + "\",\"amt\":\"9940\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            Map<String, String> noticeHeaders = sign1009(noticeBody);

            channel.onQuery = (in, expected) -> new VerifiedSplitOutcome(new SplitOutcome(
                    "SEP_RACE_1", expected.outSeparateNo(), CmdType.SEPARATE, LOG_NO, LOG_DATE, "0",
                    LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), 9962L,
                    "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 9962L, 0L, null,
                    List.of(new SplitDetail(null, RECEIVER_NO, 9940L, 9940L, 0L, null))), SHA_C);

            var writer = db.receiptWriter();
            var queryCoord = db.queryCoordinator(channel, writer);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<SettlementReceiptWriter.ReceiptResult> fNotice = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return writer.receiveNotification(noticeHeaders, noticeBody);
                });
                Future<SettlementQueryTask.Outcome> fQuery = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return queryCoord.execute(Long.parseLong(instr.instructionId()));
                });
                ready.await();
                start.countDown();
                assertTrue(fNotice.get().succeeded());
                assertEquals(SettlementQueryTask.Outcome.DONE, fQuery.get());
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='SettlementSplitSucceededEvent.v1'"));
            // Duplicate 1297 replay is idempotent.
            var replay = writer.receiveNotification(noticeHeaders, noticeBody);
            assertTrue(replay.replayed());
            assertTrue(replay.succeeded());
            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='SettlementSplitSucceededEvent.v1'"));
        }
    }

    @Test
    void splitFailureThenAdminRetryCreatesSecondAttemptAndSucceeds() throws Exception {
        try (Db db = Db.open()) {
            var admin = db.adminService();
            db.insertPaidPayment(1030L, 2030L, null, 5030L, "1", new BigDecimal("40.00"), LOG_NO, LOG_DATE);
            SettlementOrderView reg = admin.registerForPayment(new RegisterSettlementCommand(
                    new CommandContext("r30", "t", OperatorType.SYSTEM, null, "SYS"),
                    "1030", null, "DEMO", "D1030", "5030", null,
                    new BigDecimal("40.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("40.00"), "8030"));
            admin.markVerified(new MarkSettlementVerifiedCommand(
                    new CommandContext("v30", "t", OperatorType.SYSTEM, null, "SYS"),
                    reg.settlementOrderId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(8), "8031"));

            StubChannel channel = new StubChannel();
            channel.canSeparateCents = 3985L;
            var exec = db.executionService(channel, m -> Optional.of(new ActiveReceiver(m, RECEIVER_NO)),
                    SettlementOrderBlockerPort.none(), BigDecimal.ZERO);
            exec.executeDue(Long.parseLong(reg.settlementOrderId()), 1);

            var firstInstr = admin.listInstructions(reg.settlementOrderId(),
                    new QueryContext("t", OperatorType.SYSTEM, null)).getFirst();
            // 1296 returns FAIL with the same separate_no returned by 1293 submit
            channel.onQuery = (in, expected) -> new VerifiedSplitOutcome(new SplitOutcome(
                    firstInstr.separateNo(), expected.outSeparateNo(), CmdType.SEPARATE, LOG_NO, LOG_DATE, "0",
                    null, LocalDate.of(2026, 11, 25), 3985L,
                    "FAIL", "FAIL", SplitStatus.FAIL, null, null, "账户端处理失败[可用余额不足]", List.of()), SHA_A);
            var writer = db.receiptWriter();
            var queryCoord = db.queryCoordinator(channel, writer);
            assertEquals(SettlementQueryTask.Outcome.DONE, queryCoord.execute(Long.parseLong(firstInstr.instructionId())));

            SettlementOrderView failed = admin.getById(reg.settlementOrderId(),
                    new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals("SPLIT_FAILED", failed.status());
            assertEquals("账户端处理失败[可用余额不足]", failed.lastErrorMessage());
            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='SettlementSplitFailedEvent.v1'"));

            // Admin retries -> round 2 -> attempt 2 -> succeeds!
            SettlementOrderView retried = admin.retryFailedOrder(new RetrySettlementCommand(
                    new CommandContext("req-retry-1", "t", OperatorType.PLATFORM_OPERATOR, "9", "ADMIN"),
                    reg.settlementOrderId(), "retry after balance topped up"));
            assertEquals("WAITING", retried.status());

            exec.executeDue(Long.parseLong(reg.settlementOrderId()), 2);
            var instrs = admin.listInstructions(reg.settlementOrderId(),
                    new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals(2, instrs.size());
            assertEquals(2, instrs.get(1).attemptNo());

            channel.onQuery = (in, expected) -> new VerifiedSplitOutcome(new SplitOutcome(
                    instrs.get(1).separateNo(), expected.outSeparateNo(), CmdType.SEPARATE, LOG_NO, LOG_DATE, "0",
                    null, LocalDate.of(2026, 11, 25), 3985L,
                    "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 3985L, 0L, null, List.of()), SHA_B);
            assertEquals(SettlementQueryTask.Outcome.DONE,
                    queryCoord.execute(Long.parseLong(instrs.get(1).instructionId())));
            assertEquals("SPLIT_SUCCEEDED", admin.getById(reg.settlementOrderId(),
                    new QueryContext("t", OperatorType.SYSTEM, null)).status());
        }
    }

    @Test
    void refundConsumersHoldRecalculateAndCancelAndSkipLegacySettleTypeZero() throws Exception {
        try (Db db = Db.open()) {
            var admin = db.adminService();
            var claims = new JdbcOutboxConsumeGuard(db.source, db.ids);
            AtomicReference<BigDecimal> orderRefunded = new AtomicReference<>(BigDecimal.ZERO.setScale(2));
            AtomicReference<Boolean> orderVerified = new AtomicReference<>(false);
            AtomicReference<OffsetDateTime> orderVerAt = new AtomicReference<>();

            OrderSettlementFactsApi factsApi = (orderId, ctx) -> new OrderSettlementFact(
                    orderId, "5040", "6040", false, orderVerified.get(),
                    new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("20.00"),
                    new BigDecimal("80.00"), orderRefunded.get(), null, null, null, orderVerAt.get(),
                    BigDecimal.ZERO); // merchantCouponDiscount (contract 79): platform coupon only

            var onPaid = new SettlementEventConsumers.OnPaymentSucceeded(db.source, claims, factsApi, admin);
            var onVerified = new SettlementEventConsumers.OnOrderVerified(db.source, claims, factsApi, admin);
            var onRefCreated = new SettlementEventConsumers.OnRefundCreated(db.source, claims, admin);
            var onRefSucceeded = new SettlementEventConsumers.OnRefundSucceeded(db.source, claims, factsApi, admin);

            // Legacy settle_type=0 payment -> consumer claims cleanly as NOOP, creates 0 settlement rows.
            db.insertPaidPayment(1040L, 2040L, 3040L, 5040L, "0", new BigDecimal("80.00"), LOG_NO, LOG_DATE);
            onPaid.consume(event("9001", "PaymentSucceededEvent.v1", 1, "PAYMENT", 1040L,
                    "{\"paymentOrderId\":\"1040\",\"orderId\":\"3040\",\"channelTradeNo\":\"T1\",\"paidAmount\":80.00,\"paidAt\":\"2026-11-18T00:00:00Z\"}"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM settlement_order WHERE payment_id=1040"));

            // Split payment settle_type=1 -> registered, verified, paused by refund, recalculated on partial refund, canceled on full refund.
            db.insertPaidPayment(1041L, 2041L, 3041L, 5040L, "1", new BigDecimal("80.00"), LOG_NO, LOG_DATE);
            onPaid.consume(event("9002", "PaymentSucceededEvent.v1", 1, "PAYMENT", 1041L,
                    "{\"paymentOrderId\":\"1041\",\"orderId\":\"3041\",\"channelTradeNo\":\"T2\",\"paidAmount\":80.00,\"paidAt\":\"2026-11-18T00:00:00Z\"}"));
            SettlementOrderView row = admin.getByPaymentId("1041", new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals("PENDING_TRIGGER", row.status());
            assertEquals(new BigDecimal("99.40"), row.merchantShare());

            OffsetDateTime verTime = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
            orderVerified.set(true);
            orderVerAt.set(verTime);
            onVerified.consume(event("9003", "OrderVerifiedEvent.v2", 2, "ORDER", 3041L,
                    "{\"orderId\":\"3041\",\"verificationId\":\"1\",\"merchantId\":\"5040\",\"storeId\":\"6040\",\"verifiedAt\":\"" + verTime + "\"}"));
            assertEquals("WAITING", admin.getById(row.settlementOrderId(), new QueryContext("t", OperatorType.SYSTEM, null)).status());

            onRefCreated.consume(event("9004", "RefundOrderCreatedEvent.v1", 1, "REFUND", 4041L,
                    "{\"refundOrderId\":\"4041\",\"orderId\":\"3041\",\"refundType\":\"PARTIAL\",\"refundAmount\":40.00}"));
            SettlementOrderView held = admin.getById(row.settlementOrderId(), new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals("ON_HOLD", held.status());
            assertEquals("REFUND_IN_PROGRESS", held.holdReason());

            // Partial refund 40 of 80 -> splitBase=50.00, merchantShare=49.70, released back to WAITING!
            orderRefunded.set(new BigDecimal("40.00"));
            onRefSucceeded.consume(event("9005", "RefundSucceededEvent.v1", 1, "REFUND", 4041L,
                    "{\"refundOrderId\":\"4041\",\"orderId\":\"3041\",\"refundType\":\"PARTIAL\",\"refundAmount\":40.00}"));
            SettlementOrderView recalc = admin.getById(row.settlementOrderId(), new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals("WAITING", recalc.status());
            assertEquals(new BigDecimal("50.00"), recalc.splitBase());
            assertEquals(new BigDecimal("49.70"), recalc.merchantShare());

            // Full refund -> CANCELED!
            orderRefunded.set(new BigDecimal("80.00"));
            onRefSucceeded.consume(event("9006", "RefundSucceededEvent.v1", 1, "REFUND", 4042L,
                    "{\"refundOrderId\":\"4042\",\"orderId\":\"3041\",\"refundType\":\"FULL\",\"refundAmount\":80.00}"));
            SettlementOrderView canceled = admin.getById(row.settlementOrderId(), new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals("CANCELED", canceled.status());
            assertEquals(new BigDecimal("0.00"), canceled.merchantShare());

            // Query listing filter works.
            var page = admin.listOrders(new ListSettlementOrdersQuery(
                    new QueryContext("t", OperatorType.PLATFORM_OPERATOR, "1"), "5040", "CANCELED", 1, 20));
            assertEquals(1L, page.total());
        }
    }

    // ---- Test fixtures & helpers -----------------------------------------------------------

    /**
     * Contract 79 section 6.2: the paid-order consumer books the merchant-funded part of the
     * coupon discount as merchant_discount_amount and keeps only the rest as platform subsidy.
     * Original 100, no flash, coupon 20 of which the merchant funds 10, paid 80, fee 0.6%:
     * split base = 100 - 0 - 10 = 90 = paid 80 + platform subsidy 10; merchant share 89.46.
     */
    @Test
    void merchantCouponDiscountIsMerchantFundedNotPlatformSubsidy() throws Exception {
        try (Db db = Db.open()) {
            var admin = db.adminService();
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("req-mc", "t", OperatorType.PLATFORM_OPERATOR, "1", "ADMIN"),
                    "5040", 10, new BigDecimal("0.6000"), true, "v1"));
            OrderSettlementFactsApi factsApi = (orderId, ctx) -> new OrderSettlementFact(
                    "3040", "5040", "6040", false, false,
                    new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("20.00"),
                    new BigDecimal("80.00"), BigDecimal.ZERO.setScale(2), null, null, null, null,
                    new BigDecimal("10.00"));
            db.insertPaidPayment(1040L, 2040L, 3040L, 5040L, "1", new BigDecimal("80.00"), LOG_NO, LOG_DATE);

            new SettlementEventConsumers.OnPaymentSucceeded(db.source,
                    new JdbcOutboxConsumeGuard(db.source, db.ids), factsApi, admin)
                    .consume(event("9501", "PaymentSucceededEvent.v1", 1, "PAYMENT", 1040L,
                            "{\"paymentOrderId\":\"1040\",\"orderId\":\"3040\",\"channelTradeNo\":\"T1\","
                                    + "\"paidAmount\":80.00,\"paidAt\":\"2026-11-18T00:00:00Z\"}"));

            SettlementOrderView view = admin.getByPaymentId("1040",
                    new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals(new BigDecimal("10.00"), view.merchantDiscountAmount());
            assertEquals(new BigDecimal("10.00"), view.platformSubsidyAmount());
            assertEquals(new BigDecimal("90.00"), view.splitBase());
            assertEquals(new BigDecimal("89.46"), view.merchantShare());
        }
    }

    private static DispatchedEvent event(String id, String type, int ver, String aggType, long aggId, String json) {
        return new DispatchedEvent(id, type, ver, OffsetDateTime.now(ZoneOffset.UTC), aggType, aggId, "trace", json);
    }

    private static LakalaSacsHttpClient.ChannelUnknownException unknownIo() {
        try {
            var ctor = LakalaSacsHttpClient.ChannelUnknownException.class.getDeclaredConstructor(LakalaSacsHttpClient.Reason.class);
            ctor.setAccessible(true);
            return ctor.newInstance(LakalaSacsHttpClient.Reason.IO);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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

    private static final class StubChannel implements SettlementChannel {
        long canSeparateCents = 9940L;
        java.util.function.Function<QueryAmtInput, VerifiedQueryAmt> onQueryAmt;
        java.util.function.Function<SeparateInput, VerifiedSeparateSubmit> onSeparate;
        java.util.function.BiFunction<QueryPlusInput, ExpectedSplit, VerifiedSplitOutcome> onQuery;

        @Override
        public VerifiedQueryAmt querySeparableAmount(QueryAmtInput input) {
            if (onQueryAmt != null) return onQueryAmt.apply(input);
            return new VerifiedQueryAmt(new QueryAmtResult(
                    input.merchantNo(), input.logNo(), input.logDate(), canSeparateCents, canSeparateCents), SHA_A);
        }

        @Override
        public VerifiedSeparateSubmit submitSeparate(SeparateInput input) {
            if (onSeparate != null) return onSeparate.apply(input);
            return new VerifiedSeparateSubmit(new SeparateSubmitResult(
                    input.outSeparateNo(), "SEP_" + input.outSeparateNo(), SplitStatus.PROCESSING,
                    "PROCESSING", input.logNo(), input.logDate(), input.totalAmountCents()), SHA_A);
        }

        @Override
        public VerifiedFallbackSubmit submitFallback(FallbackInput input) {
            return new VerifiedFallbackSubmit(new FallbackSubmitResult(
                    input.outSeparateNo(), "FB_" + input.outSeparateNo(),
                    input.originSeparateNo(), input.originOutSeparateNo(),
                    input.totalAmountCents(), SplitStatus.PROCESSING, "PROCESSING"), SHA_A);
        }

        @Override
        public VerifiedSplitOutcome querySeparateOutcome(QueryPlusInput input, ExpectedSplit expected) {
            if (onQuery != null) return onQuery.apply(input, expected);
            throw new AssertionError("unexpected query");
        }
    }

    private static final class Db implements AutoCloseable {
        private static final List<String> SCHEMAS = List.of(
                "06-核心数据库Schema-v0.1.sql",
                "13-Async-Infra-Schema-v0.1.sql",
                "40-Payment-Foundation-Schema-v0.1.sql",
                "41-Payment-Dispatch-Schema-v0.1.sql",
                "75-Payment-Center-Schema-v0.1.sql",
                "78-Payment-Settlement-Schema-v0.1.sql",
                "79-Payment-Settlement-Fallback-Schema-v0.1.sql",
                "83-Payment-Merchant-Acquiring-Schema-v0.1.sql");

        final String name;
        final String baseUrl;
        final String user;
        final String password;
        final DataSource source;
        final AtomicLong seq = new AtomicLong(500_000_000L);
        final SnowflakeIdGenerator ids = seq::incrementAndGet;

        private Db(String name, String baseUrl, String user, String password, DataSource source) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.user = user;
            this.password = password;
            this.source = source;
        }

        static Db open() throws Exception {
            String url = System.getenv("PAYMENT_CENTER_MYSQL_URL");
            if (url == null || url.isBlank()) url = System.getenv("PAYMENT_REFUND_MYSQL_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "MySQL URL env not set");
            String user = env("PAYMENT_CENTER_MYSQL_USER", env("PAYMENT_REFUND_MYSQL_USER", "root"));
            String pass = env("PAYMENT_CENTER_MYSQL_PASSWORD", env("PAYMENT_REFUND_MYSQL_PASSWORD", "root"));
            String dbName = "paysettle_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String normalized = url.endsWith("/") ? url : url + "/";

            MysqlDataSource admin = new MysqlDataSource();
            admin.setURL(normalized + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            admin.setUser(user);
            admin.setPassword(pass);
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }

            MysqlDataSource ds = new MysqlDataSource();
            ds.setURL(normalized + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&allowMultiQueries=true");
            ds.setUser(user);
            ds.setPassword(pass);

            Path root = repoRoot().resolve("docs/03-database");
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                for (String file : SCHEMAS) {
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
                            new org.springframework.core.io.support.EncodedResource(
                                    new org.springframework.core.io.FileSystemResource(root.resolve(file)),
                                    StandardCharsets.UTF_8));
                }
                // SQL39 column required by JdbcAsyncTaskSubmitter.enqueueAt.
                s.execute("ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
            }
            return new Db(dbName, normalized, user, pass, ds);
        }

        SettlementAdminService adminService() {
            return new SettlementAdminService(source, ids, new JdbcAsyncTaskSubmitter(source, ids));
        }

        SettlementExecutionService executionService(SettlementChannel ch, SettlementReceiverLookup receivers,
                SettlementOrderBlockerPort blockers, BigDecimal lowestRatio) {
            return new SettlementExecutionService(source, ids, ch, receivers, blockers,
                    new JdbcAsyncTaskSubmitter(source, ids),
                    new SettlementExecutionService.Settings(
                            PLATFORM_MCH, ZoneId.of("Asia/Shanghai"), lowestRatio, "1950836",
                            "https://api.example.com/callbacks/lakala/separate"),
                    Clock.fixed(Instant.parse("2026-11-25T04:00:00Z"), ZoneOffset.UTC));
        }

        SettlementReceiptWriter receiptWriter() {
            return new SettlementReceiptWriter(source, ids,
                    SettlementReceiptWriter.NotificationVerifier.fromPublicKey(notifyKeys.getPublic()),
                    new TransactionalOutboxPublisher(source, ids, new com.fasterxml.jackson.databind.ObjectMapper()));
        }

        SettlementQueryCoordinator queryCoordinator(SettlementChannel ch, SettlementReceiptWriter writer) {
            return new SettlementQueryCoordinator(source, ch, writer,
                    ZoneId.of("Asia/Shanghai"), Clock.fixed(Instant.parse("2026-11-25T04:01:00Z"), ZoneOffset.UTC));
        }

        void insertPaidPayment(long id, long paymentNo, Long orderId, long merchantId, String settleType,
                BigDecimal amount, String logNo, LocalDate logDate) throws Exception {
            String sql = "INSERT INTO payment_order(id,payment_no,order_id,biz_type,biz_no,store_id,merchant_id,user_id,"
                    + "amount,status,channel,expire_at,merchant_no,term_no,sub_appid,currency,dispatch_state,"
                    + "channel_trade_no,channel_paid_amount,paid_at,settle_type,log_no,log_date,created_at,updated_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,'PAID','LAKALA_WECHAT',UTC_TIMESTAMP(3),?,'TERM1','wxapp','CNY','OBSERVED',"
                    + "?,?,UTC_TIMESTAMP(3),?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))";
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setLong(2, paymentNo);
                if (orderId == null) {
                    ps.setNull(3, java.sql.Types.BIGINT);
                    ps.setString(4, "DEMO");
                    ps.setString(5, "D" + id);
                    ps.setNull(6, java.sql.Types.BIGINT);
                    ps.setNull(7, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(3, orderId);
                    ps.setNull(4, java.sql.Types.VARCHAR);
                    ps.setNull(5, java.sql.Types.VARCHAR);
                    ps.setLong(6, 6001L);
                    ps.setLong(7, merchantId);
                }
                ps.setLong(8, 7001L);
                ps.setBigDecimal(9, amount);
                ps.setString(10, PLATFORM_MCH);
                ps.setString(11, "20261118" + (66210306990000L + id));
                ps.setBigDecimal(12, amount);
                ps.setString(13, settleType);
                if (logNo == null) ps.setNull(14, java.sql.Types.VARCHAR); else ps.setString(14, logNo);
                if (logDate == null) ps.setNull(15, java.sql.Types.DATE); else ps.setObject(15, logDate);
                ps.executeUpdate();
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
            MysqlDataSource admin = new MysqlDataSource();
            admin.setURL(baseUrl + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            admin.setUser(user);
            admin.setPassword(password);
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + name + "`");
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
}
