package com.petplatform.payment.biz.center;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Contract 75 §4.1 bizType policy: offline, no MySQL. */
class PaymentCenterBizPolicyTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 4, 8, 0, 0, 0, ZoneOffset.UTC);

    private static String code(Runnable call) {
        return assertThrows(ApiException.class, call::run).code();
    }

    @Test void onlyRegisteredTypesMayCreate() {
        var none = PaymentCenterBizPolicy.none();
        assertFalse(none.registered("DEMO"));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> none.requireCommand("DEMO", "1", new BigDecimal("0.01"))));
        var demo = PaymentCenterBizPolicy.demoOnly(new BigDecimal("0.01"));
        assertTrue(demo.registered("DEMO"));
        assertFalse(demo.registered("BOOKING_ORDER"));
        assertFalse(demo.registered(null));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> demo.requireCommand("BOOKING_ORDER", "1", new BigDecimal("0.01"))));
    }

    @Test void amountMustBePositiveTwoDecimalsAndWithinTheCeiling() {
        var demo = PaymentCenterBizPolicy.demoOnly(new BigDecimal("0.01"));
        assertDoesNotThrow(() -> demo.requireCommand("DEMO", "1", new BigDecimal("0.01")));
        assertDoesNotThrow(() -> demo.requireCommand("DEMO", "1", new BigDecimal("0.010")
                .stripTrailingZeros()));
        for (String bad : new String[] {"0", "0.00", "-0.01", "0.02", "0.001", "1"}) {
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    code(() -> demo.requireCommand("DEMO", "1", new BigDecimal(bad))), bad);
        }
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> demo.requireCommand("DEMO", "1", null)));
    }

    @Test void bizNoFormatIsEnforced() {
        var demo = PaymentCenterBizPolicy.demoOnly(new BigDecimal("0.01"));
        assertDoesNotThrow(() -> demo.requireCommand("DEMO", "A-b_9", new BigDecimal("0.01")));
        assertDoesNotThrow(() -> demo.requireCommand("DEMO", "x".repeat(64), new BigDecimal("0.01")));
        for (String bad : new String[] {"", " ", "x".repeat(65), "a b", "订单", "a/b", "a\n"}) {
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    code(() -> demo.requireCommand("DEMO", bad, new BigDecimal("0.01"))), bad);
        }
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> demo.requireCommand("DEMO", null, new BigDecimal("0.01"))));
    }

    @Test void deadlineWindowIsOneMinuteToPolicyMaximumInclusive() {
        var limits = new PaymentCenterBizPolicy.Limits(new BigDecimal("0.01"), Duration.ofMinutes(10));
        assertDoesNotThrow(() -> PaymentCenterBizPolicy.requireWindow(limits, NOW.plusMinutes(1), NOW));
        assertDoesNotThrow(() -> PaymentCenterBizPolicy.requireWindow(limits, NOW.plusMinutes(10), NOW));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> PaymentCenterBizPolicy.requireWindow(limits, NOW.plusSeconds(59), NOW)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> PaymentCenterBizPolicy.requireWindow(limits, NOW.plusMinutes(10).plusNanos(1_000_000), NOW)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> PaymentCenterBizPolicy.requireWindow(limits, NOW.minusMinutes(1), NOW)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> PaymentCenterBizPolicy.requireWindow(limits, null, NOW)));
    }

    @Test void limitsAndTypeNamesAreValidatedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentCenterBizPolicy.Limits(BigDecimal.ZERO, Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentCenterBizPolicy.Limits(new BigDecimal("0.001"), Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentCenterBizPolicy.Limits(new BigDecimal("1"), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentCenterBizPolicy.Limits(new BigDecimal("1"), Duration.ofHours(25)));
        var limits = new PaymentCenterBizPolicy.Limits(new BigDecimal("1"), Duration.ofMinutes(10));
        assertThrows(IllegalArgumentException.class, () -> new PaymentCenterBizPolicy(Map.of("demo", limits)));
        assertThrows(IllegalArgumentException.class, () -> new PaymentCenterBizPolicy(Map.of("A B", limits)));
        assertThrows(IllegalArgumentException.class, () -> PaymentCenterBizPolicy.demoOnly(new BigDecimal("0")));
    }
}
