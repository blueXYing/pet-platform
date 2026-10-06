package com.petplatform.notification.biz.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationDeliveryStore;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * NTF-002 producer side of the external WeChat delivery skeleton. Invoked from the notification
 * consumers' {@code recordOnce} transaction hook, so the authoritative inbox row, the PENDING
 * delivery row and the durable WECHAT_DELIVER task commit atomically on one DataSource (Scheduler
 * §30: the inbox record is authoritative; external delivery is a separate retryable concern).
 * Default OFF: when the delivery switch is absent no producer bean exists and consumers run
 * unchanged.
 */
public final class WechatDeliveryTaskProducer {

  public static final String TASK_TYPE = "WECHAT_DELIVER";
  public static final String RETRY_POLICY = "WECHAT_DELIVER";
  public static final Set<String> CHANNELS = Set.of("WECHAT_SUBSCRIBE", "WECHAT_OA");

  private static final ObjectMapper JSON = new ObjectMapper();

  private final NotificationDeliveryStore deliveries;
  private final JdbcAsyncTaskSubmitter tasks;
  private final SnowflakeIdGenerator ids;
  private final String channel;
  private final int maxRetryCount;

  public WechatDeliveryTaskProducer(
      NotificationDeliveryStore deliveries,
      JdbcAsyncTaskSubmitter tasks,
      SnowflakeIdGenerator ids,
      String channel,
      int maxRetryCount) {
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries is required");
    this.tasks = Objects.requireNonNull(tasks, "tasks is required");
    this.ids = Objects.requireNonNull(ids, "ids is required");
    if (channel == null || !CHANNELS.contains(channel)) {
      throw new IllegalArgumentException("Unsupported WeChat delivery channel");
    }
    if (maxRetryCount < 0 || maxRetryCount > 1000) {
      throw new IllegalArgumentException("Invalid WeChat delivery retry budget");
    }
    this.channel = channel;
    this.maxRetryCount = maxRetryCount;
  }

  /** Composition entry for the boot assembler: keeps persistence wiring inside the owner module. */
  public WechatDeliveryTaskProducer(
      javax.sql.DataSource dataSource,
      SnowflakeIdGenerator ids,
      String channel,
      int maxRetryCount) {
    this(
        new NotificationDeliveryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
        new JdbcAsyncTaskSubmitter(dataSource, ids),
        ids,
        channel,
        maxRetryCount);
  }

  /** Must run inside the caller's notification transaction on the same DataSource. */
  public void enqueueAfterInboxInsert(long notificationId, long receiverUserId) {
    if (notificationId <= 0 || receiverUserId <= 0) {
      throw new IllegalArgumentException("Invalid WeChat delivery enqueue target");
    }
    long deliveryId = ids.nextId();
    if (deliveryId <= 0) throw new IllegalStateException("delivery row ID unavailable");
    deliveries.write(
        mapper -> {
          mapper.setTimeZoneUtc();
          if (mapper.insertPending(deliveryId, notificationId, channel) != 1) {
            throw new IllegalStateException("delivery row was not persisted");
          }
          return null;
        });
    String payload = payload(notificationId, receiverUserId, channel);
    tasks.enqueue(
        TASK_TYPE + ":" + notificationId + ":" + channel,
        "NOTIFICATION",
        TASK_TYPE,
        "NOTIFICATION",
        notificationId,
        0L,
        payload,
        maxRetryCount,
        RETRY_POLICY);
  }

  /** One stable strict payload for creation and every later attempt. */
  public static String payload(long notificationId, long receiverUserId, String channel) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("notificationId", Long.toString(notificationId));
    body.put("receiverUserId", Long.toString(receiverUserId));
    body.put("channel", channel);
    try {
      return JSON.writeValueAsString(body);
    } catch (JsonProcessingException broken) {
      throw new IllegalStateException("WeChat delivery task payload cannot be serialized");
    }
  }

  /** Strict decode shared with the handler registration; unknown or missing fields are fatal. */
  public static Payload decodePayload(String payloadJson, long expectedBizId) {
    com.fasterxml.jackson.databind.JsonNode root;
    try {
      var reader =
          new ObjectMapper(
                  com.fasterxml.jackson.core.JsonFactory.builder()
                      .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                      .build())
              .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
      root = reader.readTree(payloadJson);
    } catch (Exception invalid) {
      throw new IllegalArgumentException("Invalid WeChat delivery task payload");
    }
    if (root == null || !root.isObject() || root.size() != 3) {
      throw new IllegalArgumentException("Invalid WeChat delivery task payload");
    }
    long notificationId = decimalId(root, "notificationId");
    long receiverUserId = decimalId(root, "receiverUserId");
    String channel = text(root, "channel");
    if (!CHANNELS.contains(channel) || notificationId != expectedBizId) {
      throw new IllegalArgumentException("WeChat delivery task binding mismatch");
    }
    return new Payload(notificationId, receiverUserId, channel);
  }

  public record Payload(long notificationId, long receiverUserId, String channel) {}

  private static long decimalId(com.fasterxml.jackson.databind.JsonNode root, String field) {
    String value = text(root, field);
    if (!value.matches("[1-9][0-9]{0,18}")) {
      throw new IllegalArgumentException("Invalid WeChat delivery task payload id");
    }
    return Long.parseLong(value);
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode root, String field) {
    com.fasterxml.jackson.databind.JsonNode value = root.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Invalid WeChat delivery task payload field");
    }
    return value.textValue();
  }
}
