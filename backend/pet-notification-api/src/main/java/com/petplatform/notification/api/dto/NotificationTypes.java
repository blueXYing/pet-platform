package com.petplatform.notification.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** CCR-W2-NOTIFICATION-001 wire types for the USER inbox read surface. */
public final class NotificationTypes {

  private NotificationTypes() {}

  public record NotificationItem(
      String id,
      String category,
      String messageType,
      String bizType,
      String bizId,
      String title,
      String content,
      OffsetDateTime readAt,
      OffsetDateTime createdAt) {}

  public record NotificationPage(List<NotificationItem> items, int page, int pageSize, long total) {}

  public record ReadReceipt(String id, OffsetDateTime readAt) {}
}
