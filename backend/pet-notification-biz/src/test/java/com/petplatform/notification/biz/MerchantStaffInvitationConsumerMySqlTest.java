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
import com.petplatform.notification.biz.event.MerchantStaffInvitationConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.MerchantStaffNotificationStore;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 54 NTF slice consumer acceptance over isolated real MySQL: INVITED/CANCELED notify the
 * OWNER (the pre-confirm invitee has no USER account, SQL06 §11 receiver shape), CONFIRMED fans
 * out to owner + confirmed employee in one transaction, redelivery and concurrency stay exactly
 * one delivery, a failed insert rolls the consume claim back, and strict payload violations are
 * rejected before any claim.
 */
class MerchantStaffInvitationConsumerMySqlTest {
  private final java.util.concurrent.atomic.AtomicLong ids =
      new java.util.concurrent.atomic.AtomicLong(610000);
  private final OffsetDateTime now = OffsetDateTime.parse("2026-10-06T09:30:00.000Z");

  private MerchantStaffInvitationConsumer consumer(MySqlNotificationTestDatabase db) {
    var guard = new JdbcOutboxConsumeGuard(db.dataSource(), ids::incrementAndGet);
    return new MerchantStaffInvitationConsumer(
        new MerchantStaffNotificationStore(db.dataSource(), guard::tryClaim), ids::incrementAndGet);
  }

  private Map<String, Object> payload(String changeType) {
    var body = new LinkedHashMap<String, Object>();
    body.put("invitationId", "7001");
    body.put("merchantId", "7101");
    body.put("storeId", "7201");
    body.put("ownerUserId", "1001");
    body.put("memberName", "王小明");
    body.put("phoneMasked", "139****4444");
    body.put("changeType", changeType);
    if ("CONFIRMED".equals(changeType)) {
      body.put("confirmedUserId", "1002");
      body.put("memberId", "7301");
    } else {
      body.put("confirmedUserId", null);
      body.put("memberId", null);
    }
    body.put("occurredAt", "2026-10-06T09:30:00.000Z");
    return body;
  }

  private DispatchedEvent event(String id, Map<String, Object> body) throws Exception {
    return new DispatchedEvent(
        id,
        MerchantStaffInvitationConsumer.TYPE,
        1,
        now,
        "MERCHANT_MEMBER_INVITATION",
        7001,
        "test-trace",
        new ObjectMapper().writeValueAsString(body));
  }

