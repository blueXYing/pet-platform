package com.petplatform.payment.biz.center.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.payment.api.command.PaymentCenterApi;
import com.petplatform.payment.api.query.PaymentCenterFactsApi;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Offline: strict decoding of PaymentCenterSucceededEvent.v1 and DEMO start validation. */
class DemoPaymentConfirmConsumerTest {
    private final DataSource source = mock(DataSource.class);
    private final PaymentCenterFactsApi facts = mock(PaymentCenterFactsApi.class);
    private final DemoPaymentConfirmConsumer consumer =
            new DemoPaymentConfirmConsumer(source, mock(JdbcOutboxConsumeGuard.class), facts);

    private static DispatchedEvent event(String payload) {
        return event("PaymentCenterSucceededEvent.v1", 1, "PAYMENT", 11L, payload);
    }

    private static DispatchedEvent event(String type, int version, String aggregate, long aggregateId, String payload) {
        return new DispatchedEvent("99", type, version, OffsetDateTime.parse("2026-11-04T08:00:00Z"), aggregate,
                aggregateId, "trace", payload);
    }

    private static String payload(String bizType, String bizNo, String paidAmount) {
        return "{\"paymentOrderId\":\"11\",\"paymentNo\":\"12\",\"bizType\":\"" + bizType + "\",\"bizNo\":\"" + bizNo
                + "\",\"channelTradeNo\":\"T1\",\"paidAmount\":" + paidAmount + ",\"paidAt\":\"2026-11-04T08:00:00.000Z\"}";
    }

    @Test void identityAndSubscription() {
        assertEquals("payment-demo-confirm", consumer.consumerName());
        assertEquals(Set.of("PaymentCenterSucceededEvent.v1"), consumer.eventTypes());
    }

    @Test void decodesTheExactPayload() {
        var decoded = consumer.decode(event(payload("DEMO", "345", "0.01")));
        assertEquals("DEMO", decoded.bizType());
        assertEquals(345L, decoded.demoNo());
        assertEquals(new BigDecimal("0.01"), decoded.paidAmount());
        // Another business type is accepted for a NOOP consumption; its bizNo need not be numeric.
        assertEquals(0L, consumer.decode(event(payload("BOOKING_ORDER", "B-1", "12.50"))).demoNo());
    }

    @Test void rejectsAnythingElseFailClosed() {
        String[] payloads = {
            "[]", "{}", payload("DEMO", "345", "0.001"), payload("DEMO", "345", "0"), payload("DEMO", "345", "\"0.01\""),
            payload("DEMO", "abc", "0.01"), payload("DEMO", "345", "0.01").replace("\"11\"", "\"13\""),
            payload("DEMO", "345", "0.01").replace("}", ",\"openid\":\"x\"}"),
            payload("DEMO", "345", "0.01").replace("2026-11-04T08:00:00.000Z", "yesterday"),
            payload("DEMO", "345", "0.01") + "x"
        };
        for (String p : payloads)
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> consumer.decode(event(p))).code(), p);
        String ok = payload("DEMO", "345", "0.01");
        assertThrows(ApiException.class, () -> consumer.decode(event("PaymentSucceededEvent.v1", 1, "PAYMENT", 11L, ok)));
        assertThrows(ApiException.class, () -> consumer.decode(event("PaymentCenterSucceededEvent.v1", 2, "PAYMENT", 11L, ok)));
        assertThrows(ApiException.class, () -> consumer.decode(event("PaymentCenterSucceededEvent.v1", 1, "ORDER", 11L, ok)));
        verifyNoInteractions(source, facts);
    }

    @Test void demoStartValidatesBeforeAnyWrite() {
        PaymentCenterApi center = mock(PaymentCenterApi.class);
        var demo = new DemoPaymentService(source, () -> 1L, center);
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class, () -> demo.start(
                new CommandContext("r", "t", OperatorType.SYSTEM, null, "TEST"), new BigDecimal("0.01"))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class, () -> demo.start(
                new CommandContext("", "t", OperatorType.USER, "7", "TEST"), new BigDecimal("0.01"))).code());
        for (String bad : new String[] {"0", "-1", "0.001"})
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class, () -> demo.start(
                    new CommandContext("r", "t", OperatorType.USER, "7", "TEST"), new BigDecimal(bad))).code(), bad);
        verifyNoInteractions(source, center);
    }
}
