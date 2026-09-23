package com.petplatform.notification.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

public interface NotificationServiceReviewMapper {
  void setTimeZoneUtc();

  int insert(
      @Param("id") long id,
      @Param("ownerId") long ownerId,
      @Param("serviceId") long serviceId,
      @Param("title") String title,
      @Param("content") String content,
      @Param("createdAt") LocalDateTime createdAt);
}
