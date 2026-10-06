package com.petplatform.notification.biz.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.notification.biz.delivery.spi.WechatDeliveryAdapter;
import com.petplatform.notification.biz.delivery.spi.WechatDeliveryOutcome;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationDeliveryStore;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationInboxStore;
import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationDeliveryEntity;
import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationInboxEntity;
import com.petplatform.task.core.TaskExecutionContext;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskHandler;
import com.petplatform.task.core.TaskLease;
import com.petplatform.task.core.TaskRegistration;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * NTF-002 durable delivery handler (contract 55). One attempt: bind the authoritative inbox row,
 * CAS-guard the PENDING delivery row, invoke the channel SPI once, and map the outcome:
 * SENT -> row SENT + Success; retryable within budget -> row stays PENDING with an advanced
 * retry_count and a handler-driven backoff; retryable on the last budgeted attempt or permanent
 * failure -> row FAILED + Dead (TASK-007: the dead external notification never touches any
 * trading fact); skipped -> row SKIPPED + Success. Replays against a terminal row are NOOP.
 */
public final class WechatDeliveryTaskHandler implements TaskHandler<WechatDeliveryTaskHandler.Payload> {

  public static final String TASK_TYPE = WechatDeliveryTaskProducer.TASK_TYPE;
  public static final String RETRY_POLICY = WechatDeliveryTaskProducer.RETRY_POLICY;

  /** Handler-driven backoff ladder; the boot worker mirrors it for technical HANDLER_EXCEPTIONs. */
  public static final List<Duration> BACKOFF =
      List.of(
          Duration.ofSeconds(10),
          Duration.ofSeconds(30),
          Duration.ofMinutes(2),
          Duration.ofMinutes(10),
          Duration.ofMinutes(30));

  private final NotificationDeliveryStore deliveries;
  private final NotificationInboxStore inbox;
  private final WechatDeliveryAdapter adapter;

  public WechatDeliveryTaskHandler(
      NotificationDeliveryStore deliveries,
      NotificationInboxStore inbox,
      WechatDeliveryAdapter adapter) {
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries is required");
    this.inbox = Objects.requireNonNull(inbox, "inbox is required");
    this.adapter = Objects.requireNonNull(adapter, "adapter is required");
  }

  /** Composition entry for the boot assembler: keeps persistence wiring inside the owner module. */
  public WechatDeliveryTaskHandler(javax.sql.DataSource dataSource, WechatDeliveryAdapter adapter) {
    this(
        new NotificationDeliveryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
        new NotificationInboxStore(dataSource),
        adapter);
  }

  public record Payload(
      long notificationId, long receiverUserId, String channel, int retryCount, int maxRetryCount) {}

  @Override
  public String taskType() {
    return TASK_TYPE;
  }

  @Override
  public TaskExecutionResult execute(TaskExecutionContext context, Payload payload) {
    Objects.requireNonNull(context, "context is required");
    Objects.requireNonNull(payload, "payload is required");
    NotificationInboxEntity source =
        inbox.read(
            mapper -> mapper.selectDeliverySource(payload.notificationId(), payload.receiverUserId()));
    if (source == null
        || source.getId() == null
        || source.getReceiverId() == null
        || source.getReceiverId() != payload.receiverUserId()
        || source.getTitle() == null
        || source.getContent() == null) {
      return new TaskExecutionResult.Dead("NOTIFICATION_MISSING");
    }
    NotificationDeliveryEntity row =
        deliveries.read(mapper -> mapper.selectDelivery(payload.notificationId(), payload.channel()));
    if (row == null || row.getStatus() == null) return new TaskExecutionResult.Dead("DELIVERY_ROW_MISSING");
    if (!"PENDING".equals(row.getStatus())) {
      // SENT/SKIPPED/FAILED are terminal; a replayed attempt must never resurrect them.
      return new TaskExecutionResult.Success("NOOP");
    }
    WechatDeliveryOutcome outcome;
    try {
      outcome = adapter.deliver(request(source, payload, context.requestId()));
    } catch (RuntimeException failure) {
      outcome = new WechatDeliveryOutcome.RetryableFailure("WECHAT_ADAPTER_UNEXPECTED");
    }
    return apply(payload, outcome);
  }

