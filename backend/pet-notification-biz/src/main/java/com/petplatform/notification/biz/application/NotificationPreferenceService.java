package com.petplatform.notification.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.notification.api.command.NotificationPreferenceApi.UpdatePreferenceCommand;
import com.petplatform.notification.api.dto.NotificationTypes.PreferenceView;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationPreferenceStore;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationPreferenceStore.Binding;
import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationPreferenceEntity;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owner-scoped notification preference surface (SSOT §16.4; schema 06 §11). The receiver is
 * always the caller; client parameters never select it. Reads treat a missing row as the
 * schema defaults (both switches on, version 0, no updatedAt). The update is a supplement-23
 * bound command over the shared 14号 table: the same requestId replays the first success
 * receipt, the same key with different parameters is an IDEMPOTENCY_KEY_CONFLICT, and the
 * business upsert and the binding's SUCCEEDED mark commit in one transaction. No external
 * push is performed here — the external_push_enabled switch only records the preference
 * (delivery capability is shelved, ruling #108).
 */
public final class NotificationPreferenceService {
  private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
  private static final ObjectMapper JSON = new ObjectMapper();
  /** Unpersisted default view: both schema defaults on, version still zero, never updated. */
  private static final PreferenceView DEFAULTS = new PreferenceView(true, true, "0", null);

  private final NotificationPreferenceStore store;
  private final Clock clock;

  public NotificationPreferenceService(NotificationPreferenceStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  public PreferenceView getPreference(QueryContext context) {
    long receiverId = userId(context);
    return store.read(
        mapper -> {
          NotificationPreferenceEntity row = mapper.selectPreference(receiverId);
          return row == null ? DEFAULTS : view(row);
        });
  }

  public PreferenceView updatePreference(UpdatePreferenceCommand command) {
    if (command == null) invalid("command is required");
    CommandContext context = command.context();
    if (context == null || context.operatorType() == null
        || context.operatorId() == null || context.operatorId().isBlank()) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
    }
    if (context.operatorType() != OperatorType.USER) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "preferences belong to miniapp users");
    }
    try {
      PublicContractChecks.requireCommandRequestId(context);
    } catch (IllegalArgumentException invalidContext) {
      invalid("requestId is invalid");
    }
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()) {
      throw new IllegalStateException(
          "preference command admission must not run inside an existing transaction");
    }
    final long receiverId;
    try {
      receiverId = IDS.fromApi(context.operatorId());
    } catch (IllegalArgumentException invalidPrincipal) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
    }

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("interactionEnabled", command.interactionEnabled());
    params.put("externalPushEnabled", command.externalPushEnabled());
    NotificationCanonicalParams.Canonical canonical = NotificationCanonicalParams.of(params);
    String key =
        NotificationPreferenceStore.requestKey(
            "notification.preference.update",
            context.operatorType().name(),
            context.operatorId(),
            "USER_SELF",
            context.requestId());
    admitWithRecovery(key, canonical);
    PreferenceView[] out = new PreferenceView[1];
    try {
      executeBound(key, canonical, command, receiverId, out);
    } catch (NotificationPreferenceStore.CommitUnknown first) {
      try {
        executeBound(key, canonical, command, receiverId, out);
      } catch (NotificationPreferenceStore.CommitUnknown second) {
        unavailable("notification preference commit result remains unknown");
      }
    }
    return out[0];
  }

  private void executeBound(
      String key,
      NotificationCanonicalParams.Canonical canonical,
      UpdatePreferenceCommand command,
      long receiverId,
      PreferenceView[] out) {
    store.execute(
        mapper -> {
          Binding binding = NotificationPreferenceStore.require(mapper, key);
          NotificationPreferenceStore.same(binding, canonical);
          if ("SUCCEEDED".equals(binding.status())) {
            // 23号 §5.4: replay the first success receipt; the current identity was re-derived
            // from the live session at entry, so nothing stale authorizes this call.
            out[0] = receipt(binding.receiptJson());
            return null;
          }
          var updatedAt =
              clock.instant().truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC);
          mapper.upsertPreference(
              store.nextId(),
              receiverId,
              command.interactionEnabled(),
              command.externalPushEnabled(),
              updatedAt);
          out[0] = view(mapper.selectPreference(receiverId));
          if (mapper.markBindingSucceeded(key, receiptJson(out[0])) != 1)
            unavailable("idempotency success mark failed");
          return null;
        });
  }

  private void admitWithRecovery(String key, NotificationCanonicalParams.Canonical canonical) {
    try {
      store.admit(key, canonical);
    } catch (NotificationPreferenceStore.CommitUnknown firstUnknown) {
      try {
        store.admit(key, canonical);
      } catch (NotificationPreferenceStore.CommitUnknown secondUnknown) {
        unavailable("idempotency admission result remains unknown");
      }
    }
  }

  private static PreferenceView view(NotificationPreferenceEntity row) {
    if (row == null
        || row.getId() == null || row.getId() <= 0
        || row.getReceiverId() == null || row.getReceiverId() <= 0
        || !"USER".equals(row.getReceiverType())
        || row.getInteractionEnabled() == null || row.getExternalPushEnabled() == null
        || row.getVersion() == null || row.getVersion() < 0
        || row.getCreatedAt() == null || row.getUpdatedAt() == null) {
      unavailable("notification preference projection is inconsistent");
    }
    return new PreferenceView(
        row.getInteractionEnabled(),
        row.getExternalPushEnabled(),
        Long.toString(row.getVersion()),
        row.getUpdatedAt());
  }

  /** Deterministic first-success receipt; fields are two booleans, a digit string and ISO time. */
  private static String receiptJson(PreferenceView view) {
    return "{\"interactionEnabled\":" + view.interactionEnabled()
        + ",\"externalPushEnabled\":" + view.externalPushEnabled()
        + ",\"version\":\"" + view.version() + "\""
        + ",\"updatedAt\":" + (view.updatedAt() == null
            ? "null" : "\"" + format(view.updatedAt()) + "\"") + "}";
  }

  private static PreferenceView receipt(String json) {
    try {
      JsonNode root = JSON.readTree(json);
      if (!root.isObject()
          || root.size() != 4
          || !root.has("interactionEnabled") || !root.get("interactionEnabled").isBoolean()
          || !root.has("externalPushEnabled") || !root.get("externalPushEnabled").isBoolean()
          || !root.has("version") || !root.get("version").isTextual()
          || !root.get("version").asText().matches("^(0|[1-9][0-9]{0,18})$")
          || !root.has("updatedAt") || !root.get("updatedAt").isNull()
              && !root.get("updatedAt").isTextual()) {
        unavailable("preference receipt is damaged");
      }
      JsonNode updatedAt = root.get("updatedAt");
      return new PreferenceView(
          root.get("interactionEnabled").asBoolean(),
          root.get("externalPushEnabled").asBoolean(),
          root.get("version").asText(),
          updatedAt.isNull() ? null
              : java.time.Instant.parse(updatedAt.asText()).atOffset(ZoneOffset.UTC));
    } catch (ApiException failure) {
      throw failure;
    } catch (Exception failure) {
      unavailable("preference receipt is damaged");
      return null;
    }
  }

  private static String format(java.time.OffsetDateTime value) {
    return new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        .format(value.toInstant());
  }

  private static long userId(QueryContext context) {
    if (context == null || context.operatorType() == null
        || context.operatorId() == null || context.operatorId().isBlank()) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context is required");
    }
    if (context.operatorType() != OperatorType.USER) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "preferences belong to miniapp users");
    }
    try {
      return IDS.fromApi(context.operatorId());
    } catch (IllegalArgumentException invalidPrincipal) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
    }
  }

  private static void invalid(String message) {
    throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
  }

  private static void unavailable(String message) {
    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
  }
}
