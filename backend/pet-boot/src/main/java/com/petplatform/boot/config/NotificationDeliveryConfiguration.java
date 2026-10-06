package com.petplatform.boot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.notification.biz.delivery.WechatDeliveryTaskHandler;
import com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer;
import com.petplatform.notification.biz.delivery.spi.UnconfiguredWechatDeliveryAdapter;
import com.petplatform.notification.biz.delivery.spi.WechatDeliveryAdapter;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import java.time.Clock;
import java.util.Collection;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NTF-002 external WeChat delivery skeleton, default OFF (contract 55). Turning
 * {@code pet.notification.delivery.enabled=true} wires the producer into the review consumers,
 * the WECHAT_DELIVER task registration and a shared durable-task worker. Even when enabled, V1
 * binds no real channel: the default adapter is the {@link UnconfiguredWechatDeliveryAdapter}
 * shell (no SDK, no credentials, no outbound call); a real adapter/SDK and the production
 * subscribe-message template policy remain a separate authorization. Worker and producer both
 * require a production SnowflakeIdGenerator bean (PLAT-002 enablement) and the async_task /
 * notification_delivery tables to exist — no migration is executed or implied here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.notification.delivery", name = "enabled", havingValue = "true")
@ConditionalOnBean(SnowflakeIdGenerator.class)
public class NotificationDeliveryConfiguration {

  @Bean
  WechatDeliveryTaskProducer wechatDeliveryTaskProducer(
      DataSource dataSource,
      SnowflakeIdGenerator ids,
      @Value("${pet.notification.delivery.channel:WECHAT_SUBSCRIBE}") String channel,
      @Value("${pet.notification.delivery.max-retry-count:5}") int maxRetryCount) {
    return new WechatDeliveryTaskProducer(dataSource, ids, channel, maxRetryCount);
  }

  @Bean
  @ConditionalOnMissingBean(WechatDeliveryAdapter.class)
  WechatDeliveryAdapter wechatDeliveryAdapter() {
    return new UnconfiguredWechatDeliveryAdapter();
  }

  @Bean
  TaskRegistration<WechatDeliveryTaskHandler.Payload> wechatDeliveryTaskRegistration(
      DataSource dataSource, WechatDeliveryAdapter adapter) {
    return new WechatDeliveryTaskHandler(dataSource, adapter).registration(new ObjectMapper());
  }

  /**
   * The process owns one SQL13 worker over the complete registration collection (same precedent
   * as the private-asset runtime); later handlers join the collection instead of polling in
   * parallel.
   */
  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnMissingBean(AsyncTaskWorker.class)
  AsyncTaskWorker notificationDeliveryWorker(
      DataSource dataSource,
      SnowflakeIdGenerator ids,
      Collection<TaskRegistration<?>> registrations,
      ObjectProvider<Clock> clock,
      @Value("${pet.notification.delivery.worker-owner:boot-notification-delivery-1}") String owner) {
    return AsyncTaskWorker.create(
        dataSource,
        ids,
        owner,
        clock.getIfAvailable(Clock::systemUTC),
        TaskWorkerSettings.defaults(),
        new TaskRetryDelays(java.util.Map.of(WechatDeliveryTaskHandler.RETRY_POLICY,
            WechatDeliveryTaskHandler.BACKOFF)),
        registrations);
  }
}
