package com.petplatform.notification.biz.event;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.ServiceReviewNotificationStore;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Strict consumer of ServiceReviewedEvent.v1 (nine-field payload finalized in Event08 by the
 * service-write owner, PR#68: serviceId/serviceName/merchantId/storeId/submissionNo/decisionType/
 * opinion?/decidedAt/ownerUserId). Never propagates arbitrary payload fields into a user-visible
 * message. The receiver is the ownerUserId carried by the event itself — the consumer is
 * self-contained and never reads the merchant tables (ARCH-002).
 */
public final class ServiceReviewedConsumer implements IntegrationEventConsumer {
  public static final String TYPE = "ServiceReviewedEvent.v1";
  private static final Set<String> FIELDS =
      Set.of(
          "serviceId",
          "serviceName",
          "merchantId",
          "storeId",
          "submissionNo",
          "decisionType",
          "opinion",
          "decidedAt",
          "ownerUserId");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final ServiceReviewNotificationStore store;
  private final SnowflakeIdGenerator ids;

  public ServiceReviewedConsumer(ServiceReviewNotificationStore store, SnowflakeIdGenerator ids) {
    this.store = Objects.requireNonNull(store);
    this.ids = Objects.requireNonNull(ids);
  }

  @Override
  public String consumerName() {
    return "notification.service-reviewed.v1";
  }

  @Override
  public Set<String> eventTypes() {
    return Set.of(TYPE);
  }

  @Override
  public void consume(DispatchedEvent event) {
    if (event == null
        || !TYPE.equals(event.eventType())
        || event.eventVersion() != 1
        || !"SERVICE".equals(event.aggregateType())
        || event.occurredAt() == null) throw invalid();
    publicId(event.eventId());
    JsonNode payload;
    try {
      payload = JSON.readTree(event.payloadJson());
    } catch (Exception failure) {
      throw invalid(); // Do not retain a parser exception carrying raw sensitive input.
    }
    if (payload == null || !payload.isObject()) throw invalid();
    Set<String> fields = new HashSet<>();
    payload.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(FIELDS)) throw invalid();
    long serviceId = publicId(text(payload, "serviceId"));
    publicId(text(payload, "merchantId")); // shape-checked; the receiver never comes from merchant
    publicId(text(payload, "storeId"));
    // Required recipient (Event08 nine-field final): merchant owner account as a Snowflake
    // String ID. A missing, non-textual or non-positive value is a strict-contract violation.
    long ownerId = publicId(text(payload, "ownerUserId"));
    if (event.aggregateId() != serviceId) throw invalid();
    String serviceName = text(payload, "serviceName");
    int nameLength = serviceName.codePointCount(0, serviceName.length());
    if (nameLength < 2 || nameLength > 50 || hasControl(serviceName)) throw invalid();
    int submissionNo = submissionNo(payload.get("submissionNo"));
    if (submissionNo < 1) throw invalid(); // unreachable defense; submissionNo() already bounds it
    String decision = text(payload, "decisionType");
    String label;
    if ("APPROVE".equals(decision)) label = "审核通过，已上架";
    else if ("REJECT".equals(decision)) label = "审核未通过";
    else throw invalid();
    JsonNode opinionNode = payload.get("opinion");
    String opinion = opinionNode.isNull() ? null : text(payload, "opinion");
    int length = opinion == null ? 0 : opinion.codePointCount(0, opinion.length());
    if (length > 500 || ("REJECT".equals(decision) && (length < 10 || opinion.isBlank())))
      throw invalid();
    OffsetDateTime decidedAt;
    try {
      String time = text(payload, "decidedAt");
      if (!time.matches(
          "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}(Z|[+-][0-9]{2}:[0-9]{2})"))
        throw invalid();
      decidedAt = OffsetDateTime.parse(time);
    } catch (RuntimeException failure) {
      throw invalid();
    }
    String content =
        "服务《"
            + serviceName
            + "》："
            + label
            + (opinion == null || opinion.isEmpty() ? "" : "。" + opinion);
    long id = ids.nextId();
    if (id <= 0) throw new IllegalStateException("notification ID unavailable");
    store.recordOnce(
        consumerName(), event, id, ownerId, serviceId, "服务审核结果", content, decidedAt);
  }

  private static int submissionNo(JsonNode node) {
    if (node == null || !node.isIntegralNumber()) throw invalid();
    long value;
    try {
      value = node.longValue();
    } catch (RuntimeException failure) {
      throw invalid();
    }
    if (value < 1 || value > 4_294_967_295L) throw invalid();
    return (int) value;
  }

  private static boolean hasControl(String value) {
    return value.codePoints().anyMatch(code -> Character.isISOControl(code));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) throw invalid();
    return value.textValue();
  }

  private static long publicId(String value) {
    try {
      if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
      long id = Long.parseLong(value);
      if (id <= 0) throw invalid();
      return id;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("invalid service review event");
  }
}
