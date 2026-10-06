package com.petplatform.notification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.notification.biz.delivery.WechatDeliveryTaskHandler;
import com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer;
import com.petplatform.notification.biz.delivery.spi.UnconfiguredWechatDeliveryAdapter;
import com.petplatform.notification.biz.delivery.spi.WechatDeliveryOutcome;
import com.petplatform.notification.biz.event.MerchantApplicationReviewedConsumer;
import com.petplatform.notification.biz.event.ServiceReviewedConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationDeliveryStore;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationInboxStore;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.task.core.TaskExecutionContext;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskLease;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * NTF-002 producer/consumer integration over real MySQL: atomic inbox+delivery+task commit,
 * replay idempotency, rollback containment, and the explicit default-OFF zero-behavior assertion.
 * External WeChat interactions stay on the in-process scripted fake; the unconfigured shell is
 * the production path exercised here.
 */
class WechatDeliveryFlowMySqlTest {
  private final AtomicLong ids = new AtomicLong(800000);
  private final OffsetDateTime now = OffsetDateTime.parse("2026-10-06T09:00:00.000Z");
  private static final String CHANNEL = "WECHAT_SUBSCRIBE";

  private SnowflakeIdGenerator snowflake() {
    return () -> java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }

  private WechatDeliveryTaskProducer producer(MySqlNotificationTestDatabase db, SnowflakeIdGenerator snow) {
    return new WechatDeliveryTaskProducer(
        new NotificationDeliveryStore(db.dataSource()),
        new JdbcAsyncTaskSubmitter(db.dataSource(), snow),
        snow,
        CHANNEL,
        5);
  }

  private Map<String, Object> merchantPayload(String decision) {
    var body = new LinkedHashMap<String, Object>();
    body.put("applicationId", "2001");
    body.put("applicationNo", "SQ20261006AbCd1234");
    body.put("ownerUserId", "1001");
    body.put("reservedMerchantId", "3001");
    body.put("submittedRevisionId", "4001");
    body.put("reviewDecisionId", "5001");
    body.put("decisionType", decision);
    body.put("applicationStatus", decision.equals("APPROVE") ? "APPROVED" : "REJECTED");
    body.put("applicantVisibleOpinion", decision.equals("APPROVE") ? null : "请补充清晰且完整的有效申请材料");
    body.put("decidedAt", "2026-10-06T09:00:00.000Z");
    return body;
  }

  private Map<String, Object> servicePayload() {
    var body = new LinkedHashMap<String, Object>();
    body.put("serviceId", "6001");
    body.put("serviceName", "宠物洗护标准服务");
    body.put("merchantId", "3001");
    body.put("storeId", "3101");
    body.put("submissionNo", 3);
    body.put("decisionType", "APPROVE");
    body.put("opinion", null);
    body.put("decidedAt", "2026-10-06T09:00:00.000Z");
    body.put("ownerUserId", "1001");
    return body;
  }

