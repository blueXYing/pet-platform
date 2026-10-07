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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Strict consumer of MerchantStaffGrantLifecycleEvent.v1 (Event08 registration, contract 54 NTF
 * grant slice per the 2026-10-07 ruling, seven-field payload: memberId/merchantId/storeId/
 * memberUserId/changeType/actions/occurredAt). GRANTED covers the first store grant and the
 * whole-set action replacement, REVOKED the terminal store-wide revoke; both notify only the
 * bound staff account (the OWNER actor is not notified about its own command). Action codes
 * are shape-validated before any label mapping, so no payload text reaches a user-visible
 * message unvalidated (ARCH-002).
 */
public final class MerchantStaffGrantConsumer implements IntegrationEventConsumer {
  public static final String TYPE = "MerchantStaffGrantLifecycleEvent.v1";
  private static final Set<String> FIELDS =
      Set.of(
          "memberId",
          "merchantId",
          "storeId",
          "memberUserId",
          "changeType",
          "actions",
          "occurredAt");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final MerchantStaffNotificationStore store;
  private final SnowflakeIdGenerator ids;
  private final com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery;

  public MerchantStaffGrantConsumer(
      MerchantStaffNotificationStore store, SnowflakeIdGenerator ids) {
    this(store, ids, null);
  }

  /** NTF-002: nullable delivery producer; null (switch off) keeps the consumer behavior identical. */
  public MerchantStaffGrantConsumer(
      MerchantStaffNotificationStore store,
      SnowflakeIdGenerator ids,
      com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery) {
    this.store = Objects.requireNonNull(store);
    this.ids = Objects.requireNonNull(ids);
    this.delivery = delivery;
  }

  public MerchantStaffGrantConsumer(
      javax.sql.DataSource source,
      SnowflakeIdGenerator ids,
      java.util.function.BiPredicate<String, DispatchedEvent> consumeGuard) {
    this(new MerchantStaffNotificationStore(source, consumeGuard), ids);
  }

  public MerchantStaffGrantConsumer(
      javax.sql.DataSource source,
      SnowflakeIdGenerator ids,
      java.util.function.BiPredicate<String, DispatchedEvent> consumeGuard,
      com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery) {
    this(new MerchantStaffNotificationStore(source, consumeGuard), ids, delivery);
  }

  @Override
  public String consumerName() {
    return "notification.merchant-staff-grant.v1";
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
    long storeId = publicId(text(payload, "storeId"));
    long memberUserId = publicId(text(payload, "memberUserId"));
    if (event.aggregateId() != memberId) throw invalid();
    String changeType = text(payload, "changeType");
    boolean granted = "GRANTED".equals(changeType);
    if (!granted && !"REVOKED".equals(changeType)) throw invalid();
    List<String> actions = actions(payload, granted);
    OffsetDateTime occurredAt = time(payload);
    String scope = "门店编号" + Long.toUnsignedString(storeId) + "，成员编号"
        + Long.toUnsignedString(memberId);
    String title;
    String content;
    if (granted) {
      title = "门店权限已更新";
      content =
          "你在门店的员工操作权限已更新：" + summary(actions) + "。可执行的门店操作以最新授权为准（" + scope + "）。";
    } else {
      title = "门店权限已收回";
      content =
          "商家已收回你的门店操作权限"
              + (actions.isEmpty() ? "" : "：" + summary(actions))
              + "，对应门店操作立即失效（"
              + scope
              + "）。";
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
            "MER_STAFF_GRANT",
            "MERCHANT_MEMBER",
            memberId,
            title,
            content,
            occurredAt),
        hook);
  }

  /**
   * Strict action summary: an array of at most 8 distinct dotted catalog codes; a GRANTED change
   * carries at least one (the contract-54 catalog rejects empty grants), a REVOKED change may
   * carry the withdrawn set or nothing when the stored set was already empty.
   */
  private static List<String> actions(JsonNode payload, boolean granted) {
    JsonNode node = payload.get("actions");
    if (node == null || !node.isArray() || node.size() > 8) throw invalid();
    Set<String> distinct = new HashSet<>();
    List<String> actions = new ArrayList<>(node.size());
    for (JsonNode item : node) {
      if (item == null || !item.isTextual()) throw invalid();
      String code = item.textValue();
      if (code.length() < 1 || code.length() > 64
          || !code.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*){1,5}") || !distinct.add(code))
        throw invalid();
      actions.add(code);
    }
    if (granted && actions.isEmpty()) throw invalid();
    return List.copyOf(actions);
  }

  /** Display label for the approved D2 catalog; future catalog codes show their validated code. */
  private static String summary(List<String> actions) {
    StringBuilder text = new StringBuilder();
    for (String action : actions) {
      if (text.length() > 0) text.append('、');
      text.append("merchant.order.verify".equals(action) ? "订单核销" : action);
    }
    return text.toString();
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
    return new IllegalArgumentException("invalid merchant staff grant event");
  }
}
