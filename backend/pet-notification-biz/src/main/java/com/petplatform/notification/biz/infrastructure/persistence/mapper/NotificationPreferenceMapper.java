package com.petplatform.notification.biz.infrastructure.persistence.mapper;

import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationPreferenceBindingEntity;
import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationPreferenceEntity;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * Notification preference statements; SQL lives in NotificationPreferenceMapper.xml. The
 * receiver scope is fixed to USER rows — this mapper never addresses other receiver types.
 */
public interface NotificationPreferenceMapper {

  void setTimeZoneUtc();

  void setLockWaitTimeout2Seconds();

  // ---- command_idempotency binding (14号 shared table, notification preference namespace) ----

  NotificationPreferenceBindingEntity selectBindingForUpdate(@Param("requestKey") String requestKey);

  int insertBinding(
      @Param("id") long id,
      @Param("requestKey") String requestKey,
      @Param("canonicalVersion") String canonicalVersion,
      @Param("paramsSha256") String paramsSha256,
      @Param("paramsCanonical") byte[] paramsCanonical);

  int markBindingSucceeded(
      @Param("requestKey") String requestKey, @Param("receiptJson") String receiptJson);

  // ---- notification_preference (schema 06 §11) ----

  NotificationPreferenceEntity selectPreference(@Param("receiverId") long receiverId);

  /**
   * Full-state upsert of both switches for the USER receiver; the update path bumps version.
   * The count is not an outcome signal — the caller re-selects the authoritative row.
   */
  int upsertPreference(
      @Param("id") long id,
      @Param("receiverId") long receiverId,
      @Param("interactionEnabled") boolean interactionEnabled,
      @Param("externalPushEnabled") boolean externalPushEnabled,
      @Param("updatedAt") OffsetDateTime updatedAt);
}
