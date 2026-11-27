package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.payment.api.dto.WalletTypes.ListWithdrawalsQuery;
import com.petplatform.payment.api.dto.WalletTypes.MerchantWalletView;
import com.petplatform.payment.api.dto.WalletTypes.WalletPage;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawCommand;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawalOrderView;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.DrawState;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Input;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Result;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup.ActiveReceiver;
import com.petplatform.payment.biz.task.WalletQueryTask;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
 * Real MySQL 8 integration tests for Contract 80 Stage 5 (merchant wallet & D1 withdrawal):
 * <ul>
 *   <li>Live 1269 balance + unsettled merchant_share pending projection (never includes platform_subsidy_due)</li>
 *   <li>Ruling 22: admin cannot initiate withdrawal; OWNER-only + requestId idempotency + conflict rejection</li>
 *   <li>1319 send discipline (success -> PROCESSING + task; reject -> FAILED + Outbox; unknown -> PROCESSING + 503)</li>
 *   <li>Concurrent 1271 query x 1272 notification race -> single WithdrawalSucceededEvent.v1 Outbox row</li>
 *   <li>Compensation hook recovery for stuck PROCESSING withdrawals</li>
 * </ul>
 */
class PaymentWalletMySqlTest {
    private static final String RECEIVER_NO = "SR202611250001";
    private static final String SHA_A = "a".repeat(64);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static KeyPair notifyKeys;