  @Test
  void eachChangeTypeReachesOnlyAccountedReceiversAndRedeliveryDoesNotDuplicate() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      for (String changeType : new String[] {"INVITED", "CANCELED", "CONFIRMED"}) {
        var delivery = event(Long.toString(ids.incrementAndGet()), payload(changeType));
        consumer.consume(delivery);
        consumer.consume(delivery);
      }
      // INVITED + CANCELED + CONFIRMED(owner) + CONFIRMED(employee) = 4 rows, exactly once each.
      assertEquals(4, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          3,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      assertEquals(
          3,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM notification WHERE receiver_id=1001"
                      + " AND message_type='MER_STAFF_INVITATION' AND biz_type='MEMBER_INVITATION'"
                      + " AND biz_id=7001 AND category='SERVICE' AND mandatory_inbox=0"
                      + " AND read_at IS NULL",
                  Integer.class));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM notification WHERE receiver_id=1002"
                      + " AND title='员工邀请确认成功'",
                  Integer.class));
      String invited =
          db.jdbc()
              .queryForObject(
                  "SELECT content FROM notification WHERE title='员工邀请已发出'", String.class);
      assertTrue(invited.contains("编号7001"));
      assertTrue(invited.contains("139****4444"));
      assertFalse(invited.contains("13900004444"));
    }
  }

  @Test
  void failedInboxWriteRollsBackConsumeClaimAndBothRowsOfAConfirm() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var confirmed = event("82001", payload("CONFIRMED"));
      db.jdbc().execute("RENAME TABLE notification TO unavailable_notification");
      assertThrows(RuntimeException.class, () -> consumer.consume(confirmed));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      db.jdbc().execute("RENAME TABLE unavailable_notification TO notification");
      consumer.consume(confirmed);
      // Both receivers of the retried CONFIRMED delivery are persisted atomically.
      assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
    }
  }

  @Test
  void concurrentRedeliveryCommitsExactlyOneDeliveryPerReceiver() throws Exception {
    try (var db = new MySqlNotificationTestDatabase();
        var pool = Executors.newFixedThreadPool(2)) {
      var consumer = consumer(db);
      var confirmed = event("82002", payload("CONFIRMED"));
      Callable<Void> deliver =
          () -> {
            consumer.consume(confirmed);
            return null;
          };
      for (var result : pool.invokeAll(java.util.List.of(deliver, deliver))) result.get();
      assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
    }
  }

  @Test
  void rejectsUnknownFieldsWrongChangeStateNumericIdsAndWrongAggregateBeforeClaim()
      throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var secret = payload("INVITED");
      secret.put("internalNote", "MUST_NOT_REACH_INBOX");
      var secretEvent = event("82003", secret);
      var exception =
          assertThrows(IllegalArgumentException.class, () -> consumer.consume(secretEvent));
      assertFalse(exception.getMessage().contains("MUST_NOT_REACH_INBOX"));
      assertNull(exception.getCause());
      var leakedReceiver = payload("INVITED");
      leakedReceiver.put("memberId", "7301"); // only CONFIRMED may carry the member facts
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("82004", leakedReceiver)));
      var nakedConfirm = payload("CONFIRMED");
      nakedConfirm.put("confirmedUserId", null);
      assertThrows(
          IllegalArgumentException.class, () -> consumer.consume(event("82005", nakedConfirm)));
      var numericId = payload("INVITED");
      numericId.put("ownerUserId", 1001);
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(event("82006", numericId)));
      var badMask = payload("INVITED");
      badMask.put("phoneMasked", "13900004444");
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(event("82007", badMask)));
      var controlName = payload("INVITED");
      controlName.put("memberName", "王\u0001小明");
      assertThrows(
          IllegalArgumentException.class, () -> consumer.consume(event("82008", controlName)));
      var base = event("82009", payload("INVITED"));
      var wrongAggregate =
          new DispatchedEvent(
              base.eventId(), base.eventType(), 1, now, "MERCHANT_MEMBER_INVITATION", 7002,
              base.traceId(), base.payloadJson());
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(wrongAggregate));
      var trailingDocument =
          new DispatchedEvent(
              base.eventId(), base.eventType(), 1, now, "MERCHANT_MEMBER_INVITATION", 7001,
              base.traceId(), base.payloadJson() + " {}");
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(trailingDocument));
      var duplicateField =
          new DispatchedEvent(
              base.eventId(), base.eventType(), 1, now, "MERCHANT_MEMBER_INVITATION", 7001,
              base.traceId(),
              base.payloadJson()
                  .replace(
                      "\"ownerUserId\":\"1001\"", "\"ownerUserId\":\"1001\",\"ownerUserId\":\"1002\""));
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(duplicateField));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
    }
  }

  @Test
  void outboxRollbackIsInvisibleAndCommittedInvitationEventDispatchesToOwnerInbox()
      throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var publisher =
          new TransactionalOutboxPublisher(
              db.dataSource(), ids::incrementAndGet, new ObjectMapper());
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
      var event =
          new IntegrationEvent<>(
              null,
              MerchantStaffInvitationConsumer.TYPE,
              1,
              now,
              "MERCHANT_MEMBER_INVITATION",
              "7001",
              "test-trace",
              payload("INVITED"));
      assertThrows(
          IllegalStateException.class,
          () ->
              transaction.executeWithoutResult(
                  status -> {
                    publisher.publish(event);
                    throw new IllegalStateException("simulated invite rollback");
                  }));
      assertEquals(
          0,
          db.jdbc().queryForObject("SELECT COUNT(*) FROM integration_event_outbox", Integer.class));
      transaction.executeWithoutResult(status -> publisher.publish(event));
      try (var dispatcher =
          new OutboxDispatcher(
              db.dataSource(),
              "staff-invitation-notification-test",
              OutboxDispatchSettings.defaults(),
              new OutboxRetryDelays(java.util.List.of(java.time.Duration.ofMillis(10))),
              java.util.List.of(consumer(db)))) {
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
