package com.petplatform.notification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.notification.biz.delivery.WechatDeliveryTaskHandler;
import com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer;
import com.petplatform.notification.biz.delivery.spi.UnconfiguredWechatDeliveryAdapter;
import com.petplatform.notification.biz.delivery.spi.WechatDeliveryOutcome;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationDeliveryStore;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationInboxStore;
import com.petplatform.task.core.TaskExecutionContext;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskLease;
import com.petplatform.task.core.TaskRegistration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * NTF-002 handler outcome matrix over real MySQL (Schema 06 §11 notification_delivery + inbox).
 * Every external interaction goes through the scripted in-process fake; nothing leaves the JVM.
 */
class WechatDeliveryTaskHandlerMySqlTest {
  private static final long NOTIFICATION_ID = 7001L;
  private static final long RECEIVER_ID = 1001L;
  private static final String CHANNEL = "WECHAT_SUBSCRIBE";

  private final OffsetDateTime now = OffsetDateTime.parse("2026-10-06T08:00:00.000Z");

  private WechatDeliveryTaskHandler handler(
      MySqlNotificationTestDatabase db, ScriptedWechatDeliveryAdapter adapter) {
    return new WechatDeliveryTaskHandler(
        new NotificationDeliveryStore(db.dataSource()),
        new NotificationInboxStore(db.dataSource()),
        adapter);
  }

  private TaskExecutionContext context() {
    return new TaskExecutionContext(
        "9001", WechatDeliveryTaskHandler.requestIdOf(NOTIFICATION_ID, CHANNEL), "trace", now);
  }

  private WechatDeliveryTaskHandler.Payload decodedPayload(
      WechatDeliveryTaskHandler handler, int retryCount, int maxRetryCount) {
    TaskRegistration<WechatDeliveryTaskHandler.Payload> registration =
        handler.registration(new ObjectMapper());
    return registration
        .decode()
        .apply(
            new TaskLease(
                9001,
                "WECHAT_DELIVER:" + NOTIFICATION_ID + ":" + CHANNEL,
                "WECHAT_DELIVER",
                NOTIFICATION_ID,
                0L,
                WechatDeliveryTaskProducer.payload(NOTIFICATION_ID, RECEIVER_ID, CHANNEL),
                "worker-1",
                7,
                88,
                retryCount + 1,
                retryCount,
                maxRetryCount,
                "WECHAT_DELIVER"));
  }

  private void seedNotification(MySqlNotificationTestDatabase db) {
    db.jdbc()
        .update(
            """
            INSERT INTO notification (id, receiver_type, receiver_id, category, message_type,
                biz_type, biz_id, title, content, mandatory_inbox, read_at, created_at)
            VALUES (?, 'USER', ?, 'SYSTEM', 'MERCHANT_APPLICATION_REVIEWED',
                'MERCHANT_APPLICATION', 2001, '商家入驻审核结果', '申请 SQ20260917AbCd1234：审核通过。',
                1, NULL, UTC_TIMESTAMP(3))
            """,
            NOTIFICATION_ID, RECEIVER_ID);
  }

  private void seedDeliveryRow(MySqlNotificationTestDatabase db, String status) {
    db.jdbc()
        .update(
            "INSERT INTO notification_delivery (id, notification_id, channel, status,"
                + " provider_message_id, retry_count, last_error, sent_at, created_at, updated_at)"
                + " VALUES (9002, ?, ?, ?, NULL, 0, NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
            NOTIFICATION_ID, CHANNEL, status);
  }

