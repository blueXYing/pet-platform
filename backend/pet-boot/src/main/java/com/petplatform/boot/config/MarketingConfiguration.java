package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.marketing.api.MarketingAdminApi;
import com.petplatform.marketing.api.MarketingMerchantApi;
import com.petplatform.marketing.api.MarketingPublicApi;
import com.petplatform.marketing.biz.apiimpl.MarketingAdminApiImpl;
import com.petplatform.marketing.biz.apiimpl.MarketingMerchantApiImpl;
import com.petplatform.marketing.biz.apiimpl.MarketingPublicApiImpl;
import com.petplatform.marketing.biz.application.MarketingAdminAuthorizationPort;
import com.petplatform.marketing.biz.application.MarketingCityCatalogPort;
import com.petplatform.marketing.biz.application.MarketingCouponFactsPort;
import com.petplatform.marketing.biz.application.MarketingServiceCategoryPort;
import com.petplatform.marketing.biz.application.MarketingStoreFactsPort;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.merchant.api.query.MerchantQueryApi;
import com.petplatform.merchant.api.query.StoreIdQuery;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryStores;
import com.petplatform.service.api.query.CatalogServiceCategoryStoresQuery;
import com.petplatform.service.api.query.ServiceCatalogCategoryApi;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Marketing center runtime (contract 71, CCR-MKT-001). Default OFF; the kernel assembles only
 * when the feature switch is on. Boot-level adapters bridge the module-local ports to the admin
 * authorization owner, the merchant store read/profile owners, the coupon campaign query owner
 * and the open-city catalog. Missing owners fail closed at call time (503), never open writes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.marketing", name = "enabled", havingValue = "true")
public class MarketingConfiguration {

    @Bean
    MarketingAdminAuthorizationPort marketingAdminAuthorizationPort(
            ObjectProvider<AdminAuthorizationQueryApi> authorization) {
        return new MarketingAdminAuthorizationAdapter(authorization);
    }

    @Bean
    MarketingStoreFactsPort marketingStoreFactsPort(
            ObjectProvider<MerchantStoreDisplayApi> display,
            ObjectProvider<MerchantQueryApi> stores) {
        return new MarketingStoreFactsAdapter(display, stores);
    }

    @Bean
    MarketingCouponFactsPort marketingCouponFactsPort(
            ObjectProvider<CouponCampaignQueryApi> campaigns) {
        return new MarketingCouponFactsAdapter(campaigns);
    }

    @Bean
    MarketingCityCatalogPort marketingCityCatalogPort(
            ObjectProvider<MerchantApplicationCityCatalog> cities) {
        return new MarketingCityCatalogAdapter(cities);
    }

    @Bean
    MarketingServiceCategoryPort marketingServiceCategoryPort(
            ObjectProvider<ServiceCatalogCategoryApi> categories) {
        return new MarketingServiceCategoryAdapter(categories);
    }

    @Bean
    MarketingAssetObjectStore marketingAssetObjectStore() {
        return new MarketingOssAssetStore();
    }

    @Bean
    MarketingAdminApi marketingAdminApi(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            MarketingAdminAuthorizationPort authorization,
            MarketingStoreFactsPort storeFacts,
            MarketingCouponFactsPort couponFacts,
            MarketingCityCatalogPort cityCatalog,
            MarketingServiceCategoryPort serviceCategories,
            ObjectProvider<Clock> clock,
            @Value("${pet.marketing.asset.allow-http:false}") boolean allowHttpAssets,
            @Value("${pet.marketing.campaign.category-validation.enabled:false}")
            boolean categoryValidationEnabled,
            MarketingAssetObjectStore assetObjectStore) {
        return new MarketingAdminApiImpl(source, requireIds(ids), authorization, storeFacts,
                couponFacts, cityCatalog, clock.getIfAvailable(Clock::systemUTC), allowHttpAssets,
                assetObjectStore, serviceCategories, categoryValidationEnabled);
    }

    @Bean
    MarketingPublicApi marketingPublicApi(
            DataSource source, ObjectProvider<SnowflakeIdGenerator> ids,
            ObjectProvider<Clock> clock, MarketingAssetObjectStore assetObjectStore) {
        return new MarketingPublicApiImpl(source, requireIds(ids),
                clock.getIfAvailable(Clock::systemUTC), assetObjectStore);
    }

    @Bean
    MarketingMerchantApi marketingMerchantApi(
            DataSource source, ObjectProvider<SnowflakeIdGenerator> ids,
            MarketingStoreFactsPort storeFacts, ObjectProvider<Clock> clock) {
        return new MarketingMerchantApiImpl(source, requireIds(ids), storeFacts,
                clock.getIfAvailable(Clock::systemUTC));
    }

