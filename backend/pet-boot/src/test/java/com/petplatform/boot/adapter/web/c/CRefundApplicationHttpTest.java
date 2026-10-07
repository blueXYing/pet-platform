package com.petplatform.boot.adapter.web.c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.refund.api.command.RefundApplicationCommandApi;
import com.petplatform.refund.api.command.RefundApplicationCommandApi.Receipt;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Boundary mocks only; the companion {@code RefundApplyHttpAcceptanceTest} drives the real
 * kernel, sessions and isolated MySQL. This suite pins the transport surface: strict JSON,
 * terminal X-Request-Id, no query parameters, the §3.9 receipt projection with 201/200
 * create/replay, ACTIVE-session enforcement and the Error12 §6 status mapping.
 */
class CRefundApplicationHttpTest {
    private static final String ORDER = "900101001990003";
    private static final String PATH = "/api/v1/c/orders/" + ORDER + "/refund-applications";
    private static final String REQUEST_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    private final RefundApplicationCommandApi applications = mock(RefundApplicationCommandApi.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CRefundApplicationController(applications)).build();

    private static UserAuthService.MiniSessionView session(String status) {
        return new UserAuthService.MiniSessionView("801", "701", Instant.parse("2030-01-01T00:00:00Z"), "138****0000", status);
    }