  private DispatchedEvent merchantEvent(String id, Map<String, Object> payload) throws Exception {
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

  private DispatchedEvent serviceEvent(String id, Map<String, Object> payload) throws Exception {
    return new DispatchedEvent(
        id,
        ServiceReviewedConsumer.TYPE,
        1,
        now,
        "SERVICE",
        6001,
        "test-trace",
        new ObjectMapper().writeValueAsString(payload));
  }

  @Test
  void merchantConsumerWithProducerCommitsInboxDeliveryRowAndDurableTaskAtomically()
      throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var snow = snowflake();
      var guard = new JdbcOutboxConsumeGuard(db.dataSource(), snow::nextId);
      var consumer =
          new MerchantApplicationReviewedConsumer(db.dataSource(), snow, guard::tryClaim, producer(db, snow));
      var event = merchantEvent(Long.toString(ids.incrementAndGet()), merchantPayload("APPROVE"));
      consumer.consume(event);
      consumer.consume(event); // replay: consume guard keeps one of everything
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM notification_delivery WHERE channel=? AND status='PENDING'",
                  Integer.class,
                  CHANNEL));
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
      assertEquals(
          "WECHAT_DELIVER",
          db.jdbc().queryForObject("SELECT task_type FROM async_task", String.class));
      assertEquals(
          "READY", db.jdbc().queryForObject("SELECT status FROM async_task", String.class));
      assertEquals(
          5, db.jdbc().queryForObject("SELECT max_retry_count FROM async_task", Integer.class));
      assertEquals(
          "WECHAT_DELIVER", db.jdbc().queryForObject("SELECT retry_policy FROM async_task", String.class));
      assertEquals(
          "NOTIFICATION", db.jdbc().queryForObject("SELECT owner_module FROM async_task", String.class));
      // Task payload binds the notification: handler decode against the stored row must succeed.
      String payloadJson = db.jdbc().queryForObject("SELECT payload_json FROM async_task", String.class);
      long notificationId = db.jdbc().queryForObject("SELECT id FROM notification", Long.class);
      long bizId = db.jdbc().queryForObject("SELECT biz_id FROM async_task", Long.class);
      assertEquals(notificationId, bizId);
      var decoded = WechatDeliveryTaskProducer.decodePayload(payloadJson, bizId);
      assertEquals(notificationId, decoded.notificationId());
      assertEquals(1001, decoded.receiverUserId());
    }
  }

  @Test
  void serviceReviewedConsumerEnqueuesDeliveryWithTheSameSemantics() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var snow = snowflake();
      var guard = new JdbcOutboxConsumeGuard(db.dataSource(), snow::nextId);
      var consumer =
          new ServiceReviewedConsumer(db.dataSource(), snow, guard::tryClaim, producer(db, snow));
      consumer.consume(serviceEvent(Long.toString(ids.incrementAndGet()), servicePayload()));
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification_delivery", Integer.class));
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
    }
  }

  @Test
  void switchOffConsumersShowZeroDeliveryBehavior() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var snow = snowflake();
      var guard = new JdbcOutboxConsumeGuard(db.dataSource(), snow::nextId);
      // Default OFF: no producer passed, consumers behave exactly as before NTF-002.
      var merchant = new MerchantApplicationReviewedConsumer(db.dataSource(), snow, guard::tryClaim);
      var service = new ServiceReviewedConsumer(db.dataSource(), snow, guard::tryClaim);
      merchant.consume(merchantEvent(Long.toString(ids.incrementAndGet()), merchantPayload("REJECT")));
      service.consume(serviceEvent(Long.toString(ids.incrementAndGet()), servicePayload()));
      assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification_delivery", Integer.class));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
      // The unconfigured shell itself performs no external call and skips terminally.
      assertEquals(
          new WechatDeliveryOutcome.Skipped(UnconfiguredWechatDeliveryAdapter.REASON_CODE),
          new UnconfiguredWechatDeliveryAdapter()
              .deliver(
                  new com.petplatform.notification.biz.delivery.WechatDeliveryRequest(
                      1, CHANNEL, 1, "t", "c", "SYSTEM", null, "dedup")));
    }
  }

  @Test
  void deliveryEnqueueFailureRollsBackTheWholeNotificationTransaction() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var snow = snowflake();
      var guard = new JdbcOutboxConsumeGuard(db.dataSource(), snow::nextId);
      var consumer =
          new MerchantApplicationReviewedConsumer(db.dataSource(), snow, guard::tryClaim, producer(db, snow));
      var event = merchantEvent(Long.toString(ids.incrementAndGet()), merchantPayload("APPROVE"));
      db.jdbc().execute("RENAME TABLE notification_delivery TO unavailable_delivery");
      assertThrows(RuntimeException.class, () -> consumer.consume(event));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM integration_event_consume_log", Integer.class));
      db.jdbc().execute("RENAME TABLE unavailable_delivery TO notification_delivery");
      consumer.consume(event);
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification_delivery", Integer.class));
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
    }
  }

  @Test
  void producedTaskFlowsThroughHandlerToTerminalSkippedState() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var snow = snowflake();
      var guard = new JdbcOutboxConsumeGuard(db.dataSource(), snow::nextId);
      var consumer =
          new MerchantApplicationReviewedConsumer(db.dataSource(), snow, guard::tryClaim, producer(db, snow));
      consumer.consume(merchantEvent(Long.toString(ids.incrementAndGet()), merchantPayload("APPROVE")));
      long notificationId = db.jdbc().queryForObject("SELECT id FROM notification", Long.class);
      String payloadJson = db.jdbc().queryForObject("SELECT payload_json FROM async_task", String.class);
      var handler =
          new WechatDeliveryTaskHandler(
              new NotificationDeliveryStore(db.dataSource()),
              new NotificationInboxStore(db.dataSource()),
              new UnconfiguredWechatDeliveryAdapter());
      var registration = handler.registration(new ObjectMapper());
      var lease =
          new TaskLease(
              db.jdbc().queryForObject("SELECT id FROM async_task", Long.class),
              "WECHAT_DELIVER:" + notificationId + ":" + CHANNEL,
              "WECHAT_DELIVER",
              notificationId,
              0L,
              payloadJson,
              "test-worker",
              3,
              11,
              1,
              0,
              5,
              "WECHAT_DELIVER");
      var payload = registration.decode().apply(lease);
      // Deterministic requestId resolution, exactly what the worker registers.
      assertEquals(
          WechatDeliveryTaskHandler.requestIdOf(notificationId, CHANNEL),
          registration.requestId().apply(lease));
      var result =
          handler.execute(
              new TaskExecutionContext(
                  Long.toString(lease.taskId()),
                  registration.requestId().apply(lease),
                  "TASK:" + lease.taskId() + ":0",
                  now),
              payload);
      assertEquals("WECHAT_DELIVERY_SKIPPED", ((TaskExecutionResult.Success) result).resultCode());
      assertEquals(
          "SKIPPED",
          db.jdbc()
              .queryForObject("SELECT status FROM notification_delivery WHERE notification_id=?", String.class, notificationId));
      // The durable task row itself stays untouched: only the SQL13 worker closes it.
      assertEquals("READY", db.jdbc().queryForObject("SELECT status FROM async_task", String.class));
    }
  }

  @Test
  void producedTaskFlowsThroughFakeAdapterToSentState() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var snow = snowflake();
      var guard = new JdbcOutboxConsumeGuard(db.dataSource(), snow::nextId);
      var consumer =
          new MerchantApplicationReviewedConsumer(db.dataSource(), snow, guard::tryClaim, producer(db, snow));
      consumer.consume(merchantEvent(Long.toString(ids.incrementAndGet()), merchantPayload("APPROVE")));
      long notificationId = db.jdbc().queryForObject("SELECT id FROM notification", Long.class);
      String payloadJson = db.jdbc().queryForObject("SELECT payload_json FROM async_task", String.class);
      var fake = new ScriptedWechatDeliveryAdapter().then(new WechatDeliveryOutcome.Sent("wx-fake-1"));
      var handler =
          new WechatDeliveryTaskHandler(
              new NotificationDeliveryStore(db.dataSource()),
              new NotificationInboxStore(db.dataSource()),
              fake);
      var payload =
          WechatDeliveryTaskProducer.decodePayload(payloadJson, notificationId);
      var result =
          handler.execute(
              new TaskExecutionContext(
                  "1", WechatDeliveryTaskHandler.requestIdOf(notificationId, CHANNEL), "trace", now),
              new WechatDeliveryTaskHandler.Payload(payload.notificationId(), payload.receiverUserId(), payload.channel(), 0, 5));
      assertEquals("WECHAT_DELIVERED", ((TaskExecutionResult.Success) result).resultCode());
      assertEquals(
          "SENT",
          db.jdbc()
              .queryForObject("SELECT status FROM notification_delivery WHERE notification_id=?", String.class, notificationId));
      assertEquals(
          "wx-fake-1",
          db.jdbc()
              .queryForObject("SELECT provider_message_id FROM notification_delivery WHERE notification_id=?", String.class, notificationId));
      // Replay against the terminal SENT row is a NOOP and never re-calls the adapter.
      int callsBefore = fake.requests.size();
      var replay =
          handler.execute(
              new TaskExecutionContext(
                  "1", WechatDeliveryTaskHandler.requestIdOf(notificationId, CHANNEL), "trace", now),
              new WechatDeliveryTaskHandler.Payload(payload.notificationId(), payload.receiverUserId(), payload.channel(), 1, 5));
      assertEquals("NOOP", ((TaskExecutionResult.Success) replay).resultCode());
      assertEquals(callsBefore, fake.requests.size());
    }
  }
}
