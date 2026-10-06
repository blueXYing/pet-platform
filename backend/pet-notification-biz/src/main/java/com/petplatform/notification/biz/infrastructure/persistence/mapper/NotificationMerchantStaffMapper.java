package com.petplatform.notification.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * NTF staff-binding slice: one parameterized authoritative notification insert shared by the
 * invitation and member lifecycle consumers (message_type / biz_type / biz_id differ per event).
 */
public interface NotificationMerchantStaffMapper {
  void setTimeZoneUtc();

  int insert(
      @Param("id") long id,
      @Param("receiverId") long receiverId,
      @Param("messageType") String messageType,
      @Param("bizType") String bizType,
      @Param("bizId") long bizId,
      @Param("title") String title,
      @Param("content") String content,
      @Param("createdAt") LocalDateTime createdAt);
}