    @BeforeAll
    static void initKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        notifyKeys = gen.generateKeyPair();
    }

    @Test
    void walletViewShowsLive1269BalanceAndPendingSettlementWithoutPlatformSubsidy() throws Exception {
        try (Db db = Db.open()) {
            // Insert 1 unsettled WAITING order (merchant_share=99.40, platform_subsidy_due=30.00)
            // and 1 already settled SPLIT_SUCCEEDED order with platform_subsidy_due=50.00 (MUST NOT appear!).
            db.insertSettlementOrder(1001L, 2001L, 5001L, "WAITING",
                    new BigDecimal("99.40"), new BigDecimal("30.00"));
            db.insertSettlementOrder(1002L, 2002L, 5001L, "SPLIT_SUCCEEDED",
                    new BigDecimal("119.28"), new BigDecimal("50.00"));

            FakeWalletChannel ch = new FakeWalletChannel();
            // Unconfigured receiver -> available=0.00, active=false, still shows pendingSettlement.
            WalletService unconfiguredSvc = db.walletService(ch, m -> Optional.empty());
            QueryContext ownerCtx = new QueryContext("tr-w1", OperatorType.MERCHANT_STAFF, "7001");
            MerchantWalletView unconfigured = unconfiguredSvc.getMerchantWallet("5001", ownerCtx);
            assertEquals("UNCONFIGURED", unconfigured.receiverStatus());
            assertFalse(unconfigured.receiverActive());
            assertFalse(unconfigured.channelBalanceAvailable());
            assertEquals(new BigDecimal("0.00"), unconfigured.availableBalance());
            assertEquals(new BigDecimal("99.40"), unconfigured.pendingSettlementAmount());
            assertEquals(1L, unconfigured.pendingSettlementCount());

            // Active receiver -> queries live 1269 curBalance (680.50).
            ch.balance.set(new BigDecimal("680.50"));
            WalletService activeSvc = db.walletService(ch, m -> Optional.of(new ActiveReceiver(m, RECEIVER_NO)));
            MerchantWalletView active = activeSvc.getMerchantWallet("5001", ownerCtx);
            assertEquals("ACTIVE", active.receiverStatus());
            assertTrue(active.receiverActive());
            assertTrue(active.channelBalanceAvailable());
            assertEquals(new BigDecimal("680.50"), active.availableBalance());
            assertEquals(new BigDecimal("99.40"), active.pendingSettlementAmount());
            assertEquals(1L, active.pendingSettlementCount());
            assertNotNull(active.balanceAsOf());

            // Degraded channel 1269 failure -> returns channelBalanceAvailable=false gracefully instead of 500.
            ch.failBalance = true;
            MerchantWalletView degraded = activeSvc.getMerchantWallet("5001", ownerCtx);
            assertTrue(degraded.receiverActive());
            assertFalse(degraded.channelBalanceAvailable());
            assertEquals(new BigDecimal("0.00"), degraded.availableBalance());
            assertNull(degraded.balanceAsOf());
        }
    }

    @Test
    void withdrawEnforcesOwnerOnlyIdempotencyAndConcurrent1271x1272RacePublishesOnce() throws Exception {
        try (Db db = Db.open()) {
            FakeWalletChannel ch = new FakeWalletChannel();
            ch.balance.set(new BigDecimal("500.00"));
            WalletService svc = db.walletService(ch, m -> Optional.of(new ActiveReceiver(m, RECEIVER_NO)));

            // Ruling 22: PLATFORM_OPERATOR must be rejected on withdraw!
            CommandContext adminCtx = new CommandContext("req-adm", "tr-a", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN");
            ApiException adminBlocked = assertThrows(ApiException.class, () -> svc.withdraw(
                    new WithdrawCommand(adminCtx, "5001", new BigDecimal("100.00"))));
            assertEquals(CommonApiCodes.FORBIDDEN, adminBlocked.code());

            // Insufficient balance -> 409 CONFLICT, row marked FAILED + WithdrawalFailedEvent.v1 published once.
            CommandContext overCtx = new CommandContext("req-over", "tr-o", OperatorType.MERCHANT_STAFF, "7001", "MINIAPP");
            ApiException over = assertThrows(ApiException.class, () -> svc.withdraw(
                    new WithdrawCommand(overCtx, "5001", new BigDecimal("600.00"))));
            assertEquals(CommonApiCodes.CONFLICT, over.code());
            assertEquals(1, db.count("SELECT COUNT(*) FROM withdrawal_order WHERE status='FAILED'"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='WithdrawalFailedEvent.v1'"));

            // Happy path OWNER D1 withdraw -> PROCESSING + durable WALLET_WITHDRAW_QUERY task.
            CommandContext ownerCtx = new CommandContext("req-wd-1", "tr-1", OperatorType.MERCHANT_STAFF, "7001", "MINIAPP");
            WithdrawalOrderView created = svc.withdraw(new WithdrawCommand(ownerCtx, "5001", new BigDecimal("200.00")));
            assertEquals("PROCESSING", created.status());
            assertEquals("JNL_1", created.drawJnl());
            assertEquals(new BigDecimal("200.00"), created.amount());
            assertEquals(1, ch.withdrawCalls.get());
            assertEquals("READY", db.queryString("SELECT status FROM async_task WHERE task_key='"
                    + WalletQueryTask.taskKey(Long.parseLong(created.withdrawalId())) + "'"));

            // Idempotent replay with same requestId & amount -> returns same order without a second 1319 call.
            WithdrawalOrderView replay = svc.withdraw(new WithdrawCommand(ownerCtx, "5001", new BigDecimal("200.00")));
            assertEquals(created.withdrawalId(), replay.withdrawalId());
            assertEquals(1, ch.withdrawCalls.get());

            // Same requestId with different amount -> 409 IDEMPOTENCY_KEY_CONFLICT.
            ApiException conflict = assertThrows(ApiException.class, () -> svc.withdraw(
                    new WithdrawCommand(ownerCtx, "5001", new BigDecimal("150.00"))));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());

            // Concurrent 1271 query x 1272 signed callback race -> exactly 1 WithdrawalSucceededEvent.v1 Outbox row!
            ch.queryOutcome.set(new WithdrawOutcome(
                    created.drawJnl(), created.merOrderNo(), RECEIVER_NO,
                    new BigDecimal("200.00"), new BigDecimal("1.00"), "D1",
                    DrawState.SUCCESS, "交易成功", "STL_001", LocalDateTime.of(2026, 11, 25, 14, 0, 0)));
            byte[] notifBody = ("""
                    {"drawJnl":"%s","merOrderNo":"%s","mercId":"%s","drawAmt":"200.00","drawFee":"1.00",
                     "drawMode":"D1","batchAutoSettle":"01","drawState":"DRAW.SUCCESS","settleNo":"STL_001",
                     "completeTime":"2026-11-25 14:00:00","memo":"交易成功"}
                    """.formatted(created.drawJnl(), created.merOrderNo(), RECEIVER_NO))
                    .getBytes(StandardCharsets.UTF_8);
            Map<String, String> notifHeaders = sign1009(notifBody);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<?> f1 = pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ignored) {}
                    return svc.pollWithdrawalStatus(Long.parseLong(created.withdrawalId()));
                });
                Future<?> f2 = pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ignored) {}
                    svc.handleWithdrawalNotification(notifHeaders, notifBody);
                    return "OK";
                });
                ready.await();
                go.countDown();
                assertEquals(WalletQueryTask.Outcome.DONE, f1.get());
                assertEquals("OK", f2.get());
            }

            assertEquals("SUCCEEDED", db.queryString(
                    "SELECT status FROM withdrawal_order WHERE id=" + created.withdrawalId()));
            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='WithdrawalSucceededEvent.v1'"));
            assertEquals("199.00", db.queryString(
                    "SELECT net_amount FROM withdrawal_order WHERE id=" + created.withdrawalId()));

            // Both OWNER and PLATFORM_OPERATOR can list withdrawal ledger (read-only).
            WalletPage<WithdrawalOrderView> adminPage = svc.listWithdrawals(new ListWithdrawalsQuery(
                    new QueryContext("tr-list", OperatorType.PLATFORM_OPERATOR, "9001"),
                    "5001", "SUCCEEDED", 1, 20));
            assertEquals(1L, adminPage.total());
            assertEquals(new BigDecimal("1.00"), adminPage.items().getFirst().feeAmount());
            assertEquals(new BigDecimal("199.00"), adminPage.items().getFirst().netAmount());
        }
    }

    @Test
    void unknown1319OutcomeAndCompensationHookRecoverStuckWithdrawal() throws Exception {
        try (Db db = Db.open()) {
            Instant now = Instant.parse("2026-11-25T10:00:00Z");
            FakeWalletChannel ch = new FakeWalletChannel();
            ch.balance.set(new BigDecimal("300.00"));
            ch.unknownWithdraw = true;
            WalletService svc = db.walletService(ch, m -> Optional.of(new ActiveReceiver(m, RECEIVER_NO)),
                    Clock.fixed(now, ZoneOffset.UTC));

            CommandContext ownerCtx = new CommandContext("req-unk", "tr-u", OperatorType.MERCHANT_STAFF, "7001", "MINIAPP");
            ApiException ex = assertThrows(ApiException.class, () -> svc.withdraw(
                    new WithdrawCommand(ownerCtx, "5001", new BigDecimal("80.00"))));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, ex.code());
            assertEquals(1, db.count("SELECT COUNT(*) FROM withdrawal_order WHERE status='PROCESSING'"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM async_task WHERE task_type='WALLET_WITHDRAW_QUERY'"));

            // Simulate a stuck PROCESSING withdrawal (updated 10 min ago) whose task vanished -> compensation recovers it.
            db.exec("DELETE FROM async_task");
            db.exec("UPDATE withdrawal_order SET updated_at='2026-11-25 09:50:00.000', sent_at='2026-11-25 09:50:00.000'");
            int recovered = svc.scanStuckWithdrawals();
            assertEquals(1, recovered);
            assertEquals(1, db.count("SELECT COUNT(*) FROM async_task WHERE task_type='WALLET_WITHDRAW_QUERY' AND status='READY'"));
        }
    }

    private static Map<String, String> sign1009(byte[] body) throws Exception {
        String ts = "1764052000";
        String nonce = "nonce1234567";
        String msg = ts + "\n" + nonce + "\n" + new String(body, StandardCharsets.UTF_8) + "\n";
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(notifyKeys.getPrivate());
        sig.update(msg.getBytes(StandardCharsets.UTF_8));
        String auth = "LKLAPI-SHA256withRSA timestamp=\"" + ts + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + Base64.getEncoder().encodeToString(sig.sign()) + "\"";
        return Map.of("Authorization", auth);
    }

    private static final class FakeWalletChannel implements WalletChannel {
        final AtomicReference<BigDecimal> balance = new AtomicReference<>(new BigDecimal("100.00"));
        final AtomicReference<WithdrawOutcome> queryOutcome = new AtomicReference<>();
        final AtomicInteger withdrawCalls = new AtomicInteger();
        volatile boolean failBalance = false;
        volatile boolean unknownWithdraw = false;

        @Override
        public VerifiedBalance queryBalance(BalanceQueryInput input) {
            if (failBalance) throw new LakalaV2HttpClient.ChannelRejectedException("ERR999", "channel down");
            return new VerifiedBalance(new BalanceResult("PAY01", "04", "NORMAL",
                    balance.get(), balance.get(), BigDecimal.ZERO), SHA_A);
        }

        @Override
        public VerifiedWithdrawSubmit withdrawD1(WithdrawD1Input input) {
            int n = withdrawCalls.incrementAndGet();
            if (unknownWithdraw) {
                throw new com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ProtocolException("timeout");
            }
            return new VerifiedWithdrawSubmit(new WithdrawD1Result("JNL_" + n, input.merOrderNo()), SHA_A);
        }

        @Override
        public VerifiedWithdrawOutcome queryWithdrawal(WithdrawQueryInput input) {
            WithdrawOutcome out = queryOutcome.get();
            if (out == null) throw new AssertionError("queryOutcome not set");
            return new VerifiedWithdrawOutcome(out, SHA_A);
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
                "80-Payment-Receiver-Schema-v0.1.sql",
                "81-Payment-Wallet-Schema-v0.1.sql",
                "83-Payment-Merchant-Acquiring-Schema-v0.1.sql",
                "84-Payment-Merchant-Onboarding-Schema-v0.1.sql");

        final String name;
        final String baseUrl;
        final String user;
        final String password;
        final DataSource source;
        final AtomicLong seq = new AtomicLong(700_000_000L);
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
            String dbName = "paywal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

        WalletService walletService(WalletChannel ch, SettlementReceiverLookup lookup) {
            return walletService(ch, lookup, Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneOffset.UTC));
        }

        WalletService walletService(WalletChannel ch, SettlementReceiverLookup lookup, Clock clock) {
            var writer = new WalletReceiptWriter(source, ids,
                    WalletReceiptWriter.NotificationVerifier.fromPublicKey(notifyKeys.getPublic()),
                    new TransactionalOutboxPublisher(source, ids, new ObjectMapper()),
                    SHANGHAI);
            return new WalletService(source, ids, lookup, ch, writer,
                    new JdbcAsyncTaskSubmitter(source, ids),
                    new WalletService.Settings("1950836", "https://api.example.com/api/v1/callbacks/lakala/withdrawals"),
                    clock);
        }

        void insertSettlementOrder(long id, long paymentId, long merchantId, String status,
                BigDecimal merchantShare, BigDecimal subsidyDue) throws Exception {
            String sql = "INSERT INTO settlement_order(id,payment_id,order_id,biz_type,biz_no,merchant_id,store_id,"
                    + "original_price,flash_discount_amount,merchant_discount_amount,platform_subsidy_amount,"
                    + "paid_amount,refunded_amount,split_base,fee_rate_snapshot,merchant_share,platform_subsidy_due,"
                    + "reversed_amount,status,version,created_at,updated_at) "
                    + "VALUES(?,?,?,'BOOKING_ORDER',?,?,6001,100.00,0.00,0.00,0.00,100.00,0.00,100.00,0.6000,?,?,0.00,?,0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))";
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setLong(2, paymentId);
                ps.setLong(3, id);
                ps.setString(4, "ORD" + id);
                ps.setLong(5, merchantId);
                ps.setBigDecimal(6, merchantShare);
                ps.setBigDecimal(7, subsidyDue);
                ps.setString(8, status);
                ps.executeUpdate();
            }
        }

        void exec(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
                s.execute(sql);
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
