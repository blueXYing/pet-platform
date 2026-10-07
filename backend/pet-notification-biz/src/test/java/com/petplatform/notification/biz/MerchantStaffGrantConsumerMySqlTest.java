package com.petplatform.notification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.event.core.OutboxDispatchSettings;
import com.petplatform.event.core.OutboxDispatcher;
import com.petplatform.event.core.OutboxRetryDelays;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.notification.biz.event.MerchantStaffGrantConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.MerchantStaffNotificationStore;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 54 NTF grant-slice consumer acceptance over isolated real MySQL (2026-10-07 ruling):
 * GRANTED (first grant and whole-set replacement) and REVOKED (store-wide revoke) notify only the
 * bound staff account with the action summary in the message, the OWNER actor never receives a
 * row, redelivery stays exactly-once, a failed insert rolls the consume claim back, strict
 * payload violations are rejected before any claim, and a committed outbox event dispatches end
 * to end through the real dispatcher.
 */
class MerchantStaffGrantConsumerMySqlTest {
  private final java.util.concurrent.atomic.AtomicLong ids =
      new java.util.concurrent.atomic.AtomicLong(630000);
  private final OffsetDateTime now = OffsetDateTime.parse("2026-10-07T09:00:00.000Z");

  private MerchantStaffGrantConsumer consumer(MySqlNotificationTestDatabase db) {
    var guard = new JdbcOutboxConsumeGuard(db.dataSource(), ids::incrementAndGet);
    return new MerchantStaffGrantConsumer(
        new MerchantStaffNotificationStore(db.dataSource(), guard::tryClaim), ids::incrementAndGet);
  }

  private Map<String, Object> payload(String changeType, List<String> actions) {
    var body = new LinkedHashMap<String, Object>();
    body.put("memberId", "7401");
    body.put("merchantId", "7101");
    body.put("storeId", "7201");
    body.put("memberUserId", "1002");
    body.put("changeType", changeType);
    body.put("actions", actions);
    body.put("occurredAt", "2026-10-07T09:00:00.000Z");
    return body;
  }

  private DispatchedEvent event(String id, Map<String, Object> body) throws Exception {
    return new DispatchedEvent(
        id,
        MerchantStaffGrantConsumer.TYPE,
        1,
        now,
        "MERCHANT_MEMBER",
        7401,
        "test-trace",
        new ObjectMapper().writeValueAsString(body));
  }

  @Test
  void grantedAndRevokedNotifyOnlyTheBoundStaffAccountOnceWithActionSummary() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var granted = event("94001", payload("GRANTED", List.of("merchant.order.verify")));
      consumer.consume(granted);
      consumer.consume(granted); // redelivery is exactly-once
      var revoked = event("94002", payload("REVOKED", List.of("merchant.order.verify")));
      consumer.consume(revoked);
      consumer.consume(revoked);
      assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          2,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      assertEquals(
          2,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM notification WHERE receiver_id=1002"
                      + " AND message_type='MER_STAFF_GRANT' AND biz_type='MERCHANT_MEMBER'"
                      + " AND biz_id=7401 AND category='SERVICE' AND mandatory_inbox=0",
                  Integer.class));
      // The OWNER (command actor) and any other account never receive grant lifecycle rows.
      assertEquals(
          0,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM notification WHERE receiver_id<>1002", Integer.class));
      String grantedContent =
          db.jdbc()
              .queryForObject(
                  "SELECT content FROM notification WHERE title='门店权限已更新'", String.class);
      assertTrue(grantedContent.contains("订单核销"));
      assertTrue(grantedContent.contains("门店编号7201"));
      String revokedContent =
          db.jdbc()
              .queryForObject(
                  "SELECT content FROM notification WHERE title='门店权限已收回'", String.class);
      assertTrue(revokedContent.contains("订单核销"));
    }
  }

  @Test
  void rejectsUnknownFieldsBadChangeTypeBadActionsAndWrongAggregateBeforeClaim() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var secret = payload("GRANTED", List.of("merchant.order.verify"));
      secret.put("memberName", "MUST_NOT_REACH_INBOX");
      var exception =
          assertThrows(
              IllegalArgumentException.class, () -> consumer.consume(event("94003", secret)));
      assertFalse(exception.getMessage().contains("MUST_NOT_REACH_INBOX"));
      assertNull(exception.getCause());
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("94004", payload("REPLACED", List.of("merchant.order.verify")))));
      // A granted set is never empty; a non-array or malformed code set is invalid.
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("94005", payload("GRANTED", List.of()))));
      var notArray = payload("GRANTED", List.of("merchant.order.verify"));
      notArray.put("actions", "merchant.order.verify");
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(event("94006", notArray)));
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("94007", payload("GRANTED", List.of("Merchant.Order.Verify")))));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              consumer.consume(
                  event("94008", payload("GRANTED", List.of("merchant.order.verify", "merchant.order.verify")))));
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("94009", payload("REVOKED", List.of("order;verify")))));
      var numericId = payload("GRANTED", List.of("merchant.order.verify"));
      numericId.put("memberUserId", 1002);
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(event("94010", numericId)));
      var missingField = payload("REVOKED", List.of("merchant.order.verify"));
      missingField.remove("storeId");
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(event("94011", missingField)));
      var base = event("94012", payload("GRANTED", List.of("merchant.order.verify")));
      var wrongAggregate =
          new DispatchedEvent(
              base.eventId(), base.eventType(), 1, now, "MERCHANT_MEMBER", 7402,
              base.traceId(), base.payloadJson());
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(wrongAggregate));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
    }
  }

  @Test
  void failedInboxWriteRollsBackConsumeClaimAndRetryPersists() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var delivery = event("94013", payload("GRANTED", List.of("merchant.order.verify")));
      db.jdbc().execute("RENAME TABLE notification TO unavailable_notification");
      assertThrows(RuntimeException.class, () -> consumer.consume(delivery));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      db.jdbc().execute("RENAME TABLE unavailable_notification TO notification");
      consumer.consume(delivery);
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
    }
  }

  @Test
  void outboxRollbackIsInvisibleAndCommittedGrantEventDispatchesToMemberInbox() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var publisher =
          new TransactionalOutboxPublisher(
              db.dataSource(), ids::incrementAndGet, new ObjectMapper());
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
      var event =
          new IntegrationEvent<>(
              null,
              MerchantStaffGrantConsumer.TYPE,
              1,
              now,
              "MERCHANT_MEMBER",
              "7401",
              "test-trace",
              payload("GRANTED", List.of("merchant.order.verify")));
      assertThrows(
          IllegalStateException.class,
          () ->
              transaction.executeWithoutResult(
                  status -> {
                    publisher.publish(event);
                    throw new IllegalStateException("simulated grant rollback");
                  }));
      assertEquals(
          0,
          db.jdbc().queryForObject("SELECT COUNT(*) FROM integration_event_outbox", Integer.class));
      transaction.executeWithoutResult(status -> publisher.publish(event));
      try (var dispatcher =
          new OutboxDispatcher(
              db.dataSource(),
              "staff-grant-notification-test",
              OutboxDispatchSettings.defaults(),
              new OutboxRetryDelays(List.of(java.time.Duration.ofMillis(10))),
              List.of(consumer(db)))) {
        assertEquals(OutboxDispatcher.Outcome.COMPLETED, dispatcher.dispatchOne());
      }
      assertEquals(
          "PUBLISHED",
          db.jdbc().queryForObject("SELECT status FROM integration_event_outbox", String.class));
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
    }
  }
}
