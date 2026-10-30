package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.AdminCouponCampaignApi;
import com.petplatform.coupon.api.command.CouponClaimApi;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CouponCampaignConfigurationTest {

    @Test
    void assemblesCampaignQueryClaimAndAdminApisWhenEnabled() {
        DataSource source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/not_connected", "u", "p");
        new ApplicationContextRunner()
                .withUserConfiguration(CouponCampaignConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(AdminAuthorizationQueryApi.class, () -> mock(AdminAuthorizationQueryApi.class))
                .withPropertyValues("pet.coupon.campaign.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AdminCouponCampaignApi.class);
                    assertThat(context).hasSingleBean(CouponCampaignQueryApi.class);
                    assertThat(context).hasSingleBean(CouponClaimApi.class);
                });
    }

    @Test
    void staysOffByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(CouponCampaignConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CouponClaimApi.class);
                });
    }
}
