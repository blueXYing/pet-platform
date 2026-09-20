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
import com.petplatform.notification.biz.event.MerchantApplicationReviewedConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.MerchantReviewNotificationStore;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MerchantApplicationReviewedConsumerMySqlTest {
  private final AtomicLong ids = new AtomicLong(600000);
  private final OffsetDateTime now = OffsetDateTime.parse("2026-09-17T11:00:00.000Z");

  private MerchantApplicationReviewedConsumer consumer(MySqlNotificationTestDatabase db) {
    var guard = new JdbcOutboxConsumeGuard(db.dataSource(), ids::incrementAndGet);
    return new MerchantApplicationReviewedConsumer(
        new MerchantReviewNotificationStore(db.dataSource(), guard::tryClaim),
        ids::incrementAndGet);
  }

  private Map<String, Object> payload(String decision) {
    var body = new LinkedHashMap<String, Object>();
    body.put("applicationId", "2001");
    body.put("applicationNo", "SQ20260917AbCd1234");
    body.put("ownerUserId", "1001");
    body.put("reservedMerchantId", "3001");
    body.put("submittedRevisionId", "4001");
    body.put("reviewDecisionId", "5001");
    body.put("decisionType", decision);
    body.put("applicationStatus", decision.equals("APPROVE") ? "APPROVED" : "REJECTED");
    body.put("applicantVisibleOpinion", decision.equals("APPROVE") ? null : "请补充清晰且完整的有效申请材料");
    body.put("decidedAt", "2026-09-17T11:00:00.000Z");
    return body;
  }

  private DispatchedEvent event(String id, Map<String, Object> payload) throws Exception {
    return new DispatchedEvent(
        id,
        MerchantApplicationReviewedConsumer.TYPE,
        1,
        now,
        "MERCHANT_APPLICATION",
        2001,
        "test-trace",
        new ObjectMapper().writeValueAsString(payload));
  }

  @Test
  void eachDecisionCreatesMandatoryOwnerInboxAndRedeliveryDoesNotDuplicate() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      for (String decision : new String[] {"APPROVE", "REJECT", "REQUEST_CORRECTION"}) {
        var event = event(Long.toString(ids.incrementAndGet()), payload(decision));
        consumer.consume(event);
        consumer.consume(event);
      }
      assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          3,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      assertEquals(
          3,
          db.jdbc()
              .queryForObject(
                  """
                  SELECT COUNT(*) FROM notification WHERE receiver_type='USER' AND receiver_id=1001
                    AND mandatory_inbox=1 AND biz_type='MERCHANT_APPLICATION' AND biz_id=2001
                    AND read_at IS NULL
                  """,
                  Integer.class));
      assertEquals(
          2,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM notification WHERE content LIKE '%请补充清晰且完整%'",
                  Integer.class));
    }
  }

  @Test
  void failedInboxWriteRollsBackConsumeClaimAndRetryPersistsMessage() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var event = event("81001", payload("REJECT"));
      db.jdbc().execute("RENAME TABLE notification TO unavailable_notification");
      assertThrows(RuntimeException.class, () -> consumer.consume(event));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      db.jdbc().execute("RENAME TABLE unavailable_notification TO notification");
      consumer.consume(event);
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
    }
  }

  @Test
  void concurrentRedeliveryCommitsExactlyOneInboxMessage() throws Exception {
    try (var db = new MySqlNotificationTestDatabase();
        var pool = Executors.newFixedThreadPool(2)) {
      var consumer = consumer(db);
      var event = event("81002", payload("APPROVE"));
      Callable<Void> deliver =
          () -> {
            consumer.consume(event);
            return null;
          };
      for (var result : pool.invokeAll(java.util.List.of(deliver, deliver))) result.get();
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
    }
  }

  @Test
  void rejectsSensitiveUnknownFieldsMismatchedStateNumericIdsAndWrongAggregateBeforeClaim()
      throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var secret = payload("REJECT");
      secret.put("internalNote", "MUST_NOT_REACH_INBOX");
      var secretEvent = event("81003", secret);
      var exception =
          assertThrows(IllegalArgumentException.class, () -> consumer.consume(secretEvent));
      assertFalse(exception.getMessage().contains("MUST_NOT_REACH_INBOX"));
      assertNull(exception.getCause());
      var wrongState = payload("REJECT");
      wrongState.put("applicationStatus", "APPROVED");
      var stateEvent = event("81004", wrongState);
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(stateEvent));
      var numberId = payload("APPROVE");
      numberId.put("ownerUserId", 1001);
      var numberEvent = event("81005", numberId);
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(numberEvent));
      var base = event("81006", payload("APPROVE"));
      var wrongAggregate =
          new DispatchedEvent(
              base.eventId(),
              base.eventType(),
              1,
              now,
              "MERCHANT_APPLICATION",
              2002,
              base.traceId(),
              base.payloadJson());
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(wrongAggregate));
      var trailingDocument =
          new DispatchedEvent(
              base.eventId(),
              base.eventType(),
              1,
              now,
              "MERCHANT_APPLICATION",
              2001,
              base.traceId(),
              base.payloadJson() + " {}");
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(trailingDocument));
      var duplicateField =
          new DispatchedEvent(
              base.eventId(),
              base.eventType(),
              1,
              now,
              "MERCHANT_APPLICATION",
              2001,
              base.traceId(),
              base.payloadJson()
                  .replace(
                      "\"ownerUserId\":\"1001\"",
                      "\"ownerUserId\":\"1001\",\"ownerUserId\":\"1002\""));
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(duplicateField));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
    }
  }

  @Test
  void actualOutboxRollbackIsInvisibleAndCommittedEventDispatchesToInbox() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var publisher =
          new TransactionalOutboxPublisher(
              db.dataSource(), ids::incrementAndGet, new ObjectMapper());
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
      var event =
          new IntegrationEvent<>(
              null,
              MerchantApplicationReviewedConsumer.TYPE,
              1,
              now,
              "MERCHANT_APPLICATION",
              "2001",
              "test-trace",
              payload("APPROVE"));
      assertThrows(
          IllegalStateException.class,
          () ->
              transaction.executeWithoutResult(
                  status -> {
                    publisher.publish(event);
                    throw new IllegalStateException("simulated decision rollback");
                  }));
      assertEquals(
          0,
          db.jdbc().queryForObject("SELECT COUNT(*) FROM integration_event_outbox", Integer.class));
      transaction.executeWithoutResult(status -> publisher.publish(event));
      try (var dispatcher =
          new OutboxDispatcher(
              db.dataSource(),
              "review-notification-test",
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

  @Test
  void nonUtcPayloadAndShanghaiConnectionPersistUtcDatetime() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      String url;
      try (var connection = db.dataSource().getConnection()) {
        url = connection.getMetaData().getURL();
      }
      var shanghaiSource =
          new org.springframework.jdbc.datasource.DriverManagerDataSource(
              url.replace("connectionTimeZone=UTC", "connectionTimeZone=Asia/Shanghai"),
              System.getenv().getOrDefault("PLAT003_MYSQL_USER", "root"),
              System.getenv().getOrDefault("PLAT003_MYSQL_PASSWORD", "")) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
              var connection = super.getConnection();
              try (var statement = connection.createStatement()) {
                statement.execute("SET SESSION time_zone = '+08:00'");
                return connection;
              } catch (java.sql.SQLException failure) {
                connection.close();
                throw failure;
              }
            }
          };
      var guard = new JdbcOutboxConsumeGuard(shanghaiSource, ids::incrementAndGet);
      var consumer =
          new MerchantApplicationReviewedConsumer(
              new MerchantReviewNotificationStore(shanghaiSource, guard::tryClaim),
              ids::incrementAndGet);
      var body = payload("APPROVE");
      body.put("decidedAt", "2026-09-17T19:00:00.123+08:00");
      consumer.consume(event("91001", body));
      assertEquals(
          "2026-09-17 11:00:00.123000",
          db.jdbc()
              .queryForObject(
                  "SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f') FROM notification",
                  String.class));
    }
  }
}
