package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Credentials;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Environment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.WireResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.ChannelRejectedException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.Reason;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.BindAuditStatus;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ListRelationApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ModifyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.QueryReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Offline wire-level tests for {@link LakalaV2HttpClient} (Stage 4 MMS + Stage 5 LAEP reuse). */
class LakalaV2HttpClientTest {
    private static final long TS = 1764039600123L;
    private static final String REQ_ID = "0123456789abcdef0123456789abcdef";
    private static final RequestNonce NONCE = new RequestNonce("1764039600", "AbCd12345678");
    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
    }

    @Test
    void allMmsEndpointsAndStage5PostV2HitOfficialUrisWithSigned1004Exchange() throws Exception {
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();

        // 1084 uploadFile
        byte[] upBody = ("{\"retCode\":\"000000\",\"retMsg\":\"成功\",\"respData\":{"
                + "\"orgCode\":\"200669\",\"orderNo\":\"ORD01\",\"attType\":\"BANK_CARD\","
                + "\"attFileId\":\"G1/M00/01/bank.png\"}}").getBytes(StandardCharsets.UTF_8);
        var upRes = client(Environment.SIT, req -> {
            uri.set(req.uri().toString());
            authHeader.set(req.headers().firstValue("Authorization").orElse(""));
            return signed200(upBody);
        }).uploadFile(new UploadFileInput("ORD01", "200669", "BANK_CARD", "png",
                "img".getBytes(StandardCharsets.UTF_8)), TS, REQ_ID, NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v2/mms/openApi/uploadFile", uri.get());
        assertTrue(authHeader.get().startsWith("LKLAPI-SHA256withRSA appid=\"APP01\",serial_no=\"MSERIAL01\""));
        assertEquals("G1/M00/01/bank.png", upRes.result().attFileId());

        // 1287 applyLedgerReceiver (PRODUCTION URI check)
        byte[] createBody = ("{\"retCode\":\"000000\",\"retMsg\":\"成功\",\"respData\":{"
                + "\"orderNo\":\"ORD01\",\"orgCode\":\"200669\",\"receiverNo\":\"SR202611250001\"}}")
                .getBytes(StandardCharsets.UTF_8);
        var createRes = client(Environment.PRODUCTION, req -> {
            uri.set(req.uri().toString());
            return signed200(createBody);
        }).applyLedgerReceiver(new ApplyReceiverInput("ORD01", "200669", "萌宠店", "13800138000",
                null, null, null, null, null, "6222021234567894837", "张三", "58", "17",
                "310101199001011234", "102100099996", "工商银行", "102100099996", List.of()),
                TS, REQ_ID, NONCE);
        assertEquals("https://s2.lakala.com/api/v2/mms/openApi/ledger/applyLedgerReceiver", uri.get());
        assertEquals("SR202611250001", createRes.result().receiverNo());

        // 1288 modifyLedgerReceiver
        byte[] modBody = ("{\"retCode\":\"000000\",\"retMsg\":\"成功\",\"respData\":{"
                + "\"orderNo\":\"ORD02\",\"orgCode\":\"200669\",\"receiverNo\":\"SR202611250001\"}}")
                .getBytes(StandardCharsets.UTF_8);
        var modRes = client(Environment.SIT, req -> {
            uri.set(req.uri().toString());
            return signed200(modBody);
        }).modifyLedgerReceiver(new ModifyReceiverInput("ORD02", "200669", "SR202611250001",
                "萌宠新店名", null, null, null, null, null, null, List.of(), null), TS, REQ_ID, NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v2/mms/openApi/ledger/modifyLedgerReceiver", uri.get());
        assertEquals("SR202611250001", modRes.result().receiverNo());

        // 1289 queryReceiverDetail
        byte[] qBody = ("{\"retCode\":\"000000\",\"retMsg\":\"成功\",\"respData\":{"
                + "\"receiverNo\":\"SR202611250001\",\"receiverName\":\"萌宠新店名\"}}")
                .getBytes(StandardCharsets.UTF_8);
        var qRes = client(Environment.SIT, req -> {
            uri.set(req.uri().toString());
            return signed200(qBody);
        }).queryReceiverDetail(new QueryReceiverInput("ORD03", "200669", "SR202611250001"), TS, REQ_ID, NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v2/mms/openApi/ledger/queryReceiverDetail", uri.get());
        assertEquals("萌宠新店名", qRes.result().receiverName());

        // 1290 applyBind
        byte[] bindBody = ("{\"retCode\":\"000000\",\"retMsg\":\"申请已受理\",\"respData\":{"
                + "\"orderNo\":\"ORD04\",\"orgCode\":\"200669\",\"applyId\":548099616395186176}}")
                .getBytes(StandardCharsets.UTF_8);
        var bindRes = client(Environment.SIT, req -> {
            uri.set(req.uri().toString());
            return signed200(bindBody);
        }).applyBind(new ApplyBindInput("ORD04", "200669", null, "82229005943096D",
                "SR202611250001", "coop.pdf", "G1/M00/coop.pdf",
                "https://notify.invalid/ledger", List.of()), TS, REQ_ID, NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v2/mms/openApi/ledger/applyBind", uri.get());
        assertEquals("548099616395186176", bindRes.result().applyId());

        // 1872 listRelationApply
        byte[] listBody = ("{\"retCode\":\"000000\",\"retMsg\":\"成功\",\"respData\":{"
                + "\"total\":1,\"pages\":1,\"pageSize\":10,\"pageNum\":1,\"list\":[{"
                + "\"applyId\":548099616395186176,\"optType\":\"ADD\",\"receiverNo\":\"SR202611250001\","
                + "\"auditStatus\":\"1\",\"remark\":\"通过\"}]}}").getBytes(StandardCharsets.UTF_8);
        var listRes = client(Environment.SIT, req -> {
            uri.set(req.uri().toString());
            return signed200(listBody);
        }).listRelationApply(new ListRelationApplyInput("ORD05", "200669", null, "82229005943096D",
                "SR202611250001", "548099616395186176", "ADD", null, 10, 1), TS, REQ_ID, NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v2/mms/openApi/ledger/openWeb/listRelationApply", uri.get());
        assertEquals(BindAuditStatus.APPROVED, listRes.result().list().get(0).auditStatus());

        // Stage 5 reusable postV2 (1269 balance query)
        byte[] balBody = ("{\"retCode\":\"000000\",\"retMsg\":\"成功\",\"respData\":{"
                + "\"curBalance\":\"406.37\",\"acctSt\":\"NORMAL\"}}").getBytes(StandardCharsets.UTF_8);
        var balRes = client(Environment.SIT, req -> {
            uri.set(req.uri().toString());
            return signed200(balBody);
        }).postV2(LakalaV2Protocol.EWALLET_BALANCE_QUERY_PATH,
                Map.of("orgNo", "1", "merchantNo", "SR202611250001", "payType", "04"),
                TS, REQ_ID, NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v2/laep/industry/ewalletBalanceQuery", uri.get());
        assertTrue(balRes.isSuccess());
        assertEquals("406.37", balRes.respData().path("curBalance").textValue());
    }

    @Test
    void enforcesPathWhitelistNoTransactionSizeCapSignaturesAndBusinessReject() throws Exception {
        // Path whitelist rejection
        LakalaV2HttpClient dummy = client(Environment.SIT, req -> {
            throw new AssertionError("must not call exchange");
        });
        assertThrows(IllegalArgumentException.class, () -> dummy.postV2(
                "/api/v2/evil/endpoint", Map.of("k", "v"), TS, REQ_ID, NONCE));

        // Active transaction guard
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> dummy.postV2(
                    LakalaV2Protocol.EWALLET_BALANCE_QUERY_PATH, Map.of("orgNo", "1"), TS, REQ_ID, NONCE));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        // IO error -> Reason.IO
        ChannelUnknownException ioErr = assertThrows(ChannelUnknownException.class,
                () -> client(Environment.SIT, req -> {
                    throw new IOException("connect timeout");
                }).queryReceiverDetail(new QueryReceiverInput("ORD1", "200669", "SR1"), TS, REQ_ID, NONCE));
        assertEquals(Reason.IO, ioErr.reason());

        // Non-200 HTTP -> Reason.HTTP_STATUS
        byte[] empty = "{}".getBytes(StandardCharsets.UTF_8);
        ChannelUnknownException httpErr = assertThrows(ChannelUnknownException.class,
                () -> client(Environment.SIT, req -> new WireResponse(502, headers(empty),
                        new ByteArrayInputStream(empty)))
                        .queryReceiverDetail(new QueryReceiverInput("ORD1", "200669", "SR1"), TS, REQ_ID, NONCE));
        assertEquals(Reason.HTTP_STATUS, httpErr.reason());

        // Oversized response (> 65,536 bytes) -> Reason.RESPONSE_TOO_LARGE
        byte[] huge = new byte[65_537];
        ChannelUnknownException largeErr = assertThrows(ChannelUnknownException.class,
                () -> client(Environment.SIT, req -> new WireResponse(200, headers(empty),
                        new ByteArrayInputStream(huge)))
                        .queryReceiverDetail(new QueryReceiverInput("ORD1", "200669", "SR1"), TS, REQ_ID, NONCE));
        assertEquals(Reason.RESPONSE_TOO_LARGE, largeErr.reason());

        // Bad signature -> Reason.RESPONSE_SIGNATURE
        byte[] okBody = "{\"retCode\":\"000000\",\"respData\":{\"receiverNo\":\"SR1\"}}".getBytes(StandardCharsets.UTF_8);
        ChannelUnknownException sigErr = assertThrows(ChannelUnknownException.class,
                () -> client(Environment.SIT, req -> new WireResponse(200, headers(empty),
                        new ByteArrayInputStream(okBody)))
                        .queryReceiverDetail(new QueryReceiverInput("ORD1", "200669", "SR1"), TS, REQ_ID, NONCE));
        assertEquals(Reason.RESPONSE_SIGNATURE, sigErr.reason());

        // Signed non-000000 business rejection: postV2 returns envelope; typed MMS throws ChannelRejectedException.
        byte[] bizErr = "{\"retCode\":\"MMS4001\",\"retMsg\":\"银行卡四要素校验失败\"}".getBytes(StandardCharsets.UTF_8);
        var rawV2 = client(Environment.SIT, req -> signed200(bizErr)).postV2(
                LakalaV2Protocol.EWALLET_BALANCE_QUERY_PATH, Map.of("orgNo", "1"), TS, REQ_ID, NONCE);
        assertFalse(rawV2.isSuccess());
        assertEquals("MMS4001", rawV2.retCode());

        ChannelRejectedException rejected = assertThrows(ChannelRejectedException.class,
                () -> client(Environment.SIT, req -> signed200(bizErr))
                        .queryReceiverDetail(new QueryReceiverInput("ORD1", "200669", "SR1"), TS, REQ_ID, NONCE));
        assertEquals("MMS4001", rejected.retCode());
        assertEquals("银行卡四要素校验失败", rejected.retMsg());
    }

    private static LakalaV2HttpClient client(Environment env, LakalaHttpClient.Exchange exchange) {
        Credentials creds = new Credentials("APP01", "MSERIAL01", keys.getPrivate(),
                "PSERIAL01", keys.getPublic());
        return new LakalaV2HttpClient(env, creds, exchange);
    }

    private static WireResponse signed200(byte[] body) {
        return new WireResponse(200, headers(body), new ByteArrayInputStream(body));
    }

    private static HttpHeaders headers(byte[] body) {
        try {
            String ts = "1764039600";
            String nonce = "ZzYy12345678";
            byte[] prefix = ("APP01\nPSERIAL01\n" + ts + "\n" + nonce + "\n").getBytes(StandardCharsets.US_ASCII);
            byte[] msg = new byte[prefix.length + body.length + 1];
            System.arraycopy(prefix, 0, msg, 0, prefix.length);
            System.arraycopy(body, 0, msg, prefix.length, body.length);
            msg[msg.length - 1] = '\n';
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(keys.getPrivate());
            sig.update(msg);
            Map<String, List<String>> map = new LinkedHashMap<>();
            map.put("Lklapi-Appid", List.of("APP01"));
            map.put("Lklapi-Serial", List.of("PSERIAL01"));
            map.put("Lklapi-Timestamp", List.of(ts));
            map.put("Lklapi-Nonce", List.of(nonce));
            map.put("Lklapi-Signature", List.of(Base64.getEncoder().encodeToString(sig.sign())));
            return HttpHeaders.of(map, (k, v) -> true);
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
    }
}
