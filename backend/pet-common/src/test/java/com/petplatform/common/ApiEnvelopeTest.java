package com.petplatform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiEnvelopeTest {

    @Test void successEnvelopeCarriesDataAndTraceId() {
        var response = ApiResponse.success(Map.of("orderId", "123"), "trace-1");
        assertTrue(response.isSuccess());
        assertEquals("SUCCESS", response.code());
        assertEquals("123", ((Map<?, ?>) response.data()).get("orderId"));
        assertEquals("trace-1", response.traceId());
    }

    @Test void errorEnvelopeHasNoData() {
        var response = ApiResponse.error(CommonApiCodes.NOT_FOUND, "订单不存在", "trace-2");
        assertFalse(response.isSuccess());
        assertNull(response.data());
        assertEquals(CommonApiCodes.NOT_FOUND, response.code());
    }

    @Test void envelopeRejectsBlankCode() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiResponse.error("", "message", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiError(null, "message", null, null));
    }

    @Test void apiErrorNormalizesDetailsToImmutable() {
        var error = new ApiError(CommonApiCodes.CONFLICT, "冲突", null, Map.of("field", "status"));
        assertEquals(Map.of("field", "status"), error.details());
        assertNull(new ApiError(CommonApiCodes.CONFLICT, "冲突", null, null).details().get("field"));
        assertEquals(0, new ApiError(CommonApiCodes.CONFLICT, "冲突", null, null).details().size());
    }

    @Test void apiExceptionCarriesStableCode() {
        var error = assertThrows(ApiException.class,
                () -> { throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "同 requestId 异参"); });
        assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, error.code());
        assertThrows(IllegalArgumentException.class, () -> new ApiException("  ", "message"));
    }
}
