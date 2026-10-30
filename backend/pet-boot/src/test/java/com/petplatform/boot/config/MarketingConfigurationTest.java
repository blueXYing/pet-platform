package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.marketing.api.MarketingAdminApi;
import com.petplatform.marketing.api.MarketingMerchantApi;
import com.petplatform.marketing.api.MarketingPublicApi;
import com.petplatform.marketing.biz.application.MarketingServiceCategoryPort;
import com.petplatform.merchant.api.query.MerchantQueryApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryPage;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryStores;
import com.petplatform.service.api.query.CatalogServiceCategoryQuery;
import com.petplatform.service.api.query.CatalogServiceCategoryStoresQuery;
import com.petplatform.service.api.query.ServiceCatalogCategoryApi;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MarketingConfigurationTest {

    @Test
    void assemblesAllThreeMarketingApisWhenEnabledAndProvidersExist() {
        DataSource source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/not_connected", "u", "p");
        new ApplicationContextRunner()
                .withUserConfiguration(MarketingConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(AdminAuthorizationQueryApi.class, () -> mock(AdminAuthorizationQueryApi.class))
                .withBean(MerchantStoreDisplayApi.class, () -> mock(MerchantStoreDisplayApi.class))
                .withBean(MerchantQueryApi.class, () -> mock(MerchantQueryApi.class))
                .withBean(CouponCampaignQueryApi.class, () -> mock(CouponCampaignQueryApi.class))
                .withBean(MerchantApplicationCityCatalog.class,
                        () -> new MerchantApplicationCityCatalog(
                                List.of(new MerchantApplicationCityCatalog.City("shanghai", "上海"))))
                .withPropertyValues("pet.marketing.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MarketingAdminApi.class);
                    assertThat(context).hasSingleBean(MarketingPublicApi.class);
                    assertThat(context).hasSingleBean(MarketingMerchantApi.class);
                    // Contract 71 v0.3: the service-dictionary port assembles with the kernel.
                    assertThat(context).hasSingleBean(MarketingServiceCategoryPort.class);
                });
    }

    @Test
    void staysOffByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(MarketingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MarketingPublicApi.class);
                });
    }

    @Test
    void serviceCategoryPortMapsDictionaryRejectionAndEnabledResolution() {
        DataSource source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/not_connected", "u", "p");
        // The service owner's catalog face already encodes the dictionary ruling: lexical
        // invalid/unknown/disabled codes surface as INVALID_ARGUMENT (400); an enabled category
        // answers normally (with or without visible supply).
        ServiceCatalogCategoryApi mixed = new ServiceCatalogCategoryApi() {
            @Override
            public CatalogServiceCategoryPage listCatalogCategories(CatalogServiceCategoryQuery query) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CatalogServiceCategoryStores listCatalogCategoryStores(
                    CatalogServiceCategoryStoresQuery query) {
                if ("UNKNOWN_CODE".equals(query.categoryCode())) {
                    throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "categoryCode is invalid");
                }
                return new CatalogServiceCategoryStores(query.categoryCode(), List.of());
            }
        };
        new ApplicationContextRunner()
                .withUserConfiguration(MarketingConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(ServiceCatalogCategoryApi.class, () -> mixed)
                .withPropertyValues("pet.marketing.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MarketingServiceCategoryPort port =
                            context.getBean(MarketingServiceCategoryPort.class);
                    assertThat(port.usable("PET_GROOMING")).isTrue();
                    assertThat(port.usable("UNKNOWN_CODE")).isFalse();
                });
    }

    @Test
    void serviceCategoryPortFailsClosedWithoutTheServiceOwner() {
        DataSource source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/not_connected", "u", "p");
        new ApplicationContextRunner()
                .withUserConfiguration(MarketingConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withPropertyValues("pet.marketing.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MarketingServiceCategoryPort port =
                            context.getBean(MarketingServiceCategoryPort.class);
                    ApiException unavailable = assertThrows(ApiException.class,
                            () -> port.usable("PET_GROOMING"));
                    assertThat(unavailable.code())
                            .isEqualTo(CommonApiCodes.DEPENDENCY_UNAVAILABLE);
                });
    }
}
