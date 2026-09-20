package com.petplatform.notification.biz.event;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.MerchantReviewNotificationStore;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Strict Event08 consumer. Never propagates arbitrary payload fields into a user-visible message.
 */
public final class MerchantApplicationReviewedConsumer implements IntegrationEventConsumer {
  public static final String TYPE = "MerchantApplicationReviewedEvent.v1";
  private static final Set<String> FIELDS =
      Set.of(
          "applicationId",
          "applicationNo",
          "ownerUserId",
          "reservedMerchantId",
          "submittedRevisionId",
          "reviewDecisionId",
          "decisionType",
          "applicationStatus",
          "applicantVisibleOpinion",
          "decidedAt");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final MerchantReviewNotificationStore store;
  private final SnowflakeIdGenerator ids;

  public MerchantApplicationReviewedConsumer(
      MerchantReviewNotificationStore store, SnowflakeIdGenerator ids) {
    this.store = Objects.requireNonNull(store);
    this.ids = Objects.requireNonNull(ids);
  }

  public MerchantApplicationReviewedConsumer(
      javax.sql.DataSource source,
      SnowflakeIdGenerator ids,
      java.util.function.BiPredicate<String, DispatchedEvent> consumeGuard) {
    this(new MerchantReviewNotificationStore(source, consumeGuard), ids);
  }

  @Override
  public String consumerName() {
    return "notification.merchant-application-reviewed.v1";
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
        || !"MERCHANT_APPLICATION".equals(event.aggregateType())
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
    long applicationId = publicId(text(payload, "applicationId"));
    long ownerId = publicId(text(payload, "ownerUserId"));
    publicId(text(payload, "reservedMerchantId"));
    publicId(text(payload, "submittedRevisionId"));
    publicId(text(payload, "reviewDecisionId"));
    if (event.aggregateId() != applicationId) throw invalid();
    String applicationNo = text(payload, "applicationNo");
    if (!applicationNo.matches("SQ[0-9]{8}[A-Za-z0-9]{8}")) throw invalid();
    String decision = text(payload, "decisionType");
    String state = text(payload, "applicationStatus");
    String label =
        switch (decision) {
          case "APPROVE" -> "审核通过";
          case "REJECT" -> "审核未通过";
          case "REQUEST_CORRECTION" -> "请补正申请材料";
          default -> throw invalid();
        };
    if (!(decision.equals("APPROVE") ? "APPROVED" : "REJECTED").equals(state)) throw invalid();
    JsonNode opinionNode = payload.get("applicantVisibleOpinion");
    String opinion = opinionNode.isNull() ? null : text(payload, "applicantVisibleOpinion");
    int length = opinion == null ? 0 : opinion.codePointCount(0, opinion.length());
    if (length > 500 || (!decision.equals("APPROVE") && (length < 10 || opinion.isBlank())))
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
        "申请 "
            + applicationNo
            + "："
            + label
            + (opinion == null || opinion.isEmpty() ? "" : "。" + opinion);
    long id = ids.nextId();
    if (id <= 0) throw new IllegalStateException("notification ID unavailable");
    store.recordOnce(
        consumerName(), event, id, ownerId, applicationId, "商家入驻审核结果", content, decidedAt);
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
    return new IllegalArgumentException("invalid merchant application review event");
  }
}