    private static SnowflakeIdGenerator requireIds(ObjectProvider<SnowflakeIdGenerator> ids) {
        SnowflakeIdGenerator provider = ids.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("Marketing kernel requires a Snowflake ID provider");
        }
        return provider;
    }

    /** Isolated opt-in Flyway migration; only an explicitly named mkt001_* database is accepted. */
    @Bean
    Object marketingMigration(
            DataSource source,
            @Value("${pet.marketing.migration-enabled:false}") boolean enabled,
            @Value("${pet.marketing.migration-database:}") String database) throws java.sql.SQLException {
        if (!enabled) return null;
        String catalog;
        try (java.sql.Connection connection = source.getConnection()) {
            catalog = connection.getCatalog();
        }
        if (database == null || !database.startsWith("mkt001_") || !database.equals(catalog)) {
            throw new IllegalStateException(
                    "Only explicit isolated marketing migration is permitted in this delivery");
        }
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/marketing-migration")
                .table("marketing_schema_history")
                .load()
                .migrate();
        return null;
    }

    // ------------------------------------------------------------------ adapters

    static final class MarketingAdminAuthorizationAdapter implements MarketingAdminAuthorizationPort {
        private final ObjectProvider<AdminAuthorizationQueryApi> authorization;

        MarketingAdminAuthorizationAdapter(ObjectProvider<AdminAuthorizationQueryApi> authorization) {
            this.authorization = Objects.requireNonNull(authorization);
        }

        @Override
        public Decision check(Check check) {
            AdminAuthorizationQueryApi api = authorization.getIfAvailable();
            if (api == null) throw unavailable("admin authorization owner is not assembled");
            AdminActionDecision decision;
            try {
                if (check.actionCode() != null && check.actionCode().endsWith(".read")
                        && check.resourceType() != null && check.resourceType().endsWith("_LIST")) {
                    decision = api.checkCollection(new AdminCollectionActionCheckQuery(
                            check.sessionId(), check.sessionGeneration(), check.operatorId(),
                            check.actionCode(), purpose(check.actionCode()), phase(check.actionCode())));
                } else {
                    decision = api.check(new AdminActionCheckQuery(
                            check.sessionId(), check.sessionGeneration(), check.operatorId(),
                            check.actionCode(),
                            new AdminResourceScope(
                                    check.resourceType(),
                                    check.resourceId() == null || "0".equals(check.resourceId()) ? "1" : check.resourceId(),
                                    check.merchantId(), check.cityCode(), "0"),
                            purpose(check.actionCode()), phase(check.actionCode())));
                }
            } catch (RuntimeException failure) {
                throw unavailable("admin authorization owner is unavailable");
            }
            if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                    || decision.authzVersion().isBlank()) {
                throw unavailable("authorization decision is unavailable");
            }
            return new Decision(decision.allowed(), decision.authzVersion(), decision.checkedAt());
        }

        private static AdminActionCheckQuery.CheckPhase phase(String action) {
            return action != null && action.endsWith(".read")
                    ? AdminActionCheckQuery.CheckPhase.READ_RESULT
                    : AdminActionCheckQuery.CheckPhase.EXECUTE;
        }

        private static String purpose(String action) {
            String p = "MARKETING_" + String.valueOf(action).replace('.', '_').toUpperCase(Locale.ROOT);
            return p.length() > 64 ? p.substring(0, 64) : p;
        }
    }

    static final class MarketingStoreFactsAdapter implements MarketingStoreFactsPort {
        private final ObjectProvider<MerchantStoreDisplayApi> display;
        private final ObjectProvider<MerchantQueryApi> stores;

        MarketingStoreFactsAdapter(ObjectProvider<MerchantStoreDisplayApi> display,
                ObjectProvider<MerchantQueryApi> stores) {
            this.display = display;
            this.stores = stores;
        }

        @Override
        public StoreFacts store(long storeId) {
            MerchantStoreDisplayApi api = display.getIfAvailable();
            if (api == null) throw unavailable("merchant store display owner is not assembled");
            try {
                MerchantStoreDisplayDTO dto = api.getDisplayStore(new MerchantStoreDisplayQuery(
                        Long.toUnsignedString(storeId),
                        new QueryContext("marketing", OperatorType.PLATFORM_OPERATOR, "0")));
                return new StoreFacts(true, dto.merchantId(), dto.storeName(), dto.cityCode(),
                        dto.address(), dto.promoLevel(), dto.promoTitle(), dto.promoText());
            } catch (ApiException notVisible) {
                if (CommonApiCodes.NOT_FOUND.equals(notVisible.code())) {
                    return new StoreFacts(false, null, null, null, null, null, null, null);
                }
                throw unavailable("merchant store display facts are unavailable");
            } catch (RuntimeException failure) {
                throw unavailable("merchant store display facts are unavailable");
            }
        }

        @Override
        public OwnershipFacts ownership(long userId, long merchantId, long storeId) {
            MerchantQueryApi api = stores.getIfAvailable();
            if (api == null) throw unavailable("merchant query owner is not assembled");
            try {
                var dto = api.getStore(new StoreIdQuery(
                        Long.toUnsignedString(storeId),
                        new QueryContext("marketing", OperatorType.USER, Long.toUnsignedString(userId))));
                if (!Long.toUnsignedString(merchantId).equals(dto.merchantId())) {
                    return new OwnershipFacts(false, false, false, false);
                }
                boolean merchantActive = "ACTIVE".equals(dto.merchantStatus());
                boolean storeActive = "ACTIVE".equals(dto.storeStatus());
                // An ACTIVE store can only exist after the application was approved; the
                // profile-only application fact is not required by the marketing gate.
                return new OwnershipFacts(true, merchantActive, storeActive,
                        merchantActive && storeActive);
            } catch (ApiException denied) {
                if (CommonApiCodes.NOT_FOUND.equals(denied.code())
                        || CommonApiCodes.FORBIDDEN.equals(denied.code())) {
                    return new OwnershipFacts(false, false, false, false);
                }
                throw unavailable("merchant ownership facts are unavailable");
            } catch (RuntimeException failure) {
                throw unavailable("merchant ownership facts are unavailable");
            }
        }
    }

    static final class MarketingCouponFactsAdapter implements MarketingCouponFactsPort {
        private final ObjectProvider<CouponCampaignQueryApi> campaigns;

        MarketingCouponFactsAdapter(ObjectProvider<CouponCampaignQueryApi> campaigns) {
            this.campaigns = campaigns;
        }

        @Override
        public CouponFacts facts(long couponCampaignId) {
            CouponCampaignQueryApi api = campaigns.getIfAvailable();
            if (api == null) throw unavailable("coupon campaign owner is not assembled");
            try {
                CouponCampaignQueryApi.CouponCampaignFactsView view = api.facts(
                        Long.toUnsignedString(couponCampaignId),
                        new QueryContext("marketing", OperatorType.PLATFORM_OPERATOR, "0"));
                if (view == null || !view.exists()) return new CouponFacts(false, false, null);
                boolean usable = view.templateUsable()
                        && ("ACTIVE".equals(view.status()) || "SCHEDULED".equals(view.status()));
                return new CouponFacts(true, usable, view.templateId());
            } catch (ApiException missing) {
                if (CommonApiCodes.NOT_FOUND.equals(missing.code())) {
                    return new CouponFacts(false, false, null);
                }
                throw unavailable("coupon campaign facts are unavailable");
            } catch (RuntimeException failure) {
                throw unavailable("coupon campaign facts are unavailable");
            }
        }
    }

    static final class MarketingCityCatalogAdapter implements MarketingCityCatalogPort {
        private final ObjectProvider<MerchantApplicationCityCatalog> cities;

        MarketingCityCatalogAdapter(ObjectProvider<MerchantApplicationCityCatalog> cities) {
            this.cities = cities;
        }

        @Override
        public boolean isOpen(String cityCode) {
            MerchantApplicationCityCatalog catalog = cities.getIfAvailable();
            if (catalog == null) throw unavailable("open-city catalog is not assembled");
            try {
                return catalog.list().stream().anyMatch(city -> city.code().equals(cityCode));
            } catch (RuntimeException failure) {
                throw unavailable("open-city catalog is unavailable");
            }
        }
    }

    /**
     * Contract 71 v0.3 §4.1: campaign {@code serviceCategoryCodes} ride the service owner's
     * catalog-category read face (74号 §3.2 resolution). Its error contract already encodes the
     * dictionary ruling — lexical-invalid/unknown/disabled codes are INVALID_ARGUMENT while an
     * enabled category without visible supply answers normally — so the adapter maps that split
     * onto the port: false for a rejected code, 503 for read failures (fail closed, never
     * silently accept).
     */
    static final class MarketingServiceCategoryAdapter implements MarketingServiceCategoryPort {
        private final ObjectProvider<ServiceCatalogCategoryApi> categories;

        MarketingServiceCategoryAdapter(ObjectProvider<ServiceCatalogCategoryApi> categories) {
            this.categories = Objects.requireNonNull(categories);
        }

        @Override
        public boolean usable(String categoryCode) {
            ServiceCatalogCategoryApi api = categories.getIfAvailable();
            if (api == null) throw unavailable("service category dictionary owner is not assembled");
            try {
                CatalogServiceCategoryStores resolved = api.listCatalogCategoryStores(
                        new CatalogServiceCategoryStoresQuery(categoryCode,
                                new QueryContext("marketing", OperatorType.PLATFORM_OPERATOR, "0")));
                return resolved != null;
            } catch (ApiException rejected) {
                if (CommonApiCodes.INVALID_ARGUMENT.equals(rejected.code())) return false;
                throw unavailable("service category dictionary is unavailable");
            } catch (RuntimeException failure) {
                throw unavailable("service category dictionary is unavailable");
            }
        }
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
