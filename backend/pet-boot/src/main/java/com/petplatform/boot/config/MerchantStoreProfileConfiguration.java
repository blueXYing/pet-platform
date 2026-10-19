package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi;
import com.petplatform.merchant.biz.apiimpl.MerchantStoreProfileApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.MerchantStoreProfileService.PhotoAssetPort;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Store profile composition (contract 27 §6.4 supplement): the audit/profile companion tables
 * (SQL 27-Merchant-Store-Profile) must be installed in the same merchant DB before enabling.
 * The photo port speaks the STORE_PHOTO purpose; the application review facts ride the
 * application runtime's shared reader when that assembly is present.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.store-profile", name = "enabled", havingValue = "true")
public class MerchantStoreProfileConfiguration {

    @Bean
    @ConditionalOnMissingBean(PhotoAssetPort.class)
    PhotoAssetPort storePhotoAssets(PrivateAssetApi assets) {
        return MerchantStoreProfileApiImpl.photoPort(
                new MerchantPrivateAssetQueryAdapter(assets, MerchantPrivateAssetQueryAdapter.STORE_PHOTO_PURPOSE));
    }

    @Bean
    MerchantStoreProfileQueryApi merchantStoreProfileApi(DataSource source, SnowflakeIdGenerator ids,
            ObjectProvider<ApplicationReviewFactsReader> applications, PhotoAssetPort photos,
            ObjectProvider<Clock> clock) {
        ApplicationReviewFactsReader unavailable = merchantId -> {
            throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant application review facts are not configured");
        };
        return new MerchantStoreProfileApiImpl(source, ids,
                applications.getIfAvailable(() -> unavailable), photos, clock.getIfAvailable(Clock::systemUTC));
    }
}
