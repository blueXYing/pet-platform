package com.petplatform.boot.config;

import com.petplatform.notification.biz.apiimpl.NotificationInboxApiImpl;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * USER inbox read surface (CCR-W2-NOTIFICATION-001): assembled with the C-end session slice;
 * approvals write notifications under the merchant application switch, reads do not depend on it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class NotificationInboxConfiguration {

  @Bean
  NotificationInboxApiImpl notificationInboxApi(DataSource source, ObjectProvider<Clock> clock) {
    return new NotificationInboxApiImpl(source, clock.getIfAvailable(Clock::systemUTC));
  }
}
