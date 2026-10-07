package com.petplatform.notification.biz.event;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.notification.biz.infrastructure.persistence.MerchantStaffNotificationStore;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Strict consumer of MerchantStaffMemberLifecycleEvent.v1 (Event08 registration, contract 54 NTF
 * slice, six-field payload: memberId/merchantId/storeId/memberUserId/changeType/occurredAt). The
 * only reachable subject is the bound staff account (SQL06 §11 USER receiver); the OWNER actor is
 * not notified about its own command. Never propagates arbitrary payload fields into a
 * user-visible message (ARCH-002).
 */
public final class MerchantStaffMemberConsumer implements IntegrationEventConsumer {
  public static final String TYPE = "MerchantStaffMemberLifecycleEvent.v1";
  private static final Set<String> FIELDS =
      Set.of(
          "memberId",
          "merchantId",
          "storeId",
          "memberUserId",
          "changeType",
          "occurredAt");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final MerchantStaffNotificationStore store;
  private final SnowflakeIdGenerator ids;
  private final com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery;

  public MerchantStaffMemberConsumer(
      MerchantStaffNotificationStore store, SnowflakeIdGenerator ids) {
    this(store, ids, null);
  }

  /** NTF-002: nullable delivery producer; null (switch off) keeps the consumer behavior identical. */
  public MerchantStaffMemberConsumer(
      MerchantStaffNotificationStore store,
      SnowflakeIdGenerator ids,
      com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery) {
    this.store = Objects.requireNonNull(store);
    this.ids = Objects.requireNonNull(ids);
    this.delivery = delivery;
  }

  public MerchantStaffMemberConsumer(
      javax.sql.DataSource source,
      SnowflakeIdGenerator ids,
      java.util.function.BiPredicate<String, DispatchedEvent> consumeGuard) {
    this(new MerchantStaffNotificationStore(source, consumeGuard), ids);
  }

  public MerchantStaffMemberConsumer(
      javax.sql.DataSource source,
      SnowflakeIdGenerator ids,
      java.util.function.BiPredicate<String, DispatchedEvent> consumeGuard,
      com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery) {
    this(new MerchantStaffNotificationStore(source, consumeGuard), ids, delivery);
  }

  @Override
  public String consumerName() {
    return "notification.merchant-staff-member.v1";
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
        || !"MERCHANT_MEMBER".equals(event.aggregateType())
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
    long memberId = publicId(text(payload, "memberId"));
    publicId(text(payload, "merchantId"));
    publicId(text(payload, "storeId"));
    long memberUserId = publicId(text(payload, "memberUserId"));
    if (event.aggregateId() != memberId) throw invalid();
    String changeType = text(payload, "changeType");
    OffsetDateTime occurredAt = time(payload);
    String memberNo = "成员编号" + Long.toUnsignedString(memberId);
    String title;
    String content;
    if ("DISABLED".equals(changeType)) {
      title = "员工身份已停用";
      content = "你在商家的员工身份已被停用（" + memberNo + "），停用期间无法执行门店操作。";
    } else if ("ENABLED".equals(changeType)) {
      title = "员工身份已恢复";
      content = "你在商家的员工身份已恢复启用（" + memberNo + "），可继续执行已授权的门店操作。";
    } else {
      throw invalid();
    }
    BiConsumer<Long, Long> hook =
        delivery == null ? null : (id, receiver) -> delivery.enqueueAfterInboxInsert(id, receiver);
    long id = ids.nextId();
    if (id <= 0) throw new IllegalStateException("notification ID unavailable");
    store.recordOnce(
        consumerName(),
        event,
        new MerchantStaffNotificationStore.Outbox(
            id,
            memberUserId,
            "MER_STAFF_MEMBER",
            "MERCHANT_MEMBER",
            memberId,
            title,
            content,
            occurredAt),
        hook);
  }

  private static OffsetDateTime time(JsonNode payload) {
    try {
      String value = text(payload, "occurredAt");
      if (!value.matches(
          "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}(Z|[+-][0-9]{2}:[0-9]{2})"))
        throw invalid();
      return OffsetDateTime.parse(value);
    } catch (RuntimeException failure) {
      throw invalid();
    }
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
    return new IllegalArgumentException("invalid merchant staff member event");
  }
}
