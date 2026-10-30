package com.petplatform.boot.config;

import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsApi;
import com.petplatform.merchant.api.query.MerchantFeaturedStoreFactsApi;
import com.petplatform.merchant.biz.apiimpl.MerchantFeaturedStoreFactsApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.MerchantAgreementEligibilityFactsAdapter;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi;
import com.petplatform.order.biz.apiimpl.ServiceCatalogSalesFactsApiImpl;
import com.petplatform.review.api.query.ReviewPublicFactsApi;
import com.petplatform.service.api.query.FeaturedStoreCatalogApi;
import com.petplatform.thirdparty.api.StorePhotoSigningApi;
import com.petplatform.thirdparty.biz.apiimpl.StorePhotoSigningApiImpl;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 74 §3.4 featured-store card composition (B-CARD-01/02/03): assembled with the C service
 * catalog slice, whose {@link FeaturedStoreCatalogApi} rides the existing
 * {@code pet.service.query.enabled} assembly (ServiceQueryApiImpl implements it). The order-domain
 * sales facts port is wired here because no earlier slice exposed it; the review rating facts ride
 * the review kernel's assembly and degrade to {@code available=false} when that kernel is off; the
 * merchant card facts reuse the merchant application slice's review-facts reader exactly like the
 * STR-D5/D6 display faces. The avatar signer exists only under the real private-asset pipeline;
 * stores with a bound avatar fail closed (503) without it, mirroring the cover-signing precedent.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.service.query", name = "enabled", havingValue = "true")
public class FeaturedStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(ServiceCatalogSalesFactsApi.class)
    ServiceCatalogSalesFactsApi serviceCatalogSalesFacts(DataSource source) {
        return new ServiceCatalogSalesFactsApiImpl(source);
    }

    @Bean
    MerchantFeaturedStoreFactsApi merchantFeaturedStoreFacts(
            DataSource source, ApplicationReviewFactsReader applications) {
        return new MerchantFeaturedStoreFactsApiImpl(
                source, new MerchantAgreementEligibilityFactsAdapter(source, applications));
    }

    /** Only the shared 31号 pipeline signs store photos; the cover signer carries the OSS keys. */
    @Bean
    @ConditionalOnProperty(prefix = "pet.private-assets", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(StorePhotoSigningApi.class)
    StorePhotoSigningApi storePhotoSigning(
            DataSource source,
            ObjectProvider<ServiceCoverObjectSigner> signers,
            ObjectProvider<Clock> clocks) {
        return new StorePhotoSigningApiImpl(
                source, signers.getIfAvailable(), clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    FeaturedStoreCardComposer featuredStoreCardComposer(
            ObjectProvider<MerchantApplicationCityCatalog> catalogs,
            MerchantDisplayStoreIdsApi displayStoreIds,
            FeaturedStoreCatalogApi featuredCatalog,
            MerchantFeaturedStoreFactsApi merchantFacts,
            ObjectProvider<ReviewPublicFactsApi> reviews,
            ObjectProvider<ServiceCatalogSalesFactsApi> sales,
            ObjectProvider<StorePhotoSigningApi> photoSigning,
            DataSource source) {
        // The merchant faces join the caller's active transaction on the shared DataSource (their
        // facts readers refuse to run outside one), so the composer supplies the same read-only
        // repeatable-read snapshot discipline the service domain's read store uses.
        var merchantReads =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        merchantReads.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        merchantReads.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        merchantReads.setReadOnly(true);
        merchantReads.setTimeout(10);
        return new FeaturedStoreCardComposer(
                catalogs, displayStoreIds, featuredCatalog, merchantFacts, reviews, sales,
                photoSigning, merchantReads);
    }
}
