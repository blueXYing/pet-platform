package com.petplatform.notification.biz.apiimpl;

import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.notification.api.command.NotificationPreferenceApi;
import com.petplatform.notification.api.command.NotificationPreferenceApi.UpdatePreferenceCommand;
import com.petplatform.notification.api.dto.NotificationTypes.PreferenceView;
import com.petplatform.notification.biz.application.NotificationPreferenceService;
import com.petplatform.notification.biz.infrastructure.persistence.NotificationPreferenceStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the C-side notification preference surface (SSOT §16.4): reads and
 * the supplement-23 bound update over the shared notification_preference table.
 */
public final class NotificationPreferenceApiImpl implements NotificationPreferenceApi {
  private final NotificationPreferenceService service;

  public NotificationPreferenceApiImpl(DataSource dataSource, Clock clock, SnowflakeIdGenerator ids) {
    this.service = new NotificationPreferenceService(
        new NotificationPreferenceStore(
            Objects.requireNonNull(dataSource, "dataSource is required"),
            Objects.requireNonNull(ids, "ID provider is required")),
        Objects.requireNonNull(clock, "clock is required"));
  }

  @Override
  public PreferenceView getPreference(QueryContext context) {
    return service.getPreference(context);
  }

  @Override
  public PreferenceView updatePreference(UpdatePreferenceCommand command) {
    return service.updatePreference(command);
  }
}
