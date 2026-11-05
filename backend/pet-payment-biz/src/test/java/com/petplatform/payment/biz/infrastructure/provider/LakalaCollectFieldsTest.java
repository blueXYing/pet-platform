package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Credentials;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Environment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.WireResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.NotificationReceipt;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ProtocolException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.TradeState;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Offline checks for settle_type and the optional log_no/log_date reconciliation trace.
 * Keys are generated in memory and discarded; nothing is read from disk.
 * The log_no/log_date names for 1111/1112 are pending SIT verification, so these tests pin
 * the "optional and never gates payment state" behavior, not an official sample.
 */
class LakalaCollectFieldsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExpectedPayment EXPECTED = new ExpectedPayment("123456", "2100001", 123);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 28, 10, 11, 12);
    private static KeyPair keys;

    @BeforeAll
    static void temporaryTestKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    // ---- settle_type -------------------------------------------------------------------

    @Test
    void preorderOmitsSettleTypeForNullAndZero() throws Exception {
        byte[] legacy11 = LakalaProtocol.preparePreorder(new PreorderInput("OP123", AT, "123456",
                "TERM1", "2100001", new BigDecimal("1.23"), "宠物服务", "wx-sub-app", "sub-open-id",
                "127.0.0.1", "https://example.test/notify")).rawBody();
        byte[] legacy12 = LakalaProtocol.preparePreorder(new PreorderInput("OP123", AT, "123456",
                "TERM1", "2100001", new BigDecimal("1.23"), "宠物服务", "wx-sub-app", "sub-open-id",
                "127.0.0.1", "https://example.test/notify", 9)).rawBody();
        for (String settle : new String[] {null, "0"}) {
            byte[] without = LakalaProtocol.preparePreorder(input(null, settle)).rawBody();
            byte[] bounded = LakalaProtocol.preparePreorder(input(9, settle)).rawBody();
            assertFalse(JSON.readTree(without).path("req_data").has("settle_type"));
            assertFalse(JSON.readTree(bounded).path("req_data").has("settle_type"));
            assertArrayEquals(legacy11, without);
            assertArrayEquals(legacy12, bounded);
        }
    }

    @Test
    void preorderSendsSettleTypeOneAtTopLevel() throws Exception {
        byte[] raw = LakalaProtocol.preparePreorder(input(null, "1")).rawBody();
        JsonNode data = JSON.readTree(raw).path("req_data");
        assertEquals("1", data.path("settle_type").asText());
        assertTrue(data.path("settle_type").isTextual());
        assertFalse(data.path("acc_busi_fields").has("settle_type"));
        assertEquals("wx-sub-app", data.path("acc_busi_fields").path("sub_appid").asText());
        String text = new String(raw, StandardCharsets.UTF_8);
        assertTrue(text.indexOf("\"notify_url\"") < text.indexOf("\"settle_type\""));
        assertTrue(text.indexOf("\"settle_type\"") < text.indexOf("\"acc_busi_fields\""));
    }

    @Test
    void preorderRejectsUnknownSettleType() {
        for (String settle : new String[] {"2", "", " 1", "1 ", "true"}) {
            assertThrows(ProtocolException.class,
                    () -> LakalaProtocol.preparePreorder(input(null, settle)), "[" + settle + "]");
        }
    }

    // ---- notification trace ------------------------------------------------------------

    @Test
    void notificationParsesLogNoAndLogDate() throws Exception {
        byte[] body = notice("SUCCESS", "\"log_no\":\"66210620148855\",\"log_date\":\"20260928\"");
        NotificationReceipt receipt = LakalaProtocol.verifyNotification(
                noticeHeaders(body), body, keys.getPublic(), EXPECTED);
        assertTrue(receipt.isSuccess());
        assertEquals("66210620148855", receipt.logNo());
        assertEquals(LocalDate.of(2026, 9, 28), receipt.logDate());
        assertEquals(120L, receipt.payerAmountCents());
    }

    @Test
    void notificationWithoutLogFieldsStillSucceeds() throws Exception {
        byte[] body = notice("SUCCESS", "");
        NotificationReceipt receipt = LakalaProtocol.verifyNotification(
                noticeHeaders(body), body, keys.getPublic(), EXPECTED);
        assertTrue(receipt.isSuccess());
        assertNull(receipt.logNo());
        assertNull(receipt.logDate());
    }

    @Test
    void malformedLogFieldsBecomeNullNotFailure() throws Exception {
        List<String> malformed = List.of(
                "\"log_no\":\"" + "1".repeat(33) + "\",\"log_date\":\"20260928\"",
                "\"log_no\":\"12-34\",\"log_date\":\"20260928\"",
                "\"log_no\":\"\",\"log_date\":\"20260928\"",
                "\"log_no\":12345678901234,\"log_date\":\"20260928\"",
                "\"log_no\":null,\"log_date\":\"20260928\"",
                "\"log_no\":\"66210620148855\",\"log_date\":\"20241332\"",
                "\"log_no\":\"66210620148855\",\"log_date\":\"2024-10-10\"",
                "\"log_no\":\"66210620148855\",\"log_date\":20260928",
                "\"log_no\":\"66210620148855\",\"log_date\":\"\"",
                "\"log_no\":\"66210620148855\",\"log_date\":null");
        for (String trace : malformed) {
            byte[] body = notice("SUCCESS", trace);
            NotificationReceipt receipt = LakalaProtocol.verifyNotification(
                    noticeHeaders(body), body, keys.getPublic(), EXPECTED);
            assertTrue(receipt.isSuccess(), trace);
            assertEquals(120L, receipt.payerAmountCents(), trace);
            assertNull(receipt.logNo(), trace);
            assertNull(receipt.logDate(), trace);
        }
    }

    @Test
    void logFieldsArePaired() throws Exception {
        for (String trace : new String[] {"\"log_no\":\"66210620148855\"", "\"log_date\":\"20260928\""}) {
            byte[] body = notice("SUCCESS", trace);
            NotificationReceipt receipt = LakalaProtocol.verifyNotification(
                    noticeHeaders(body), body, keys.getPublic(), EXPECTED);
            assertTrue(receipt.isSuccess(), trace);
            assertNull(receipt.logNo(), trace);
            assertNull(receipt.logDate(), trace);
        }
    }

    // ---- query trace -------------------------------------------------------------------

    @Test
    void queryResponseParsesLogFields() throws Exception {
        String trace = "\"log_no\":\"66210620148855\",\"log_date\":\"20260928\"";
        byte[] success = queryBody("SUCCESS", trace);
        QueryResult paid = LakalaProtocol.verifyQueryResponse(responseHeaders(success), success,
                "app-1", "serial-1", keys.getPublic(), EXPECTED);
        assertTrue(paid.isSuccess());
        assertEquals("66210620148855", paid.logNo());
        assertEquals(LocalDate.of(2026, 9, 28), paid.logDate());
        for (String state : new String[] {"FAIL", "DEAL"}) {
            byte[] body = queryBody(state, trace);
            QueryResult result = LakalaProtocol.verifyQueryResponse(responseHeaders(body), body,
                    "app-1", "serial-1", keys.getPublic(), EXPECTED);
            assertFalse(result.isSuccess());
            assertEquals(TradeState.valueOf(state), result.tradeState());
            assertEquals("66210620148855", result.logNo(), state);
            assertEquals(LocalDate.of(2026, 9, 28), result.logDate(), state);
        }
    }

    @Test
    void queryResponseWithoutOrMalformedLogFieldsKeepsPaymentOutcome() throws Exception {
        for (String trace : new String[] {"", "\"log_no\":\"66210620148855\"",
                "\"log_no\":\"66210620148855\",\"log_date\":\"20241332\"",
                "\"log_no\":7,\"log_date\":\"20260928\""}) {
            byte[] body = queryBody("SUCCESS", trace);
            QueryResult result = LakalaProtocol.verifyQueryResponse(responseHeaders(body), body,
                    "app-1", "serial-1", keys.getPublic(), EXPECTED);
            assertTrue(result.isSuccess(), trace);
            assertNull(result.logNo(), trace);
            assertNull(result.logDate(), trace);
        }
    }

    @Test
    void httpClientLookupCarriesLogFields() throws Exception {
        KeyPair merchant = keys;
        byte[] body = queryBody("SUCCESS", "\"log_no\":\"66210620148855\",\"log_date\":\"20260928\"");
        HttpHeaders wireHeaders = HttpHeaders.of(headerLists(responseHeaders(body)), (name, value) -> true);
        LakalaHttpClient client = new LakalaHttpClient(Environment.SIT,
                new Credentials("app-1", "merchant-serial", merchant.getPrivate(), "serial-1",
                        keys.getPublic()),
                request -> new WireResponse(200, wireHeaders, new ByteArrayInputStream(body)));
        var verified = client.lookup(new QueryInput("OP123", AT, "123456", "TERM1", "2100001",
                LocalDate.of(2026, 9, 28)), EXPECTED, new RequestNonce("1727499000", "AbCd12345678"));
        assertTrue(verified.result().isSuccess());
        assertEquals("66210620148855", verified.result().logNo());
        assertEquals(LocalDate.of(2026, 9, 28), verified.result().logDate());
    }

    // ---- legacy constructors -----------------------------------------------------------

    @Test
    void legacyConstructorsDefaultToNull() {
        PreorderInput legacy = new PreorderInput("OP123", AT, "123456", "TERM1", "2100001",
                new BigDecimal("1.23"), "s", "app", "user", "127.0.0.1", "https://example.test/n");
        assertNull(legacy.settleType());
        assertNull(legacy.timeoutExpressMinutes());
        PreorderInput bounded = new PreorderInput("OP123", AT, "123456", "TERM1", "2100001",
                new BigDecimal("1.23"), "s", "app", "user", "127.0.0.1", "https://example.test/n", 5);
        assertNull(bounded.settleType());
        assertEquals(5, bounded.timeoutExpressMinutes());
        assertFalse(input(null, "1").toString().contains("sub-open-id"));

        NotificationReceipt receipt = new NotificationReceipt("123456", "2100001", "T01",
                TradeState.SUCCESS, 123, 120L, AT, "WECHAT");
        assertNull(receipt.logNo());
        assertNull(receipt.logDate());
        QueryResult query = new QueryResult("123456", "2100001", "T01", TradeState.SUCCESS,
                123, 120L, AT, "WECHAT");
        assertNull(query.logNo());
        assertNull(query.logDate());
        var notice = new PaymentReceiptVerifier.VerifiedNotice("123456", "2100001", "T01", "SUCCESS",
                new BigDecimal("1.23"), new BigDecimal("1.20"), AT, "WECHAT");
        assertNull(notice.logNo());
        assertNull(notice.logDate());
        var traced = new PaymentReceiptVerifier.VerifiedNotice("123456", "2100001", "T01", "SUCCESS",
                new BigDecimal("1.23"), new BigDecimal("1.20"), AT, "WECHAT", "66210620148855",
                LocalDate.of(2026, 9, 28));
        assertEquals("66210620148855", traced.logNo());
    }

    // ---- helpers -----------------------------------------------------------------------

    private static PreorderInput input(Integer timeout, String settle) {
        return new PreorderInput("OP123", AT, "123456", "TERM1", "2100001", new BigDecimal("1.23"),
                "宠物服务", "wx-sub-app", "sub-open-id", "127.0.0.1", "https://example.test/notify",
                timeout, settle);
    }

    private static byte[] notice(String state, String extra) {
        return ("{\"merchant_no\":\"123456\",\"out_trade_no\":\"2100001\",\"trade_no\":\"T01\","
                + "\"trade_status\":\"" + state + "\",\"total_amount\":\"123\","
                + "\"payer_amount\":\"120\",\"trade_time\":\"20260928123456\","
                + "\"account_type\":\"WECHAT\"" + (extra.isEmpty() ? "" : "," + extra) + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] queryBody(String state, String extra) {
        String payer = "SUCCESS".equals(state) ? "120" : "";
        String time = "SUCCESS".equals(state) ? "20260928123456" : "";
        return ("{\"code\":\"BBS00000\",\"resp_data\":{\"merchant_no\":\"123456\","
                + "\"out_trade_no\":\"2100001\",\"trade_no\":\"T01\",\"trade_state\":\"" + state
                + "\",\"total_amount\":\"123\",\"payer_amount\":\"" + payer
                + "\",\"trade_time\":\"" + time + "\",\"account_type\":\"WECHAT\""
                + (extra.isEmpty() ? "" : "," + extra) + "}}").getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> noticeHeaders(byte[] body) throws Exception {
        String time = "1727499000", nonce = "AbCd12345678";
        return Map.of("Authorization", "LKLAPI-SHA256withRSA timestamp=\"" + time
                + "\", nonce_str=\"" + nonce + "\", signature=\""
                + sign(concat(time + "\n" + nonce + "\n", body)) + "\"");
    }

    private static Map<String, String> responseHeaders(byte[] body) throws Exception {
        String time = "1727499000", nonce = "AbCd12345678";
        return Map.of("Lklapi-Appid", "app-1", "Lklapi-Serial", "serial-1",
                "Lklapi-Timestamp", time, "Lklapi-Nonce", nonce,
                "Lklapi-Signature", sign(concat("app-1\nserial-1\n" + time + "\n" + nonce + "\n", body)));
    }

    private static Map<String, List<String>> headerLists(Map<String, String> headers) {
        Map<String, List<String>> lists = new java.util.LinkedHashMap<>();
        headers.forEach((name, value) -> lists.put(name, List.of(value)));
        return lists;
    }

    private static String sign(byte[] message) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(message);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static byte[] concat(String prefix, byte[] body) {
        byte[] first = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[first.length + body.length + 1];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(body, 0, result, first.length, body.length);
        result[result.length - 1] = '\n';
        return result;
    }
}
