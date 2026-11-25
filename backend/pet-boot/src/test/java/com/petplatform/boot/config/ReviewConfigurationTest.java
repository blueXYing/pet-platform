package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.review.biz.apiimpl.ReviewApiImpl;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** REV-001 review assembly gates (mirror of the refund configuration test). */
class ReviewConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ReviewConfiguration.class);
    }

    @Test void defaultsAssembleNoKernel() {
        runner().run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).doesNotHaveBean(ReviewApiImpl.class);
        });
    }

    @Test void assemblesWithCAuthAndAppealRequiresAdmin() {
        dependencies("pet.auth.c.enabled=true").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(ReviewApiImpl.class);
            assertThat(c).doesNotHaveBean(ReviewAppealAuthorityAdapter.class);
        });
        new ApplicationContextRunner()
                .withUserConfiguration(com.petplatform.boot.adapter.web.c.CReviewController.class)
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c).doesNotHaveBean(
                            com.petplatform.boot.adapter.web.c.CReviewController.class);
                });
        new ApplicationContextRunner()
                .withUserConfiguration(
                        com.petplatform.boot.adapter.web.review.MerchantReviewAppealController.class)
                .withPropertyValues("pet.auth.c.enabled=true")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c).doesNotHaveBean(
                            com.petplatform.boot.adapter.web.review.MerchantReviewAppealController.class);
                });
    }

    private ApplicationContextRunner dependencies(String... properties) {
        return runner().withPropertyValues(properties)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(OrderQueryApi.class, () -> mock(OrderQueryApi.class));
    }
}
