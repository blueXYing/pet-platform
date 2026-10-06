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

  /**
   * SSOT §16.4 preference projection over notification_preference: the only two togglable
   * switches plus the schema's version counter (BIGINT carried as String) and updated_at
   * (null while no row exists — the unread defaults are in effect).
   */
  public record PreferenceView(
      boolean interactionEnabled,
      boolean externalPushEnabled,
      String version,
      OffsetDateTime updatedAt) {}
}
