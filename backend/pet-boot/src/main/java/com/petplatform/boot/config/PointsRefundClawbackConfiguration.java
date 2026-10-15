package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.points.biz.event.PointsRefundClawbackConsumer;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 60 §7 refund clawback (PTS-002, CCR-POINTS-ORDER-001), default OFF — the same
 * assembly shape as the coupon restore consumer: the consumer is dispatched by the outbox once
 * both switches are on; with the switch off, pending {@code RefundSucceededEvent.v1} rows keep
 * waiting (dispatched once enabled). Requires a production Snowflake bean (PLAT-002, the same
 * constraint the earn kernel carries) and the outbox consume-guard assembly; the ledger writes
 * need the SQL06 §9 tables only — no migration is executed or implied here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.points.refund-clawback", name = "enabled",
        havingValue = "true")
public class PointsRefundClawbackConfiguration {

    @Bean
    PointsRefundClawbackConsumer pointsRefundClawbackConsumer(DataSource source,
            SnowflakeIdGenerator ids, JdbcOutboxConsumeGuard consumeGuard) {
        return new PointsRefundClawbackConsumer(source, ids, consumeGuard);
    }
}
