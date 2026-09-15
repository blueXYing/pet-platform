package com.petplatform.boot.web;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.adapter.web.GlobalApiExceptionHandler;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalApiHandlerTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/not-found-code")
        public String notFoundCode() {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "订单不存在");
        }

        @GetMapping("/probe/boom")
        public String boom() {
            throw new IllegalStateException("SELECT secret FROM payment WHERE pwd='x'");
        }

        @PostMapping("/probe/echo")
        public String echo(@RequestBody java.util.Map<String, Object> body) {
            return "echo";
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalApiExceptionHandler())
            .addFilters(new TraceContextFilter())
            .build();

    @Test void apiExceptionMapsRegistryStatusAndEnvelope() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/probe/not-found-code"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value(CommonApiCodes.NOT_FOUND))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.traceId")
                        .isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"));
    }

    @Test void unknownExceptionHidesInternalsBehindInternalError() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/probe/boom"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value(CommonApiCodes.INTERNAL_ERROR))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("服务内部错误，请稍后重试"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.traceId")
                        .isNotEmpty());
    }

    @Test void malformedJsonBodyIsInvalidArgument() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/probe/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value(CommonApiCodes.INVALID_ARGUMENT));
    }

    @Test void envelopeTraceIdMatchesEchoedHeader() throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/probe/not-found-code")
                        .header("X-Trace-Id", "trace-aabbccdd-1234"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"traceId\":\"trace-aabbccdd-1234\""));
        assertEquals("trace-aabbccdd-1234", result.getResponse().getHeader("X-Trace-Id"));
    }
}
