package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ProtocolException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LakalaProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExpectedPayment EXPECTED = new ExpectedPayment("123456", "2100001", 123);
    private static KeyPair keys;

    @BeforeAll
    static void temporaryTestKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @Test
    void requestSigningUsesFiveRawLinesAndExactFinalLineFeed() throws Exception {
        byte[] body = "{\"text\":\"宠物\"}".getBytes(StandardCharsets.UTF_8);
        String authorization = LakalaProtocol.authorization("app-1", "serial-1",
                "1727499000", "AbCd12345678", body, keys.getPrivate());
        String encoded = authorization.substring(authorization.lastIndexOf("signature=\"") + 11,
                authorization.length() - 1);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update(concat("app-1\nserial-1\n1727499000\nAbCd12345678\n", body, "\n"));
        assertTrue(verifier.verify(Base64.getDecoder().decode(encoded)));
        assertThrows(ProtocolException.class, () -> LakalaProtocol.authorization("app-1",
                "serial-1", "1727499000", "short", body, keys.getPrivate()));
        KeyPairGenerator weakGenerator = KeyPairGenerator.getInstance("RSA");
        weakGenerator.initialize(1024);
        var weak = weakGenerator.generateKeyPair();
        assertThrows(ProtocolException.class, () -> LakalaProtocol.authorization("app-1",
                "serial-1", "1727499000", "AbCd12345678", body, weak.getPrivate()));
    }

    @Test
    void notificationUsesThreeRawLinesAndFailsClosedBeforeParsing() throws Exception {
        String source = "{\"merchant_no\":\"123456\",\"out_trade_no\":\"2100001\","
                + "\"trade_no\":\"202609281234\",\"trade_status\":\"SUCCESS\","
                + "\"total_amount\":\"123\",\"payer_amount\":\"120\","
                + "\"trade_time\":\"20260928123456\",\"account_type\":\"WECHAT\"}";
        byte[] body = source.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = noticeHeaders(body);
        var receipt = LakalaProtocol.verifyNotification(headers, body, keys.getPublic(), EXPECTED);
        assertTrue(receipt.isSuccess());
        assertEquals(120L, receipt.payerAmountCents());
        assertEquals(LocalDateTime.of(2026, 9, 28, 12, 34, 56), receipt.channelTradeTime());
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyNotification(
                headers, (source + " ").getBytes(StandardCharsets.UTF_8), keys.getPublic(), EXPECTED));
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyNotification(
                Map.of("Authorization", headers.get("Authorization"),
                        "authorization", headers.get("Authorization")), body, keys.getPublic(), EXPECTED));
    }

    @Test
    void signedSuccessWithoutActualPayerAmountOrWithUnknownStateIsRejected() throws Exception {
        byte[] missingPaid = notice("SUCCESS", null, "20260928123456");
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyNotification(
                noticeHeaders(missingPaid), missingPaid, keys.getPublic(), EXPECTED));
        byte[] unknown = notice("MYSTERY", "123", "20260928123456");
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyNotification(
                noticeHeaders(unknown), unknown, keys.getPublic(), EXPECTED));
        byte[] duplicate = (new String(notice("SUCCESS", "123", "20260928123456"),
                StandardCharsets.UTF_8).replace("\"trade_status\":\"SUCCESS\"",
                        "\"trade_status\":\"SUCCESS\",\"trade_status\":\"FAIL\""))
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyNotification(
                noticeHeaders(duplicate), duplicate, keys.getPublic(), EXPECTED));
        byte[] impossibleDate = notice("SUCCESS", "123", "20260230123456");
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyNotification(
                noticeHeaders(impossibleDate), impossibleDate, keys.getPublic(), EXPECTED));
        byte[] changedIdentity = new String(notice("SUCCESS", "123", "20260928123456"),
                StandardCharsets.UTF_8).replace("\"out_trade_no\":\"2100001\"",
                        "\"out_trade_no\":\"2100002\"").getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyNotification(
                noticeHeaders(changedIdentity), changedIdentity, keys.getPublic(), EXPECTED));
        byte[] numericAmount = new String(notice("SUCCESS", "123", "20260928123456"),
                StandardCharsets.UTF_8).replace("\"total_amount\":\"123\"",
                        "\"total_amount\":123").getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyNotification(
                noticeHeaders(numericAmount), numericAmount, keys.getPublic(), EXPECTED));
        byte[] pending = notice("UNKNOWN", null, "");
        var pendingReceipt = LakalaProtocol.verifyNotification(
                noticeHeaders(pending), pending, keys.getPublic(), EXPECTED);
        assertFalse(pendingReceipt.isSuccess());
        assertEquals(null, pendingReceipt.payerAmountCents());
        assertEquals(null, pendingReceipt.channelTradeTime());
    }

    @Test
    void buildersUseDocumentedV3FieldsAndExactCentStrings() throws Exception {
        LocalDateTime at = LocalDateTime.of(2026, 9, 28, 10, 11, 12);
        var preorder = LakalaProtocol.preparePreorder(new LakalaProtocol.PreorderInput(
                "OP123", at, "123456", "TERM1", "2100001", new BigDecimal("1.23"),
                "宠物服务", "wx-sub-app", "sub-open-id", "127.0.0.1",
                "https://example.test/notify"));
        JsonNode body = JSON.readTree(preorder.rawBody());
        assertEquals("/api/v3/labs/trans/preorder", preorder.path());
        assertEquals("20260928101112", body.path("req_time").asText());
        assertEquals("3.0", body.path("version").asText());
        assertEquals("WECHAT", body.path("req_data").path("account_type").asText());
        assertEquals("71", body.path("req_data").path("trans_type").asText());
        assertEquals("123", body.path("req_data").path("total_amount").asText());
        assertEquals("wx-sub-app", body.path("req_data").path("acc_busi_fields")
                .path("sub_appid").asText());
        assertEquals("sub-open-id", body.path("req_data").path("acc_busi_fields")
                .path("user_id").asText());

        var query = LakalaProtocol.prepareQuery(new LakalaProtocol.QueryInput("OP123", at,
                "123456", "TERM1", "2100001", LocalDate.of(2026, 9, 28)));
        assertEquals("/api/v3/labs/query/tradequery", query.path());
        assertEquals("20260928", JSON.readTree(query.rawBody()).path("req_data")
                .path("trade_req_date").asText());
        var close = LakalaProtocol.prepareClose(new LakalaProtocol.CloseInput("OP123", at,
                "123456", "TERM1", "2100001", "127.0.0.1"));
        assertEquals("/api/v3/labs/relation/close", close.path());
        assertEquals("127.0.0.1", JSON.readTree(close.rawBody()).path("req_data")
                .path("location_info").path("request_ip").asText());
        assertThrows(ProtocolException.class, () -> LakalaProtocol.preparePreorder(
                new LakalaProtocol.PreorderInput("OP123", at, "123456", "TERM1", "2100001",
                        new BigDecimal("1.234"), "service", "app", "user", "127.0.0.1",
                        "https://example.test/notify")));
    }

    @Test
    void verifiedQueryDistinguishesFoundFromPaidAndCloseIsOnlyAcknowledgement()
            throws Exception {
        byte[] pending = ("{\"code\":\"BBS00000\",\"resp_data\":{"
                + "\"merchant_no\":\"123456\",\"out_trade_no\":\"2100001\","
                + "\"trade_no\":\"T01\",\"trade_state\":\"DEAL\","
                + "\"total_amount\":\"123\",\"payer_amount\":\"\","
                + "\"account_type\":\"WECHAT\"}}")
                .getBytes(StandardCharsets.UTF_8);
        var result = LakalaProtocol.verifyQueryResponse(responseHeaders(pending), pending,
                "app-1", "serial-1", keys.getPublic(), EXPECTED);
        assertFalse(result.isSuccess());
        assertEquals(null, result.payerAmountCents());
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyQueryResponse(
                responseHeaders(pending), pending, "app-1", "other-serial",
                keys.getPublic(), EXPECTED));

        byte[] falselyPaid = new String(pending, StandardCharsets.UTF_8)
                .replace("\"trade_state\":\"DEAL\"", "\"trade_state\":\"SUCCESS\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyQueryResponse(
                responseHeaders(falselyPaid), falselyPaid, "app-1", "serial-1",
                keys.getPublic(), EXPECTED));

        byte[] close = ("{\"code\":\"BBS00000\",\"resp_data\":{"
                + "\"origin_out_trade_no\":\"2100001\","
                + "\"origin_trade_no\":\"T01\","
                + "\"trade_time\":\"20260928123456\"}}")
                .getBytes(StandardCharsets.UTF_8);
        var acknowledgement = LakalaProtocol.verifyCloseResponse(responseHeaders(close), close,
                "app-1", "serial-1", keys.getPublic(), "2100001");
        assertEquals("T01", acknowledgement.originTradeNo());
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyResponse(
                responseHeaders(close), (new String(close, StandardCharsets.UTF_8) + " ")
                        .getBytes(StandardCharsets.UTF_8), "app-1", "serial-1", keys.getPublic()));
    }

    @Test
    void preorderParametersAreBoundToRequestedSubAppAndPrepayId() throws Exception {
        String fields = "\"app_id\":\"wx-sub-app\",\"prepay_id\":\"wx-prepay\","
                + "\"pay_sign\":\"" + "S".repeat(344) + "\","
                + "\"time_stamp\":\"1727499000\",\"nonce_str\":\"AbCd12345678\","
                + "\"package\":\"prepay_id=wx-prepay\",\"sign_type\":\"RSA\"";
        String json = "{\"code\":\"BBS00000\",\"resp_data\":{"
                + "\"merchant_no\":\"123456\",\"out_trade_no\":\"2100001\","
                + "\"trade_no\":\"T01\",\"acc_resp_fields\":{" + fields + "}}}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        var result = LakalaProtocol.verifyPreorderResponse(responseHeaders(body), body,
                "app-1", "serial-1", keys.getPublic(), EXPECTED, "wx-sub-app");
        assertEquals(344, result.paySign().length());
        assertFalse(result.toString().contains(result.paySign()));
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyPreorderResponse(
                responseHeaders(body), body, "app-1", "serial-1", keys.getPublic(),
                EXPECTED, "another-app"));
        byte[] badPackage = json.replace("prepay_id=wx-prepay", "prepay_id=other")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> LakalaProtocol.verifyPreorderResponse(
                responseHeaders(badPackage), badPackage, "app-1", "serial-1",
                keys.getPublic(), EXPECTED, "wx-sub-app"));
    }

    private static byte[] notice(String state, String payer, String tradeTime) {
        String amount = payer == null ? "" : ",\"payer_amount\":\"" + payer + "\"";
        return ("{\"merchant_no\":\"123456\",\"out_trade_no\":\"2100001\","
                + "\"trade_no\":\"T01\",\"trade_status\":\"" + state + "\","
                + "\"total_amount\":\"123\"" + amount + ",\"trade_time\":\""
                + tradeTime + "\",\"account_type\":\"WECHAT\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> noticeHeaders(byte[] body) throws Exception {
        String time = "1727499000", nonce = "AbCd12345678";
        return Map.of("Authorization", "LKLAPI-SHA256withRSA timestamp=\"" + time
                + "\", nonce_str=\"" + nonce + "\", signature=\""
                + sign(concat(time + "\n" + nonce + "\n", body, "\n")) + "\"");
    }

    private static Map<String, String> responseHeaders(byte[] body) throws Exception {
        String time = "1727499000", nonce = "AbCd12345678";
        return Map.of("Lklapi-Appid", "app-1", "Lklapi-Serial", "serial-1",
                "Lklapi-Timestamp", time, "Lklapi-Nonce", nonce,
                "Lklapi-Signature", sign(concat("app-1\nserial-1\n" + time + "\n"
                        + nonce + "\n", body, "\n")));
    }

    private static String sign(byte[] message) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(message);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static byte[] concat(String prefix, byte[] body, String suffix) {
        byte[] first = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] last = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[first.length + body.length + last.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(body, 0, result, first.length, body.length);
        System.arraycopy(last, 0, result, first.length + body.length, last.length);
        return result;
    }
}
