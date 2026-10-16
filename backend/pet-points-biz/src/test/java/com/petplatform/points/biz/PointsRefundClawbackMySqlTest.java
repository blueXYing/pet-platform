package com.petplatform.points.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.points.api.command.PointsTaskCommandApi;
import com.petplatform.points.api.dto.PointsTaskTypes.AwardClaimReceipt;
import com.petplatform.points.biz.apiimpl.PointsTaskCommandApiImpl;
import com.petplatform.points.biz.domain.service.PointsTaskFacts;
import com.petplatform.points.biz.event.PointsRefundClawbackConsumer;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract 60 §7 clawback acceptance over isolated real MySQL (SQL06 §9 + SQL14 + SQL58):
 * FULL reverses the whole remaining order reward; PARTIAL rounds HALF_UP per refund and stays
 * capped by the award across multiple refunds; orders without an order-reward ledger line
 * (never claimed, incl. the late-payment source) are an authoritative NOOP; unknown refund
 * types fail closed; redelivery is absorbed by the consume-log claim. Only ORDER_REWARD is
 * reversed — other earn sources stay untouched.
 */
class PointsRefundClawbackMySqlTest {

  private static final long USER = 9_400_000_000_002_001L;
  private static final long OTHER_USER = 9_400_000_000_002_002L;
  private static final long OPERATOR = 9_100_000_000_000_001L;
  private static final long FIRST_ORDER_ID = 6_100_000_000_000_100L;

  private PointsTaskMySqlTestDatabase database;
  private PointsTaskCommandApiImpl commands;
  private PointsRefundClawbackConsumer consumer;
  private PointsTaskFacts.PointsTaskFixed facts;
  private AtomicLong ids;

  @BeforeEach
  void start() throws Exception {
    database = new PointsTaskMySqlTestDatabase();
    facts = new PointsTaskFacts.PointsTaskFixed();
    facts.firstPaidAt = OffsetDateTime.parse("2026-10-13T02:00:00Z");
    facts.firstPaidOrder = new PointsTaskFacts.FirstPaidOrder(
        Long.toString(FIRST_ORDER_ID), facts.firstPaidAt);
    ids = new AtomicLong(9_400_000_000_300_000L);
    commands = new PointsTaskCommandApiImpl(database.dataSource(), ids::incrementAndGet, facts,
        Clock.fixed(Instant.parse("2026-10-13T03:00:00.000Z"), ZoneOffset.UTC));
    consumer = new PointsRefundClawbackConsumer(database.dataSource(), ids::incrementAndGet,
        new JdbcOutboxConsumeGuard(database.dataSource(), ids::incrementAndGet));
  }

  @AfterEach
  void stop() {
    if (database != null) {
      database.close();
    }
  }

  @Test
  void fullRefundClawsBackWholeRewardAndRedeliveryIsNoop() {
    claimFirstOrder(100);

    DispatchedEvent refund = refundEvent(8_100L, FIRST_ORDER_ID, "FULL", "PRESTART_AUTO",
            "100.00", "100.00");
    consumer.consume(refund);
    assertEquals(0L, balance(USER));
    assertEquals(1, clawbackRows(FIRST_ORDER_ID));
    assertEquals(-100L, clawbackDelta(8_100L));

    // Redelivery of the same event: the consume-log claim absorbs it — no second line.
    consumer.consume(refund);
    assertEquals(0L, balance(USER));
    assertEquals(1, clawbackRows(FIRST_ORDER_ID));

    // A different refund order for the same fully clawed order: remaining is zero — NOOP.
    consumer.consume(refundEvent(8_101L, FIRST_ORDER_ID, "FULL", "PRESTART_AUTO",
            "100.00", "100.00"));
    assertEquals(0L, balance(USER));
    assertEquals(1, clawbackRows(FIRST_ORDER_ID));
  }

  @Test
  void partialRefundClawsRoundedProportionAndStaysCapped() {
    commands.updateConfig(new com.petplatform.points.api.dto.PointsTaskTypes.UpdateConfigCommand(
        5, 30, 50, 3, 100, 101, 20,
        new com.petplatform.common.CommandContext("cfg-1", "t", OperatorType.PLATFORM_OPERATOR,
            String.valueOf(OPERATOR), "ADMIN_WEB")));
    claimFirstOrder(101);

    // 101 × 50% = 50.5 → 四舍五入 51 (SSOT §10.1).
    consumer.consume(refundEvent(8_200L, FIRST_ORDER_ID, "PARTIAL", "MERCHANT_APPROVED",
        "50.00", "100.00"));
    assertEquals(50L, balance(USER));
    assertEquals(-51L, clawbackDelta(8_200L));

    // A second 50% refund recomputes 51 from the award, but only 50 remains unclawed: the
    // cumulative cap books exactly the remainder (the money is fully gone, so are the points).
    consumer.consume(refundEvent(8_201L, FIRST_ORDER_ID, "PARTIAL", "MERCHANT_APPROVED",
        "50.00", "100.00"));
    assertEquals(0L, balance(USER));
    assertEquals(-50L, clawbackDelta(8_201L));

    // Tiny refund rounds to zero: nothing to book, event still consumed.
    consumer.consume(refundEvent(8_202L, FIRST_ORDER_ID, "PARTIAL", "MERCHANT_APPROVED",
        "0.01", "100.00"));
    assertEquals(0L, balance(USER));
    assertEquals(0, clawbackRowsForRefund(8_202L));
  }