  private TaskExecutionResult apply(Payload payload, WechatDeliveryOutcome outcome) {
    if (outcome instanceof WechatDeliveryOutcome.Sent sent) {
      deliveries.write(
          mapper -> {
            mapper.markSent(payload.notificationId(), payload.channel(), sent.providerMessageId());
            return null;
          });
      return new TaskExecutionResult.Success("WECHAT_DELIVERED");
    }
    if (outcome instanceof WechatDeliveryOutcome.Skipped skip) {
      deliveries.write(
          mapper -> {
            mapper.markTerminal(payload.notificationId(), payload.channel(), "SKIPPED", skip.reasonCode());
            return null;
          });
      return new TaskExecutionResult.Success("WECHAT_DELIVERY_SKIPPED");
    }
    if (outcome instanceof WechatDeliveryOutcome.PermanentFailure permanent) {
      deliveries.write(
          mapper -> {
            mapper.markTerminal(
                payload.notificationId(), payload.channel(), "FAILED", permanent.errorCode());
            return null;
          });
      return new TaskExecutionResult.Dead(permanent.errorCode());
    }
    WechatDeliveryOutcome.RetryableFailure retryable = (WechatDeliveryOutcome.RetryableFailure) outcome;
    if (payload.retryCount() >= payload.maxRetryCount()) {
      // Last budgeted attempt: terminalize the delivery row here so the repository-side DEAD
      // and the delivery state cannot drift apart (TASK-007: delivery=FAILED, trading untouched).
      deliveries.write(
          mapper -> {
            mapper.markTerminal(
                payload.notificationId(), payload.channel(), "FAILED", retryable.errorCode());
            return null;
          });
      return new TaskExecutionResult.Dead(retryable.errorCode());
    }
    deliveries.write(
        mapper -> {
          mapper.recordRetry(
              payload.notificationId(), payload.channel(), payload.retryCount() + 1, retryable.errorCode());
          return null;
        });
    return new TaskExecutionResult.Retry(retryable.errorCode(), BACKOFF.get(backoffIndex(payload.retryCount())));
  }

  private static int backoffIndex(int retryCount) {
    return Math.min(Math.max(retryCount, 0), BACKOFF.size() - 1);
  }

  private static WechatDeliveryRequest request(
      NotificationInboxEntity source, Payload payload, String dedupKey) {
    // dedupKey is the task's deterministic requestId (attempt-independent): stable across
    // retries so a real provider can dedupe at-least-once redelivery.
    return new WechatDeliveryRequest(
        source.getId(),
        payload.channel(),
        source.getReceiverId(),
        source.getTitle(),
        source.getContent(),
        source.getBizType(),
        source.getBizId(),
        dedupKey);
  }

  /** Registration with strict payload decoding and a deterministic, attempt-independent requestId. */
  public TaskRegistration<Payload> registration(ObjectMapper codec) {
    Objects.requireNonNull(codec);
    return new TaskRegistration<>(
        this,
        lease -> {
          WechatDeliveryTaskProducer.Payload payload =
              WechatDeliveryTaskProducer.decodePayload(lease.payloadJson(), lease.bizId());
          if (payload.notificationId() != lease.bizId() || payload.channel() == null) {
            throw new IllegalArgumentException("WeChat delivery task binding mismatch");
          }
          if (lease.retryCount() < 0 || lease.maxRetryCount() < 0) {
            throw new IllegalArgumentException("Invalid WeChat delivery attempt budget");
          }
          return new Payload(
              payload.notificationId(),
              payload.receiverUserId(),
              payload.channel(),
              lease.retryCount(),
              lease.maxRetryCount());
        },
        lease ->
            "TASK:"
                + TASK_TYPE
                + ":"
                + lease.bizId()
                + ":"
                + lease.taskKey().substring(lease.taskKey().lastIndexOf(':') + 1)
                + ":0");
  }

  /** Test/inspection helper mirroring the registration requestId convention. */
  public static String requestIdOf(long notificationId, String channel) {
    return "TASK:" + TASK_TYPE + ":" + notificationId + ":" + channel + ":0";
  }
}
