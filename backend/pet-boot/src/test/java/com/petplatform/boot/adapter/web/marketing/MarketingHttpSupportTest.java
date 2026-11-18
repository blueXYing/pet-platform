package com.petplatform.boot.adapter.web.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import org.junit.jupiter.api.Test;

/** Boundary compatibility for optimistic-lock versions (contract 76 §4.4). */
class MarketingHttpSupportTest {

    @Test
    void longValueAcceptsIntegerAndDecimalStringWireShapes() {
        assertEquals(3L, MarketingHttpSupport.longValue(MarketingHttpSupport.body("{\"v\":3}"), "v"));
        assertEquals(3L, MarketingHttpSupport.longValue(MarketingHttpSupport.body("{\"v\":\"3\"}"), "v"));
        assertEquals(0L, MarketingHttpSupport.longValue(MarketingHttpSupport.body("{\"v\":\"0\"}"), "v"));
    }

    @Test
    void longValueRejectsAnythingElse() {
        for (String raw : new String[] {
                "{\"v\":\"03\"}", "{\"v\":\"-1\"}", "{\"v\":\"3.0\"}", "{\"v\":3.0}",
                "{\"v\":true}", "{\"v\":null}", "{}", "{\"v\":\"abc\"}",
                "{\"v\":\"9223372036854775808\"}"}) {
            assertThrows(ApiException.class,
                    () -> MarketingHttpSupport.longValue(MarketingHttpSupport.body(raw), "v"), raw);
        }
    }
}
