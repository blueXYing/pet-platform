package com.petplatform.boot.adapter.web.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.petplatform.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class MarketingHttpExceptionHandlerTest {

    private final MarketingHttpExceptionHandler handler = new MarketingHttpExceptionHandler();

    @Test
    void registryStatusesArePreservedForMarketingAndCouponCodes() {
        assertStatus(409, "COUPON_CLAIMED");
        assertStatus(409, "COUPON_CLAIM_LIMIT_REACHED");
        assertStatus(409, "COUPON_CAMPAIGN_SOLD_OUT");
        assertStatus(409, "COUPON_CAMPAIGN_NOT_CLAIMABLE");
        assertStatus(409, "CAMPAIGN_STATE_NOT_ALLOWED");
        assertStatus(409, "CAMPAIGN_LANDING_INVALID");
        assertStatus(409, "PLACEMENT_LIMIT_EXCEEDED");
        assertStatus(400, "SERVICE_CATEGORY_INVALID");
        assertStatus(409, "ENROLLMENT_ALREADY_EXISTS");
        assertStatus(409, "ENROLLMENT_STATE_NOT_ALLOWED");
        assertStatus(400, "CAMPAIGN_PAGE_INVALID");
        assertStatus(404, "CAMPAIGN_NOT_FOUND");
        assertStatus(404, "COUPON_CAMPAIGN_NOT_FOUND");
        assertStatus(400, "MARKETING_ASSET_INVALID");
        assertStatus(413, "MARKETING_ASSET_TOO_LARGE");
    }

    @Test
    void malformedBodyIsInvalidArgumentAndUnknownCodesStayFailClosed() {
        MockHttpServletResponse malformed = new MockHttpServletResponse();
        var invalid = handler.invalid(new IllegalArgumentException("name"), malformed);
        assertEquals(400, malformed.getStatus());
        assertEquals("COMMON_INVALID_ARGUMENT", invalid.code());

        MockHttpServletResponse unknown = new MockHttpServletResponse();
        var unmapped = handler.apiException(new ApiException("UNMAPPED_BUSINESS_CODE", "x"), unknown);
        assertEquals(500, unknown.getStatus());
        assertEquals("UNMAPPED_BUSINESS_CODE", unmapped.code());
    }

    private void assertStatus(int expected, String code) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        var body = handler.apiException(new ApiException(code, "message"), response);
        assertEquals(expected, response.getStatus(), code);
        assertEquals(code, body.code(), code);
        assertEquals(null, body.data(), code);
    }
}
