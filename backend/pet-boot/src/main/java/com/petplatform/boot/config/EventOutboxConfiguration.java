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
import com.petplatform.notification.biz.event.MerchantStaffGrantConsumer;
import com.petplatform.notification.biz.event.MerchantStaffInvitationConsumer;
import com.petplatform.notification.biz.event.MerchantStaffMemberConsumer;
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
@ConditionalOnBean(SnowflakeIdGenerator.class)
public class EventOutboxConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(com.petplatform.event.api.IntegrationEventPublisher.class)
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
  MerchantApplicationReviewedConsumer merchantApplicationReviewedConsumer(
      DataSource dataSource,
      SnowflakeIdGenerator ids,
      JdbcOutboxConsumeGuard guard,
      ObjectProvider<com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer>
              wechatDelivery) {
    return new MerchantApplicationReviewedConsumer(
        dataSource, ids, guard::tryClaim, wechatDelivery.getIfAvailable());
    }

    @Bean
    ServiceReviewedConsumer serviceReviewedConsumer(
      DataSource dataSource,
      SnowflakeIdGenerator ids,
      JdbcOutboxConsumeGuard guard,
      ObjectProvider<com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer>
              wechatDelivery) {
        return new ServiceReviewedConsumer(dataSource, ids, guard::tryClaim, wechatDelivery.getIfAvailable());
    }

    @Bean
    MerchantStaffInvitationConsumer merchantStaffInvitationConsumer(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            JdbcOutboxConsumeGuard guard,
            ObjectProvider<com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer>
                    wechatDelivery) {
        return new MerchantStaffInvitationConsumer(
                dataSource, ids, guard::tryClaim, wechatDelivery.getIfAvailable());
    }

    @Bean
    MerchantStaffMemberConsumer merchantStaffMemberConsumer(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            JdbcOutboxConsumeGuard guard,
            ObjectProvider<com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer>
                    wechatDelivery) {
        return new MerchantStaffMemberConsumer(
                dataSource, ids, guard::tryClaim, wechatDelivery.getIfAvailable());
    }

    @Bean
    MerchantStaffGrantConsumer merchantStaffGrantConsumer(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            JdbcOutboxConsumeGuard guard,
            ObjectProvider<com.petplatform.notification.biz.delivery.WechatDeliveryTaskProducer>
                    wechatDelivery) {
        return new MerchantStaffGrantConsumer(
                dataSource, ids, guard::tryClaim, wechatDelivery.getIfAvailable());
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
