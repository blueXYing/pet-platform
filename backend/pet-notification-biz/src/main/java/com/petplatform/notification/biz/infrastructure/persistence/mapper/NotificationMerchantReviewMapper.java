package com.petplatform.notification.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

public interface NotificationMerchantReviewMapper {
  void setTimeZoneUtc();

  int insert(
      @Param("id") long id,
      @Param("ownerId") long ownerId,
      @Param("applicationId") long applicationId,
      @Param("title") String title,
      @Param("content") String content,
      @Param("createdAt") LocalDateTime createdAt);
}
