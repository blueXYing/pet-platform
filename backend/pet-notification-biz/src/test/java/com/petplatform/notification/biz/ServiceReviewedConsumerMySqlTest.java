package com.petplatform.notification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.event.core.OutboxDispatchSettings;
import com.petplatform.event.core.OutboxDispatcher;
import com.petplatform.event.core.OutboxRetryDelays;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.notification.api.command.NotificationReadCommandApi.MarkReadCommand;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationItem;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationPage;
import com.petplatform.notification.biz.application.NotificationInboxService;
import com.petplatform.notification.biz.event.ServiceReviewedConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationInboxStore;
import com.petplatform.notification.biz.infrastructure.persistence.ServiceReviewNotificationStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ServiceReviewedEvent.v1 consumption slice (nine-field payload finalized in Event08, PR#68):
 * strict payload validation, owner-scoped inbox write with consume-log idempotency, the
 * receiver taken from the event-carried ownerUserId (no merchant table read), retry-without-loss,
 * and receiver isolation on the existing USER inbox read surface. Runs on the authoritative
 * Schema 06 over real MySQL.
 */
class ServiceReviewedConsumerMySqlTest {
  private static final Instant READ_CLOCK = Instant.parse("2026-09-22T12:00:00.123Z");
  private final AtomicLong ids = new AtomicLong(760000);
  private final OffsetDateTime now = OffsetDateTime.parse("2026-09-22T10:00:00.000Z");

  private ServiceReviewedConsumer consumer(MySqlNotificationTestDatabase db) {
    var guard = new JdbcOutboxConsumeGuard(db.dataSource(), ids::incrementAndGet);
    return new ServiceReviewedConsumer(
        new ServiceReviewNotificationStore(db.dataSource(), guard::tryClaim),
        ids::incrementAndGet);
  }

  private Map<String, Object> payload(String decision) {
    var body = new LinkedHashMap<String, Object>();
    body.put("serviceId", "7001");
    body.put("serviceName", "宠物基础洗护");
    body.put("merchantId", "3001");
    body.put("storeId", "3101");
    body.put("submissionNo", 1);
    body.put("decisionType", decision);
    body.put("opinion", "REJECT".equals(decision) ? "服务图片与门类不符，请补充真实拍摄图片后重新提交" : null);
    body.put("decidedAt", "2026-09-22T10:00:00.000Z");
    body.put("ownerUserId", "1001");
    return body;
  }

  private DispatchedEvent event(String id, Map<String, Object> payload) throws Exception {
    return new DispatchedEvent(
        id,
        ServiceReviewedConsumer.TYPE,
        1,
        now,
        "SERVICE",
        7001,
        "test-trace",
        new ObjectMapper().writeValueAsString(payload));
  }

  @Test
  void eachDecisionCreatesMandatoryOwnerInboxAndRedeliveryDoesNotDuplicate() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      for (String decision : new String[] {"APPROVE", "REJECT"}) {
        var event = event(Long.toString(ids.incrementAndGet()), payload(decision));
        consumer.consume(event);
        consumer.consume(event);
      }
      assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          2,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      var row =
          db.jdbc()
              .queryForMap(
                  "SELECT receiver_type, receiver_id, category, message_type, biz_type, biz_id,"
                      + " title, content, mandatory_inbox FROM notification WHERE content LIKE '%已上架%'");
      assertEquals("USER", row.get("receiver_type"));
      assertEquals(1001L, ((Number) row.get("receiver_id")).longValue());
      assertEquals("SYSTEM", row.get("category"));
      assertEquals("SERVICE_REVIEWED", row.get("message_type"));
      assertEquals("SERVICE", row.get("biz_type"));
      assertEquals(7001L, ((Number) row.get("biz_id")).longValue());
      assertEquals("服务审核结果", row.get("title"));
      assertEquals("服务《宠物基础洗护》：审核通过，已上架", row.get("content"));
      assertEquals(Boolean.TRUE, row.get("mandatory_inbox"));
      assertEquals(
          "服务《宠物基础洗护》：审核未通过。服务图片与门类不符，请补充真实拍摄图片后重新提交",
          db.jdbc()
              .queryForObject(
                  "SELECT content FROM notification WHERE content LIKE '%未通过%'", String.class));
    }
  }

  @Test
  void failedInboxWriteRollsBackConsumeClaimAndRetryPersistsMessage() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var event = event("97001", payload("REJECT"));
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
      var event = event("97002", payload("APPROVE"));
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
  void rejectsSensitiveUnknownFieldsMismatchedShapesAndWrongAggregateBeforeClaim()
      throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var secret = payload("REJECT");
      secret.put("internalNote", "MUST_NOT_REACH_INBOX");
      var secretEvent = event("97003", secret);
      var exception =
          assertThrows(IllegalArgumentException.class, () -> consumer.consume(secretEvent));
      assertFalse(exception.getMessage().contains("MUST_NOT_REACH_INBOX"));
      assertNull(exception.getCause());
      var unknownDecision = payload("REQUEST_CORRECTION");
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("97004", unknownDecision)));
      var stringSubmissionNo = payload("APPROVE");
      stringSubmissionNo.put("submissionNo", "1");
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("97005", stringSubmissionNo)));
      var longName = payload("APPROVE");
      longName.put("serviceName", "超".repeat(51));
      assertThrows(
          IllegalArgumentException.class, () -> consumer.consume(event("97006", longName)));
      var rejectWithoutOpinion = payload("REJECT");
      rejectWithoutOpinion.put("opinion", null);
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("97007", rejectWithoutOpinion)));
      var overlongOpinion = payload("REJECT");
      overlongOpinion.put("opinion", "驳回".repeat(251));
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(event("97008", overlongOpinion)));
      var numberId = payload("APPROVE");
      numberId.put("merchantId", 3001);
      var numberEvent = event("97009", numberId);
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(numberEvent));
      var base = event("97010", payload("APPROVE"));
      var wrongAggregate =
          new DispatchedEvent(
              base.eventId(),
              base.eventType(),
              1,
              now,
              "SERVICE",
              7002,
              base.traceId(),
              base.payloadJson());
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(wrongAggregate));
      var trailingDocument =
          new DispatchedEvent(
              base.eventId(),
              base.eventType(),
              1,
              now,
              "SERVICE",
              7001,
              base.traceId(),
              base.payloadJson() + " {}");
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(trailingDocument));
      var duplicateField =
          new DispatchedEvent(
              base.eventId(),
              base.eventType(),
              1,
              now,
              "SERVICE",
              7001,
              base.traceId(),
              base.payloadJson()
                  .replace(
                      "\"merchantId\":\"3001\"",
                      "\"merchantId\":\"3001\",\"merchantId\":\"3002\""));
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(duplicateField));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
    }
  }

  @Test
  void missingOrMalformedOwnerUserIdIsStrictlyRejectedBeforeClaim() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      var missing = payload("APPROVE");
      missing.remove("ownerUserId");
      assertThrows(
          IllegalArgumentException.class, () -> consumer.consume(event("97011", missing)));
      var numeric = payload("APPROVE");
      numeric.put("ownerUserId", 1001);
      assertThrows(
          IllegalArgumentException.class, () -> consumer.consume(event("97014", numeric)));
      var zero = payload("APPROVE");
      zero.put("ownerUserId", "0");
      assertThrows(IllegalArgumentException.class, () -> consumer.consume(event("97015", zero)));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      // Strict failures happen before the claim, so the same eventId stays retryable; a
      // well-formed redelivery persists with the receiver taken from the payload field.
      var addressed = payload("REJECT");
      addressed.put("ownerUserId", "1099");
      consumer.consume(event("97011", addressed));
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          1099L,
          db.jdbc()
              .queryForObject("SELECT receiver_id FROM notification", Long.class)
              .longValue());
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
              ServiceReviewedConsumer.TYPE,
              1,
              now,
              "SERVICE",
              "7001",
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
              "service-review-notification-test",
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
  void ownerInboxReadIsolationAndIdempotentMarkRead() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var consumer = consumer(db);
      consumer.consume(event("97012", payload("REJECT")));
      var service =
          new NotificationInboxService(
              new NotificationInboxStore(db.dataSource()),
              Clock.fixed(READ_CLOCK, ZoneOffset.UTC));
      NotificationPage owner =
          service.listInbox(1, 20, new QueryContext("trace", OperatorType.USER, "1001"));
      assertEquals(1, owner.total());
      NotificationItem item = owner.items().get(0);
      assertEquals("SERVICE_REVIEWED", item.messageType());
      assertEquals("SERVICE", item.bizType());
      assertEquals("7001", item.bizId());
      assertEquals("服务审核结果", item.title());
      assertTrue(item.content().contains("审核未通过"));
      assertNull(item.readAt());
      NotificationPage other =
          service.listInbox(1, 20, new QueryContext("trace", OperatorType.USER, "1002"));
      assertEquals(0, other.total());
      assertTrue(other.items().isEmpty());
      var foreign =
          assertThrows(
              ApiException.class,
              () ->
                  service.getInboxItem(
                      item.id(), new QueryContext("trace", OperatorType.USER, "1002")));
      assertEquals("COMMON_NOT_FOUND", foreign.code());
      var foreignRead =
          assertThrows(
              ApiException.class,
              () ->
                  service.markRead(
                      new MarkReadCommand(
                          item.id(),
                          new CommandContext(
                              "97012-read", "trace", OperatorType.USER, "1002", "C_MINIAPP"))));
      assertEquals("COMMON_NOT_FOUND", foreignRead.code());
      var first =
          service.markRead(
              new MarkReadCommand(
                  item.id(),
                  new CommandContext(
                      "97012-read", "trace", OperatorType.USER, "1001", "C_MINIAPP")));
      var replay =
          service.markRead(
              new MarkReadCommand(
                  item.id(),
                  new CommandContext(
                      "97012-other-request", "trace", OperatorType.USER, "1001", "C_MINIAPP")));
      assertEquals(first.readAt(), replay.readAt());
      assertEquals(
          first.readAt(),
          service
              .getInboxItem(item.id(), new QueryContext("trace", OperatorType.USER, "1001"))
              .readAt());
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
          new ServiceReviewedConsumer(
              new ServiceReviewNotificationStore(shanghaiSource, guard::tryClaim),
              ids::incrementAndGet);
      var body = payload("APPROVE");
      body.put("decidedAt", "2026-09-22T18:00:00.123+08:00");
      consumer.consume(event("97013", body));
      assertEquals(
          "2026-09-22 10:00:00.123000",
          db.jdbc()
              .queryForObject(
                  "SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f') FROM notification",
                  String.class));
    }
  }
}
