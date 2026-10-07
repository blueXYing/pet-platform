package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.notification.biz.apiimpl.NotificationInboxApiImpl;
import com.petplatform.notification.biz.apiimpl.NotificationPreferenceApiImpl;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * USER inbox read surface (CCR-W2-NOTIFICATION-001): assembled with the C-end session slice;
 * approvals write notifications under the merchant application switch, reads do not depend on it.
 * The preference surface (SSOT §16.4) rides the same switch: its update needs the shared
 * Snowflake provider for the preference row and the 14号 binding ids.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class NotificationInboxConfiguration {

  @Bean
  NotificationInboxApiImpl notificationInboxApi(DataSource source, ObjectProvider<Clock> clock) {
    return new NotificationInboxApiImpl(source, clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  NotificationPreferenceApiImpl notificationPreferenceApi(
      DataSource source, ObjectProvider<Clock> clock, ObjectProvider<SnowflakeIdGenerator> ids) {
    return new NotificationPreferenceApiImpl(
        source,
        clock.getIfAvailable(Clock::systemUTC),
        Objects.requireNonNull(
            ids.getIfAvailable(),
            "PLAT-002 ID provider required before notification preference writes can start"));
  }
}
