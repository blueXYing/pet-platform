package com.petplatform.boot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.event.core.OutboxDispatchSettings;
import com.petplatform.event.core.OutboxDispatcher;
import com.petplatform.event.core.OutboxRetryDelays;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.notification.biz.event.MerchantApplicationReviewedConsumer;
import com.petplatform.notification.biz.event.ServiceReviewedConsumer;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PLAT-003 outbox assembly, default OFF and additionally gated on a production SnowflakeIdGenerator
 * bean (PLAT-002 S2 enablement is a separate decision). Application-review notifications are
 * separately opt-in; an empty registration keeps the dispatcher idle. Turning this on also requires
 * the outbox tables to exist — no migration is executed or implied here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.outbox", name = "enabled", havingValue = "true")
@ConditionalOnBean(SnowflakeIdGenerator.class)
public class EventOutboxConfiguration {

    @Bean
    TransactionalOutboxPublisher transactionalOutboxPublisher(DataSource dataSource,
            SnowflakeIdGenerator ids, ObjectProvider<ObjectMapper> objectMapper) {
        return new TransactionalOutboxPublisher(dataSource, ids,
                objectMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    JdbcOutboxConsumeGuard outboxConsumeGuard(DataSource dataSource, SnowflakeIdGenerator ids) {
        return new JdbcOutboxConsumeGuard(dataSource, ids);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "pet.merchant.application",
      name = "notifications-enabled",
      havingValue = "true")
  MerchantApplicationReviewedConsumer merchantApplicationReviewedConsumer(
      DataSource dataSource, SnowflakeIdGenerator ids, JdbcOutboxConsumeGuard guard) {
    return new MerchantApplicationReviewedConsumer(dataSource, ids, guard::tryClaim);
    }

    // Role W wiring (2026-09-23): the consumer class from PR#69 is now on the classpath, so the
    // reserved registration lands here, gated by pet.service.review.notifications-enabled
    // (default off) exactly like the merchant application consumer above. The consumer is
    // self-contained (recipient = event ownerUserId, ARCH-002): only its own store and the
    // shared Snowflake/guard beans are needed. With the switch off, pending ServiceReviewedEvent
    // rows keep waiting in the outbox (dispatched once enabled).
    @Bean
    @ConditionalOnProperty(
            prefix = "pet.service.review",
            name = "notifications-enabled",
            havingValue = "true")
    ServiceReviewedConsumer serviceReviewedConsumer(
            DataSource dataSource, SnowflakeIdGenerator ids, JdbcOutboxConsumeGuard guard) {
        return new ServiceReviewedConsumer(dataSource, ids, guard::tryClaim);
    }

    @Bean(destroyMethod = "close")
    OutboxDispatcher outboxDispatcher(DataSource dataSource,
            ObjectProvider<IntegrationEventConsumer> consumers,
            @org.springframework.beans.factory.annotation.Value("${pet.outbox.owner:boot-outbox-1}") String owner) {
        OutboxDispatcher dispatcher = new OutboxDispatcher(dataSource, owner,
                OutboxDispatchSettings.defaults(),
                new OutboxRetryDelays(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
                        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(300))),
                consumers.orderedStream().toList());
        dispatcher.start();
        return dispatcher;
    }
}
