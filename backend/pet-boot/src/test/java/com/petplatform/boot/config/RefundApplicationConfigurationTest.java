package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.command.OrderRefundApplicationApi;
import com.petplatform.order.api.query.OrderRefundApplicationFactsApi;
import com.petplatform.order.biz.apiimpl.OrderApplicationRefundProjectionConsumer;
import com.petplatform.order.biz.application.MerchantOrderPorts;
import com.petplatform.order.biz.application.MerchantOrderService;
import com.petplatform.payment.api.command.PaymentRefundApi;
import com.petplatform.payment.api.query.PaymentRefundResultFactsApi;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.refund.api.command.RefundApplicationTimeoutApi;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.refund.biz.application.*;
import com.petplatform.schedule.api.command.*;
import com.petplatform.schedule.api.protection.*;
import com.petplatform.task.core.*;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.user.api.query.BookingUserFactsApi;
import java.time.*;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RefundApplicationConfigurationTest {
    private static final String TIMEOUT = "REFUND_MERCHANT_TIMEOUT";
    private static final String CREATE = "REFUND_APPLICATION_CREATE";
    private static final OffsetDateTime DEADLINE = OffsetDateTime.parse("2026-09-30T12:00:00Z");
    private static final String TIMEOUT_PAYLOAD = "{\"applicationId\":\"123\",\"storeId\":\"456\",\"merchantDeadline\":\"2026-09-30T12:00:00Z\"}";
    private static final String CREATE_PAYLOAD = "{\"applicationId\":\"123\",\"storeId\":\"456\",\"decisionId\":\"789\"}";

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(RefundApplicationConfiguration.class);
    }

    @Test void defaultsCreateNoApplicationProjectionOrBackgroundExecution() {
        runner().run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).doesNotHaveBean(RefundApplicationService.class);
            assertThat(c).doesNotHaveBean(OrderApplicationRefundProjectionConsumer.class);
            assertThat(c).doesNotHaveBean(AsyncTaskWorker.class);
            assertThat(c).doesNotHaveBean(RefundApplicationConfiguration.ApplicationReconciler.class);
        });
    }

    @Test void orphanWorkerAndAnyHttpFlagFailClosed() {
        for (String flag : List.of("pet.refund.application.worker.enabled=true", "pet.refund.application.http.enabled=true"))
            runner().withPropertyValues(flag).run(c -> {
                assertThat(c).hasFailed();
                assertThat(c.getStartupFailure()).hasStackTraceContaining("HTTP is not available");
            });
        dependencies("").withPropertyValues("pet.refund.application.http.enabled=true")
                .run(c -> assertThat(c).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pet.auth.c.enabled", "pet.order.merchant.enabled", "pet.payment.foundation.enabled"})
    void disabledRequiredSubsystemFailsEvenWhenAdaptersExist(String flag) {
        dependencies("").withPropertyValues(flag + "=false").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasStackTraceContaining("real session, merchant and payment dependencies");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "merchant", "payment", "moderation", "protection", "reasons"})
    void absentTrustedDependencyCannotBecomePermissiveDefault(String missing) {
        dependencies(missing).run(c -> {
            assertThat(c).hasFailed();
            String failure = switch (missing) {
                case "auth" -> "UserAuthService";
                case "merchant" -> "MerchantOrderAuthorityApi";
                case "payment" -> "PaymentSuccessFactsApi";
                case "moderation" -> "Moderation";
                case "protection" -> "Refund protection configuration unavailable";
                default -> "Approved refund reason configuration required";
            };
            assertThat(c.getStartupFailure()).hasStackTraceContaining(failure);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A,A", "A,", ",A", "A,   ", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABC"})
    void invalidReasonDictionaryFailsAtStartup(String codes) {
        dependencies("").withPropertyValues("pet.refund.application.reason-codes=" + codes)
                .run(c -> assertThat(c).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not_base64!", "YQ=="})
    void malformedOrShortProtectionKeyFailsAtStartup(String key) {
        dependencies("").withPropertyValues("pet.refund.application.protection-key=" + key)
                .run(c -> assertThat(c).hasFailed());
    }

    @Test void completeOfflineCompositionStartsNoWorkerAndCallsNoPaymentOrOutbox() {
        dependencies("").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(RefundApplicationService.class);
            assertThat(c).hasSingleBean(RefundApplicationApprovalFactsApi.class);
            assertThat(c).hasSingleBean(OrderRefundApplicationApi.class);
            assertThat(c).hasSingleBean(OrderRefundApplicationFactsApi.class);
            assertThat(c).hasSingleBean(OrderApplicationRefundProjectionConsumer.class);
            assertThat(c).doesNotHaveBean(AsyncTaskWorker.class);
            assertThat(c).doesNotHaveBean(RefundApplicationConfiguration.ApplicationReconciler.class);
            verifyNoInteractions(c.getBean(PaymentRefundChannel.class), c.getBean(PaymentSuccessFactsApi.class),
                    c.getBean(LateRefundService.class), c.getBean(IntegrationEventPublisher.class));
            var reasons = c.getBean(RefundApplicationPorts.ReasonPolicy.class);
            reasons.requireCode("SERVICE_ISSUE");
            assertThatThrownBy(() -> reasons.requireCode("UNAPPROVED_REASON"))
                    .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code())
                    .isEqualTo(CommonApiCodes.INVALID_ARGUMENT);
        });
    }

    @Test void ownerAdapterRetainsActorAndDelegatesToMerchantAuthority() {
        dependencies("").run(c -> {
            assertThat(c).hasNotFailed();
            var context = new CommandContext("owner-decision", "trace-owner", OperatorType.USER, "123", "MINIAPP");
            c.getBean(RefundApplicationPorts.OwnerAuthority.class).requireOwner(context, "789", "456");
            verify(c.getBean(MerchantOrderAuthorityApi.class)).requireOwner("789", "456",
                    new QueryContext("trace-owner", OperatorType.USER, "123"));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void standardMerchantAndPaymentCompositionHasNoCircularDependencyOrImplicitWorkers(boolean lateEnabled) {
        new ApplicationContextRunner()
                .withInitializer(c -> ((DefaultListableBeanFactory) c.getBeanFactory()).setAllowCircularReferences(false))
                .withUserConfiguration(RefundApplicationConfiguration.class, MerchantOrderConfiguration.class,
                        LateRefundConfiguration.class, OrderAutoConfirmTaskConfiguration.class, PaymentFoundationConfiguration.class)
                .withPropertyValues("pet.refund.application.enabled=true", "pet.auth.c.enabled=true",
                        "pet.order.merchant.enabled=true", "pet.payment.foundation.enabled=true",
                        "pet.refund.late.enabled=" + lateEnabled, "pet.payment.dispatch.request-ip=127.0.0.1",
                        "pet.payment.lakala.channel-time-zone=Asia/Shanghai",
                        "pet.refund.application.reason-codes=SERVICE_ISSUE",
                        "pet.refund.application.protection-key=" + Base64.getEncoder().encodeToString(new byte[32]),
                        "pet.order.merchant.protection-key=" + Base64.getEncoder().encodeToString(new byte[32]))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(ScheduleCapacityGuardApi.class, () -> mock(ScheduleCapacityGuardApi.class))
                .withBean(ScheduleProtectionFactsApi.class, () -> mock(ScheduleProtectionFactsApi.class))
                .withBean(ReservationExpiryApi.class, () -> mock(ReservationExpiryApi.class))
                .withBean(BookingUserFactsApi.class, () -> mock(BookingUserFactsApi.class))
                .withBean(UserAuthService.class, () -> mock(UserAuthService.class))
                .withBean(MerchantOrderPorts.Moderation.class, () -> mock(MerchantOrderPorts.Moderation.class))
                .withBean(RefundApplicationPorts.Moderation.class, () -> mock(RefundApplicationPorts.Moderation.class))
                .withBean(PaymentRefundChannel.class, () -> mock(PaymentRefundChannel.class))
                .withBean(IntegrationEventPublisher.class, () -> mock(IntegrationEventPublisher.class))
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c).hasSingleBean(RefundApplicationService.class);
                    assertThat(c).hasSingleBean(RefundApplicationApprovalFactsApi.class);
                    assertThat(c).hasSingleBean(OrderRefundApplicationApi.class);
                    assertThat(c).hasSingleBean(OrderRefundApplicationFactsApi.class);
                    assertThat(c).hasSingleBean(RefundOrderFactsApi.class);
                    assertThat(c).hasSingleBean(MerchantOrderService.class);
                    assertThat(c).hasSingleBean(LateRefundService.class);
                    assertThat(c).hasSingleBean(RefundExecutionService.class);
                    assertThat(c).hasSingleBean(PaymentRefundApi.class);
                    assertThat(c).hasSingleBean(PaymentRefundResultFactsApi.class);
                    assertThat(c).hasSingleBean(ReservationRefundReleaseApi.class);
                    assertThat(c).doesNotHaveBean(AsyncTaskWorker.class);
                    assertThat(c).doesNotHaveBean(RefundApplicationConfiguration.ApplicationReconciler.class);
                    assertThat(c).doesNotHaveBean(LateRefundConfiguration.DeadTaskReconciler.class);
                    assertThat(c.getBean(LateRefundService.class).eventTypes()).hasSize(lateEnabled ? 1 : 0);
                    verifyNoInteractions(c.getBean(PaymentRefundChannel.class), c.getBean(IntegrationEventPublisher.class),
                            c.getBean(UserAuthService.class), c.getBean(BookingUserFactsApi.class));
                });
    }

    @Test void sessionAdapterRequiresRequestAndResolvesCurrentBearerOnEveryReplay() {
        var auth = mock(UserAuthService.class);
        var authority = RefundApplicationConfiguration.sessionAuthority(auth);
        assertThatThrownBy(() -> authority.requireCurrent("123")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(CommonApiCodes.UNAUTHORIZED);
        verifyNoInteractions(auth);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer opaque-current-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(auth.resolveSession("opaque-current-token")).thenReturn(session("123", "ACTIVE"));
        authority.requireCurrent("123");
        authority.requireCurrent("123");
        verify(auth, times(2)).resolveSession("opaque-current-token");
        when(auth.resolveSession("opaque-current-token")).thenThrow(new ApiException(CommonApiCodes.UNAUTHORIZED, "Session revoked"));
        assertThatThrownBy(() -> authority.requireCurrent("123")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(CommonApiCodes.UNAUTHORIZED);
    }

    @Test void sessionAdapterRejectsIdentityMismatchInactiveAccountAndMissingBearer() {
        var auth = mock(UserAuthService.class);
        var authority = RefundApplicationConfiguration.sessionAuthority(auth);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer session-token");
        request.setAttribute(CBearerSessionFilter.VIEW, session("123", "ACTIVE"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        for (var session : List.of(session("999", "ACTIVE"), session("123", "DISABLED"), session("123", "DELETED"))) {
            when(auth.resolveSession("session-token")).thenReturn(session);
            assertThatThrownBy(() -> authority.requireCurrent("123")).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(CommonApiCodes.FORBIDDEN);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(auth.resolveSession(null)).thenThrow(new ApiException(CommonApiCodes.UNAUTHORIZED, "Bearer required"));
        assertThatThrownBy(() -> authority.requireCurrent("123")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(CommonApiCodes.UNAUTHORIZED);
        verify(auth).resolveSession(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {TIMEOUT, CREATE})
    void taskPayloadIsBoundToItsCanonicalIdentityAndGeneration(String type) {
        var applications = mock(RefundApplicationTimeoutApi.class);
        var registration = RefundApplicationConfiguration.registration(type, applications, mock(DataSource.class));
        var valid = lease(type, type + ":123", 123, version(type), payload(type), 0, 8, "REFUND_APPLICATION");
        assertThat(registration.decode().apply(valid)).isEqualTo(new RefundApplicationConfiguration.ApplicationPayload(
                "123", type.equals(CREATE) ? "789" : null, "456", type.equals(TIMEOUT) ? DEADLINE : null));
        assertThat(registration.requestId().apply(valid)).isEqualTo("TASK:" + type + ":123");
        for (var forged : List.of(
                lease("OTHER", valid.taskKey(), 123, version(type), payload(type), 0, 8, "REFUND_APPLICATION"),
                lease(type, type + ":124", 123, version(type), payload(type), 0, 8, "REFUND_APPLICATION"),
                lease(type, valid.taskKey(), 124, version(type), payload(type), 0, 8, "REFUND_APPLICATION"),
                lease(type, valid.taskKey(), 123, 9L, payload(type), 0, 8, "REFUND_APPLICATION"),
                lease(type, valid.taskKey(), 123, null, payload(type), 0, 8, "REFUND_APPLICATION"),
                lease(type, valid.taskKey(), 123, version(type), payload(type), -1, 8, "REFUND_APPLICATION"),
                lease(type, valid.taskKey(), 123, version(type), payload(type), 0, 9, "REFUND_APPLICATION"),
                lease(type, valid.taskKey(), 123, version(type), payload(type), 0, 8, "REFUND_CHANNEL")))
            assertThatThrownBy(() -> registration.decode().apply(forged)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(applications);
    }

    @ParameterizedTest
    @ValueSource(strings = {TIMEOUT, CREATE})
    void taskDecoderRejectsAmbiguousJsonAndNonCanonicalPublicIds(String type) {
        var registration = RefundApplicationConfiguration.registration(type, mock(RefundApplicationTimeoutApi.class), mock(DataSource.class));
        String json = payload(type);
        for (String invalid : List.of("null", "[]", json + "{}", json.replaceFirst("\\{", "{\"applicationId\":\"123\","),
                json.replace("\"123\"", "123"), json.replace("\"123\"", "\"0123\""),
                json.replace("\"456\"", "null"), json.replace("\"456\"", "\"0\""),
                json.replace("\"456\"", "\"9223372036854775808\""), json.replace("}", ",\"extra\":\"x\"}")))
            assertThatThrownBy(() -> registration.decode().apply(lease(type, type + ":123", 123,
                    version(type), invalid, 0, 8, "REFUND_APPLICATION"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void timeoutDecoderRejectsWrongTimezonePrecisionAndCreateOnlyFields() {
        var registration = RefundApplicationConfiguration.registration(TIMEOUT, mock(RefundApplicationTimeoutApi.class), mock(DataSource.class));
        for (String json : List.of(CREATE_PAYLOAD, TIMEOUT_PAYLOAD.replace("12:00:00Z", "20:00:00+08:00"),
                TIMEOUT_PAYLOAD.replace("12:00:00Z", "12:00:00.000001Z"), TIMEOUT_PAYLOAD.replace("12:00:00Z", "invalid")))
            assertThatThrownBy(() -> registration.decode().apply(lease(TIMEOUT, TIMEOUT + ":123", 123, 0L,
                    json, 0, 8, "REFUND_APPLICATION"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundApplicationConfiguration.registration("OTHER", mock(RefundApplicationTimeoutApi.class), mock(DataSource.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void earlyTimeoutReturnsDurableRetryUsingDatabaseClock() {
        var applications = mock(RefundApplicationTimeoutApi.class);
        try (var clocks = mockConstruction(TaskDatabaseClock.class, (mock, context) -> when(mock.now()).thenReturn(DEADLINE.minusSeconds(30).toLocalDateTime()))) {
            var registration = RefundApplicationConfiguration.registration(TIMEOUT, applications, mock(DataSource.class));
            var lease = lease(TIMEOUT, TIMEOUT + ":123", 123, 0L, TIMEOUT_PAYLOAD, 0, 8, "REFUND_APPLICATION");
            when(applications.handle(any())).thenReturn(new RefundApplicationTimeoutApi.TaskResult(false, DEADLINE));
            var result = registration.handler().execute(taskContext(), registration.decode().apply(lease));
            assertThat(result).isEqualTo(new TaskExecutionResult.Retry("REFUND_MERCHANT_NOT_DUE", Duration.ofMillis(30_250)));
            var captured = ArgumentCaptor.forClass(RefundApplicationTimeoutApi.Timeout.class);
            verify(applications).handle(captured.capture());
            assertThat(captured.getValue().expectedMerchantDeadline()).isEqualTo(DEADLINE);
            assertThat(captured.getValue().context().operatorType()).isEqualTo(OperatorType.SYSTEM);
            assertThat(captured.getValue().context().operatorId()).isNull();
            assertThat(clocks.constructed()).hasSize(1);
            verify(applications, never()).createApproved(any());
        }
    }

    @Test void createTaskUsesItsPersistedDecisionAndNeverStartsChannelWork() {
        var applications = mock(RefundApplicationTimeoutApi.class);
        var registration = RefundApplicationConfiguration.registration(CREATE, applications, mock(DataSource.class));
        var lease = lease(CREATE, CREATE + ":123", 123, 1L, CREATE_PAYLOAD, 0, 8, "REFUND_APPLICATION");
        assertThat(registration.handler().execute(taskContext(), registration.decode().apply(lease)))
                .isEqualTo(new TaskExecutionResult.Success("REFUND_CREATED"));
        verify(applications).createApproved(new RefundApplicationTimeoutApi.Create(
                new CommandContext("TASK:" + CREATE + ":123", "task-trace", OperatorType.SYSTEM, null, "ASYNC_TASK"), "123", "789", "456"));
        verifyNoMoreInteractions(applications);
    }

    private ApplicationContextRunner dependencies(String missing) {
        var result = runner().withPropertyValues("pet.refund.application.enabled=true", "pet.auth.c.enabled=true",
                        "pet.order.merchant.enabled=true", "pet.payment.foundation.enabled=true")
                .withBean(DataSource.class, () -> mock(DataSource.class)).withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(ScheduleCapacityGuardApi.class, () -> mock(ScheduleCapacityGuardApi.class))
                .withBean(ScheduleProtectionFactsApi.class, () -> mock(ScheduleProtectionFactsApi.class))
                .withBean(ReservationConfirmApi.class, () -> mock(ReservationConfirmApi.class))
                .withBean(ReservationRefundReleaseApi.class, () -> mock(ReservationRefundReleaseApi.class))
                .withBean(RefundOrderFactsApi.class, () -> mock(RefundOrderFactsApi.class))
                .withBean(LateRefundService.class, () -> mock(LateRefundService.class))
                .withBean(PaymentRefundChannel.class, () -> mock(PaymentRefundChannel.class))
                .withBean(IntegrationEventPublisher.class, () -> mock(IntegrationEventPublisher.class));
        if (!missing.equals("auth")) result = result.withBean(UserAuthService.class, () -> mock(UserAuthService.class));
        if (!missing.equals("merchant")) result = result.withBean(MerchantOrderAuthorityApi.class, () -> mock(MerchantOrderAuthorityApi.class));
        if (!missing.equals("payment")) result = result.withBean(PaymentSuccessFactsApi.class, () -> mock(PaymentSuccessFactsApi.class));
        if (!missing.equals("moderation")) result = result.withBean(RefundApplicationPorts.Moderation.class, () -> mock(RefundApplicationPorts.Moderation.class));
        if (!missing.equals("protection")) result = result.withPropertyValues("pet.refund.application.protection-key=" + Base64.getEncoder().encodeToString(new byte[32]));
        if (!missing.equals("reasons")) result = result.withPropertyValues("pet.refund.application.reason-codes=SERVICE_ISSUE,OTHER_APPROVED");
        return result;
    }

    private static UserAuthService.MiniSessionView session(String user, String state) {
        return new UserAuthService.MiniSessionView("current-session", user, Instant.parse("2026-10-01T00:00:00Z"), "138****0000", state);
    }
    private static String payload(String type) { return type.equals(TIMEOUT) ? TIMEOUT_PAYLOAD : CREATE_PAYLOAD; }
    private static long version(String type) { return type.equals(TIMEOUT) ? 0L : 1L; }
    private static TaskLease lease(String type, String key, long bizId, Long version, String payload, int retries, int maxRetries, String policy) {
        return new TaskLease(1L, key, type, bizId, version, payload, "offline", 1L, 2L, 1, retries, maxRetries, policy);
    }
    private static TaskExecutionContext taskContext() {
        return new TaskExecutionContext("1", "task-request", "task-trace", DEADLINE.minusDays(1));
    }
}
