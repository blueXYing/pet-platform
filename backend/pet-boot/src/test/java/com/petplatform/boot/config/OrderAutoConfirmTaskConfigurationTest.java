package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.petplatform.order.api.query.OrderAutoConfirmTaskInspectionApi;
import com.petplatform.task.core.AsyncTaskWorker;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderAutoConfirmTaskConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(OrderAutoConfirmTaskConfiguration.class);
    }

    @Test void defaultIsOffAndCreatesNoWorkerOrInspector() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AsyncTaskWorker.class);
            assertThat(context).doesNotHaveBean(OrderAutoConfirmTaskInspectionApi.class);
        });
    }

    @Test void taskProductionRequiresPaymentFoundation() {
        runner().withPropertyValues("pet.order.auto-confirm.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("pet.order.auto-confirm.enabled=true", "pet.payment.foundation.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AsyncTaskWorker.class);
                });
    }

    @Test void executionAndRepairCannotBypassMissingContractA() {
        for (String flag : new String[] {"worker", "repair"}) {
            for (boolean enabled : new boolean[] {false, true}) {
                runner().withPropertyValues("pet.order.auto-confirm." + flag + ".enabled=true",
                                "pet.order.auto-confirm.enabled=" + enabled, "pet.payment.foundation.enabled=true")
                        .run(context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                                    "Auto-confirm execution and repair require contract A implementation");
                        });
            }
        }
    }

    @Test void inspectionIsAnIndependentOptInWithoutStartupDatabaseAccess() {
        DataSource source = mock(DataSource.class);
        runner().withBean(DataSource.class, () -> source)
                .withPropertyValues("pet.order.auto-confirm.inspection.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OrderAutoConfirmTaskInspectionApi.class);
                    assertThat(context).doesNotHaveBean(AsyncTaskWorker.class);
                    verifyNoInteractions(source);
                });
    }
}