    @Test void preServiceAutoApprovalReceiptProjectsTheTwoWindowSurface() throws Exception {
        when(applications.applyWithOutcome(any())).thenReturn(new RefundApplicationCommandApi.CreationResult(
            new Receipt(ORDER, "960000000000000001", "AUTO_APPROVED", "1",
                "2030-01-01T10:00:00.015Z", "2030-01-01T10:00:00.015Z", "970000000000000002"), true));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID)
                .requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content("{\"reasonCode\":\"USER_REQUEST\",\"reasonText\":\"临时无法到店\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.message").value("ok"))
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.data.applicationId").value("960000000000000001"))
            .andExpect(jsonPath("$.data.applicationStatus").value("AUTO_APPROVED"))
            .andExpect(jsonPath("$.data.route").value("AUTO_FULL_BEFORE_SERVICE"))
            .andExpect(jsonPath("$.data.merchantDeadline").value("2030-01-01T10:00:00.015Z"))
            .andExpect(jsonPath("$.data.refundOrderId").value((Object) null))
            .andExpect(jsonPath("$.data.displayStatus").value("REFUNDING"))
            .andExpect(header().string("Cache-Control", "no-store, private"));
        verify(applications).applyWithOutcome(argThat((RefundApplicationCommandApi.Apply c) ->
            c.orderId().equals(ORDER) && c.reasonCode().equals("USER_REQUEST")
                && "临时无法到店".equals(c.reasonText()) && c.context().operatorId().equals("701")
                && c.context().operatorType() == OperatorType.USER
                && c.context().requestId().equals(REQUEST_ID) && "MINIAPP".equals(c.context().source())));
    }

    @Test void postServiceReceiptCarriesMerchantDeadlineAndPendingConfirmDisplay() throws Exception {
        when(applications.applyWithOutcome(any())).thenReturn(new RefundApplicationCommandApi.CreationResult(
            new Receipt(ORDER, "960000000000000003", "PENDING_MERCHANT", "0",
                "2030-01-02T10:00Z", null, null), true));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content("{\"reasonCode\":\"USER_REQUEST\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.applicationStatus").value("PENDING_MERCHANT"))
            .andExpect(jsonPath("$.data.route").value("MERCHANT_CONFIRM_AFTER_SERVICE"))
            .andExpect(jsonPath("$.data.merchantDeadline").value("2030-01-02T10:00:00.000Z"))
            .andExpect(jsonPath("$.data.displayStatus").value("REFUND_PENDING_CONFIRM"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test void protectedReplayAnswers200WithTheFirstReceiptAndReasonTextMayBeNull() throws Exception {
        when(applications.applyWithOutcome(any())).thenReturn(new RefundApplicationCommandApi.CreationResult(
            new Receipt(ORDER, "960000000000000003", "PENDING_MERCHANT", "0",
                "2030-01-02T10:00:00.000Z", null, null), false));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content("{\"reasonCode\":\"QA_REASON\",\"reasonText\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applicationStatus").value("PENDING_MERCHANT"));
    }

    @Test void strictBodyQueryAndSessionBoundariesNeverReachTheKernel() throws Exception {
        List<String> bodies = List.of(
            "{}",
            "{\"reasonCode\":null}",
            "{\"reasonCode\":\"USER_REQUEST\",\"reasonCode\":\"USER_REQUEST\"}",
            "{\"reasonCode\":\"USER_REQUEST\",\"extra\":1}",
            "{\"reasonCode\":123}",
            "{\"reasonText\":\"only text\"}",
            "{\"reasonCode\":\"USER_REQUEST\"} trailing",
            "not json");
        for (String body : bodies) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CommonApiCodes.INVALID_ARGUMENT))
                .andExpect(jsonPath("$.data").value((Object) null));
        }
        for (String query : List.of("?foo=1", "?reasonCode=USER_REQUEST")) {
            mvc.perform(post(PATH + query).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                    .content("{\"reasonCode\":\"USER_REQUEST\"}"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "not-a-uuid").requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content("{\"reasonCode\":\"USER_REQUEST\"}"))
            .andExpect(status().isBadRequest());
        // Order id shape is part of the request contract, not a kernel concern.
        mvc.perform(post("/api/v1/c/orders/0abc/refund-applications").contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content("{\"reasonCode\":\"USER_REQUEST\"}"))
            .andExpect(status().isBadRequest());
        // Missing session view (the filter would already have replied 401) and frozen accounts
        // never start a command.
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", REQUEST_ID)
                .content("{\"reasonCode\":\"USER_REQUEST\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", REQUEST_ID)
                .requestAttr(CBearerSessionFilter.VIEW, session("FROZEN"))
                .content("{\"reasonCode\":\"USER_REQUEST\"}"))
            .andExpect(status().isForbidden());
        verify(applications, times(0)).applyWithOutcome(any());
    }

    /** Error12 §6 mapping: admission rejections are 409, the unflagged pre-service route is 503. */
    @Test void kernelAdmissionCodesMapToTheContractStatuses() throws Exception {
        List<String[]> cases = List.of(
            new String[] {CommonApiCodes.INVALID_ARGUMENT, "400"},
            new String[] {CommonApiCodes.UNAUTHORIZED, "401"},
            new String[] {CommonApiCodes.FORBIDDEN, "403"},
            new String[] {CommonApiCodes.NOT_FOUND, "404"},
            new String[] {"REFUND_NOT_ELIGIBLE", "409"},
            new String[] {"REFUND_APPLICATION_ALREADY_PROCESSED", "409"},
            new String[] {"REFUND_ORDER_ALREADY_EXISTS", "409"},
            new String[] {"REFUND_ALREADY_EXISTS", "409"},
            new String[] {CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "409"},
            new String[] {CommonApiCodes.CONFLICT, "409"},
            new String[] {"REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED", "503"},
            new String[] {CommonApiCodes.DEPENDENCY_UNAVAILABLE, "503"});
        for (String[] probe : cases) {
            doThrow(new ApiException(probe[0], "kernel")).when(applications).applyWithOutcome(any());
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                    .content("{\"reasonCode\":\"USER_REQUEST\"}"))
                .andExpect(status().is(Integer.parseInt(probe[1])))
                .andExpect(jsonPath("$.code").value(probe[0]))
                .andExpect(jsonPath("$.data").value((Object) null))
                .andExpect(header().string("Cache-Control", "no-store, private"));
        }
        doThrow(new RuntimeException("unrecognized")).when(applications).applyWithOutcome(any());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content("{\"reasonCode\":\"USER_REQUEST\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(CommonApiCodes.INTERNAL_ERROR))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unrecognized"))));
    }

    /** Default off: without pet.refund.application.http.enabled the controller never assembles. */
    @Test void defaultOffRegistersNoControllerBean() {
        new ApplicationContextRunner().withUserConfiguration(CRefundApplicationController.class)
                .withBean(RefundApplicationCommandApi.class, () -> applications)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CRefundApplicationController.class);
                });
    }
}
