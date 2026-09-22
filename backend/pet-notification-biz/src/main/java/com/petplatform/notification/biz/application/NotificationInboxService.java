package com.petplatform.notification.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.notification.api.command.NotificationReadCommandApi.MarkReadCommand;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationItem;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationPage;
import com.petplatform.notification.api.dto.NotificationTypes.ReadReceipt;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationInboxStore;
import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationInboxEntity;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Owner-scoped USER inbox surface (CCR-W2-NOTIFICATION-001). The receiver is always the
 * caller; client parameters never select it. Mark-read is a result-idempotent CAS write.
 */
public final class NotificationInboxService {
  private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
  private static final Pattern CATEGORY = Pattern.compile("[A-Z][A-Z_]{2,31}");
  private static final Pattern MESSAGE_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
  private static final Set<String> CATEGORIES = Set.of("INTERACTION", "SERVICE", "SYSTEM");

  private final NotificationInboxStore store;
  private final Clock clock;

  public NotificationInboxService(NotificationInboxStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  public NotificationPage listInbox(int page, int pageSize, QueryContext context) {
    if (page < 1 || page > 10_000) invalid("page is invalid");
    if (pageSize < 1 || pageSize > 50) invalid("pageSize is invalid");
    long receiverId = userId(context);
    return store.read(mapper -> {
      long total = mapper.countInbox(receiverId);
      List<NotificationInboxEntity> rows =
          mapper.selectInboxPage(receiverId, pageSize, (int) ((page - 1L) * pageSize));
      List<NotificationItem> items = new ArrayList<>(rows.size());
      for (NotificationInboxEntity row : rows) items.add(item(row));
      return new NotificationPage(List.copyOf(items), page, pageSize, total);
    });
  }

  public NotificationItem getInboxItem(String notificationId, QueryContext context) {
    long id = targetId(notificationId);
    long receiverId = userId(context);
    NotificationInboxEntity row = store.read(mapper -> mapper.selectInboxItem(id, receiverId));
    if (row == null) notFound();
    return item(row);
  }

  public ReadReceipt markRead(MarkReadCommand command) {
    if (command == null) invalid("command is required");
    long id = targetId(command.notificationId());
    CommandContext context = command.context();
    if (context == null || context.operatorType() == null
        || context.operatorId() == null || context.operatorId().isBlank()) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
    }
    if (context.operatorType() != OperatorType.USER) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "inbox reads require a miniapp user");
    }
    try {
      PublicContractChecks.requireCommandRequestId(context);
    } catch (IllegalArgumentException invalidContext) {
      invalid("requestId is invalid");
    }
    long receiverId;
    try {
      receiverId = IDS.fromApi(context.operatorId());
    } catch (IllegalArgumentException invalidPrincipal) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
    }
    var readAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC);
    return store.write(mapper -> {
      NotificationInboxEntity row = mapper.selectInboxItem(id, receiverId);
      if (row == null) notFound();
      if (row.getReadAt() == null) {
        // CAS: only the first marking sets the timestamp; replays observe the current state.
        if (mapper.markReadIfUnread(id, receiverId, readAt) != 1) {
          row = mapper.selectInboxItem(id, receiverId);
          if (row == null) notFound();
        } else {
          row.setReadAt(readAt);
        }
      }
      return new ReadReceipt(IDS.toApi(id), row.getReadAt());
    });
  }

  private static NotificationItem item(NotificationInboxEntity row) {
    if (row == null || row.getId() == null || row.getId() <= 0
        || row.getReceiverId() == null || row.getReceiverId() <= 0
        || !"USER".equals(row.getReceiverType())
        || row.getCategory() == null || !CATEGORIES.contains(row.getCategory())
        || row.getMessageType() == null || !MESSAGE_TYPE.matcher(row.getMessageType()).matches()
        || row.getTitle() == null || row.getTitle().isEmpty() || row.getTitle().length() > 128
        || row.getContent() == null || row.getContent().isEmpty() || row.getContent().length() > 1000
        || (row.getBizType() != null && !CATEGORY.matcher(row.getBizType()).matches())
        || row.getCreatedAt() == null) {
      unavailable("notification projection is inconsistent");
    }
    return new NotificationItem(
        IDS.toApi(row.getId()),
        row.getCategory(),
        row.getMessageType(),
        row.getBizType(),
        row.getBizId() == null ? null : IDS.toApi(row.getBizId()),
        row.getTitle(),
        row.getContent(),
        row.getReadAt(),
        row.getCreatedAt());
  }

  private static long userId(QueryContext context) {
    if (context == null || context.operatorType() == null
        || context.operatorId() == null || context.operatorId().isBlank()) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context is required");
    }
    if (context.operatorType() != OperatorType.USER) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "inbox reads require a miniapp user");
    }
    try {
      return IDS.fromApi(context.operatorId());
    } catch (IllegalArgumentException invalidPrincipal) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
    }
  }

  private static long targetId(String value) {
    if (value == null) invalid("notificationId is required");
    try {
      return IDS.fromApi(value);
    } catch (IllegalArgumentException invalidTarget) {
      invalid("notificationId is invalid");
      return 0;
    }
  }

  private static void invalid(String message) {
    throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
  }

  private static void notFound() {
    throw new ApiException(CommonApiCodes.NOT_FOUND, "notification not found");
  }

  private static void unavailable(String message) {
    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
  }
}
