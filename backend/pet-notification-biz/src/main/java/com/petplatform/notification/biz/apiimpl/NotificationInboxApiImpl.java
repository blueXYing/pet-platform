package com.petplatform.notification.biz.apiimpl;

import com.petplatform.common.QueryContext;
import com.petplatform.notification.api.command.NotificationReadCommandApi.MarkReadCommand;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationItem;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationPage;
import com.petplatform.notification.api.dto.NotificationTypes.ReadReceipt;
import com.petplatform.notification.api.query.NotificationQueryApi;
import com.petplatform.notification.biz.application.NotificationInboxService;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationInboxStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the USER inbox read surface (CCR-W2-NOTIFICATION-001). */
public final class NotificationInboxApiImpl implements NotificationQueryApi {
  private final NotificationInboxService service;

  public NotificationInboxApiImpl(DataSource dataSource, Clock clock) {
    this.service = new NotificationInboxService(
        new NotificationInboxStore(Objects.requireNonNull(dataSource, "dataSource is required")),
        Objects.requireNonNull(clock, "clock is required"));
  }

  @Override
  public NotificationPage listInbox(int page, int pageSize, QueryContext context) {
    return service.listInbox(page, pageSize, context);
  }

  @Override
  public NotificationItem getInboxItem(String notificationId, QueryContext context) {
    return service.getInboxItem(notificationId, context);
  }

  @Override
  public ReadReceipt markRead(MarkReadCommand command) {
    return service.markRead(command);
  }
}
