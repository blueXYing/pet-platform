package com.petplatform.boot.web;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.TraceContextFilter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class TraceContextFilterTest {

    @RestController
    static class ProbeController {
        final AtomicReference<String> traceId = new AtomicReference<>();
        final AtomicReference<String> requestId = new AtomicReference<>();

        @GetMapping("/probe/mdc")
        public String mdc() {
            traceId.set(MDC.get(TraceContextFilter.TRACE_MDC_KEY));
            requestId.set(MDC.get(TraceContextFilter.REQUEST_MDC_KEY));
            return "ok";
        }
    }

    private final ProbeController probe = new ProbeController();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(probe)
            .addFilters(new TraceContextFilter())
            .build();

    @Test void generatesAndEchoesTraceIdWhenAbsent() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/probe/mdc"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .exists("X-Trace-Id"));
        assertNotNull(probe.traceId.get());
        assertNull(probe.requestId.get(), "no request id header means no request id MDC");
        assertNull(MDC.get(TraceContextFilter.TRACE_MDC_KEY), "MDC must be cleared after the request");
    }

    @Test void acceptsValidIncomingHeadersVerbatim() throws Exception {
        String traceId = "fixed-trace-id-123456";
        String requestId = UUID.randomUUID().toString();
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/probe/mdc")
                        .header("X-Trace-Id", traceId)
                        .header("X-Request-Id", requestId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        assertEquals(traceId, probe.traceId.get());
        assertEquals(requestId, probe.requestId.get());
        assertEquals(traceId, result.getResponse().getHeader("X-Trace-Id"));
        assertEquals(requestId, result.getResponse().getHeader("X-Request-Id"));
    }

    @Test void malformedHeadersAreIgnoredNotGuessedFrom() throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/probe/mdc")
                        .header("X-Trace-Id", "bad trace!")   // illegal characters
                        .header("X-Request-Id", "not-a-uuid"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        assertNotEquals("bad trace!", result.getResponse().getHeader("X-Trace-Id"));
        assertNull(result.getResponse().getHeader("X-Request-Id"), "malformed request id is not echoed");
        assertNull(probe.requestId.get());
    }
}
