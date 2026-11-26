package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ExpectedSplit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackSubmitResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ProtocolException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryPlusInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ReceiverShare;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateSubmitResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitStatus;
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

/** Offline unit tests for Lakala v3 SACS (2396/1293/1296/1297) protocol primitives. */
class LakalaSacsProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 25, 10, 20, 30);
    private static final LocalDate LOG_DATE = LocalDate.of(2026, 11, 18);
    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
    }

    @Test
    void prepareQueryAmtProducesV3EnvelopeWithoutOutOrgCode() throws Exception {
        var req = LakalaSacsProtocol.prepareQueryAmt(
                new QueryAmtInput(AT, "82229005943096D", "66210306990190", LOG_DATE));
        assertEquals(LakalaSacsProtocol.QUERY_AMT_PATH, req.path());
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals("20261125102030", root.path("req_time").asText());
        assertEquals("3.0", root.path("version").asText());
        assertFalse(root.has("out_org_code"));
        assertEquals("82229005943096D", root.path("req_data").path("merchant_no").asText());
        assertEquals("66210306990190", root.path("req_data").path("log_no").asText());
        assertEquals("20261118", root.path("req_data").path("log_date").asText());
    }

    @Test
    void prepareSeparateBuildsFixedAmountRecvDatasAndValidatesSum() throws Exception {
        var req = LakalaSacsProtocol.prepareSeparate(new SeparateInput(
                AT, "82229005943096D", "66210306990190", LOG_DATE, "SP10001", 9962L,
                "1950836", "https://api.example.com/callbacks/lakala/separate",
                List.of(ReceiverShare.toPlatform("82229005943096D", 22L),
                        ReceiverShare.toReceiver("SR202611250001", 9940L)),
                "order split"));
        assertEquals(LakalaSacsProtocol.SEPARATE_PATH, req.path());
        JsonNode data = JSON.readTree(req.rawBody()).path("req_data");
        assertEquals("0", data.path("cal_type").asText());
        assertEquals("9962", data.path("total_amt").asText());
        assertEquals("1950836", data.path("lkl_org_no").asText());
        assertEquals(2, data.path("recv_datas").size());
        assertEquals("82229005943096D", data.path("recv_datas").get(0).path("recv_merchant_no").asText());
        assertEquals("22", data.path("recv_datas").get(0).path("separate_value").asText());
        assertEquals("SR202611250001", data.path("recv_datas").get(1).path("recv_no").asText());
        assertEquals("9940", data.path("recv_datas").get(1).path("separate_value").asText());

        // Mismatched total vs recv_datas sum must be rejected.
        assertThrows(ProtocolException.class, () -> LakalaSacsProtocol.prepareSeparate(new SeparateInput(
                AT, "82229005943096D", "66210306990190", LOG_DATE, "SP10001", 9963L,
                null, null,
                List.of(ReceiverShare.toReceiver("SR202611250001", 9962L)), null)));
        // Mismatched platform recv_merchant_no must be rejected.
        assertThrows(ProtocolException.class, () -> LakalaSacsProtocol.prepareSeparate(new SeparateInput(
                AT, "82229005943096D", "66210306990190", LOG_DATE, "SP10001", 100L,
                null, null,
                List.of(ReceiverShare.toPlatform("OTHER_MERCHANT", 100L)), null)));
    }

    @Test
    void verifyQueryAmtAndSeparateSubmitResponses() throws Exception {
        byte[] amtBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_time\":\"20261125102031\","
                + "\"resp_data\":{\"merchant_no\":\"82229005943096D\",\"log_no\":\"66210306990190\","
                + "\"log_date\":\"20261118\",\"total_separate_amt\":\"9962\",\"can_separate_amt\":\"9962\"}}")
                .getBytes(StandardCharsets.UTF_8);
        QueryAmtResult amt = LakalaSacsProtocol.verifyQueryAmtResponse(responseHeaders(amtBody), amtBody,
                "app-1", "serial-1", keys.getPublic(), "82229005943096D", "66210306990190", LOG_DATE);
        assertEquals(9962L, amt.totalSeparateCents());
        assertEquals(9962L, amt.canSeparateCents());

        byte[] sepBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_time\":\"20261125102032\","
                + "\"resp_data\":{\"separate_no\":\"202611257701880001\",\"out_separate_no\":\"SP10001\","
                + "\"status\":\"PROCESSING\",\"log_no\":\"66210306990190\",\"log_date\":\"20261118\","
                + "\"total_amt\":\"9962\"}}").getBytes(StandardCharsets.UTF_8);
        SeparateSubmitResult sub = LakalaSacsProtocol.verifySeparateResponse(responseHeaders(sepBody), sepBody,
                "app-1", "serial-1", keys.getPublic(), "SP10001");
        assertEquals("202611257701880001", sub.separateNo());
        assertEquals(SplitStatus.PROCESSING, sub.status());
    }

    @Test
    void verifyQueryPlusHandlesNumericDetailAmountsAndCamelCaseFailureRespData() throws Exception {
        // 1296 success response with numeric detail_datas[].amt and fee fields.
        byte[] okBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_time\":\"20261125102100\","
                + "\"resp_data\":{\"separate_no\":\"202611257701880001\",\"out_separate_no\":\"SP10001\","
                + "\"cmd_type\":\"SEPARATE\",\"log_no\":\"66210306990190\",\"log_date\":\"20261118\","
                + "\"cal_type\":\"0\",\"finish_date\":\"20261125\",\"total_amt\":\"9962\","
                + "\"status\":\"SUCCESS\",\"final_status\":\"SUCCESS\",\"actual_separate_amt\":\"9960\","
                + "\"total_fee_amt\":\"2\",\"detail_datas\":["
                + "{\"recv_no\":\"82229005943096D\",\"amt\":22,\"actual_amt\":22,\"fee_amt\":0},"
                + "{\"recv_no\":\"SR202611250001\",\"amt\":9940,\"actual_amt\":9938,\"fee_amt\":2}"
                + "]}}").getBytes(StandardCharsets.UTF_8);
        ExpectedSplit expected = new ExpectedSplit("SP10001", "202611257701880001",
                CmdType.SEPARATE, "66210306990190", LOG_DATE);
        SplitOutcome ok = LakalaSacsProtocol.verifyQueryPlusResponse(responseHeaders(okBody), okBody,
                "app-1", "serial-1", keys.getPublic(), expected);
        assertTrue(ok.isSuccess());
        assertEquals(9960L, ok.actualSeparateCents());
        assertEquals(2L, ok.totalFeeCents());
        assertEquals(LocalDate.of(2026, 11, 25), ok.finishDate());
        var recv = ok.findReceiverDetail("SR202611250001").orElseThrow();
        assertEquals(9940L, recv.amountCents());
        assertEquals(9938L, recv.actualAmountCents());
        assertEquals(2L, recv.feeAmountCents());

        // 1296 failure example from official PDF page 5: camelCase respData + SACS0000 + FAIL + acc_result_desc.
        byte[] failBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"respTime\":\"20261125102105\","
                + "\"respData\":{\"separate_no\":\"202611257701880002\",\"out_separate_no\":\"SP10002\","
                + "\"status\":\"FAIL\",\"final_status\":\"FAIL\",\"cmd_type\":\"SEPARATE\","
                + "\"log_no\":\"66210306990190\",\"log_date\":\"20261118\",\"total_amt\":\"9962\","
                + "\"acc_result_desc\":\"账户端处理失败[可用余额不足]\"}}").getBytes(StandardCharsets.UTF_8);
        SplitOutcome fail = LakalaSacsProtocol.verifyQueryPlusResponse(responseHeaders(failBody), failBody,
                "app-1", "serial-1", keys.getPublic(),
                new ExpectedSplit("SP10002", null, CmdType.SEPARATE, "66210306990190", LOG_DATE));
        assertTrue(fail.isFail());
        assertEquals("账户端处理失败[可用余额不足]", fail.accResultDesc());
    }

    @Test
    void verifyNotificationSupports1297SampleWithoutFinalStatusAndRejectsBadSignature() throws Exception {
        byte[] body = ("{\"separate_no\":\"202611257701880001\",\"out_separate_no\":\"SP10001\","
                + "\"cmd_type\":\"SEPARATE\",\"log_no\":\"66210306990190\",\"log_date\":\"20261118\","
                + "\"cal_type\":\"0\",\"separate_type\":\"1\",\"total_amt\":\"9962\",\"status\":\"SUCCESS\","
                + "\"detail_datas\":[{\"recv_no\":\"SR202611250001\",\"amt\":\"9940\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        SplitOutcome outcome = LakalaSacsProtocol.verifyNotification(noticeHeaders(body), body, keys.getPublic());
        assertTrue(outcome.isSuccess());
        assertNull(outcome.rawFinalStatus());
        assertEquals(9940L, outcome.findReceiverDetail("SR202611250001").orElseThrow().amountCents());

        // Tampered body must fail signature verification.
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("9940", "9941")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(LakalaProtocol.ProtocolException.class,
                () -> LakalaSacsProtocol.verifyNotification(noticeHeaders(body), tampered, keys.getPublic()));
    }

    @Test
    void prepareQueryPlusRequiresAtLeastOneSeparateIdentifier() throws Exception {
        assertThrows(ProtocolException.class, () -> LakalaSacsProtocol.prepareQueryPlus(
                new QueryPlusInput(AT, "82229005943096D", "", null)));
        var req = LakalaSacsProtocol.prepareQueryPlus(
                new QueryPlusInput(AT, "82229005943096D", null, "SP10001"));
        assertEquals(LakalaSacsProtocol.QUERY_PLUS_PATH, req.path());
        JsonNode data = JSON.readTree(req.rawBody()).path("req_data");
        assertEquals("SP10001", data.path("out_separate_no").asText());
        assertFalse(data.has("separate_no"));
    }

    @Test
    void prepareAndVerifyFallback2395AndFallbackOutcomes() throws Exception {
        var req = LakalaSacsProtocol.prepareFallback(new FallbackInput(
                AT, "82229005943096D", "202611257701880001", "SP10001",
                "FB20001", 2982L, "SR202611250001", "REFUND:9001"));
        assertEquals(LakalaSacsProtocol.FALLBACK_PATH, req.path());
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals("3.0", root.path("version").asText());
        assertFalse(root.has("out_org_code"));
        JsonNode data = root.path("req_data");
        assertEquals("82229005943096D", data.path("merchant_no").asText());
        assertEquals("202611257701880001", data.path("origin_separate_no").asText());
        assertEquals("SP10001", data.path("origin_out_separate_no").asText());
        assertEquals("FB20001", data.path("out_separate_no").asText());
        assertEquals("2982", data.path("total_amt").asText());
        assertEquals("REFUND:9001", data.path("fallback_reason").asText());
        assertEquals(1, data.path("origin_recv_datas").size());
        assertEquals("SR202611250001", data.path("origin_recv_datas").get(0).path("recv_no").asText());
        assertEquals("2982", data.path("origin_recv_datas").get(0).path("amt").asText());

        // Missing both origin refs or non-positive amount must fail.
        assertThrows(ProtocolException.class, () -> LakalaSacsProtocol.prepareFallback(
                new FallbackInput(AT, "82229005943096D", null, "", "FB20001", 100L, "SR1", null)));
        assertThrows(ProtocolException.class, () -> LakalaSacsProtocol.prepareFallback(
                new FallbackInput(AT, "82229005943096D", "SEP1", null, "FB20001", 0L, "SR1", null)));

        // Verify 2395 response.
        byte[] respBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_time\":\"20261125102031\","
                + "\"resp_data\":{\"total_amt\":\"2982\",\"separate_no\":\"202611257702880001\","
                + "\"origin_separate_no\":\"202611257701880001\",\"out_separate_no\":\"FB20001\","
                + "\"status\":\"PROCESSING\"}}").getBytes(StandardCharsets.UTF_8);
        FallbackSubmitResult fb = LakalaSacsProtocol.verifyFallbackResponse(
                responseHeaders(respBody), respBody, "app-1", "serial-1", keys.getPublic(), "FB20001", 2982L);
        assertEquals("202611257702880001", fb.separateNo());
        assertEquals(2982L, fb.totalAmountCents());
        assertEquals(SplitStatus.PROCESSING, fb.status());

        // Mismatched total_amt in 2395 response must be rejected.
        assertThrows(ProtocolException.class, () -> LakalaSacsProtocol.verifyFallbackResponse(
                responseHeaders(respBody), respBody, "app-1", "serial-1", keys.getPublic(), "FB20001", 2983L));

        // 1296 official FALLBACK insufficient-balance failure sample (PDF p.5).
        byte[] fbFail = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"respTime\":\"20261125102105\","
                + "\"respData\":{\"separate_no\":\"202611257702880001\",\"out_separate_no\":\"FB20001\","
                + "\"status\":\"FAIL\",\"final_status\":\"FAIL\",\"cmd_type\":\"FALLBACK\","
                + "\"log_no\":\"66210306990190\",\"log_date\":\"20261118\",\"total_amt\":\"2982\","
                + "\"actual_separate_amt\":\"2982\",\"total_fee_amt\":\"0\","
                + "\"acc_result_desc\":\"账户端处理失败[可用余额不足]\"}}").getBytes(StandardCharsets.UTF_8);
        SplitOutcome out = LakalaSacsProtocol.verifyQueryPlusResponse(
                responseHeaders(fbFail), fbFail, "app-1", "serial-1", keys.getPublic(),
                new ExpectedSplit("FB20001", "202611257702880001", CmdType.FALLBACK, "66210306990190", LOG_DATE));
        assertEquals(CmdType.FALLBACK, out.cmdType());
        assertTrue(out.isFail());
        assertEquals("账户端处理失败[可用余额不足]", out.accResultDesc());
    }

    private static Map<String, String> responseHeaders(byte[] body) throws Exception {
        String time = "1764037230", nonce = "AbCd12345678";
        return Map.of("Lklapi-Appid", "app-1", "Lklapi-Serial", "serial-1",
                "Lklapi-Timestamp", time, "Lklapi-Nonce", nonce,
                "Lklapi-Signature", sign(concat("app-1\nserial-1\n" + time + "\n" + nonce + "\n", body)));
    }

    private static Map<String, String> noticeHeaders(byte[] body) throws Exception {
        String time = "1764037230", nonce = "AbCd12345678";
        return Map.of("Authorization", "LKLAPI-SHA256withRSA timestamp=\"" + time
                + "\", nonce_str=\"" + nonce + "\", signature=\""
                + sign(concat(time + "\n" + nonce + "\n", body)) + "\"");
    }

    private static String sign(byte[] message) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(message);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static byte[] concat(String prefix, byte[] body) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[p.length + body.length + 1];
        System.arraycopy(p, 0, out, 0, p.length);
        System.arraycopy(body, 0, out, p.length, body.length);
        out[out.length - 1] = '\n';
        return out;
    }
}
