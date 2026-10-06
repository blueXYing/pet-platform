package com.petplatform.notification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.notification.biz.event.MerchantStaffMemberConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.MerchantStaffNotificationStore;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract 54 NTF slice member-consumer acceptance over isolated real MySQL: DISABLED/ENABLED
 * notify only the bound staff account (the OWNER actor is not notified about its own command),
 * redelivery stays exactly-once, and strict payload violations are rejected before any claim.
 */
class MerchantStaffMemberConsumerMySqlTest {
  private final java.util.concurrent.atomic.AtomicLong ids =
      new java.util.concurrent.atomic.AtomicLong(620000);
  private final OffsetDateTime now = OffsetDateTime.parse("2026-10-06T10:00:00.000Z");

  private MerchantStaffMemberConsumer consumer(MySqlNotificationTestDatabase db) {
    var guard = new JdbcOutboxConsumeGuard(db.dataSource(), ids::incrementAndGet);
    return new MerchantStaffMemberConsumer(
        new MerchantStaffNotificationStore(db.dataSource(), guard::tryClaim), ids::incrementAndGet);
  }

  private Map<String, Object> payload(String changeType) {
    var body = new LinkedHashMap<String, Object>();
    body.put("memberId", "7301");
    body.put("merchantId", "7101");
    body.put("storeId", "7201");
    body.put("memberUserId", "1002");
    body.put("changeType", changeType);
    body.put("occurredAt", "2026-10-06T10:00:00.000Z");
    return body;
  }

  private DispatchedEvent event(String id, Map<String, Object> body) throws Exception {
    return new DispatchedEvent(
        id,
        MerchantStaffMemberConsumer.TYPE,
        1,
        now,
        "MERCHANT_MEMBER",
        7301,
        "test-trace",
        new ObjectMapper().writeValueAsString(body));
  }

  @Test
  void disableAndEnableNotifyOnlyTheBoundStaffAccountOnce() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      for (String changeType : new String[] {"DISABLED", "ENABLED"}) {
        var delivery = event(Long.toString(ids.incrementAndGet()), payload(changeType));
        consumer.consume(delivery);
        consumer.consume(delivery);
      }
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
                      + " AND message_type='MER_STAFF_MEMBER' AND biz_type='MERCHANT_MEMBER'"
                      + " AND biz_id=7301 AND category='SERVICE' AND mandatory_inbox=0",
                  Integer.class));
      // The OWNER (actor) and any other account never receive member lifecycle rows.
      assertEquals(
          0,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM notification WHERE receiver_id<>1002", Integer.class));
      String disabled =
          db.jdbc()
              .queryForObject(
                  "SELECT content FROM notification WHERE title='员工身份已停用'", String.class);
      assertTrue(disabled.contains("成员编号7301"));
    }
  }

  @Test
  void rejectsUnknownFieldsBadChangeTypeNumericIdsAndWrongAggregateBeforeClaim() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var secret = payload("DISABLED");
      secret.put("memberName", "MUST_NOT_REACH_INBOX");
      var exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> consumer.consume(event("83001", secret)));
      assertFalse(exception.getMessage().contains("MUST_NOT_REACH_INBOX"));
      assertNull(exception.getCause());
      var badChange = payload("REVOKED");
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(event("83002", badChange)));
      var numericId = payload("ENABLED");
      numericId.put("memberUserId", 1002);
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(event("83003", numericId)));
      var missingField = payload("ENABLED");
      missingField.remove("storeId");
      assertThrows(
          IllegalArgumentException.class, () -> consumer.consume(event("83004", missingField)));
      var base = event("83005", payload("DISABLED"));
      var wrongAggregate =
          new DispatchedEvent(
              base.eventId(), base.eventType(), 1, now, "MERCHANT_MEMBER", 7302,
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
      var delivery = event("83006", payload("DISABLED"));
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
}
