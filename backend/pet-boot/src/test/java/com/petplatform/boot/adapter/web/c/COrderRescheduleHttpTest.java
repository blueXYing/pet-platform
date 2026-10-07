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
import com.petplatform.order.api.command.OrderRescheduleApi;
import com.petplatform.order.api.command.OrderRescheduleApi.Receipt;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Boundary mocks only; the companion {@code OrderRescheduleHttpAcceptanceTest} drives the real
 * kernel, sessions, credential fence and isolated MySQL. This suite pins the transport surface:
 * strict JSON with the OpenAPI oneOf branch shape, terminal X-Request-Id, no query parameters,
 * the contract-46 receipt projection on 200 (first submit and protected replay share it), the
 * ACTIVE-session enforcement and the Error12 §6 status mapping for the §3.8 code family.
 */
class COrderRescheduleHttpTest {
    private static final String ORDER = "900101001990003";
    private static final String PATH = "/api/v1/c/orders/" + ORDER + "/reschedule";
    private static final String REQUEST_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final String STORE_BODY = "{\"expectedOrderVersion\":\"1\","
        + "\"appointmentStart\":\"2030-01-02T14:30:00+08:00\","
        + "\"appointmentEnd\":\"2030-01-02T16:00:00+08:00\","
        + "\"selectedGeneralWindowId\":\"710593\"}";

    private final OrderRescheduleApi reschedules = mock(OrderRescheduleApi.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new COrderRescheduleController(reschedules)).build();

    private static UserAuthService.MiniSessionView session(String status) {
        return new UserAuthService.MiniSessionView("801", "701", Instant.parse("2030-01-01T00:00:00Z"), "138****0000", status);
    }

