package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.marketing.api.MarketingAdminApi;
import com.petplatform.marketing.api.MarketingMerchantApi;
import com.petplatform.marketing.api.MarketingPublicApi;
import com.petplatform.merchant.api.query.MerchantQueryApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
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
}
