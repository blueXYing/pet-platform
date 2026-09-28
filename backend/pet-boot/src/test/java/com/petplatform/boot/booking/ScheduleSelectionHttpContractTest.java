package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.boot.adapter.web.c.CScheduleController;
import com.petplatform.boot.adapter.web.c.CServiceExceptionHandler;
import com.petplatform.boot.config.BookingExpiryConfiguration;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.schedule.api.dto.AvailabilityPageDTO;
import com.petplatform.schedule.api.dto.AvailabilityWindowDTO;
import com.petplatform.schedule.api.query.ScheduleSelectionQueryApi;
import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import com.petplatform.schedule.biz.application.SelectionWindowQueryService;
import com.petplatform.schedule.biz.infrastructure.persistence.SelectionReadStore;
import com.petplatform.schedule.biz.infrastructure.persistence.SelectionReadStore.WindowRow;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleSelectionMapper;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Servlet/controller contract with the real MINIAPP filter and selection validation.
 * Window and Owner facts are doubles; this is not a database or live-session end-to-end test.
 */
class ScheduleSelectionHttpContractTest {
    private static final String PATH = "/api/v1/c/services/3/availability"
            + "?storeId=2&startDate=2030-01-01&endDate=2030-01-01";
    private static final Set<String> LEGACY_KEYS = Set.of("start", "end",
            "effectiveCapacity", "occupiedCount", "remainingCapacity", "available");
    private static final Set<String> SELECTED_KEYS = Set.of("start", "end",
            "effectiveCapacity", "occupiedCount", "remainingCapacity", "available",
            "windowId", "kind");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void enabledProjectionAddsOnlyOriginalIdentityAndKindAndFiltersKind() throws Exception {
        MockMvc mvc = mvc(true);
        MockHttpServletResponse all = mvc.perform(get(PATH).header("Authorization", "Bearer ok"))
                .andReturn().getResponse();
        assertEquals(200, all.getStatus());
        assertEquals("no-store", all.getHeader("Cache-Control"));
        JsonNode items = body(all).path("data").path("items");
        assertEquals(2, items.size());
        assertEquals(SELECTED_KEYS, keys(items.get(0)));
        assertEquals("11", items.get(0).path("windowId").asText());
        assertEquals("PICKUP", items.get(0).path("kind").asText());
        assertEquals("2030-01-01T09:00:00+08:00", items.get(0).path("start").asText());
        assertEquals("12", items.get(1).path("windowId").asText());
        assertEquals("RETURN", items.get(1).path("kind").asText());

        MockHttpServletResponse filtered = mvc.perform(get(PATH + "&kind=RETURN")
                .header("Authorization", "Bearer ok")).andReturn().getResponse();
        assertEquals(200, filtered.getStatus());
        JsonNode filteredItems = body(filtered).path("data").path("items");
        assertEquals(1, filteredItems.size());
        assertEquals("RETURN", filteredItems.get(0).path("kind").asText());
        assertEquals("12", filteredItems.get(0).path("windowId").asText());
    }

