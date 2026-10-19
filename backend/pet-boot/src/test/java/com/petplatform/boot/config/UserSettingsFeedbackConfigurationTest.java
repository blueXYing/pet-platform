package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.customerservice.biz.application.FeedbackTicketService;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.user.api.query.UserActiveStatusApi;
import com.petplatform.user.biz.application.AccountDeletionService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Contract 66/67 assembly gates (offline, per the configuration-test discipline): both new
 * C faces stay unassembled at the production defaults; the settings/deletion face needs its
 * three-way hard precondition (C session + slice switch + OWNER-membership surface) and the
 * feedback face the two-way one (C session + slice switch); a missing fact/status port is an
 * assembly failure, never a silent fallback (66号 §2 / 67号 §5; CCR-USR-DELETION-001 /
 * CCR-CS-FEEDBACK-001).
 */
class UserSettingsFeedbackConfigurationTest {

    private ApplicationContextRunner deletionRunner() {
        return new ApplicationContextRunner()
                .withInitializer(c -> ((DefaultListableBeanFactory) c.getBeanFactory())
                        .setAllowCircularReferences(false))
                .withUserConfiguration(UserSettingsDeletionConfiguration.class);
    }

    private ApplicationContextRunner feedbackRunner() {
        return new ApplicationContextRunner()
                .withInitializer(c -> ((DefaultListableBeanFactory) c.getBeanFactory())
                        .setAllowCircularReferences(false))
                .withUserConfiguration(FeedbackConfiguration.class);
    }

    private static void seedCommonBeans(org.springframework.context.ApplicationContext context) {
        var factory = (DefaultListableBeanFactory) context.getAutowireCapableBeanFactory();
        factory.registerSingleton("dataSource", mock(DataSource.class));
        factory.registerSingleton("ids", new com.petplatform.common.SnowflakeIdGenerator() {
            @Override
            public long nextId() {
                return 1L;
            }
        });
        factory.registerSingleton("orderDeletionFacts", mock(OrderDeletionFactsApi.class));
        factory.registerSingleton("afterSaleDeletionFacts", mock(AfterSaleDeletionFactsApi.class));
        factory.registerSingleton("merchantAdmission", mock(MerchantAdmissionQueryApi.class));
        factory.registerSingleton("userActiveStatus", mock(UserActiveStatusApi.class));
    }

    @Test
    void productionDefaultsAssembleNothing() {
        deletionRunner().run(context -> {
            seedCommonBeans(context);
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AccountDeletionService.class);
            assertThat(context).doesNotHaveBean(OrderDeletionFactsApi.class);
        });
        feedbackRunner().run(context -> {
            seedCommonBeans(context);
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FeedbackTicketService.class);
            assertThat(context).doesNotHaveBean(UserActiveStatusApi.class);
        });
    }

    @Test
    void deletionFaceNeedsAllThreeHardPreconditions() {
        // Slice switch on, but no C session domain: nothing assembles.
        deletionRunner().withPropertyValues("pet.usersettings.http.enabled=true").run(context -> {
            seedCommonBeans(context);
            assertThat(context).doesNotHaveBean(AccountDeletionService.class);
        });
        // C session + slice switch, but no OWNER-membership surface: assembly fails closed.
        deletionRunner()
                .withPropertyValues("pet.auth.c.enabled=true", "pet.usersettings.http.enabled=true")
                .run(context -> {
                    var factory = (DefaultListableBeanFactory) context.getAutowireCapableBeanFactory();
                    factory.registerSingleton("dataSource", mock(DataSource.class));
                    factory.registerSingleton("ids", new com.petplatform.common.SnowflakeIdGenerator() {
                        @Override
                        public long nextId() {
                            return 1L;
                        }
                    });
                    factory.registerSingleton("orderDeletionFacts", mock(OrderDeletionFactsApi.class));
                    factory.registerSingleton("afterSaleDeletionFacts", mock(AfterSaleDeletionFactsApi.class));
                    assertThat(context).hasFailed();
                    assertThat(context).doesNotHaveBean(AccountDeletionService.class);
                });
        // All three hard preconditions + the fact ports: the slice assembles.
        deletionRunner()
                .withPropertyValues("pet.auth.c.enabled=true", "pet.usersettings.http.enabled=true",
                        "pet.merchant.application.enabled=true")
                .run(context -> {
                    seedCommonBeans(context);
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AccountDeletionService.class);
                    assertThat(context).hasSingleBean(OrderDeletionFactsApi.class);
                    assertThat(context).hasSingleBean(AfterSaleDeletionFactsApi.class);
                });
    }

    @Test
    void feedbackFaceNeedsSessionAndSwitchAndStatusPort() {
        feedbackRunner().withPropertyValues("pet.feedback.http.enabled=true").run(context -> {
            seedCommonBeans(context);
            assertThat(context).doesNotHaveBean(FeedbackTicketService.class);
        });
        feedbackRunner()
                .withPropertyValues("pet.auth.c.enabled=true", "pet.feedback.http.enabled=true")
                .run(context -> {
                    var factory = (DefaultListableBeanFactory) context.getAutowireCapableBeanFactory();
                    factory.registerSingleton("dataSource", mock(DataSource.class));
                    factory.registerSingleton("ids", new com.petplatform.common.SnowflakeIdGenerator() {
                        @Override
                        public long nextId() {
                            return 1L;
                        }
                    });
                    assertThat(context).hasFailed();
                    assertThat(context).doesNotHaveBean(FeedbackTicketService.class);
                });
        feedbackRunner()
                .withPropertyValues("pet.auth.c.enabled=true", "pet.feedback.http.enabled=true")
                .run(context -> {
                    seedCommonBeans(context);
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FeedbackTicketService.class);
                    assertThat(context).hasSingleBean(UserActiveStatusApi.class);
                });
    }
}