  @Test
  void orderWithoutRewardIsNoopAndOtherEarnSourcesStayIntact() {
    // A signed-in user with a first-order claim; the refund targets a DIFFERENT order.
    commands.signIn(new PointsTaskCommandApi.SignInCommand(command(USER, "sign-1")));
    claimFirstOrder(100);

    DispatchedEvent otherOrder = refundEvent(8_300L, 6_999_999_999_999_999L, "FULL",
        "LATE_PAYMENT_TIMEOUT", "100.00", "100.00");
    consumer.consume(otherOrder);
    assertEquals(105L, balance(USER));
    assertEquals(0, clawbackRows(6_999_999_999_999_999L));

    // The user's own first order then refunds in full: only ORDER_REWARD is reversed, the
    // sign-in line survives; a late-payment source with an issued award claws back like any
    // other source (09号 §29.2: ledger presence decides, not the source).
    consumer.consume(refundEvent(8_301L, FIRST_ORDER_ID, "FULL", "LATE_PAYMENT_TIMEOUT",
        "100.00", "100.00"));
    assertEquals(5L, balance(USER));
    assertEquals(1, database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM points_ledger WHERE user_id=? AND biz_type='SIGN_IN'",
        Integer.class, USER));
  }

  @Test
  void neverClaimedOrderAndMalformedEventsFailOrNoop() {
    // Never issued at all: authoritative NOOP with the claim booked.
    consumer.consume(refundEvent(8_400L, FIRST_ORDER_ID, "FULL", "LATE_PAYMENT_TIMEOUT",
        "100.00", "100.00"));
    assertEquals(0, clawbackRows(FIRST_ORDER_ID));
    assertEquals(1, consumeLogRows("POINTS_REFUND_CLAWBACK", "evt-8400"));

    // Unknown refund type is outside the registered matrix: fail closed 503.
    ApiException unknown = assertThrows(ApiException.class,
        () -> consumer.consume(refundEvent(8_401L, FIRST_ORDER_ID, "WEIRD", "PRESTART_AUTO",
            "100.00", "100.00")));
    assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unknown.code());

    // Malformed payload (unknown key) fails closed the same way.
    ApiException malformed = assertThrows(ApiException.class,
        () -> consumer.consume(new DispatchedEvent("evt-8402", "RefundSucceededEvent.v1", 1,
            OffsetDateTime.parse("2026-10-13T04:00:00Z"), "REFUND", 8_402L, "t",
            "{\"refundOrderId\":\"8402\",\"orderId\":\"" + FIRST_ORDER_ID
                + "\",\"extra\":1}")));
    assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, malformed.code());
  }

  // ------------------------------------------------------------------ helpers

  /** Claims the first-order reward through the kernel so the ledger fact is kernel-shaped. */
  private void claimFirstOrder(long expectedPoints) {
    facts.nickname = "豆豆妈";
    AwardClaimReceipt receipt = commands.claimReward(
        new PointsTaskCommandApi.ClaimRewardCommand("FIRST_ORDER", command(USER, "claim-1")));
    assertEquals(Long.toString(expectedPoints), receipt.pointsGranted());
  }

  private long balance(long user) {
    Long value = database.jdbc().queryForObject(
        "SELECT balance FROM points_account WHERE user_id=?", Long.class, user);
    return value == null ? 0L : value;
  }

  private int clawbackRows(long orderId) {
    return database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM points_ledger WHERE order_id=? AND biz_type='REFUND_CLAWBACK'",
        Integer.class, orderId);
  }

  private int clawbackRowsForRefund(long refundOrderId) {
    return database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM points_ledger WHERE biz_id=? AND biz_type='REFUND_CLAWBACK'",
        Integer.class, refundOrderId);
  }

  private long clawbackDelta(long refundOrderId) {
    return database.jdbc().queryForObject(
        "SELECT delta FROM points_ledger WHERE biz_id=? AND biz_type='REFUND_CLAWBACK'",
        Long.class, refundOrderId);
  }

  private int consumeLogRows(String consumerName, String eventId) {
    return database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name=? AND event_id=?",
        Integer.class, consumerName, eventId);
  }

  private DispatchedEvent refundEvent(long refundOrderId, long orderId, String refundType,
      String refundSource, String refundAmount, String originalPaidAmount) {
    String payload = "{\"refundOrderId\":\"" + refundOrderId + "\",\"refundNo\":\""
        + (9_000_000_000L + refundOrderId) + "\",\"orderId\":\"" + orderId
        + "\",\"refundType\":\"" + refundType + "\",\"refundSource\":\"" + refundSource
        + "\",\"refundAmount\":" + refundAmount + ",\"originalPaidAmount\":" + originalPaidAmount
        + ",\"channelRefundNo\":\"CH" + refundOrderId + "\",\"succeededAt\":"
        + "\"2026-10-13T04:00:00.000Z\"}";
    return new DispatchedEvent("evt-" + refundOrderId, "RefundSucceededEvent.v1", 1,
        OffsetDateTime.parse("2026-10-13T04:00:00.000Z"), "REFUND", refundOrderId, "t", payload);
  }

  private static com.petplatform.common.CommandContext command(long user, String requestId) {
    return new com.petplatform.common.CommandContext(requestId, "t", OperatorType.USER,
        String.valueOf(user), "C_MINIAPP");
  }
}