    @Test void storeBranchProjectsTheContract46Receipt() throws Exception {
        when(reschedules.reschedule(any())).thenReturn(new Receipt(ORDER, "900102001990001",
            "900103001990002", 1, "2", "PENDING_CONFIRM",
            "2030-01-02T06:30Z", "2030-01-02T08:00Z", null, null,
            "2030-01-01T09:05:00.015Z", "2030-01-01T09:35:00.015Z"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID)
                .requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content(STORE_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.message").value("ok"))
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.data.orderId").value(ORDER))
            .andExpect(jsonPath("$.data.reservationId").value("900102001990001"))
            .andExpect(jsonPath("$.data.rescheduleId").value("900103001990002"))
            .andExpect(jsonPath("$.data.confirmRound").value(1))
            .andExpect(jsonPath("$.data.orderVersion").value("2"))
            .andExpect(jsonPath("$.data.orderStageAtCommit").value("PENDING_CONFIRM"))
            .andExpect(jsonPath("$.data.appointmentStart").value("2030-01-02T06:30:00.000Z"))
            .andExpect(jsonPath("$.data.appointmentEnd").value("2030-01-02T08:00:00.000Z"))
            .andExpect(jsonPath("$.data.pickupStart").value((Object) null))
            .andExpect(jsonPath("$.data.returnStart").value((Object) null))
            .andExpect(jsonPath("$.data.rescheduledAt").value("2030-01-01T09:05:00.015Z"))
            .andExpect(jsonPath("$.data.confirmDeadline").value("2030-01-01T09:35:00.015Z"))
            .andExpect(header().string("Cache-Control", "no-store, private"));
        verify(reschedules).reschedule(argThat((OrderRescheduleApi.Command c) ->
            c.orderId().equals(ORDER) && c.expectedOrderVersion().equals("1")
                && c.appointmentStart().equals(java.time.OffsetDateTime.parse("2030-01-02T14:30:00+08:00"))
                && c.appointmentEnd().equals(java.time.OffsetDateTime.parse("2030-01-02T16:00:00+08:00"))
                && c.selectedGeneralWindowId().equals("710593") && c.pickupStart() == null
                && c.returnStart() == null && c.selectedPickupWindowId() == null && c.selectedReturnWindowId() == null
                && c.context().operatorId().equals("701") && c.context().operatorType() == OperatorType.USER
                && c.context().requestId().equals(REQUEST_ID) && "MINIAPP".equals(c.context().source())));
    }

    @Test void pickupBranchCarriesTheTwoWholeWindows() throws Exception {
        when(reschedules.reschedule(any())).thenReturn(new Receipt(ORDER, "900102001990001",
            "900103001990003", 1, "1", "PENDING_CONFIRM",
            "2030-01-02T05:20Z", "2030-01-02T08:00Z",
            "2030-01-02T02:30Z", "2030-01-02T06:20Z",
            "2030-01-01T09:05:00Z", "2030-01-01T09:35:00Z"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID)
                .requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content("{\"expectedOrderVersion\":\"0\",\"pickupStart\":\"2030-01-02T02:30:00Z\","
                    + "\"returnStart\":\"2030-01-02T06:20:00Z\","
                    + "\"selectedPickupWindowId\":\"710594\",\"selectedReturnWindowId\":\"710595\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pickupStart").value("2030-01-02T02:30:00.000Z"))
            .andExpect(jsonPath("$.data.returnStart").value("2030-01-02T06:20:00.000Z"));
        verify(reschedules).reschedule(argThat((OrderRescheduleApi.Command c) ->
            c.appointmentStart() == null && c.appointmentEnd() == null
                && c.selectedGeneralWindowId() == null
                && "710594".equals(c.selectedPickupWindowId()) && "710595".equals(c.selectedReturnWindowId())));
    }

    /** The replay answers the same 200 first-receipt body (46号: 回执保存首提交结果). */
    @Test void kernelReplayResultIsStillASingle200() throws Exception {
        when(reschedules.reschedule(any())).thenReturn(new Receipt(ORDER, "900102001990001",
            "900103001990002", 1, "2", "PENDING_CONFIRM",
            "2030-01-02T06:30Z", "2030-01-02T08:00Z", null, null, null, null));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content(STORE_BODY))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test void strictBodyBranchShapeQueryAndSessionBoundariesNeverReachTheKernel() throws Exception {
        List<String> bodies = List.of(
            "{}",
            "{\"expectedOrderVersion\":null}",
            "{\"expectedOrderVersion\":\"01\"}",
            "{\"expectedOrderVersion\":\"-1\"}",
            "{\"expectedOrderVersion\":\"1.0\"}",
            "{\"expectedOrderVersion\":\"1\"}",
            "{\"expectedOrderVersion\":\"1\",\"appointmentStart\":\"2030-01-02T14:30:00+08:00\"}",
            "{\"expectedOrderVersion\":\"1\",\"appointmentStart\":\"2030-01-02T14:30:00+08:00\","
                + "\"appointmentEnd\":\"2030-01-02T16:00:00+08:00\"}",
            "{\"expectedOrderVersion\":\"1\",\"pickupStart\":\"2030-01-02T02:30:00Z\","
                + "\"returnStart\":\"2030-01-02T06:20:00Z\",\"selectedPickupWindowId\":\"710594\"}",
            // Mixed branches are never allowed (OpenAPI oneOf not/anyOf).
            "{\"expectedOrderVersion\":\"1\",\"appointmentStart\":\"2030-01-02T14:30:00+08:00\","
                + "\"appointmentEnd\":\"2030-01-02T16:00:00+08:00\",\"selectedGeneralWindowId\":\"710593\","
                + "\"pickupStart\":\"2030-01-02T02:30:00Z\"}",
            "{\"expectedOrderVersion\":\"1\",\"appointmentStart\":\"not-a-time\","
                + "\"appointmentEnd\":\"2030-01-02T16:00:00+08:00\",\"selectedGeneralWindowId\":\"710593\"}",
            "{\"expectedOrderVersion\":\"1\",\"appointmentStart\":\"2030-01-02T14:30:00+08:00\","
                + "\"appointmentEnd\":\"2030-01-02T16:00:00+08:00\",\"selectedGeneralWindowId\":\"0abc\"}",
            "{\"expectedOrderVersion\":\"1\",\"appointmentStart\":\"2030-01-02T14:30:00+08:00\","
                + "\"appointmentEnd\":\"2030-01-02T16:00:00+08:00\",\"selectedGeneralWindowId\":\"710593\",\"extra\":1}",
            "{\"expectedOrderVersion\":\"1\",\"appointmentStart\":\"2030-01-02T14:30:00+08:00\","
                + "\"appointmentStart\":\"2030-01-02T14:30:00+08:00\",\"appointmentEnd\":\"2030-01-02T16:00:00+08:00\","
                + "\"selectedGeneralWindowId\":\"710593\"}",
            "{\"expectedOrderVersion\":\"1\",\"appointmentStart\":\"2030-01-02T14:30:00+08:00\","
                + "\"appointmentEnd\":\"2030-01-02T16:00:00+08:00\",\"selectedGeneralWindowId\":\"710593\"} trailing",
            "not json");
        for (String body : bodies) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CommonApiCodes.INVALID_ARGUMENT))
                .andExpect(jsonPath("$.data").value((Object) null));
        }
        for (String query : List.of("?foo=1", "?expectedOrderVersion=1")) {
            mvc.perform(post(PATH + query).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                    .content(STORE_BODY))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "not-a-uuid").requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content(STORE_BODY))
            .andExpect(status().isBadRequest());
        // Path id shape is part of the request contract, not a kernel concern.
        mvc.perform(post("/api/v1/c/orders/0abc/reschedule").contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content(STORE_BODY))
            .andExpect(status().isBadRequest());
        // Missing session view (the filter would already have replied 401) and frozen accounts
        // never start a command.
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", REQUEST_ID)
                .content(STORE_BODY))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", REQUEST_ID)
                .requestAttr(CBearerSessionFilter.VIEW, session("FROZEN"))
                .content(STORE_BODY))
            .andExpect(status().isForbidden());
        verify(reschedules, times(0)).reschedule(any());
    }

    /** Error12 §6 mapping: 46号/§3.8 admission rejections are 409, missing owners 503. */
    @Test void kernelAdmissionCodesMapToTheContractStatuses() throws Exception {
        List<String[]> cases = List.of(
            new String[] {CommonApiCodes.INVALID_ARGUMENT, "400"},
            new String[] {CommonApiCodes.UNAUTHORIZED, "401"},
            new String[] {CommonApiCodes.FORBIDDEN, "403"},
            new String[] {CommonApiCodes.CONFLICT, "409"},
            new String[] {CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "409"},
            new String[] {"ORDER_OPERATION_BUSY", "409"},
            new String[] {"ORDER_RESCHEDULE_LIMIT_REACHED", "409"},
            new String[] {"ORDER_RESCHEDULE_AFTER_START", "409"},
            new String[] {"ORDER_STATE_NOT_ALLOWED", "409"},
            new String[] {"ORDER_REFUND_ALREADY_CREATED", "409"},
            new String[] {"SCHEDULE_SWAP_FAILED", "409"},
            new String[] {"SCHEDULE_CAPACITY_EXCEEDED", "409"},
            new String[] {CommonApiCodes.DEPENDENCY_UNAVAILABLE, "503"});
        for (String[] probe : cases) {
            doThrow(new ApiException(probe[0], "kernel")).when(reschedules).reschedule(any());
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                    .content(STORE_BODY))
                .andExpect(status().is(Integer.parseInt(probe[1])))
                .andExpect(jsonPath("$.code").value(probe[0]))
                .andExpect(jsonPath("$.data").value((Object) null))
                .andExpect(header().string("Cache-Control", "no-store, private"));
        }
        doThrow(new RuntimeException("unrecognized")).when(reschedules).reschedule(any());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", REQUEST_ID).requestAttr(CBearerSessionFilter.VIEW, session("ACTIVE"))
                .content(STORE_BODY))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(CommonApiCodes.INTERNAL_ERROR))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unrecognized"))));
    }

    /** Default off: without pet.order.reschedule.http.enabled the controller never assembles. */
    @Test void defaultOffRegistersNoControllerBean() {
        new ApplicationContextRunner().withUserConfiguration(COrderRescheduleController.class)
                .withBean(OrderRescheduleApi.class, () -> reschedules)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(COrderRescheduleController.class);
                });
    }
}
