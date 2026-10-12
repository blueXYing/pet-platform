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

    @Test void orphanHttpOrKernelWithoutRealCDependenciesFailsClosed() {
        runner().withPropertyValues("pet.review.http.enabled=true").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure())
                    .hasStackTraceContaining("real C session and order query dependencies");
        });
        // Kernel on without pet.auth.c.enabled: the beans exist but the switch validation
        // still fails closed (provide the collaborators so the failure is the gate, not wiring).
        dependencies().withPropertyValues("pet.review.enabled=true").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure())
                    .hasStackTraceContaining("real C session and order query dependencies");
        });
        // The full real-dependency stack passes: kernel on, HTTP face allowed on top of it.
        dependencies("pet.review.enabled=true", "pet.auth.c.enabled=true").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(ReviewApiImpl.class);
        });
        dependencies("pet.review.enabled=true", "pet.auth.c.enabled=true",
                "pet.review.http.enabled=true").run(c -> assertThat(c).hasNotFailed());
    }

    /** The controller stays absent unless both pet.auth.c.enabled and the HTTP switch are on. */
    @Test void controllerStaysDefaultOffWithoutBothSwitches() {
        new ApplicationContextRunner()
                .withUserConfiguration(com.petplatform.boot.adapter.web.c.CReviewController.class)
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c).doesNotHaveBean(
                            com.petplatform.boot.adapter.web.c.CReviewController.class);
                });
        new ApplicationContextRunner()
                .withUserConfiguration(com.petplatform.boot.adapter.web.c.CReviewController.class)
                .withPropertyValues("auth.c.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(
                        com.petplatform.boot.adapter.web.c.CReviewController.class));
    }

    /** REV-002 appeal gates: orphan http fails, appeal without the guard/admin session fails. */
    @Test void appealSwitchesFailClosedWithoutTheirDependencies() {
        // Appeal HTTP on top of an off kernel: fail closed.
        runner().withPropertyValues("pet.review.appeal.http.enabled=true").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure())
                    .hasStackTraceContaining("Review appeal requires");
        });
        // Appeal on with the C stack but without the store guard and ADMIN_WEB session domain.
        dependencies("pet.review.enabled=true", "pet.auth.c.enabled=true",
                "pet.review.appeal.enabled=true").run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure())
                            .hasStackTraceContaining("Review appeal requires");
                });
        // The appeal controller itself stays default off regardless of the C face flags.
        new ApplicationContextRunner()
                .withUserConfiguration(
                        com.petplatform.boot.adapter.web.review.MerchantReviewAppealController.class)
                .withPropertyValues("pet.review.enabled=true", "pet.auth.c.enabled=true",
                        "pet.review.http.enabled=true")
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
