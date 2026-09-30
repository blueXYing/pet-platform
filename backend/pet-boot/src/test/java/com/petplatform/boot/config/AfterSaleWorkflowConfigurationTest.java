package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.petplatform.admin.api.query.*;
import com.petplatform.aftersale.biz.application.*;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.biz.apiimpl.OrderRefundApplicationApiImpl;
import com.petplatform.payment.api.query.*;
import com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.user.biz.application.UserAuthService;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Configuration tests use offline mocks only; actual authorization belongs to the DB acceptance suite. */
class AfterSaleWorkflowConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(c->((DefaultListableBeanFactory)c.getBeanFactory()).setAllowCircularReferences(false))
                .withUserConfiguration(AfterSaleWorkflowConfiguration.class);
    }

    @Test void defaultsDoNotInstantiateWorkflowFundingOrWorkers() {
        runner().run(c->{
            assertThat(c).hasNotFailed();
            assertThat(c).doesNotHaveBean(AfterSaleService.class);
            assertThat(c).doesNotHaveBean(AsyncTaskWorker.class);
            assertThat(c).doesNotHaveBean(AfterSaleWorkflowConfiguration.Reconciler.class);
            assertThat(c).doesNotHaveBean(RefundFundingEligibilityFactsApi.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"pet.aftersale.http.enabled","pet.aftersale.worker.enabled","pet.aftersale.refund.enabled"})
    void childSwitchCannotOpenAnUnavailableWorkflow(String flag) {
        runner().withPropertyValues(flag+"=true").run(c->{
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasStackTraceContaining("dependent switches require workflow");
        });
    }

    @Test void httpCannotBeEnabledEvenWithCompleteInternalDependencies() {
        dependencies("").withPropertyValues("pet.aftersale.http.enabled=true").run(c->{
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasStackTraceContaining("Aftersale HTTP is unavailable");
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"pet.refund.application.enabled","pet.verification.completion.enabled","pet.private-assets.enabled","pet.auth.c.enabled"})
    void requiredSubsystemCannotBeDisabledBehindAReadyAdapter(String flag) {
        dependencies("").withPropertyValues(flag+"=false").run(c->{
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasStackTraceContaining("requires real refund, verification, private assets and sessions");
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"user","admin-session","admin-authorization","merchant","payment","history","object-store","grant-key","reason-protection","watermark","moderation"})
    void missingTrustedSourceHasNoPermissiveReplacement(String missing) {
        dependencies(missing).run(c->assertThat(c).hasFailed());
    }

    @Test void openingRefundWithoutAuthoritativeFundingProviderFailsStartup() {
        dependencies("").withPropertyValues("pet.aftersale.refund.enabled=true").run(c->{
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasStackTraceContaining("RefundFundingEligibilityFactsApi");
        });
    }

    @Test void offlineA1StartsWithoutFundingAndNeverCallsExternalOrFinancialDependencies() {
        dependencies("").run(c->{
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(AfterSaleService.class);
            assertThat(c).doesNotHaveBean(RefundFundingEligibilityFactsApi.class);
            assertThat(c).doesNotHaveBean(AsyncTaskWorker.class);
            assertThat(c).doesNotHaveBean(AfterSaleWorkflowConfiguration.Reconciler.class);
            verifyNoInteractions(c.getBean(PrivateObjectStore.class),c.getBean(PaymentSuccessFactsApi.class),
                    c.getBean(IntegrationEventPublisher.class),c.getBean(AdminSessionQueryApi.class),
                    c.getBean(AdminAuthorizationQueryApi.class));
        });
    }

    @Test void explicitTestProviderEnablesOnlyInternalCompositionWithoutImplicitExecution() {
        dependencies("").withPropertyValues("pet.aftersale.refund.enabled=true")
                .withBean(RefundFundingEligibilityFactsApi.class,()->mock(RefundFundingEligibilityFactsApi.class))
                .run(c->{
                    assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(AfterSaleService.class);
                    assertThat(c).doesNotHaveBean(AsyncTaskWorker.class);
                    verifyNoInteractions(c.getBean(RefundFundingEligibilityFactsApi.class));
                });
    }

    private ApplicationContextRunner dependencies(String missing) {
        var result=runner().withPropertyValues("pet.aftersale.enabled=true","pet.refund.application.enabled=true",
                "pet.verification.completion.enabled=true","pet.private-assets.enabled=true","pet.auth.c.enabled=true",
                "pet.aftersale.type-codes=QUALITY","pet.aftersale.demand-codes=REFUND",
                "pet.aftersale.protection-key="+Base64.getEncoder().encodeToString(new byte[32]))
                .withBean(DataSource.class,()->mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class,()->()->1L)
                .withBean(ScheduleCapacityGuardApi.class,()->mock(ScheduleCapacityGuardApi.class))
                .withBean(OrderRefundApplicationApiImpl.class,()->mock(OrderRefundApplicationApiImpl.class))
                .withBean(IntegrationEventPublisher.class,()->mock(IntegrationEventPublisher.class));
        if(!missing.equals("user"))result=result.withBean(UserAuthService.class,()->mock(UserAuthService.class));
        if(!missing.equals("admin-session"))result=result.withBean(AdminSessionQueryApi.class,()->mock(AdminSessionQueryApi.class));
        if(!missing.equals("admin-authorization"))result=result.withBean(AdminAuthorizationQueryApi.class,()->mock(AdminAuthorizationQueryApi.class));
        if(!missing.equals("merchant"))result=result.withBean(MerchantOrderAuthorityApi.class,()->mock(MerchantOrderAuthorityApi.class));
        if(!missing.equals("payment"))result=result.withBean(PaymentSuccessFactsApi.class,()->mock(PaymentSuccessFactsApi.class));
        if(!missing.equals("history"))result=result.withBean(RefundApplicationHistoryFactsApi.class,()->mock(RefundApplicationHistoryFactsApi.class));
        if(!missing.equals("object-store"))result=result.withBean(PrivateObjectStore.class,()->mock(PrivateObjectStore.class));
        if(!missing.equals("grant-key"))result=result.withBean(PrivateAssetGrantKeyProvider.class,()->mock(PrivateAssetGrantKeyProvider.class));
        if(!missing.equals("reason-protection"))result=result.withBean(PrivateAssetReasonProtector.class,()->mock(PrivateAssetReasonProtector.class));
        if(!missing.equals("watermark"))result=result.withBean(PrivateAssetWatermarkRenderer.class,()->mock(PrivateAssetWatermarkRenderer.class));
        if(!missing.equals("moderation"))result=result.withBean(AfterSalePorts.Moderation.class,()->mock(AfterSalePorts.Moderation.class));
        return result;
    }
}
