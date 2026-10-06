package com.petplatform.notification.biz.infrastructure.persistence.mapper;

import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationDeliveryEntity;
import org.apache.ibatis.annotations.Param;

/** Owner mapper over the shared notification_delivery table (Schema 06 section 11). */
public interface NotificationDeliveryMapper {

  void setTimeZoneUtc();

  int insertPending(
      @Param("id") long id,
      @Param("notificationId") long notificationId,
      @Param("channel") String channel);

  NotificationDeliveryEntity selectDelivery(
      @Param("notificationId") long notificationId, @Param("channel") String channel);

  int markSent(
      @Param("notificationId") long notificationId,
      @Param("channel") String channel,
      @Param("providerMessageId") String providerMessageId);

  /** status must be FAILED or SKIPPED; the XML whitelist enforces it a second time. */
  int markTerminal(
      @Param("notificationId") long notificationId,
      @Param("channel") String channel,
      @Param("status") String status,
      @Param("lastError") String lastError);

  int recordRetry(
      @Param("notificationId") long notificationId,
      @Param("channel") String channel,
      @Param("retryCount") int retryCount,
      @Param("lastError") String lastError);
}
