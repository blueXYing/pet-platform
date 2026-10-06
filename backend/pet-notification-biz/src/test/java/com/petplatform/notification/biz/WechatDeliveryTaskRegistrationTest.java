package com.petplatform.notification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.notification.biz.delivery.WechatDeliveryTaskHandler;
import com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer;
import com.petplatform.task.core.TaskLease;
import com.petplatform.task.core.TaskRegistration;
import org.junit.jupiter.api.Test;

/** Pure decode/binding/requestId tests; no database connection is opened. */
class WechatDeliveryTaskRegistrationTest {
  private static final String PAYLOAD =
      WechatDeliveryTaskProducer.payload(7001, 1001, "WECHAT_SUBSCRIBE");
  // Lazy, never-connected DataSources: the constructor validates presence, not connectivity.
  private final WechatDeliveryTaskHandler handler =
      new WechatDeliveryTaskHandler(
          new com.petplatform.notification.biz.infrastructure.persistence.NotificationDeliveryStore(
              new org.springframework.jdbc.datasource.DriverManagerDataSource()),
          new com.petplatform.notification.biz.infrastructure.persistence.NotificationInboxStore(
              new org.springframework.jdbc.datasource.DriverManagerDataSource()),
          request -> null);
  private final TaskRegistration<WechatDeliveryTaskHandler.Payload> registration =
      handler.registration(new ObjectMapper());

  private TaskLease lease(long bizId, String payloadJson, int retryCount, int maxRetryCount) {
    return new TaskLease(
        9001,
        "WECHAT_DELIVER:" + bizId + ":WECHAT_SUBSCRIBE",
        "WECHAT_DELIVER",
        bizId,
        0L,
        payloadJson,
        "worker-1",
        7,
        88,
        retryCount + 1,
        retryCount,
        maxRetryCount,
        "WECHAT_DELIVER");
  }

  @Test
  void decodesStrictPayloadAndCarriesAttemptBudget() {
    var payload = registration.decode().apply(lease(7001, PAYLOAD, 2, 5));
    assertEquals(7001, payload.notificationId());
    assertEquals(1001, payload.receiverUserId());
    assertEquals("WECHAT_SUBSCRIBE", payload.channel());
    assertEquals(2, payload.retryCount());
    assertEquals(5, payload.maxRetryCount());
  }

  @Test
  void rejectsBizIdMismatchForeignFieldsAndMalformedIds() {
    assertThrows(
        IllegalArgumentException.class, () -> registration.decode().apply(lease(7002, PAYLOAD, 0, 5)));
    assertThrows(
        IllegalArgumentException.class,
        () -> registration.decode().apply(lease(7001, "{\"notificationId\":\"7001\"}", 0, 5)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registration.decode()
                .apply(lease(7001, WechatDeliveryTaskProducer.payload(7001, 1001, "WECHAT_OA2"), 0, 5)));
    String numericId =
        WechatDeliveryTaskProducer.payload(7001, 1001, "WECHAT_SUBSCRIBE")
            .replace("\"7001\"", "7001");
    assertThrows(
        IllegalArgumentException.class, () -> registration.decode().apply(lease(7001, numericId, 0, 5)));
  }

  @Test
  void requestIdIsDeterministicAcrossAttemptsWorkersAndVersions() {
    String first = registration.requestId().apply(lease(7001, PAYLOAD, 0, 5));
    String second =
        registration.requestId().apply(lease(7001, PAYLOAD, 3, 5));
    assertEquals(first, second);
    assertEquals(WechatDeliveryTaskHandler.requestIdOf(7001, "WECHAT_SUBSCRIBE"), first);
    assertNotEquals(
        first, registration.requestId().apply(lease(7002, PAYLOAD, 0, 5)));
  }
}
