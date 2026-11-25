package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.DrawState;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Input;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Result;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.PreparedV2Request;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Offline protocol tests for Lakala LAEP 1269/1319/1271/1272 (Stage 5). */
class LakalaLaepProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQ_ID = "bf167a53f4a34716b66e26a9a5d803a6";

    @Test
    void balanceQueryAlwaysSetsReceiverPayType04AndParsesYuanAmounts() throws Exception {
        PreparedV2Request req = LakalaLaepProtocol.prepareBalanceQuery(
                new BalanceQueryInput("1", "SR202611250001"), 1764050000000L, REQ_ID);
        assertEquals(LakalaV2Protocol.EWALLET_BALANCE_QUERY_PATH, req.path());
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals("1.0.0", root.path("ver").textValue());
        assertEquals("1", root.path("reqData").path("orgNo").textValue());
        assertEquals("SR202611250001", root.path("reqData").path("merchantNo").textValue());
        assertEquals("04", root.path("reqData").path("payType").textValue());

        JsonNode respData = JSON.readTree("""
                {"payNo":"02001012000000002645","payType":"04","acctSt":"NORMAL",
                 "forceBalance":"0.00","hisBalance":"406.37","reBalance":"406.37","curBalance":"406.37"}
                """);
        BalanceResult parsed = LakalaLaepProtocol.parseBalanceRespData(respData);
        assertTrue(parsed.isAccountNormal());
        assertEquals(new BigDecimal("406.37"), parsed.curBalance());
        assertEquals(new BigDecimal("0.00"), parsed.forceBalance());
    }

    @Test
    void withdrawD1FormatsYuanAndForcesReceiverPayType04() throws Exception {
        PreparedV2Request req = LakalaLaepProtocol.prepareWithdrawD1(
                new WithdrawD1Input("1", "SR202611250001", new BigDecimal("100.5"),
                        "WD1001", "https://api.example.com/api/v1/callbacks/lakala/withdrawals", "m-5001"),
                1764050000000L, REQ_ID);
        assertEquals(LakalaV2Protocol.EWALLET_WITHDRAW_D1_PATH, req.path());
        JsonNode data = JSON.readTree(req.rawBody()).path("reqData");
        assertEquals("100.50", data.path("drawAmt").textValue());
        assertEquals("04", data.path("payType").textValue());
        assertEquals("WD1001", data.path("merOrderNo").textValue());

        WithdrawD1Result res = LakalaLaepProtocol.parseWithdrawD1RespData(
                JSON.readTree("""
                        {"drawJnl":"220816141533426266212404","merOrderNo":"WD1001"}
                        """), "WD1001");
        assertEquals("220816141533426266212404", res.drawJnl());
        assertEquals("WD1001", res.merOrderNo());

        // Mismatched merOrderNo rejected.
        assertThrows(LakalaV2Protocol.ProtocolException.class, () -> LakalaLaepProtocol.parseWithdrawD1RespData(
                JSON.readTree("""
                        {"drawJnl":"220816141533426266212404","merOrderNo":"WRONG"}
                        """), "WD1001"));
        // Zero / >2 decimals rejected.
        assertThrows(LakalaV2Protocol.ProtocolException.class, () -> LakalaLaepProtocol.prepareWithdrawD1(
                new WithdrawD1Input("1", "SR1", BigDecimal.ZERO, "WD1", null, null), 1L, REQ_ID));
        assertThrows(LakalaV2Protocol.ProtocolException.class, () -> LakalaLaepProtocol.prepareWithdrawD1(
                new WithdrawD1Input("1", "SR1", new BigDecimal("1.234"), "WD1", null, null), 1L, REQ_ID));
    }

    @Test
    void queryAnd1272NotificationVerify1009SignatureAndComputeNetAmount() throws Exception {
        PreparedV2Request qReq = LakalaLaepProtocol.prepareWithdrawQuery(
                new WithdrawQueryInput("1", "SR202611250001", null, "WD1001"), 1764050000000L, REQ_ID);
        assertEquals(LakalaV2Protocol.EWALLET_WITHDRAW_QUERY_PATH, qReq.path());

        // 1271 response where merOrderNo is empty string (as shown in PDF 1271 example)
        WithdrawOutcome qOut = LakalaLaepProtocol.parseWithdrawOutcomeRespData(JSON.readTree("""
                {"drawJnl":"JNL1001","drawAmt":"100.00","drawFee":"1.50","drawMode":"D1",
                 "drawState":"DRAW.PROCESSING","memo":"处理中","merOrderNo":"","mercId":"SR202611250001"}
                """), "SR202611250001", "WD1001", "JNL1001");
        assertFalse(qOut.drawState().isTerminal());
        assertEquals(DrawState.PROCESSING, qOut.drawState());
        assertEquals(new BigDecimal("98.50"), qOut.netAmount());

        // 1272 signed callback (top-level JSON per PDF 1272 example)
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        byte[] body = """
                {"ewalletId":"112208441024","reqDate":"20261125","drawJnl":"JNL1001","drawAmt":"100.00",
                 "drawFee":"1.00","drawMode":"D1","batchAutoSettle":"01","drawState":"DRAW.SUCCESS",
                 "merOrderNo":"WD1001","settleNo":"ST001","mercId":"SR202611250001",
                 "completeTime":"2026-11-25 14:15:34","memo":"交易成功"}
                """.getBytes(StandardCharsets.UTF_8);
        String ts = "1764051000";
        String nonce = "abc123XYZ789";
        String msg = ts + "\n" + nonce + "\n" + new String(body, StandardCharsets.UTF_8) + "\n";
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(msg.getBytes(StandardCharsets.UTF_8));
        String auth = "LKLAPI-SHA256withRSA timestamp=\"" + ts + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + Base64.getEncoder().encodeToString(sig.sign()) + "\"";

        WithdrawOutcome notif = LakalaLaepProtocol.verifyWithdrawalNotification(
                Map.of("Authorization", auth), body, kp.getPublic());
        assertTrue(notif.drawState().isTerminal());
        assertEquals(DrawState.SUCCESS, notif.drawState());
        assertEquals(new BigDecimal("99.00"), notif.netAmount());
        assertEquals("ST001", notif.settleNo());

        // Tampered body must fail signature check.
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("100.00", "200.00")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(LakalaProtocol.ProtocolException.class, () -> LakalaLaepProtocol.verifyWithdrawalNotification(
                Map.of("Authorization", auth), tampered, kp.getPublic()));
    }
}
