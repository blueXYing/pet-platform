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
 * Strict consumer of MerchantStaffInvitationLifecycleEvent.v1 (Event08 registration, contract 54
 * NTF slice, ten-field payload: invitationId/merchantId/storeId/ownerUserId/memberName/
 * phoneMasked/changeType/confirmedUserId?/memberId?/occurredAt). Reachability is bounded by the
 * notification schema (SQL06 §11): a row is addressed to a USER account id only, and the invitee
 * has no user_account until confirm (contract 54 §2), so INVITED/CANCELED notify the OWNER —
 * carrying the invitation number the owner must relay offline — and CONFIRMED notifies the owner
 * plus the confirming employee. Never propagates arbitrary payload fields into a user-visible
 * message; the receiver ids come from the event itself, the consumer never reads merchant tables
 * (ARCH-002).
 */
public final class MerchantStaffInvitationConsumer implements IntegrationEventConsumer {
  public static final String TYPE = "MerchantStaffInvitationLifecycleEvent.v1";
  private static final Set<String> FIELDS =
      Set.of(
          "invitationId",
          "merchantId",
          "storeId",
          "ownerUserId",
          "memberName",
          "phoneMasked",
          "changeType",
          "confirmedUserId",
          "memberId",
          "occurredAt");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final MerchantStaffNotificationStore store;
  private final SnowflakeIdGenerator ids;
  private final com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery;

  public MerchantStaffInvitationConsumer(
      MerchantStaffNotificationStore store, SnowflakeIdGenerator ids) {
    this(store, ids, null);
  }

  /** NTF-002: nullable delivery producer; null (switch off) keeps the consumer behavior identical. */
  public MerchantStaffInvitationConsumer(
      MerchantStaffNotificationStore store,
      SnowflakeIdGenerator ids,
      com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery) {
    this.store = Objects.requireNonNull(store);
    this.ids = Objects.requireNonNull(ids);
    this.delivery = delivery;
  }

  public MerchantStaffInvitationConsumer(
      javax.sql.DataSource source,
      SnowflakeIdGenerator ids,
      java.util.function.BiPredicate<String, DispatchedEvent> consumeGuard) {
    this(new MerchantStaffNotificationStore(source, consumeGuard), ids);
  }

  public MerchantStaffInvitationConsumer(
      javax.sql.DataSource source,
      SnowflakeIdGenerator ids,
      java.util.function.BiPredicate<String, DispatchedEvent> consumeGuard,
      com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer delivery) {
    this(new MerchantStaffNotificationStore(source, consumeGuard), ids, delivery);
  }

  @Override
  public String consumerName() {
    return "notification.merchant-staff-invitation.v1";
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
        || !"MERCHANT_MEMBER_INVITATION".equals(event.aggregateType())
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
    long invitationId = publicId(text(payload, "invitationId"));
    publicId(text(payload, "merchantId")); // shape-checked; the receiver never comes from merchant
    publicId(text(payload, "storeId"));
    long ownerId = publicId(text(payload, "ownerUserId"));
    if (event.aggregateId() != invitationId) throw invalid();
    String memberName = text(payload, "memberName");
    int nameLength = memberName.codePointCount(0, memberName.length());
    if (nameLength < 1 || nameLength > 64 || hasControl(memberName)) throw invalid();
    String phoneMasked = text(payload, "phoneMasked");
    if (!phoneMasked.matches("1[0-9]{2}\\*{4}[0-9]{4}")) throw invalid();
    String changeType = text(payload, "changeType");
    OffsetDateTime occurredAt = time(payload);
    String invitationNo = Long.toUnsignedString(invitationId);
    BiConsumer<Long, Long> hook =
        delivery == null ? null : (id, receiver) -> delivery.enqueueAfterInboxInsert(id, receiver);
    String number = "编号" + invitationNo;
    switch (changeType) {
      case "INVITED" -> {
        requireNull(payload, "confirmedUserId");
        requireNull(payload, "memberId");
        store.recordOnce(
            consumerName(),
            event,
            new MerchantStaffNotificationStore.Outbox(
                notificationId(),
                ownerId,
                "MER_STAFF_INVITATION",
                "MEMBER_INVITATION",
                invitationId,
                "员工邀请已发出",
                "已向"
                    + memberName
                    + "（"
                    + phoneMasked
                    + "）发出员工邀请，"
                    + number
                    + "。请将编号转达被邀人，用于在小程序内完成确认。",
                occurredAt),
            null,
            hook);
      }
      case "CANCELED" -> {
        requireNull(payload, "confirmedUserId");
        requireNull(payload, "memberId");
        store.recordOnce(
            consumerName(),
            event,
            new MerchantStaffNotificationStore.Outbox(
                notificationId(),
                ownerId,
                "MER_STAFF_INVITATION",
                "MEMBER_INVITATION",
                invitationId,
                "员工邀请已撤销",
                "向"
                    + memberName
                    + "（"
                    + phoneMasked
                    + "）发出的员工邀请已撤销，"
                    + number
                    + "，被邀人将无法再确认。",
                occurredAt),
            null,
            hook);
      }
      case "CONFIRMED" -> {
        long confirmedUserId = publicId(text(payload, "confirmedUserId"));
        long memberId = publicId(text(payload, "memberId"));
        store.recordOnce(
            consumerName(),
            event,
            new MerchantStaffNotificationStore.Outbox(
                notificationId(),
                ownerId,
                "MER_STAFF_INVITATION",
                "MEMBER_INVITATION",
                invitationId,
                "员工邀请已确认",
                memberName + "已确认员工邀请（" + number + "），绑定完成，门店操作权限已按邀请内容开通。",
                occurredAt),
            new MerchantStaffNotificationStore.Outbox(
                notificationId(),
                confirmedUserId,
                "MER_STAFF_INVITATION",
                "MEMBER_INVITATION",
                invitationId,
                "员工邀请确认成功",
                "你已确认员工邀请（" + number + "），绑定完成。",
                occurredAt),
            hook);
      }
      default -> throw invalid();
    }
  }

  private long notificationId() {
    long id = ids.nextId();
    if (id <= 0) throw new IllegalStateException("notification ID unavailable");
    return id;
  }

  private static void requireNull(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isNull()) throw invalid();
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

  private static boolean hasControl(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
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
    return new IllegalArgumentException("invalid merchant staff invitation event");
  }
}
