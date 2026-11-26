package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.petplatform.payment.api.dto.ReconcileTypes.ListReconcileDiffsQuery;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileDiffView;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcilePage;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileRunSummary;
import com.petplatform.payment.api.dto.ReconcileTypes.ResolveReconcileDiffCommand;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportQuery;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedDetailPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedResultListPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.DetailItem;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.ResultListItem;
import com.petplatform.payment.biz.task.SettlementDueTask;
import com.petplatform.payment.biz.task.SettlementQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Real MySQL 8 integration tests for Contract 80 Stage 6:
 * <ul>
 *   <li>Strictly read-only platform subsidy payable report aggregation & filtering</li>
 *   <li>Lakala 1879/1877 daily SACS reconciliation diff detection, idempotent re-run dedupe,
 *       manual diff resolve with requestId idempotency, and zero auto-mutation of ledger</li>
 *   <li>Periodic 5-min compensation scanner recovering missed SETTLEMENT_DUE and stuck
 *       SETTLEMENT_CHANNEL_QUERY tasks plus extensible hooks</li>
 * </ul>
 */
class PaymentReconcileMySqlTest {
    private static final String PLATFORM_MCH = "82229005943096D";
    private static final String RECEIVER_NO = "SR202611250001";
    private static final String SHA_A = "a".repeat(64);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void subsidyReportAggregatesOnlySettledPositiveSubsidyRowsAndFiltersCleanly() throws Exception {
        try (Db db = Db.open()) {
            // Merchant 5001, 2026-11: two settled rows with positive platform subsidy.
            db.insertSettlementOrder(1001L, 2001L, 5001L, "SPLIT_SUCCEEDED",
                    new BigDecimal("100.00"), new BigDecimal("70.00"), new BigDecimal("99.40"),
                    new BigDecimal("69.58"), new BigDecimal("0.00"), new BigDecimal("29.82"),
                    Timestamp.valueOf("2026-11-10 08:00:00"));
            db.insertSettlementOrder(1002L, 2002L, 5001L, "PARTIALLY_REVERSED",
                    new BigDecimal("120.00"), new BigDecimal("90.00"), new BigDecimal("119.28"),
                    new BigDecimal("89.46"), new BigDecimal("20.00"), new BigDecimal("9.82"),
                    Timestamp.valueOf("2026-11-20 08:00:00"));
            // Merchant 5001, 2026-11: zero subsidy -> must be excluded.
            db.insertSettlementOrder(1003L, 2003L, 5001L, "SPLIT_SUCCEEDED",
                    new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("99.40"),
                    new BigDecimal("99.40"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    Timestamp.valueOf("2026-11-21 08:00:00"));
            // Merchant 5001, 2026-11: WAITING (not settled yet) -> must be excluded.
            db.insertSettlementOrder(1004L, 2004L, 5001L, "WAITING",
                    new BigDecimal("100.00"), new BigDecimal("60.00"), new BigDecimal("99.40"),
                    null, new BigDecimal("0.00"), new BigDecimal("39.40"),
                    Timestamp.valueOf("2026-11-22 08:00:00"));
            // Merchant 5002, 2026-10: one settled row with subsidy.
            db.insertSettlementOrder(1005L, 2005L, 5002L, "SPLIT_SUCCEEDED",
                    new BigDecimal("80.00"), new BigDecimal("50.00"), new BigDecimal("79.52"),
                    new BigDecimal("49.70"), new BigDecimal("0.00"), new BigDecimal("29.82"),
                    Timestamp.valueOf("2026-10-15 10:00:00"));

            SubsidyReportService service = new SubsidyReportService(db.source);
            QueryContext adminCtx = new QueryContext("tr-1", OperatorType.PLATFORM_OPERATOR, "9001");

            SubsidyReportResponse all = service.querySubsidyReport(
                    new SubsidyReportQuery(adminCtx, null, null, null, 1, 20));
            assertEquals(3L, all.summary().totalOrderCount());
            assertEquals(new BigDecimal("298.20"), all.summary().totalMerchantShare());
            assertEquals(new BigDecimal("208.74"), all.summary().totalSplitAmount());
            assertEquals(new BigDecimal("69.46"), all.summary().totalSubsidyDue());
            assertEquals(2L, all.total());
            assertEquals(2, all.items().size());
            // Ordered by period DESC, merchantId ASC.
            assertEquals("5001", all.items().get(0).merchantId());
            assertEquals("2026-11", all.items().get(0).period());
            assertEquals(2L, all.items().get(0).orderCount());
            assertEquals(new BigDecimal("39.64"), all.items().get(0).totalSubsidyDue());
            assertEquals(new BigDecimal("20.00"), all.items().get(0).totalReversedAmount());
            assertEquals("5002", all.items().get(1).merchantId());
            assertEquals("2026-10", all.items().get(1).period());

            // Date range filter (Nov only).
            SubsidyReportResponse novOnly = service.querySubsidyReport(
                    new SubsidyReportQuery(adminCtx, "5001", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 1, 10));
            assertEquals(1L, novOnly.total());
            assertEquals(2L, novOnly.summary().totalOrderCount());
            assertEquals(new BigDecimal("39.64"), novOnly.summary().totalSubsidyDue());

            // Non-admin caller rejected.
            QueryContext merchantCtx = new QueryContext("tr-2", OperatorType.MERCHANT_STAFF, "5001");
            ApiException forbidden = assertThrows(ApiException.class, () -> service.querySubsidyReport(
                    new SubsidyReportQuery(merchantCtx, null, null, null, 1, 20)));
            assertEquals(CommonApiCodes.FORBIDDEN, forbidden.code());
        }
    }

    @Test
    void dailyReconcileDetectsAllDiffTypesIsIdempotentNeverMutatesLedgerAndResolvesWithRequestId() throws Exception {
        try (Db db = Db.open()) {
            LocalDate reconcileDate = LocalDate.of(2026, 11, 24);
            Timestamp sentUtc = Timestamp.valueOf("2026-11-24 04:00:00");

            // Local instructions on 2026-11-24:
            // 1) SEP_OK: matches channel 100% -> no diff
            db.insertInstruction(3001L, 1001L, 2001L, 5001L, "SPLIT", 1, "SEP_OK", "LKL_OK",
                    new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("100.00"),
                    new BigDecimal("0.60"), "SUCCEEDED", reconcileDate, sentUtc);
            // 2) SEP_AMT_FEE: channel returns different actual_amt and 1877 fee_amt -> AMOUNT_MISMATCH + FEE_MISMATCH
            db.insertInstruction(3002L, 1002L, 2002L, 5001L, "SPLIT", 1, "SEP_AMT_FEE", "LKL_AMT_FEE",
                    new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("100.00"),
                    new BigDecimal("0.60"), "SUCCEEDED", reconcileDate, sentUtc);
            // 3) SEP_ST: local PROCESSING, channel FAIL -> STATUS_MISMATCH
            db.insertInstruction(3003L, 1003L, 2003L, 5001L, "SPLIT", 1, "SEP_ST", "LKL_ST",
                    new BigDecimal("50.00"), new BigDecimal("45.00"), null,
                    null, "PROCESSING", reconcileDate, sentUtc);
            // 4) SEP_LOCAL_ONLY: absent from channel -> MISSING_CHANNEL
            db.insertInstruction(3004L, 1004L, 2004L, 5001L, "SPLIT", 1, "SEP_LOCAL_ONLY", null,
                    new BigDecimal("60.00"), new BigDecimal("50.00"), null,
                    null, "SENT", reconcileDate, sentUtc);

            // Fake 1879/1877 channel returning 2 pages + 1877 details for SEP_AMT_FEE.
            SettlementReconcileChannel fakeChannel = new SettlementReconcileChannel() {
                @Override
                public VerifiedResultListPage queryResultList(QueryResultListInput input) {
                    if (input.pageNo() == 1) {
                        return new VerifiedResultListPage(new QueryResultListPage(2, 4, 2, List.of(
                                new ResultListItem(PLATFORM_MCH, "SEP_OK", "LKL_OK", "LOG001", 10000L,
                                        reconcileDate, CmdType.SEPARATE, List.of(RECEIVER_NO), null, "SUCCESS",
                                        null, 10000L, 60L),
                                new ResultListItem(PLATFORM_MCH, "SEP_AMT_FEE", "LKL_AMT_FEE", "LOG002", 10000L,
                                        reconcileDate, CmdType.SEPARATE, List.of(RECEIVER_NO), null, "SUCCESS",
                                        null, 9900L, null))), SHA_A);
                    }
                    return new VerifiedResultListPage(new QueryResultListPage(2, 4, 2, List.of(
                            new ResultListItem(PLATFORM_MCH, "SEP_ST", "LKL_ST", "LOG003", 5000L,
                                    reconcileDate, CmdType.SEPARATE, List.of(RECEIVER_NO), "insufficient balance", "FAIL",
                                    null, null, null),
                            new ResultListItem(PLATFORM_MCH, "SEP_CH_ONLY", "LKL_CH_ONLY", "LOG005", 7500L,
                                    reconcileDate, CmdType.FALLBACK, List.of(RECEIVER_NO), null, "SUCCESS",
                                    null, 7500L, 0L))), SHA_A);
                }

                @Override
                public VerifiedDetailPage queryDetail(QueryDetailInput input) {
                    if ("SEP_AMT_FEE".equals(input.outSeparateNo())) {
                        return new VerifiedDetailPage(new QueryDetailPage(1, 1, 1, List.of(
                                new DetailItem(PLATFORM_MCH, "LKL_AMT_FEE", "SEP_AMT_FEE", "PetShop",
                                        CmdType.SEPARATE, RECEIVER_NO, 8000L, "LOG002", reconcileDate,
                                        "SUCCESS", reconcileDate, LocalDateTime.of(2026, 11, 24, 12, 0, 0),
                                        null, 7900L, 90L, null))), SHA_A);
                    }
                    return new VerifiedDetailPage(new QueryDetailPage(0, 0, 0, List.of()), SHA_A);
                }
            };

            SettlementReconcileService service = new SettlementReconcileService(
                    db.source, db.ids, fakeChannel, PLATFORM_MCH, SHANGHAI,
                    Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC));
            CommandContext sysCtx = new CommandContext("req-rec-1", "tr-rec", OperatorType.SYSTEM, null, "SCHEDULER");

            ReconcileRunSummary run1 = service.runDailyReconcile(reconcileDate, sysCtx);
            assertEquals("COMPLETED", run1.status());
            assertEquals(4, run1.channelCount());
            assertEquals(4, run1.localCount());
            assertEquals(5, run1.diffCount());
            assertEquals(5, db.count("SELECT COUNT(*) FROM reconcile_diff"));

            // Re-run for same date must dedupe diffs without creating duplicate rows.
            ReconcileRunSummary run2 = service.runDailyReconcile(reconcileDate, sysCtx);
            assertEquals(run1.runId(), run2.runId());
            assertEquals("COMPLETED", run2.status());
            assertEquals(5, db.count("SELECT COUNT(*) FROM reconcile_diff"));

            // Ledger tables must remain 100% untouched (zero auto-mutation).
            assertEquals("PROCESSING", db.queryString("SELECT status FROM settlement_instruction WHERE id=3003"));
            assertEquals("SENT", db.queryString("SELECT status FROM settlement_instruction WHERE id=3004"));

            // Filter by diffType.
            QueryContext adminQ = new QueryContext("tr-q", OperatorType.PLATFORM_OPERATOR, "9001");
            ReconcilePage<ReconcileDiffView> feeDiffs = service.listDiffs(
                    new ListReconcileDiffsQuery(adminQ, reconcileDate, "FEE_MISMATCH", "OPEN", 1, 20));
            assertEquals(1L, feeDiffs.total());
            ReconcileDiffView feeDiff = feeDiffs.items().getFirst();
            assertEquals(new BigDecimal("0.60"), feeDiff.localFeeAmount());
            assertEquals(new BigDecimal("0.90"), feeDiff.channelFeeAmount());

            ReconcilePage<ReconcileDiffView> missingLocal = service.listDiffs(
                    new ListReconcileDiffsQuery(adminQ, reconcileDate, "MISSING_LOCAL", "OPEN", 1, 20));
            assertEquals(1L, missingLocal.total());
            assertEquals("FALLBACK", missingLocal.items().getFirst().bizType());

            // Resolve diff with requestId idempotency.
            CommandContext opCtx = new CommandContext("req-res-1", "tr-res", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN");
            ReconcileDiffView resolved = service.resolveDiff(
                    new ResolveReconcileDiffCommand(opCtx, feeDiff.diffId(), "Confirmed channel fee tier adjustment"));
            assertEquals("RESOLVED", resolved.status());
            assertEquals("9001", resolved.resolvedByOperatorId());
            assertNotNull(resolved.resolvedAt());

            // Same requestId + same note replays cleanly.
            ReconcileDiffView replay = service.resolveDiff(
                    new ResolveReconcileDiffCommand(opCtx, feeDiff.diffId(), "Confirmed channel fee tier adjustment"));
            assertEquals(resolved.diffId(), replay.diffId());
            assertEquals("RESOLVED", replay.status());

            // Same requestId + different note -> IDEMPOTENCY_KEY_CONFLICT.
            ApiException idemConflict = assertThrows(ApiException.class, () -> service.resolveDiff(
                    new ResolveReconcileDiffCommand(opCtx, feeDiff.diffId(), "Different note")));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, idemConflict.code());

            // Different requestId on already RESOLVED diff -> CONFLICT.
            CommandContext opCtx2 = new CommandContext("req-res-2", "tr-res2", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN");
            ApiException alreadyResolved = assertThrows(ApiException.class, () -> service.resolveDiff(
                    new ResolveReconcileDiffCommand(opCtx2, feeDiff.diffId(), "Try again")));
            assertEquals(CommonApiCodes.CONFLICT, alreadyResolved.code());
        }
    }

    @Test
    void compensationScannerRecoversOverdueWaitingOrdersStuckInstructionsAndRunsHooks() throws Exception {
        try (Db db = Db.open()) {
            Instant now = Instant.parse("2026-11-25T10:00:00Z");
            Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

            // 1) Overdue WAITING settlement_order (eligible_at 10 min ago) with NO async_task -> should recover.
            db.insertWaitingOrder(4001L, 5001L, 5001L,
                    Timestamp.from(now.minusSeconds(600)), 1, Timestamp.from(now.minusSeconds(600)));
            // 2) Overdue WAITING settlement_order with ALREADY READY async_task -> should skip.
            db.insertWaitingOrder(4002L, 5002L, 5001L,
                    Timestamp.from(now.minusSeconds(600)), 1, Timestamp.from(now.minusSeconds(600)));
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.source))
                    .executeWithoutResult(st -> new JdbcAsyncTaskSubmitter(db.source, db.ids).enqueueAt(
                            SettlementDueTask.taskKey(4002L, 1), SettlementDueTask.OWNER_MODULE,
                            SettlementDueTask.TASK_TYPE, SettlementDueTask.BIZ_TYPE, 4002L, 1L,
                            SettlementDueTask.payload(4002L, 1), SettlementDueTask.MAX_RETRY_COUNT,
                            SettlementDueTask.RETRY_POLICY, OffsetDateTime.ofInstant(now.minusSeconds(600), ZoneOffset.UTC)));
            // 3) Stuck SENT instruction (updated_at 10 min ago) with NO query task -> should recover.
            db.insertInstruction(6001L, 4003L, 5003L, 5001L, "SPLIT", 1, "SEP_STUCK_1", "LKL_STUCK_1",
                    new BigDecimal("100.00"), new BigDecimal("90.00"), null, null,
                    "SENT", null, Timestamp.from(now.minusSeconds(600)), Timestamp.from(now.minusSeconds(600)));
            // 4) Fresh SENT instruction (updated_at 1 min ago, inside 5-min grace) -> should skip.
            db.insertInstruction(6002L, 4004L, 5004L, 5001L, "SPLIT", 1, "SEP_FRESH_1", "LKL_FRESH_1",
                    new BigDecimal("100.00"), new BigDecimal("90.00"), null, null,
                    "SENT", null, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(60)));

