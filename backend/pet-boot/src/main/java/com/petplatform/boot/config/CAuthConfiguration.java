package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.thirdparty.api.PetAvatarSigningApi;
import com.petplatform.user.biz.application.PetService;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.user.biz.application.UserProfileService;
import com.petplatform.user.biz.application.WechatSessionProvider;
import com.petplatform.user.biz.infrastructure.provider.MiniAuthVolatileStore;
import com.petplatform.user.biz.infrastructure.provider.WechatMiniApiProvider;
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
    PetService petService(DataSource source, ObjectProvider<SnowflakeIdGenerator> ids,
                          ObjectProvider<PetAvatarSigningApi> avatarSigning) {
        SnowflakeIdGenerator idProvider = ids.getIfAvailable();
        return new PetService(source, Objects.requireNonNull(idProvider,
                "PLAT-002 ID provider required before C pet writes can start"),
                avatarSigning.getIfAvailable());
    }

    /**
     * Order-contact store phone read (HTTP10 §3.7 supplement, 2026-10-19 ruling): the C order
     * detail adapter composes the merchant domain's contact line onto the owner-scoped order
     * read. Rides the C session assembly (same gate as the order routes), never the store
     * catalog switch — an existing order must stay contactable regardless of catalog assembly.
     */
    @Bean
    com.petplatform.merchant.api.query.MerchantStoreContactQueryApi merchantStoreContactApi(
            DataSource source) {
        return new com.petplatform.merchant.biz.apiimpl.MerchantStoreContactApiImpl(source);
    }

    /** Health records (contract 68, 2026-10-19 ruling): same user-domain assembly as pets. */
    @Bean
    com.petplatform.user.biz.application.PetHealthRecordService petHealthRecordService(
            DataSource source, ObjectProvider<SnowflakeIdGenerator> ids) {
        SnowflakeIdGenerator idProvider = ids.getIfAvailable();
        return new com.petplatform.user.biz.application.PetHealthRecordService(source,
                Objects.requireNonNull(idProvider,
                        "PLAT-002 ID provider required before C health-record writes can start"));
    }

    /** Address book (contract 59, ruling-32 second batch): same user-domain assembly as pets. */
    @Bean
    com.petplatform.user.biz.application.AddressService addressService(
            DataSource source, ObjectProvider<SnowflakeIdGenerator> ids) {
        SnowflakeIdGenerator idProvider = ids.getIfAvailable();
        return new com.petplatform.user.biz.application.AddressService(source,
                Objects.requireNonNull(idProvider,
                        "PLAT-002 ID provider required before C address writes can start"));
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
    @ConditionalOnMissingBean(WechatSessionProvider.class)
    WechatSessionProvider wechatSessionProvider(CAuthProperties p) {
        if (!p.getWechat().configured()) {
            throw new IllegalStateException(
                    "pet.auth.c.wechat.app-id / app-secret must be configured (deployment"
                            + " environment) to assemble the real WeChat provider");
        }
        return new WechatMiniApiProvider(new WechatMiniApiProvider.Settings(
                p.getWechat().getAppId(), p.getWechat().getAppSecret(), p.getWechat().getApiBase()));
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
