package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.user.biz.apiimpl.FavoriteApiImpl;
import com.petplatform.user.biz.apiimpl.FootprintApiImpl;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 58 favorite/footprint slice (32号裁决第二节批次): assembled only when the C session
 * slice AND both catalog query slices are on — target admission and list enrichment call the
 * frozen C catalog surfaces (ServiceQueryApi / MerchantStoreDisplayApi), so a missing catalog
 * bean is an assembly error, never a runtime fallback. Default off in production until the
 * deployment gate opens it (pet.favtrack.http.enabled).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.c.enabled", "favtrack.http.enabled",
            "service.query.enabled", "store.query.enabled"},
    havingValue = "true")
public class FavoriteFootprintConfiguration {

    @Bean
    FavoriteApiImpl favoriteApi(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            ServiceQueryApi services,
            MerchantStoreDisplayApi stores) {
        return new FavoriteApiImpl(source, idProvider(ids), services, stores);
    }

    @Bean
    FootprintApiImpl footprintApi(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            ServiceQueryApi services,
            MerchantStoreDisplayApi stores) {
        return new FootprintApiImpl(source, idProvider(ids), services, stores);
    }

    private static SnowflakeIdGenerator idProvider(ObjectProvider<SnowflakeIdGenerator> ids) {
        return java.util.Objects.requireNonNull(ids.getIfAvailable(),
                "PLAT-002 ID provider required before favorite/footprint writes can start");
    }
}