            AtomicInteger hookCalls = new AtomicInteger();
            SettlementCompensationScanner.CompensationHook okHook = () -> {
                hookCalls.incrementAndGet();
                return 2;
            };
            SettlementCompensationScanner.CompensationHook failingHook = () -> {
                throw new RuntimeException("simulated hook error");
            };

            try (SettlementCompensationScanner scanner = new SettlementCompensationScanner(
                    db.source, db.ids, fixedClock, List.of(failingHook, okHook))) {
                int recovered = scanner.scanOnce();
                // 1 due order + 1 stuck instruction + 2 from okHook = 4.
                assertEquals(4, recovered);
                assertEquals(1, hookCalls.get());

                assertEquals("READY", db.queryString(
                        "SELECT status FROM async_task WHERE task_key='" + SettlementDueTask.taskKey(4001L, 1) + "'"));
                assertEquals("READY", db.queryString(
                        "SELECT status FROM async_task WHERE task_key='" + SettlementQueryTask.taskKey(6001L) + "'"));
                assertNull(db.queryString(
                        "SELECT status FROM async_task WHERE task_key='" + SettlementQueryTask.taskKey(6002L) + "'"));
                // Zero duplicate instructions created.
                assertEquals(2, db.count("SELECT COUNT(*) FROM settlement_instruction"));

                // Second scan immediately after sees alive tasks and recovers 0 built-in items (+2 from okHook).
                assertEquals(2, scanner.scanOnce());
                assertTrue(hookCalls.get() == 2);
            }
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
                "82-Payment-Reconcile-Schema-v0.1.sql",
                "83-Payment-Merchant-Acquiring-Schema-v0.1.sql");

        final String name;
        final String baseUrl;
        final String user;
        final String password;
        final DataSource source;
        final AtomicLong seq = new AtomicLong(600_000_000L);
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
            String dbName = "payrec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
                s.execute("ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
            }
            return new Db(dbName, normalized, user, pass, ds);
        }

        void insertSettlementOrder(long id, long paymentId, long merchantId, String status,
                BigDecimal originalPrice, BigDecimal paidAmount, BigDecimal merchantShare,
                BigDecimal splitAmount, BigDecimal reversedAmount, BigDecimal subsidyDue,
                Timestamp updatedAt) throws Exception {
            String sql = "INSERT INTO settlement_order(id,payment_id,order_id,biz_type,biz_no,merchant_id,store_id,"
                    + "original_price,flash_discount_amount,merchant_discount_amount,platform_subsidy_amount,"
                    + "paid_amount,refunded_amount,split_base,fee_rate_snapshot,merchant_share,split_amount,"
                    + "platform_retained_amount,platform_subsidy_due,reversed_amount,status,version,created_at,updated_at) "
                    + "VALUES(?,?,?,'BOOKING_ORDER',?,?,6001,?,0.00,0.00,0.00,?,0.00,?,0.6000,?,?,0.00,?,?,?,0,?,?)";
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setLong(2, paymentId);
                ps.setLong(3, id);
                ps.setString(4, "ORD" + id);
                ps.setLong(5, merchantId);
                ps.setBigDecimal(6, originalPrice);
                ps.setBigDecimal(7, paidAmount);
                ps.setBigDecimal(8, originalPrice);
                ps.setBigDecimal(9, merchantShare);
                if (splitAmount == null) ps.setNull(10, java.sql.Types.DECIMAL); else ps.setBigDecimal(10, splitAmount);
                ps.setBigDecimal(11, subsidyDue);
                ps.setBigDecimal(12, reversedAmount);
                ps.setString(13, status);
                ps.setTimestamp(14, updatedAt);
                ps.setTimestamp(15, updatedAt);
                ps.executeUpdate();
            }
        }

        void insertWaitingOrder(long id, long paymentId, long merchantId,
                Timestamp eligibleAt, int round, Timestamp updatedAt) throws Exception {
            String sql = "INSERT INTO settlement_order(id,payment_id,order_id,biz_type,biz_no,merchant_id,store_id,"
                    + "original_price,flash_discount_amount,merchant_discount_amount,platform_subsidy_amount,"
                    + "paid_amount,refunded_amount,split_base,fee_rate_snapshot,merchant_share,platform_subsidy_due,"
                    + "verified_at,settle_days_snapshot,eligible_at,due_schedule_round,reversed_amount,status,version,created_at,updated_at) "
                    + "VALUES(?,?,?,'BOOKING_ORDER',?,?,6001,100.00,0.00,0.00,0.00,100.00,0.00,100.00,0.6000,99.40,0.00,"
                    + "?,7,?,?,0.00,'WAITING',0,?,?)";
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setLong(2, paymentId);
                ps.setLong(3, id);
                ps.setString(4, "W" + id);
                ps.setLong(5, merchantId);
                ps.setTimestamp(6, updatedAt);
                ps.setTimestamp(7, eligibleAt);
                ps.setInt(8, round);
                ps.setTimestamp(9, updatedAt);
                ps.setTimestamp(10, updatedAt);
                ps.executeUpdate();
            }
        }

        void insertInstruction(long id, long settlementOrderId, long paymentId, long merchantId,
                String type, int attemptNo, String outSeparateNo, String separateNo,
                BigDecimal totalAmount, BigDecimal merchantAmount, BigDecimal actualSeparateAmount,
                BigDecimal totalFeeAmount, String status, LocalDate finishDate, Timestamp sentAt) throws Exception {
            insertInstruction(id, settlementOrderId, paymentId, merchantId, type, attemptNo, outSeparateNo, separateNo,
                    totalAmount, merchantAmount, actualSeparateAmount, totalFeeAmount, status, finishDate, sentAt, sentAt);
        }

        void insertInstruction(long id, long settlementOrderId, long paymentId, long merchantId,
                String type, int attemptNo, String outSeparateNo, String separateNo,
                BigDecimal totalAmount, BigDecimal merchantAmount, BigDecimal actualSeparateAmount,
                BigDecimal totalFeeAmount, String status, LocalDate finishDate,
                Timestamp sentAt, Timestamp updatedAt) throws Exception {
            String sql = "INSERT INTO settlement_instruction(id,settlement_order_id,payment_id,merchant_id,"
                    + "instruction_type,attempt_no,out_separate_no,separate_no,merchant_no,receiver_no,log_no,log_date,"
                    + "total_amount,merchant_amount,platform_amount,actual_separate_amount,total_fee_amount,"
                    + "status,finish_date,sent_at,version,created_at,updated_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,'LOG001',?,?,?,?,?,?,?, ?,?,0,?,?)";
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setLong(2, settlementOrderId);
                ps.setLong(3, paymentId);
                ps.setLong(4, merchantId);
                ps.setString(5, type);
                ps.setInt(6, attemptNo);
                ps.setString(7, outSeparateNo);
                if (separateNo == null) ps.setNull(8, java.sql.Types.VARCHAR); else ps.setString(8, separateNo);
                ps.setString(9, PLATFORM_MCH);
                ps.setString(10, RECEIVER_NO);
                ps.setObject(11, LocalDate.of(2026, 11, 20));
                ps.setBigDecimal(12, totalAmount);
                ps.setBigDecimal(13, merchantAmount);
                ps.setBigDecimal(14, totalAmount.subtract(merchantAmount));
                if (actualSeparateAmount == null) ps.setNull(15, java.sql.Types.DECIMAL);
                else ps.setBigDecimal(15, actualSeparateAmount);
                if (totalFeeAmount == null) ps.setNull(16, java.sql.Types.DECIMAL);
                else ps.setBigDecimal(16, totalFeeAmount);
                ps.setString(17, status);
                if (finishDate == null) ps.setNull(18, java.sql.Types.DATE); else ps.setObject(18, finishDate);
                ps.setTimestamp(19, sentAt);
                ps.setTimestamp(20, sentAt);
                ps.setTimestamp(21, updatedAt);
                ps.executeUpdate();
            }
        }

        int count(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            }
        }

        String queryString(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
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
