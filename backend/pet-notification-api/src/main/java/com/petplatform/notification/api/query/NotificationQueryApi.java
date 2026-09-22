package com.petplatform.notification.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationPage;
import com.petplatform.notification.api.dto.NotificationTypes.ReadReceipt;
import com.petplatform.notification.api.command.NotificationReadCommandApi.MarkReadCommand;

/** Owner-scoped inbox reads (CCR-W2-NOTIFICATION-001); receiver is always the caller. */
public interface NotificationQueryApi {

  NotificationPage listInbox(int page, int pageSize, QueryContext context);

  com.petplatform.notification.api.dto.NotificationTypes.NotificationItem getInboxItem(
      String notificationId, QueryContext context);

  ReadReceipt markRead(MarkReadCommand command);
}
