package com.petplatform.boot.adapter.web.merchant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.*;
import com.petplatform.refund.api.command.RefundApplicationCommandApi;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.Summary;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.TimelineEntry;
import com.petplatform.user.biz.application.UserAuthService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * CCR-M-REFUND-VIEW-001 (approved 2026-11-06 §3.1/§3.3) adapter boundary with mocked queries:
 * list items carry the nine contract-57 keys plus customerNickname / customerPhoneMasked /
 * serviceName (explicit nulls when absent) and never a timeline; the detail carries the twelve
 * keys plus the same three and the ascending {stage, occurredAt} timeline; only the server mask
 * ever reaches the wire (a raw number renders null); the buyer reason text (§3.2 not approved)
 * and the buyer id never appear; refusals keep the bare envelope without any customer fact. The
 * real OWNER/store projection is covered by MerchantRefundViewHttpAcceptanceTest.
 */
class MerchantRefundViewHttpTest {
    private static final List<String> CUSTOMER_KEYS = List.of("customerNickname", "customerPhoneMasked", "serviceName");
    private static final Set<String> BASE_KEYS = Set.of("applicationId", "applicationNo", "orderId", "status",
            "applicationVersion", "reasonCode", "refundAmount", "createdAt", "merchantDeadline");
    private static final Set<String> TERMINAL_KEYS = Set.of("decidedAt", "decisionId", "refundOrderId");
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-05T08:15:32.649Z");
    private final MerchantRefundApplicationQueryApi queries = mock(MerchantRefundApplicationQueryApi.class);
    private final RefundApplicationCommandApi commands = mock(RefundApplicationCommandApi.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MerchantRefundApplicationController(commands, queries)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private static UserAuthService.MiniSessionView session() {
        return new UserAuthService.MiniSessionView("801", "710300", Instant.parse("2099-01-01T00:00:00Z"), "139****9999", "ACTIVE");
    }
    private static Summary summary(String id, String status, OffsetDateTime decidedAt, String decisionId, String refundOrderId,
            String nickname, String phone, String service, List<TimelineEntry> timeline) {
        return new Summary(id, "88" + id, "777001", status, decidedAt == null ? "0" : "1", "QA_REASON", new BigDecimal("128.00"),
                T0, T0.plusHours(24), decidedAt, decisionId, refundOrderId, nickname, phone, service, timeline);
    }
    private static List<TimelineEntry> pendingTimeline() {
        return List.of(new TimelineEntry("APPLIED", T0), new TimelineEntry("MERCHANT_DEADLINE", T0.plusHours(24)));
    }
    private ResultActions read(String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer current").requestAttr(CBearerSessionFilter.VIEW, session()));
    }
    private static String wire(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
    @SuppressWarnings("unchecked") private Map<String, Object> data(String wire) { return (Map<String, Object>) json.readValue(wire, Map.class).get("data"); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }
    /** Never the buyer reason text, a buyer id key, a raw mobile number or a mobile/userId key. */
    private static void noPrivateFacts(String wire) {
        String lower = wire.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("reasontext"), wire);
        assertFalse(lower.contains("userid"), wire);
        assertFalse(lower.contains("mobile"), wire);
        assertFalse(lower.contains("cipher"), wire);
        for (String raw : List.of("13800000100", "13900009999")) assertFalse(wire.contains(raw), "raw number leaked: " + wire);
        assertFalse(wire.contains("private refund reason"), wire);
    }

    @Test void listItemsCarryTheThreeCustomerKeysWithExplicitNullsAndNoTimeline() throws Exception {
        when(queries.listStoreApplications(any())).thenReturn(new PageResult<>(List.of(
                summary("881001", "PENDING_MERCHANT", null, null, null, "E2E体验用户", "138****0100", "E2E到店精细洗护", pendingTimeline()),
                summary("881002", "PENDING_MERCHANT", null, null, null, null, null, null, pendingTimeline())), 2, 1, 20));
        String wire = wire(read("/api/v1/merchant/refund-applications?merchantId=710301&storeId=710302").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].customerNickname").value("E2E体验用户"))
                .andExpect(jsonPath("$.data.items[0].customerPhoneMasked").value("138****0100"))
                .andExpect(jsonPath("$.data.items[0].serviceName").value("E2E到店精细洗护"))
                .andExpect(jsonPath("$.data.items[1].customerNickname").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[1].customerPhoneMasked").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[1].serviceName").value(Matchers.nullValue())));
        var expected = new HashSet<>(BASE_KEYS);
        expected.addAll(CUSTOMER_KEYS);
        var items = list(data(wire).get("items"));
        assertEquals(2, items.size());
        for (var item : items) {
            assertEquals(expected, item.keySet());
            assertFalse(item.containsKey("timeline"), "the list never carries the detail-only timeline");
        }
        noPrivateFacts(wire);
        // Same query as before the CCR: coordinates, paging and the USER session subject.
        verify(queries).listStoreApplications(argThat(q -> q != null && "710301".equals(q.merchantId()) && "710302".equals(q.storeId())
                && q.page() == 1 && q.pageSize() == 20 && q.context().operatorType() == OperatorType.USER
                && "710300".equals(q.context().operatorId())));
        verifyNoInteractions(commands);
    }

    @Test void detailCarriesTheCustomerKeysAndTheAscendingTimeline() throws Exception {
        OffsetDateTime decided = T0.plusHours(1);
        OffsetDateTime created = decided.plusSeconds(5);
        when(queries.readStoreApplication(any())).thenReturn(summary("881001", "APPROVED", decided, "881003", "881004",
                "E2E体验用户", "138****0100", "E2E到店精细洗护", List.of(new TimelineEntry("APPLIED", T0),
                        new TimelineEntry("DECIDED", decided), new TimelineEntry("REFUND_ORDER_CREATED", created),
                        new TimelineEntry("MERCHANT_DEADLINE", T0.plusHours(24)))));
        String wire = wire(read("/api/v1/merchant/refund-applications/881001?merchantId=710301&storeId=710302").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.customerNickname").value("E2E体验用户"))
                .andExpect(jsonPath("$.data.customerPhoneMasked").value("138****0100"))
                .andExpect(jsonPath("$.data.serviceName").value("E2E到店精细洗护"))
                .andExpect(jsonPath("$.data.decidedAt").value("2026-11-05T09:15:32.649Z"))
                .andExpect(jsonPath("$.data.refundOrderId").value("881004")));
        var expected = new HashSet<>(BASE_KEYS);
        expected.addAll(TERMINAL_KEYS);
        expected.addAll(CUSTOMER_KEYS);
        expected.add("timeline");
        var detail = data(wire);
        assertEquals(expected, detail.keySet());
        var timeline = list(detail.get("timeline"));
        assertEquals(List.of("APPLIED", "DECIDED", "REFUND_ORDER_CREATED", "MERCHANT_DEADLINE"),
                timeline.stream().map(entry -> entry.get("stage")).toList());
        assertEquals(List.of("2026-11-05T08:15:32.649Z", "2026-11-05T09:15:32.649Z", "2026-11-05T09:15:37.649Z", "2026-11-06T08:15:32.649Z"),
                timeline.stream().map(entry -> entry.get("occurredAt")).toList());
        for (var entry : timeline) assertEquals(Set.of("stage", "occurredAt"), entry.keySet());
        // The timeline instants are the detail's own facts (no shifted or invented instant).
        assertEquals(detail.get("createdAt"), timeline.get(0).get("occurredAt"));
        assertEquals(detail.get("decidedAt"), timeline.get(1).get("occurredAt"));
        assertEquals(detail.get("merchantDeadline"), timeline.get(3).get("occurredAt"));
        noPrivateFacts(wire);
        verify(queries).readStoreApplication(argThat(q -> q != null && "881001".equals(q.applicationId()) && "710301".equals(q.merchantId())
                && "710302".equals(q.storeId()) && "710300".equals(q.context().operatorId())));
    }

    @Test void pendingDetailRendersExactlyAppliedAndMerchantDeadline() throws Exception {
        when(queries.readStoreApplication(any())).thenReturn(summary("881001", "PENDING_MERCHANT", null, null, null,
                null, null, null, pendingTimeline()));
        String wire = wire(read("/api/v1/merchant/refund-applications/881001?merchantId=710301&storeId=710302").andExpect(status().isOk()));
        var detail = data(wire);
        assertEquals(List.of(Map.of("stage", "APPLIED", "occurredAt", "2026-11-05T08:15:32.649Z"),
                Map.of("stage", "MERCHANT_DEADLINE", "occurredAt", "2026-11-06T08:15:32.649Z")), list(detail.get("timeline")));
        for (String key : CUSTOMER_KEYS) {
            assertTrue(detail.containsKey(key), key);
            assertNull(detail.get(key), key);
        }
        assertNull(detail.get("decidedAt"));
        noPrivateFacts(wire);
    }

    @Test void onlyTheServerMaskEverReachesTheWire() throws Exception {
        // Defense in depth: even a misbehaving query implementation cannot put a raw or
        // malformed number on the wire — the boundary renders it as null.
        for (String phone : List.of("13800000100", "+86****0100", "****", "138****010")) {
            reset(queries);
            when(queries.readStoreApplication(any())).thenReturn(summary("881001", "PENDING_MERCHANT", null, null, null,
                    "E2E体验用户", phone, "E2E到店精细洗护", pendingTimeline()));
            String wire = wire(read("/api/v1/merchant/refund-applications/881001?merchantId=710301&storeId=710302")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.customerPhoneMasked").value(Matchers.nullValue())));
            assertFalse(wire.contains(phone), phone);
            noPrivateFacts(wire);
        }
    }

    @Test void refusalsAndOutagesKeepTheBareEnvelopeWithoutAnyCustomerFact() throws Exception {
        when(queries.readStoreApplication(argThat(q -> q != null && "881009".equals(q.applicationId()))))
                .thenThrow(new ApiException(CommonApiCodes.FORBIDDEN, "foreign store"));
        when(queries.readStoreApplication(argThat(q -> q != null && "881010".equals(q.applicationId()))))
                .thenThrow(new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "labels down"));
        when(queries.listStoreApplications(argThat(q -> q != null && "710399".equals(q.storeId()))))
                .thenThrow(new ApiException(CommonApiCodes.FORBIDDEN, "foreign store"));
        for (var probe : List.of(Map.entry("/api/v1/merchant/refund-applications/881009?merchantId=710301&storeId=710302", 403),
                Map.entry("/api/v1/merchant/refund-applications/881010?merchantId=710301&storeId=710302", 503),
                Map.entry("/api/v1/merchant/refund-applications?merchantId=710301&storeId=710399", 403))) {
            String wire = wire(read(probe.getKey()).andExpect(status().is(probe.getValue())).andExpect(jsonPath("$.data").value(Matchers.nullValue())));
            for (String key : CUSTOMER_KEYS) assertFalse(wire.contains(key), probe.getKey() + " " + key);
            assertFalse(wire.contains("timeline"), probe.getKey());
            assertFalse(wire.contains("E2E体验用户"), probe.getKey());
            assertFalse(wire.contains("****"), probe.getKey());
            noPrivateFacts(wire);
        }
        // Missing session never reaches the query (401 before any projection).
        mvc.perform(get("/api/v1/merchant/refund-applications/881001?merchantId=710301&storeId=710302")).andExpect(status().isUnauthorized());
        verify(queries, never()).readStoreApplication(argThat(q -> q != null && "881001".equals(q.applicationId())));
        verifyNoInteractions(commands);
    }
}
