package com.petplatform.notification.biz.infrastructure.persistence.mapper;

import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationInboxEntity;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Param;

/** Inbox read statements; SQL lives in NotificationInboxMapper.xml (CCR-W2-NOTIFICATION-001). */
public interface NotificationInboxMapper {

  NotificationInboxEntity selectInboxItem(
      @Param("id") long id, @Param("receiverId") long receiverId);

  long countInbox(@Param("receiverId") long receiverId);

  java.util.List<NotificationInboxEntity> selectInboxPage(
      @Param("receiverId") long receiverId,
      @Param("limit") int limit,
      @Param("offset") int offset);

  int markReadIfUnread(@Param("id") long id, @Param("receiverId") long receiverId, @Param("readAt") OffsetDateTime readAt);
}
