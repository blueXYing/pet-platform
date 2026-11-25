package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.AttachItem;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.BindAuditStatus;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.BindNotification;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ListRelationApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ModifyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.PreparedV2Request;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ProtocolException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.QueryReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.V2Envelope;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Offline protocol tests for {@link LakalaV2Protocol} (1084/1287/1288/1289/1290/1872 + v2 envelope). */
class LakalaV2ProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TS = 1764039600123L;
    private static final String REQ_ID = "baff59de4694438ca9089862253517a5";
    private static KeyPair notifyKeys;

    @BeforeAll
    static void initKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        notifyKeys = gen.generateKeyPair();
    }

    @Test
    void v2EnvelopeFormatAndParsingMatchOfficialSpec() throws Exception {
        PreparedV2Request req = LakalaV2Protocol.buildV2Request(
                LakalaV2Protocol.EWALLET_BALANCE_QUERY_PATH,
                Map.of("orgNo", "1", "merchantNo", "SR202611250001"),
                TS, REQ_ID);
        assertEquals("/api/v2/laep/industry/ewalletBalanceQuery", req.path());
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals("1.0.0", root.path("ver").textValue());
        assertEquals(Long.toString(TS), root.path("timestamp").textValue());
        assertEquals(REQ_ID, root.path("reqId").textValue());
        assertEquals("SR202611250001", root.path("reqData").path("merchantNo").textValue());

        assertThrows(ProtocolException.class, () -> LakalaV2Protocol.buildV2Request(
                "/api/v3/sacs/separate", Map.of(), TS, REQ_ID));
        assertThrows(ProtocolException.class, () -> LakalaV2Protocol.buildV2Request(
                LakalaV2Protocol.UPLOAD_FILE_PATH, Map.of(), TS, "not-32-hex"));

        V2Envelope ok = LakalaV2Protocol.parseV2Envelope(
                "{\"retCode\":\"000000\",\"retMsg\":\"交易成功\",\"respData\":{\"curBalance\":\"12.34\"}}"
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(ok.isSuccess());
        assertEquals("12.34", ok.respData().path("curBalance").textValue());

        V2Envelope rejected = LakalaV2Protocol.parseV2Envelope(
                "{\"retCode\":\"999001\",\"retMsg\":\"余额不足\"}".getBytes(StandardCharsets.UTF_8));
        assertFalse(rejected.isSuccess());
        assertEquals("999001", rejected.retCode());
        assertEquals("余额不足", rejected.retMsg());
    }

    @Test
    void uploadFile1084ValidatesExtSizeAndParsesAttFileId() throws Exception {
        byte[] content = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
        PreparedV2Request req = LakalaV2Protocol.prepareUploadFile(
                new UploadFileInput("2026112512000012345678", "200669", "ID_CARD_FRONT", "JPEG", content),
                TS, REQ_ID);
        assertEquals("/api/v2/mms/openApi/uploadFile", req.path());
        JsonNode data = JSON.readTree(req.rawBody()).path("reqData");
        assertEquals("1.0", data.path("version").textValue());
        assertEquals("jpg", data.path("attExtName").textValue());
        assertEquals(Base64.getEncoder().encodeToString(content), data.path("attContext").textValue());

        assertThrows(ProtocolException.class, () -> LakalaV2Protocol.prepareUploadFile(
                new UploadFileInput("2026112512000012345678", "200669", "ID_CARD_FRONT", "exe", content),
                TS, REQ_ID));
        assertThrows(ProtocolException.class, () -> new UploadFileInput(
                "2026112512000012345678", "200669", "ID_CARD_FRONT", "png",
                new byte[LakalaV2Protocol.MAX_ATTACHMENT_BYTES + 1]));

        JsonNode respData = JSON.readTree(
                "{\"orgCode\":\"200669\",\"orderNo\":\"2026112512000012345678\","
                        + "\"attType\":\"ID_CARD_FRONT\",\"attFileId\":\"G1/M00/06/64/file.jpg\"}");
        var res = LakalaV2Protocol.parseUploadFileRespData(respData, "2026112512000012345678", "ID_CARD_FRONT");
        assertEquals("G1/M00/06/64/file.jpg", res.attFileId());
    }

    @Test
    void apply1287AndModify1288AlwaysForceSettleType01() throws Exception {
        var att = new AttachItem("ID_CARD_FRONT", "id_front.jpg", "G1/M00/01/id.jpg");
        PreparedV2Request createReq = LakalaV2Protocol.prepareApplyReceiver(
                new ApplyReceiverInput(
                        "2026112512000012345678", "200669", "萌宠测试店", "13800138000",
                        null, null, null, null, null,
                        "6222021234567894837", "张三", "58", "17", "310101199001011234",
                        "102100099996", "中国工商银行", "102100099996", List.of(att)),
                TS, REQ_ID);
        assertEquals("/api/v2/mms/openApi/ledger/applyLedgerReceiver", createReq.path());
        JsonNode createData = JSON.readTree(createReq.rawBody()).path("reqData");
        // Critical Ruling 40 §5.4 requirement: settleType MUST be "01".
        assertEquals("01", createData.path("settleType").textValue());
        assertEquals("58", createData.path("acctTypeCode").textValue());
        assertEquals("G1/M00/01/id.jpg", createData.path("attachList").get(0).path("attachStorePath").textValue());

        // Corporate account ("57") without licenseNo must fail validation.
        assertThrows(ProtocolException.class, () -> LakalaV2Protocol.prepareApplyReceiver(
                new ApplyReceiverInput(
                        "2026112512000012345678", "200669", "萌宠企业店", "13800138000",
                        null, null, null, null, null,
                        "6222021234567894837", "萌宠有限公司", "57", "17", "310101199001011234",
                        "102100099996", "中国工商银行", "102100099996", List.of()),
                TS, REQ_ID));

        PreparedV2Request modReq = LakalaV2Protocol.prepareModifyReceiver(
                new ModifyReceiverInput(
                        "2026112512050012345678", "200669", "SR202611250001",
                        "萌宠测试店改", "13900139000", "6222029999999991234", "58",
                        "102100099996", "中国工商银行", "102100099996", List.of(att), null),
                TS, REQ_ID);
        assertEquals("/api/v2/mms/openApi/ledger/modifyLedgerReceiver", modReq.path());
        assertEquals("01", JSON.readTree(modReq.rawBody()).path("reqData").path("settleType").textValue());

        JsonNode createResp = JSON.readTree(
                "{\"version\":\"1.0\",\"orderNo\":\"2026112512000012345678\","
                        + "\"orgCode\":\"200669\",\"orgId\":\"200669\",\"orgName\":\"测试机构\","
                        + "\"receiverNo\":\"SR202611250001\"}");
        var created = LakalaV2Protocol.parseApplyReceiverRespData(createResp, "2026112512000012345678");
        assertEquals("SR202611250001", created.receiverNo());
    }

    @Test
    void query1289Bind1290AndList1872MatchOfficialSchemas() throws Exception {
        PreparedV2Request qReq = LakalaV2Protocol.prepareQueryReceiver(
                new QueryReceiverInput("2026112512100012345678", "200669", "SR202611250001"), TS, REQ_ID);
        assertEquals("/api/v2/mms/openApi/ledger/queryReceiverDetail", qReq.path());

        PreparedV2Request bindReq = LakalaV2Protocol.prepareApplyBind(
                new ApplyBindInput("2026112512150012345678", "200669", null, "82229005943096D",
                        "SR202611250001", "合作协议.pdf", "G1/M00/00/16/coop.pdf",
                        "https://example.invalid/api/v1/callbacks/lakala/ledger", List.of()),
                TS, REQ_ID);
        assertEquals("/api/v2/mms/openApi/ledger/applyBind", bindReq.path());
        JsonNode bindData = JSON.readTree(bindReq.rawBody()).path("reqData");
        assertEquals("82229005943096D", bindData.path("merCupNo").textValue());
        assertEquals("合作协议.pdf", bindData.path("entrustFileName").textValue());

        // Missing both merInnerNo and merCupNo must fail.
        assertThrows(ProtocolException.class, () -> LakalaV2Protocol.prepareApplyBind(
                new ApplyBindInput("2026112512150012345678", "200669", null, null,
                        "SR202611250001", "合作协议.pdf", "G1/M00/00/16/coop.pdf",
                        "https://example.invalid/cb", List.of()), TS, REQ_ID));

        // 1290 respData applyId as numeric JSON Long (official PDF page 4).
        var bindRes = LakalaV2Protocol.parseApplyBindRespData(
                JSON.readTree("{\"version\":\"1.0\",\"orderNo\":\"2026112512150012345678\","
                        + "\"orgCode\":\"200669\",\"applyId\":548099616395186176}"),
                "2026112512150012345678");
        assertEquals("548099616395186176", bindRes.applyId());

        // 1872 request serializes numeric applyId as JSON Long and parses auditStatus 0/1/2.
        PreparedV2Request listReq = LakalaV2Protocol.prepareListRelationApply(
                new ListRelationApplyInput("2026112512200012345678", "200669", null, "82229005943096D",
                        "SR202611250001", "548099616395186176", "ADD", null, 10, 1),
                TS, REQ_ID);
        assertEquals("/api/v2/mms/openApi/ledger/openWeb/listRelationApply", listReq.path());
        assertTrue(JSON.readTree(listReq.rawBody()).path("reqData").path("applyId").isIntegralNumber());

        JsonNode listResp = JSON.readTree(
                "{\"total\":2,\"pages\":1,\"pageSize\":10,\"pageNum\":1,\"list\":["
                        + "{\"applyId\":548099616395186176,\"optType\":\"ADD\",\"receiverNo\":\"SR202611250001\","
                        + "\"auditStatus\":\"0\",\"remark\":\"审核中\"},"
                        + "{\"applyId\":548099616395186177,\"optType\":\"ADD\",\"receiverNo\":\"SR202611250002\","
                        + "\"auditStatus\":\"2\",\"remark\":\"协议缺失\"}]}");
        var page = LakalaV2Protocol.parseListRelationApplyRespData(listResp);
        assertEquals(2, page.list().size());
        assertEquals(BindAuditStatus.PENDING, page.list().get(0).auditStatus());
        assertEquals(BindAuditStatus.REJECTED, page.list().get(1).auditStatus());
        assertEquals("协议缺失", page.list().get(1).remark());
    }

    @Test
    void bindNotification1290Verifies1009SignatureAndRejectsTampering() throws Exception {
        byte[] approvedBody = ("{\"respData\":{\"optType\":\"ADD\",\"applyId\":547055274822594560,"
                + "\"merCupNo\":\"82229005943096D\",\"receiverNo\":\"SR202611250001\","
                + "\"auditStatus\":\"1\",\"auditStatusText\":\"审核通过\",\"remark\":\"通过\"}}")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signNotification(approvedBody);
        BindNotification parsed = LakalaV2Protocol.verifyBindNotification(
                headers, approvedBody, notifyKeys.getPublic());
        assertEquals("547055274822594560", parsed.applyId());
        assertEquals("SR202611250001", parsed.receiverNo());
        assertEquals(BindAuditStatus.APPROVED, parsed.auditStatus());

        // Tampered body must fail 1009 signature verification.
        byte[] tampered = new String(approvedBody, StandardCharsets.UTF_8)
                .replace("\"1\"", "\"2\"").getBytes(StandardCharsets.UTF_8);
        assertThrows(LakalaProtocol.ProtocolException.class, () -> LakalaV2Protocol.verifyBindNotification(
                headers, tampered, notifyKeys.getPublic()));

        // Callback with pending auditStatus "0" is invalid for 1290 terminal callback.
        byte[] pendingBody = ("{\"respData\":{\"applyId\":547055274822594560,"
                + "\"receiverNo\":\"SR202611250001\",\"auditStatus\":\"0\"}}").getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> LakalaV2Protocol.verifyBindNotification(
                signNotification(pendingBody), pendingBody, notifyKeys.getPublic()));
    }

    static Map<String, String> signNotification(byte[] body) throws Exception {
        String ts = "1764039600";
        String nonce = "AbCd12345678";
        byte[] prefix = (ts + "\n" + nonce + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] message = new byte[prefix.length + body.length + 1];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        message[message.length - 1] = '\n';
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(notifyKeys.getPrivate());
        sig.update(message);
        String auth = "LKLAPI-SHA256withRSA timestamp=\"" + ts + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + Base64.getEncoder().encodeToString(sig.sign()) + "\"";
        return Map.of("Authorization", auth);
    }
}
