package com.petplatform.coupon.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponConsumeCommand;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponFreezeCommand;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponReleaseCommand;
import com.petplatform.coupon.biz.apiimpl.CouponCommandApiImpl;
import com.petplatform.coupon.biz.event.RefundSucceededCouponConsumer;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 61 (CPN-001/CPN-002) acceptance against isolated real MySQL (schema 06 section 8):
 * freeze eligibility gates and row-level CAS (QA-002/CPN-004 same-coupon race is a one-winner
 * decision), consume on payment success, release on payment-timeout close with the
 * AVAILABLE/EXPIRED landing, refund restore (FULL returns with the 24h extension, PARTIAL and
 * LATE_PAYMENT_TIMEOUT are authoritative NOOPs, CPN-005) and the claim/ledger idempotent replays.
 */
class CouponLifecycleMySqlTest {

    private static final long USER = 9_410_000_000_000_201L;
    private static final String STORE = "94100000000000901";
    private static final AtomicLong NUMBERS = new AtomicLong(41_000_000L);
    // Deterministic positive id stand-in; the coupon module only needs uniqueness.
    private static final SnowflakeIdGenerator IDS = NUMBERS::incrementAndGet;

    private CouponLifecycleMySqlTestDatabase database;
    private CouponCommandApiImpl coupons;
    private RefundSucceededCouponConsumer consumer;
    private TransactionTemplate transaction;
    private JdbcOutboxConsumeGuard claims;

