package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.biz.application.PetService;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.user.biz.application.UserProfileService;
import com.petplatform.user.biz.application.WechatSessionProvider;
import com.petplatform.user.biz.infrastructure.provider.MiniAuthVolatileStore;
import com.petplatform.user.biz.infrastructure.provider.RedisMiniAuthVolatileStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end auth assembly, default off. Enabling without an externally supplied (authorized)
 * WechatSessionProvider bean fails startup instead of silently degrading; IDs must come from
 * the PLAT-002 provider — this module never issues its own.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CAuthProperties.class)
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CAuthConfiguration {

    /** Duplicate JSON keys must be a 400 even when the admin slice (which adds its own) is off. */
    @Bean
    org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer cStrictJson() {
        return builder ->
                builder.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    @Bean
    PetService petService(DataSource source, ObjectProvider<SnowflakeIdGenerator> ids) {
        SnowflakeIdGenerator idProvider = ids.getIfAvailable();
        return new PetService(source, Objects.requireNonNull(idProvider,
                "PLAT-002 ID provider required before C pet writes can start"));
    }

    @Bean
    @ConditionalOnMissingBean(MiniAuthVolatileStore.class)
    MiniAuthVolatileStore miniAuthVolatileStore(CAuthProperties p) {
        return new RedisMiniAuthVolatileStore(
                p.getRedisHost(),
                p.getRedisPort(),
                p.getRedisUsername(),
                p.getRedisPassword() == null ? null : p.getRedisPassword().toCharArray(),
                p.getCachePrefix());
    }

    @Bean
    UserAuthService userAuthService(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            ObjectProvider<Clock> clock,
            ObjectProvider<WechatSessionProvider> wechat,
            MiniAuthVolatileStore volatileStore,
            CAuthProperties p) {
        SnowflakeIdGenerator idProvider = ids.getIfAvailable();
        if (idProvider == null) {
            throw new IllegalStateException("PLAT-002 ID provider required before C auth can start");
        }
        WechatSessionProvider provider = wechat.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException(
                    "No authorized WechatSessionProvider assembled; C auth stays off");
        }
        return new UserAuthService(
                source,
                idProvider,
                clock.getIfAvailable(Clock::systemUTC),
                provider,
                volatileStore,
                new UserAuthService.MiniAuthPolicy(
                        p.getAttemptTtlSeconds(),
                        p.getAccessTtlSeconds(),
                        p.getGrantWindowSeconds(),
                        p.getAttemptCreatePerMinute(),
                        p.getAttemptFailureLimit()));
    }

    @Bean
    UserProfileService userProfileService(DataSource source, ObjectProvider<SnowflakeIdGenerator> ids) {
        SnowflakeIdGenerator idProvider = ids.getIfAvailable();
        return new UserProfileService(source, Objects.requireNonNull(idProvider,
                "PLAT-002 ID provider required before C profile writes can start"));
    }
}
