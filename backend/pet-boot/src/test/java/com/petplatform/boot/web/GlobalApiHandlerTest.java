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

        @PostMapping(value="/probe/json-only",consumes=MediaType.APPLICATION_JSON_VALUE)
        public String jsonOnly(@RequestBody java.util.Map<String,Object> body){return "echo";}
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

    @Test void unsupportedContentTypeBeforeHandlerSelectionIsSafe415()throws Exception{
        var result=mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/probe/json-only")
                        .contentType(MediaType.TEXT_PLAIN).content("SELECT sensitive FROM evidence WHERE token='secret'"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnsupportedMediaType())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(CommonApiCodes.INVALID_ARGUMENT))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("请求内容类型不支持"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.traceId").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store, private"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options","nosniff"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("secret"));
        assertFalse(result.getResponse().getContentAsString().contains("SELECT"));
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

    @Test void earlyAftersaleMultipartMappingDoesNotChangeOtherUploadRoutes() {
        var handler=new GlobalApiExceptionHandler();
        for(String path:java.util.List.of("/api/v1/c/private-assets","/api/v1/c/aftersale-evidence-assets/")){
            var request=new org.springframework.mock.web.MockHttpServletRequest("POST",path);
            var response=new org.springframework.mock.web.MockHttpServletResponse();
            var result=handler.multipartBeforeHandler(new org.springframework.web.multipart.MaxUploadSizeExceededException(10),request,response);
            assertEquals(500,response.getStatus());
            assertInstanceOf(com.petplatform.common.ApiResponse.class,result);
            assertEquals(CommonApiCodes.INTERNAL_ERROR,((com.petplatform.common.ApiResponse<?>)result).code());
        }
    }
}