    @BeforeEach
    void start() throws Exception {
        database = new CouponLifecycleMySqlTestDatabase();
        var guard = new com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi() {
            @Override public void acquire(List<String> storeIds,
                    com.petplatform.common.QueryContext context) {}
            @Override public void requireHeld(String storeId, javax.sql.DataSource callerSource) {}
        };
        coupons = new CouponCommandApiImpl(database.dataSource(), guard, IDS);
        claims = new JdbcOutboxConsumeGuard(database.dataSource(), IDS);
        consumer = new RefundSucceededCouponConsumer(database.dataSource(), IDS, claims);
        transaction = new TransactionTemplate(
                new DataSourceTransactionManager(database.dataSource()));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void stop() {
        if (database != null) database.close();
    }

    private static CommandContext context(String key) {
        return new CommandContext(key + ":" + NUMBERS.incrementAndGet(), null,
                OperatorType.USER, Long.toString(USER), "test");
    }

    private long template(String ruleJson) {
        long id = NUMBERS.incrementAndGet();
        jdbc().update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at)"
                + " VALUES(?,?,'ACTIVE',100,0,'2026-01-01 00:00:00.000','2099-12-31 23:59:59.000',?,"
                + "0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", id, "测试券" + id, ruleJson);
        return id;
    }

    /** Seeds an AVAILABLE instance; expireAt "far" keeps it valid well past any test clock. */
    private long instance(long templateId, String expireAt) {
        long id = NUMBERS.incrementAndGet();
        jdbc().update("INSERT INTO coupon_instance(id,coupon_template_id,user_id,status,order_id,"
                + "frozen_at,freeze_expire_at,used_at,original_expire_at,expire_at,version,"
                + "created_at,updated_at) VALUES(?,?,?,'AVAILABLE',NULL,NULL,NULL,NULL,?,?,0,"
                + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", id, templateId, USER, expireAt, expireAt);
        return id;
    }

    private JdbcTemplate jdbc() {
        return database.jdbc();
    }

    private CouponFreezeCommand freeze(long couponId, long orderId, String price, OffsetDateTime deadline) {
        return new CouponFreezeCommand(context("freeze"), Long.toString(orderId),
                Long.toString(couponId), Long.toString(USER), STORE, new BigDecimal(price), deadline);
    }

    private static OffsetDateTime deadline() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10).withNano(0);
    }

    private String status(long couponId) {
        return jdbc().queryForObject("SELECT status FROM coupon_instance WHERE id=?", String.class, couponId);
    }

    private int ledgerCount(String requestId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_ledger WHERE request_id=?", Integer.class, requestId);
    }

    @Test
    void freezeBooksDiscountBindsOrderAndWritesLedger() {
        long couponId = instance(template(
                "{\"amountOff\":\"20\",\"thresholdAmount\":\"100.00\"}"), "2099-01-01 00:00:00.000");
        var result = transaction.execute(status -> coupons.freeze(
                freeze(couponId, 9001L, "128.00", deadline())));
        assertEquals(Long.toString(couponId), result.couponInstanceId());
        assertEquals(new BigDecimal("20"), result.discountAmount());
        assertEquals("FROZEN", status(couponId));
        assertEquals(9001L, jdbc().queryForObject(
                "SELECT order_id FROM coupon_instance WHERE id=?", Long.class, couponId));
        assertEquals(1, ledgerCount("CPN:FREEZE:9001"));
        assertEquals("FREEZE", jdbc().queryForObject(
                "SELECT action FROM coupon_ledger WHERE request_id='CPN:FREEZE:9001'", String.class));
    }

    @Test
    void freezeRejectsThresholdShortfallAndNonDiscountableAmountWithoutWrites() {
        long templateId = template(
                "{\"amountOff\":\"20\",\"thresholdAmount\":\"100.00\"}");
        long below = instance(templateId, "2099-01-01 00:00:00.000");
        long over = instance(template(
                "{\"amountOff\":\"50\",\"thresholdAmount\":\"10.00\"}"), "2099-01-01 00:00:00.000");
        long broken = instance(template("{\"thresholdAmount\":\"10.00\"}"), "2099-01-01 00:00:00.000");
        // below: price misses the threshold; over: the face value would zero the payable;
        // broken: no usable amountOff. All three must answer 409 with zero write side effects.
        String[][] cases = {{"below", Long.toString(below), "99.00"},
                {"over", Long.toString(over), "40.00"},
                {"broken", Long.toString(broken), "40.00"}};
        for (String[] probe : cases) {
            var error = assertThrows(ApiException.class, () -> transaction.execute(status ->
                    coupons.freeze(freeze(Long.parseLong(probe[1]), 9100L, probe[2], deadline()))));
            assertEquals("COUPON_NOT_AVAILABLE", error.code(), probe[0]);
        }
        assertEquals("AVAILABLE", status(below));
        assertEquals("AVAILABLE", status(over));
        assertEquals("AVAILABLE", status(broken));
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM coupon_ledger", Integer.class));
    }

    @Test
    void freezeRejectsForeignTemplateValidityAndStatusStates() {
        long expired = instance(template("{\"amountOff\":\"5\"}"), "2020-01-01 00:00:00.000");
        long offlineTemplate = template("{\"amountOff\":\"5\"}");
        long inactive = instance(offlineTemplate, "2099-01-01 00:00:00.000");
        jdbc().update("UPDATE coupon_template SET status='OFFLINE' WHERE id=?", offlineTemplate);
        long absent = NUMBERS.incrementAndGet();
        for (long couponId : new long[] {expired, inactive, absent}) {
            assertEquals("COUPON_NOT_AVAILABLE", assertThrows(ApiException.class,
                    () -> transaction.execute(status -> coupons.freeze(
                            freeze(couponId, 9200L, "50.00", deadline())))).code());
        }
    }

    @Test
    void sameCouponConcurrentFreezeLetsExactlyOneOrderWin() throws Exception {
        long couponId = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        var workers = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var one = workers.submit(() -> transaction.execute(status -> {
                await(start);
                return coupons.freeze(freeze(couponId, 9301L, "80.00", deadline()));
            }));
            var two = workers.submit(() -> transaction.execute(status -> {
                await(start);
                return coupons.freeze(freeze(couponId, 9302L, "80.00", deadline()));
            }));
            start.countDown();
            int winners = 0;
            for (var future : List.of(one, two)) {
                try {
                    assertNotNull(future.get(20, TimeUnit.SECONDS));
                    winners++;
                } catch (java.util.concurrent.ExecutionException lost) {
                    assertTrue(lost.getCause() instanceof ApiException
                            && "COUPON_NOT_AVAILABLE".equals(((ApiException) lost.getCause()).code()),
                            "loser must be COUPON_NOT_AVAILABLE: " + lost.getCause());
                }
            }
            assertEquals(1, winners);
            assertEquals("FROZEN", status(couponId));
            assertEquals(1, jdbc().queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance WHERE order_id IN (9301,9302)",
                    Integer.class));
            assertEquals(1, ledgerCount("CPN:FREEZE:9301") + ledgerCount("CPN:FREEZE:9302"));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void consumeMarksUsedWithChannelPaidAtAndReplayFailsClosed() {
        long couponId = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        transaction.executeWithoutResult(status -> coupons.freeze(
                freeze(couponId, 9401L, "50.00", deadline())));
        OffsetDateTime paidAt = OffsetDateTime.parse("2098-06-01T02:03:04.123Z");
        var result = transaction.execute(status -> coupons.consume(new CouponConsumeCommand(
                context("consume"), "9401", STORE, paidAt)));
        assertEquals(Long.toString(couponId), result.couponInstanceId());
        assertEquals("USED", status(couponId));
        assertEquals("2098-06-01 02:03:04.123", jdbc().queryForObject(
                "SELECT DATE_FORMAT(used_at,'%Y-%m-%d %H:%i:%s.%f') FROM coupon_instance WHERE id=?",
                String.class, couponId).substring(0, 23));
        assertEquals(1, ledgerCount("CPN:CONSUME:9401"));
        // A direct replay is not a silent pass: the binding is no longer frozen.
        assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", assertThrows(ApiException.class,
                () -> transaction.execute(status -> coupons.consume(new CouponConsumeCommand(
                        context("consume"), "9401", STORE, paidAt)))).code());
    }

    @Test
    void releaseRestoresStillValidCouponAndExpiresFrozenPastValidity() {
        long couponId = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        transaction.executeWithoutResult(status -> coupons.freeze(
                freeze(couponId, 9501L, "50.00", deadline())));
        OffsetDateTime closedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var result = transaction.execute(status -> coupons.release(new CouponReleaseCommand(
                context("release"), "9501", STORE, closedAt)));
        assertEquals("AVAILABLE", result.toStatus());
        assertEquals("AVAILABLE", status(couponId));
        assertNull(jdbc().queryForObject(
                "SELECT order_id FROM coupon_instance WHERE id=?", Long.class, couponId));
        assertEquals(1, ledgerCount("CPN:RELEASE:9501"));

        long dying = instance(template("{\"amountOff\":\"10\"}"), "2099-02-01 00:00:00.000");
        transaction.executeWithoutResult(status -> coupons.freeze(
                freeze(dying, 9502L, "50.00", deadline())));
        jdbc().update("UPDATE coupon_instance SET expire_at='2020-01-01 00:00:00.000' WHERE id=?", dying);
        var expiredLanding = transaction.execute(status -> coupons.release(new CouponReleaseCommand(
                context("release"), "9502", STORE, closedAt)));
        assertEquals("EXPIRED", expiredLanding.toStatus());
        assertEquals("EXPIRED", status(dying));
        assertEquals("RELEASE", jdbc().queryForObject(
                "SELECT action FROM coupon_ledger WHERE request_id='CPN:RELEASE:9502'", String.class));
    }

    @Test
    void fullRefundRestoresCouponWithTwentyFourHourExtension() {
        long couponId = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        jdbc().update("UPDATE coupon_instance SET expire_at='2098-01-01 10:00:00.000' WHERE id=?", couponId);
        freezeAndConsume(couponId, 9601L);
        // Remaining validity (10h) is below the 24h boundary relative to succeededAt.
        OffsetDateTime succeededAt = OffsetDateTime.parse("2098-01-01T00:00:00.000Z");
        consumer.consume(refundEvent(88001L, 9601L, "FULL", "MERCHANT_APPROVED", succeededAt));
        assertEquals("AVAILABLE", status(couponId));
        assertEquals("2098-01-02 00:00:00.000", jdbc().queryForObject(
                "SELECT DATE_FORMAT(expire_at,'%Y-%m-%d %H:%i:%s.%f') FROM coupon_instance WHERE id=?",
                String.class, couponId).substring(0, 23));
        assertEquals(1, ledgerCount("CPN:RESTORE:88001"));
    }

    @Test
    void fullRefundKeepsExpireAtWhenRemainingValidityExceedsTwentyFourHours() {
        long couponId = instance(template("{\"amountOff\":\"10\"}"), "2099-05-01 00:00:00.000");
        freezeAndConsume(couponId, 9602L);
        consumer.consume(refundEvent(88002L, 9602L, "FULL", "MERCHANT_REJECT_ORDER",
                OffsetDateTime.now(ZoneOffset.UTC)
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));
        assertEquals("AVAILABLE", status(couponId));
        assertEquals("2099-05-01 00:00:00.000", jdbc().queryForObject(
                "SELECT DATE_FORMAT(expire_at,'%Y-%m-%d %H:%i:%s.%f') FROM coupon_instance WHERE id=?",
                String.class, couponId).substring(0, 23));
        assertEquals(1, ledgerCount("CPN:RESTORE:88002"));
    }

    @Test
    void refundConsumerNoopsForLatePaymentPartialAndInvalidStates() {
        // CPN-005: the late-payment source never touches the coupon lifecycle.
        long late = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        freezeAndConsume(late, 9603L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        consumer.consume(refundEvent(88003L, 9603L, "FULL", "LATE_PAYMENT_TIMEOUT", now));
        assertEquals("USED", status(late));
        assertEquals(0, ledgerCount("CPN:RESTORE:88003"));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='"
                        + RefundSucceededCouponConsumer.CONSUMER + "' AND event_id=88003",
                Integer.class));

        // Partial refunds never return coupons (SSOT section 9.2).
        long partial = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        freezeAndConsume(partial, 9604L);
        consumer.consume(refundEvent(88004L, 9604L, "PARTIAL", "AFTERSALE_DECISION", now));
        assertEquals("USED", status(partial));
        assertEquals(0, ledgerCount("CPN:RESTORE:88004"));

        // FULL refund of an order without a consumed coupon is a NOOP too.
        consumer.consume(refundEvent(88005L, 9788L, "FULL", "MERCHANT_APPROVED", now));
        assertEquals(0, ledgerCount("CPN:RESTORE:88005"));

        // A coupon already past validity stays USED (SSOT 9.1 remaining-validity precondition).
        long stale = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        freezeAndConsume(stale, 9605L);
        jdbc().update("UPDATE coupon_instance SET expire_at='2020-01-01 00:00:00.000' WHERE id=?", stale);
        consumer.consume(refundEvent(88006L, 9605L, "FULL", "MERCHANT_APPROVED", now));
        assertEquals("USED", status(stale));
        assertEquals(0, ledgerCount("CPN:RESTORE:88006"));
    }

    @Test
    void refundConsumerReplayClaimsOnceAndNeverDoubleRestores() {
        long couponId = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        freezeAndConsume(couponId, 9606L);
        var event = refundEvent(88007L, 9606L, "FULL", "MERCHANT_APPROVED",
                OffsetDateTime.now(ZoneOffset.UTC)
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        consumer.consume(event);
        consumer.consume(event);
        assertEquals("AVAILABLE", status(couponId));
        assertEquals(1, ledgerCount("CPN:RESTORE:88007"));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM integration_event_consume_log WHERE event_id=88007",
                Integer.class));
    }

    @Test
    void refundConsumerRejectsTamperedPayloadsFailClosed() {
        long couponId = instance(template("{\"amountOff\":\"10\"}"), "2099-01-01 00:00:00.000");
        freezeAndConsume(couponId, 9607L);
        var good = refundEvent(88008L, 9607L, "FULL", "MERCHANT_APPROVED",
                OffsetDateTime.now(ZoneOffset.UTC)
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        var tampered = new DispatchedEvent(good.eventId(), good.eventType(), 1, good.occurredAt(),
                "REFUND", good.aggregateId(), good.traceId(),
                "{\"refundOrderId\":\"88008\",\"refundNo\":\"1\",\"orderId\":\"9607\","
                        + "\"refundType\":\"FULL\",\"refundSource\":\"MERCHANT_APPROVED\","
                        + "\"refundAmount\":1.00,\"originalPaidAmount\":1.00,"
                        + "\"channelRefundNo\":\"x\",\"succeededAt\":\"2098-01-01T00:00:00.000Z\","
                        + "\"extra\":true}");
        assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", assertThrows(ApiException.class,
                () -> consumer.consume(tampered)).code());
        // Failed decode claims nothing: the event stays retryable for a conforming redelivery.
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM integration_event_consume_log WHERE event_id=88008",
                Integer.class));
        assertEquals("USED", status(couponId));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("race starters never aligned");
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(stopped);
        }
    }

    private void freezeAndConsume(long couponId, long orderId) {
        transaction.executeWithoutResult(status -> coupons.freeze(
                freeze(couponId, orderId, "50.00", deadline())));
        transaction.executeWithoutResult(status -> coupons.consume(new CouponConsumeCommand(
                context("consume"), Long.toString(orderId), STORE, OffsetDateTime.now(ZoneOffset.UTC)
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS))));
        assertEquals("USED", status(couponId));
    }

    private DispatchedEvent refundEvent(long refundOrderId, long orderId, String refundType,
            String refundSource, OffsetDateTime succeededAt) {
        String payload = "{\"refundOrderId\":\"" + refundOrderId + "\",\"refundNo\":\""
                + (refundOrderId + 1) + "\",\"orderId\":\"" + orderId + "\",\"refundType\":\""
                + refundType + "\",\"refundSource\":\"" + refundSource
                + "\",\"refundAmount\":50.00,\"originalPaidAmount\":50.00,"
                + "\"channelRefundNo\":\"CH" + refundOrderId + "\",\"succeededAt\":\""
                + succeededAt + "\"}";
        return new DispatchedEvent(Long.toString(refundOrderId), "RefundSucceededEvent.v1",
                1, succeededAt, "REFUND", refundOrderId, null, payload);
    }
}