  @Test
  void sentOutcomeMarksRowSentWithProviderMessageId() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      seedNotification(db);
      seedDeliveryRow(db, "PENDING");
      var adapter = new ScriptedWechatDeliveryAdapter().then(new WechatDeliveryOutcome.Sent("wx-msg-1"));
      var handler = handler(db, adapter);
      var result = handler.execute(context(), decodedPayload(handler, 0, 5));
      assertEquals("WECHAT_DELIVERED", ((TaskExecutionResult.Success) result).resultCode());
      assertEquals(
          "SENT",
          db.jdbc()
              .queryForObject(
                  "SELECT status FROM notification_delivery WHERE notification_id=? AND channel=?",
                  String.class,
                  NOTIFICATION_ID,
                  CHANNEL));
      assertEquals(
          "wx-msg-1",
          db.jdbc()
              .queryForObject(
                  "SELECT provider_message_id FROM notification_delivery WHERE notification_id=?",
                  String.class,
                  NOTIFICATION_ID));
      assertNotNull(
          db.jdbc()
              .queryForObject(
                  "SELECT sent_at FROM notification_delivery WHERE notification_id=?",
                  Object.class,
                  NOTIFICATION_ID));
      assertEquals(1, adapter.requests.size());
      assertEquals(RECEIVER_ID, adapter.requests.get(0).receiverUserId());
      assertEquals(
          WechatDeliveryTaskHandler.requestIdOf(NOTIFICATION_ID, CHANNEL),
          adapter.requests.get(0).dedupKey());
    }
  }

  @Test
  void retryableWithinBudgetKeepsPendingAdvancesRetryCountAndBacksOff() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      seedNotification(db);
      seedDeliveryRow(db, "PENDING");
      var adapter =
          new ScriptedWechatDeliveryAdapter()
              .then(new WechatDeliveryOutcome.RetryableFailure("WX_RATE_LIMITED"))
              .then(new WechatDeliveryOutcome.RetryableFailure("WX_RATE_LIMITED"));
      var handler = handler(db, adapter);
      var result = handler.execute(context(), decodedPayload(handler, 0, 5));
      var retry = (TaskExecutionResult.Retry) result;
      assertEquals("WX_RATE_LIMITED", retry.errorCode());
      assertEquals(java.time.Duration.ofSeconds(10), retry.nextDelay());
      assertEquals(
          "PENDING",
          db.jdbc()
              .queryForObject("SELECT status FROM notification_delivery WHERE notification_id=?", String.class, NOTIFICATION_ID));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject("SELECT retry_count FROM notification_delivery WHERE notification_id=?", Integer.class, NOTIFICATION_ID));
      // Second-to-last budget: larger step of the ladder, still PENDING.
      var late = (TaskExecutionResult.Retry) handler.execute(context(), decodedPayload(handler, 3, 5));
      assertEquals(java.time.Duration.ofMinutes(10), late.nextDelay());
      assertEquals(
          4,
          db.jdbc()
              .queryForObject("SELECT retry_count FROM notification_delivery WHERE notification_id=?", Integer.class, NOTIFICATION_ID));
    }
  }

  @Test
  void retryableOnLastBudgetMarksDeliveryFailedAndTaskDeadWithoutTouchingTrading() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      seedNotification(db);
      seedDeliveryRow(db, "PENDING");
      var adapter =
          new ScriptedWechatDeliveryAdapter().then(new WechatDeliveryOutcome.RetryableFailure("WX_CHANNEL_DOWN"));
      var handler = handler(db, adapter);
      var result = handler.execute(context(), decodedPayload(handler, 5, 5));
      assertEquals("WX_CHANNEL_DOWN", ((TaskExecutionResult.Dead) result).errorCode());
      assertEquals(
          "FAILED",
          db.jdbc()
              .queryForObject("SELECT status FROM notification_delivery WHERE notification_id=?", String.class, NOTIFICATION_ID));
      assertEquals(
          "WX_CHANNEL_DOWN",
          db.jdbc()
              .queryForObject("SELECT last_error FROM notification_delivery WHERE notification_id=?", String.class, NOTIFICATION_ID));
      // MSG-003/TASK-007: the authoritative inbox row and any trading facts stay untouched.
      assertEquals(
          1, db.jdbc().queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
    }
  }

  @Test
  void permanentFailureFailsRowAndTaskOnFirstAttempt() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      seedNotification(db);
      seedDeliveryRow(db, "PENDING");
      var adapter =
          new ScriptedWechatDeliveryAdapter().then(new WechatDeliveryOutcome.PermanentFailure("WX_TEMPLATE_INVALID"));
      var handler = handler(db, adapter);
      var result = handler.execute(context(), decodedPayload(handler, 0, 5));
      assertEquals("WX_TEMPLATE_INVALID", ((TaskExecutionResult.Dead) result).errorCode());
      assertEquals(
          "FAILED",
          db.jdbc().queryForObject("SELECT status FROM notification_delivery WHERE notification_id=?", String.class, NOTIFICATION_ID));
    }
  }

  @Test
  void unconfiguredShellSkipsTerminalWithoutExternalCallOrRetryBudget() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      seedNotification(db);
      seedDeliveryRow(db, "PENDING");
      var handler =
          new WechatDeliveryTaskHandler(
              new NotificationDeliveryStore(db.dataSource()),
              new NotificationInboxStore(db.dataSource()),
              new UnconfiguredWechatDeliveryAdapter());
      var result = handler.execute(context(), decodedPayload(handler, 0, 5));
      assertEquals("WECHAT_DELIVERY_SKIPPED", ((TaskExecutionResult.Success) result).resultCode());
      assertEquals(
          "SKIPPED",
          db.jdbc().queryForObject("SELECT status FROM notification_delivery WHERE notification_id=?", String.class, NOTIFICATION_ID));
      assertEquals(
          UnconfiguredWechatDeliveryAdapter.REASON_CODE,
          db.jdbc().queryForObject("SELECT last_error FROM notification_delivery WHERE notification_id=?", String.class, NOTIFICATION_ID));
    }
  }

  @Test
  void terminalRowsAreNeverResurrectedAndAdapterIsNotCalled() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var adapter = new ScriptedWechatDeliveryAdapter();
      var handler = handler(db, adapter);
      for (String terminal : new String[] {"SENT", "FAILED", "SKIPPED"}) {
        seedNotification(db);
        seedDeliveryRow(db, terminal);
        var result = handler.execute(context(), decodedPayload(handler, 0, 5));
        assertEquals("NOOP", ((TaskExecutionResult.Success) result).resultCode());
        assertEquals(
            terminal,
            db.jdbc()
                .queryForObject("SELECT status FROM notification_delivery WHERE notification_id=?", String.class, NOTIFICATION_ID));
        db.jdbc().update("DELETE FROM notification_delivery");
        db.jdbc().update("DELETE FROM notification");
      }
      assertEquals(0, adapter.requests.size());
    }
  }

  @Test
  void missingAuthoritativeOrDeliveryRowIsDeadWithoutAdapterCall() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      var adapter = new ScriptedWechatDeliveryAdapter();
      var handler = handler(db, adapter);
      var noNotification = handler.execute(context(), decodedPayload(handler, 0, 5));
      assertEquals("NOTIFICATION_MISSING", ((TaskExecutionResult.Dead) noNotification).errorCode());
      seedNotification(db);
      var noDeliveryRow = handler.execute(context(), decodedPayload(handler, 0, 5));
      assertEquals("DELIVERY_ROW_MISSING", ((TaskExecutionResult.Dead) noDeliveryRow).errorCode());
      assertEquals(0, adapter.requests.size());
    }
  }

  @Test
  void adapterRuntimeExceptionIsDefensiveRetryableAndRowStaysPending() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      seedNotification(db);
      seedDeliveryRow(db, "PENDING");
      var adapter =
          new ScriptedWechatDeliveryAdapter().alwaysThrow(new IllegalStateException("simulated timeout"));
      var handler = handler(db, adapter);
      var result = handler.execute(context(), decodedPayload(handler, 1, 5));
      var retry = (TaskExecutionResult.Retry) result;
      assertEquals("WECHAT_ADAPTER_UNEXPECTED", retry.errorCode());
      assertEquals(
          "PENDING",
          db.jdbc().queryForObject("SELECT status FROM notification_delivery WHERE notification_id=?", String.class, NOTIFICATION_ID));
      assertEquals(
          2,
          db.jdbc().queryForObject("SELECT retry_count FROM notification_delivery WHERE notification_id=?", Integer.class, NOTIFICATION_ID));
    }
  }

  @Test
  void dedupKeyIsStableAcrossAttempts() throws Exception {
    try (var db = new MySqlNotificationTestDatabase()) {
      seedNotification(db);
      seedDeliveryRow(db, "PENDING");
      var adapter =
          new ScriptedWechatDeliveryAdapter()
              .then(new WechatDeliveryOutcome.RetryableFailure("WX_RATE_LIMITED"))
              .then(new WechatDeliveryOutcome.RetryableFailure("WX_RATE_LIMITED"));
      var handler = handler(db, adapter);
      handler.execute(context(), decodedPayload(handler, 0, 5));
      handler.execute(context(), decodedPayload(handler, 1, 5));
      assertEquals(2, adapter.requests.size());
      assertEquals(adapter.requests.get(0).dedupKey(), adapter.requests.get(1).dedupKey());
    }
  }
}
