package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundProtocol.ExpectedRefund;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundProtocol.QueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundProtocol.RefundInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundProtocol.State;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LakalaRefundProtocolTest {
    private static final String APP = "offline-app";
    private static final String SERIAL = "offline-platform-serial";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 28, 10, 11, 12);
    private static KeyPair platform;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll static void keys() {
        platform();
    }

    @Test void preparesOnlyDocumentedV3PathsAndAmountFields() throws Exception {
        var refund = LakalaRefundProtocol.prepareRefund(input());
        assertEquals(LakalaRefundProtocol.REFUND_PATH, refund.path());
        JsonNode body = JSON.readTree(refund.rawBody());
        assertEquals("20260928101112", body.get("req_time").asText());
        assertEquals("3.0", body.get("version").asText());
        assertFalse(body.has("out_org_code"));
        JsonNode data = body.get("req_data");
        assertEquals("123", data.get("refund_amount").asText());
        assertEquals("ORIGINAL-1", data.get("origin_out_trade_no").asText());
        assertEquals("CHANNEL-1", data.get("origin_trade_no").asText());
        assertEquals("127.0.0.1", data.get("location_info").get("request_ip").asText());
        assertEquals(LakalaRefundProtocol.QUERY_PATH,
                LakalaRefundProtocol.prepareQuery(query()).path());
        assertThrows(LakalaRefundProtocol.ProtocolException.class, () ->
                LakalaRefundProtocol.prepareRefund(new RefundInput(AT, "123456", "TERM1",
                        "REFUND-1", new BigDecimal("1.23"), null, null,
                        "127.0.0.1", null, null)));
    }

    @Test void signedProcessingAndSuccessAreDistinctAndBoundToOriginalPayment() throws Exception {
        var processing = LakalaRefundProtocol.verifySubmit(headers(body("PROCESSING", "123",
                "123", "20260928101112", "CHANNEL-1")),
                body("PROCESSING", "123", "123", "20260928101112", "CHANNEL-1"),
                APP, SERIAL, platform.getPublic(), expected());
        assertEquals(State.PROCESSING, processing.state());
        assertFalse(processing.isSuccess());
        byte[] success = body("SUCCESS", "123", "123", "20260928101112", "CHANNEL-1");
        var result = LakalaRefundProtocol.verifyQuery(headers(success), success,
                APP, SERIAL, platform.getPublic(), expected());
        assertTrue(result.isSuccess());
        assertEquals(123, result.actualRefundCents());
        assertEquals(AT, result.channelTime());

        byte[] mismatchedOriginal = body("SUCCESS", "123", "123", "20260928101112", "OTHER");
        assertThrows(LakalaRefundProtocol.ProtocolException.class, () ->
                LakalaRefundProtocol.verifyQuery(headers(mismatchedOriginal), mismatchedOriginal,
                        APP, SERIAL, platform.getPublic(), expected()));
        for (byte[] invalid : new byte[][] {
                body("SUCCESS", "124", "123", "20260928101112", "CHANNEL-1"),
                body("SUCCESS", "123", null, "20260928101112", "CHANNEL-1"),
                body("SUCCESS", "123", "123", null, "CHANNEL-1")}) {
            assertThrows(LakalaRefundProtocol.ProtocolException.class, () ->
                    LakalaRefundProtocol.verifyQuery(headers(invalid), invalid,
                            APP, SERIAL, platform.getPublic(), expected()));
        }
    }

    @Test void timeoutCodeAndTamperedSignedBodyCannotBeSuccess() throws Exception {
        byte[] timeout = "{\"code\":\"RFD11112\"}".getBytes(StandardCharsets.UTF_8);
        var unknown = LakalaRefundProtocol.verifySubmit(headers(timeout), timeout,
                APP, SERIAL, platform.getPublic(), expected());
        assertEquals(State.UNKNOWN, unknown.state());
        byte[] signed = body("SUCCESS", "123", "123", "20260928101112", "CHANNEL-1");
        byte[] tampered = body("SUCCESS", "124", "123", "20260928101112", "CHANNEL-1");
        assertThrows(LakalaProtocol.ProtocolException.class, () ->
                LakalaRefundProtocol.verifyQuery(headers(signed), tampered,
                        APP, SERIAL, platform.getPublic(), expected()));
    }

    static RefundInput input() {
        return new RefundInput(AT, "123456", "TERM1", "REFUND-1", new BigDecimal("1.23"),
                "ORIGINAL-1", "CHANNEL-1", "127.0.0.1", "refund", null);
    }
    static QueryInput query() { return new QueryInput(AT, "123456", "TERM1", "REFUND-1"); }
    static ExpectedRefund expected() {
        return new ExpectedRefund("123456", "REFUND-1", 123, "ORIGINAL-1", "CHANNEL-1");
    }

    static byte[] body(String state, String requested, String actual, String at, String origin) {
        String fields = "\"trade_state\":\"" + state + "\",\"merchant_no\":\"123456\","
                + "\"out_trade_no\":\"REFUND-1\",\"trade_no\":\"CHANNEL-REFUND-1\","
                + "\"refund_amount\":\"" + requested + "\","
                + "\"origin_out_trade_no\":\"ORIGINAL-1\",\"origin_trade_no\":\""
                + origin + "\"";
        if (actual != null) fields += ",\"payer_amount\":\"" + actual + "\"";
        if (at != null) fields += ",\"trade_time\":\"" + at + "\"";
        return ("{\"code\":\"000000\",\"resp_data\":{" + fields + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, String> headers(byte[] body) {
        try {
            platform();
            String stamp = "1727499000", nonce = "AbCd12345678";
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(platform.getPrivate());
            signer.update((APP + "\n" + SERIAL + "\n" + stamp + "\n" + nonce + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            signer.update(body);
            signer.update((byte) '\n');
            return Map.of("Lklapi-Appid", APP, "Lklapi-Serial", SERIAL,
                    "Lklapi-Timestamp", stamp, "Lklapi-Nonce", nonce,
                    "Lklapi-Signature", Base64.getEncoder().encodeToString(signer.sign()));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    static synchronized KeyPair platform() {
        if (platform == null) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                platform = generator.generateKeyPair();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        return platform;
    }
}