    @Test
    void invalidOrRepeatedKindIs400AndAnonymousRequestIs401() throws Exception {
        MockMvc mvc = mvc(true);
        assertError(mvc.perform(get(PATH + "&kind=UNKNOWN")
                .header("Authorization", "Bearer ok")).andReturn().getResponse(),
                400, "COMMON_INVALID_ARGUMENT");
        assertError(mvc.perform(get(PATH + "&kind=GENERAL")
                .header("Authorization", "Bearer ok")).andReturn().getResponse(),
                400, "COMMON_INVALID_ARGUMENT");
        assertError(mvc.perform(get(PATH + "&kind=PICKUP&kind=RETURN")
                .header("Authorization", "Bearer ok")).andReturn().getResponse(),
                400, "COMMON_INVALID_ARGUMENT");
        assertError(mvc.perform(get(PATH)).andReturn().getResponse(),
                401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void disabledSelectionKeepsSixFieldsAndRejectsKind() throws Exception {
        MockMvc mvc = mvc(false);
        MockHttpServletResponse response = mvc.perform(get(PATH)
                .header("Authorization", "Bearer ok")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode items = body(response).path("data").path("items");
        assertEquals(1, items.size());
        assertEquals(LEGACY_KEYS, keys(items.get(0)));
        assertFalse(items.get(0).has("windowId"));
        assertFalse(items.get(0).has("kind"));
        assertError(mvc.perform(get(PATH + "&kind=PICKUP")
                .header("Authorization", "Bearer ok")).andReturn().getResponse(),
                400, "COMMON_INVALID_ARGUMENT");
    }

    @Test
    void expiryWorkerIsAbsentWithoutExplicitEnablement() {
        new ApplicationContextRunner().withUserConfiguration(BookingExpiryConfiguration.class)
                .run(context -> assertFalse(context.containsBean("bookingExpiryWorker")));
    }

    private MockMvc mvc(boolean selectionEnabled) {
        ScheduleQueryApiImpl legacy = mock(ScheduleQueryApiImpl.class);
        OffsetDateTime start = OffsetDateTime.parse("2030-01-01T01:00:00Z");
        when(legacy.queryAvailability(any())).thenReturn(new AvailabilityPageDTO("2", "3",
                LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 1), List.of(
                        new AvailabilityWindowDTO("2", "3", start, start.plusMinutes(35),
                                3, 2, 2, 0, 2, true))));
        ScheduleSelectionQueryApi selection = selectionService()::page;
        @SuppressWarnings("unchecked")
        ObjectProvider<ScheduleSelectionQueryApi> selectionProvider = mock(ObjectProvider.class);
        when(selectionProvider.getIfAvailable()).thenReturn(selection);
        CScheduleController controller = new CScheduleController(legacy, selectionProvider,
                selectionEnabled);

        UserAuthService sessions = mock(UserAuthService.class);
        when(sessions.resolveSession("ok")).thenReturn(new MiniSessionView("session", "1",
                Instant.parse("2031-01-01T00:00:00Z"), "***", "ACTIVE"));
        @SuppressWarnings("unchecked")
        ObjectProvider<UserAuthService> sessionProvider = mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(sessions);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CServiceExceptionHandler())
                .addFilters(new CBearerSessionFilter(sessionProvider)).build();
    }

    private SelectionWindowQueryService selectionService() {
        ScheduleSelectionMapper unusedMapper = mock(ScheduleSelectionMapper.class);
        SelectionReadStore store = mock(SelectionReadStore.class, invocation -> {
            if ("read".equals(invocation.getMethod().getName())) {
                @SuppressWarnings("unchecked")
                Function<ScheduleSelectionMapper, ?> work = invocation.getArgument(0);
                return work.apply(unusedMapper);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(store.windows(any(), anyLong(), anyLong(), any(), any())).thenReturn(List.of(
                new WindowRow(11, 4, 2, 3, "PICKUP",
                        LocalDateTime.of(2030, 1, 1, 1, 0),
                        LocalDateTime.of(2030, 1, 1, 1, 35), 3, "OPEN", 0),
                new WindowRow(12, 4, 2, 3, "RETURN",
                        LocalDateTime.of(2030, 1, 1, 4, 0),
                        LocalDateTime.of(2030, 1, 1, 4, 35), 3, "OPEN", 0)));
        when(store.claims(any(), anyLong(), anyLong(), any(), any())).thenReturn(List.of());
        ServiceQueryApi services = mock(ServiceQueryApi.class);
        when(services.checkBookable(any())).thenReturn(new ServiceBookabilityDTO(
                "3", "4", "2", true, List.of()));
        when(services.getServiceSnapshot(any())).thenReturn(new ServiceSnapshotDTO(
                "3", "4", "2", "Pickup", null, null, null, 35,
                FulfillmentType.PICKUP_DELIVERY, null, null, null, null));
        return new SelectionWindowQueryService(store, services, (storeId, serviceId, from, to) -> 2,
                Clock.fixed(Instant.parse("2029-12-31T00:00:00Z"), ZoneOffset.UTC));
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    private static Set<String> keys(JsonNode object) {
        List<String> result = new ArrayList<>();
        object.fieldNames().forEachRemaining(result::add);
        return Set.copyOf(result);
    }

    private void assertError(MockHttpServletResponse response, int status, String code)
            throws Exception {
        assertEquals(status, response.getStatus());
        JsonNode error = body(response);
        assertEquals(code, error.path("code").asText());
        assertTrue(error.path("data").isNull());
    }
}
