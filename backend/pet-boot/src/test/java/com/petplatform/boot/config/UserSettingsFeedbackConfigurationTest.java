package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.customerservice.biz.application.FeedbackTicketService;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.user.api.query.UserActiveStatusApi;
import com.petplatform.user.biz.application.AccountDeletionService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Contract 66/67 assembly gates (offline, per the configuration-test discipline): both new
 * C faces stay unassembled at the production defaults; the settings/deletion face needs its
 * three-way hard precondition (C session + slice switch + OWNER-membership surface) and the
 * feedback face the two-way one (C session + slice switch); a missing fact/status port is an
 * assembly failure, never a silent fallback (66号 §2 / 67号 §5; CCR-USR-DELETION-001 /
 * CCR-CS-FEEDBACK-001). Beans are registered through {@code withBean} before refresh so the
 * fail-closed cases can assert on the startup failure itself.
 */
class UserSettingsFeedbackConfigurationTest {

    private static final String[] DELETION_PRECONDITIONS = {
            "pet.auth.c.enabled=true", "pet.usersettings.http.enabled=true",
            "pet.merchant.application.enabled=true"};
    private static final String[] FEEDBACK_PRECONDITIONS = {
            "pet.auth.c.enabled=true", "pet.feedback.http.enabled=true"};

    private static ApplicationContextRunner deletionRunner(String... properties) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(UserSettingsDeletionConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> new SnowflakeIdGenerator() {
                    @Override
                    public long nextId() {
                        return 1L;
                    }
                })
                .withBean(MerchantAdmissionQueryApi.class, () -> mock(MerchantAdmissionQueryApi.class))
                .withPropertyValues(properties);
        return runner;
    }

    private static ApplicationContextRunner deletionRunnerWithoutMembership() {
        return new ApplicationContextRunner()
                .withUserConfiguration(UserSettingsDeletionConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> new SnowflakeIdGenerator() {
                    @Override
                    public long nextId() {
                        return 1L;
                    }
                })
                .withPropertyValues(DELETION_PRECONDITIONS);
    }

    private static ApplicationContextRunner feedbackRunner(String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(FeedbackConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> new SnowflakeIdGenerator() {
                    @Override
                    public long nextId() {
                        return 1L;
                    }
                })
                .withPropertyValues(properties);
    }

    @Test
    void productionDefaultsAssembleNothing() {
        deletionRunner().run(context -> {
            assertThat(context).hasNotFailed();
            // By service type and by the configuration's own bean names (the test mocks are
            // registered under different names, so the default-off assertion targets the
            // slice's beans only).
            assertThat(context).doesNotHaveBean(AccountDeletionService.class);
            assertThat(context).doesNotHaveBean("orderDeletionFacts");
            assertThat(context).doesNotHaveBean("afterSaleDeletionFacts");
        });
        feedbackRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FeedbackTicketService.class);
            assertThat(context).doesNotHaveBean("userActiveStatus");
        });
    }

    @Test
    void deletionFaceNeedsAllThreeHardPreconditions() {
        // Slice switch on, but no C session domain: nothing assembles.
        deletionRunner("pet.usersettings.http.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AccountDeletionService.class);
        });
        // All three hard preconditions but the OWNER-membership surface missing: assembly
        // fails closed (never a silent fallback).
        deletionRunnerWithoutMembership().run(context -> assertThat(context).hasFailed());
        // Everything present: the slice assembles with the fact ports.
        deletionRunner(DELETION_PRECONDITIONS).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AccountDeletionService.class);
            assertThat(context).hasSingleBean(OrderDeletionFactsApi.class);
            assertThat(context).hasSingleBean(AfterSaleDeletionFactsApi.class);
        });
    }

    @Test
    void feedbackFaceNeedsSessionAndSwitch() {
        // Only the slice switch on (no C session domain): nothing assembles.
        feedbackRunner("pet.feedback.http.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FeedbackTicketService.class);
            assertThat(context).doesNotHaveBean("userActiveStatus");
        });
        // Both hard preconditions: the slice assembles and owns the status port bean
        // (the port is config-provided, so no external missing-port case exists here —
        // unlike the deletion face, whose membership port comes from another slice).
        feedbackRunner(FEEDBACK_PRECONDITIONS).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FeedbackTicketService.class);
            assertThat(context).hasSingleBean(UserActiveStatusApi.class);
        });
    }
}
