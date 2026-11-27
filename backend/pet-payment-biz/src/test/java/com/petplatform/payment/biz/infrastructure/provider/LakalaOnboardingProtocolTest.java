package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.AddMerInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.ApplyLedgerInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.BusinessRejected;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.ContractInfo;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcContractText;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcEnvelope;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.FeeRate;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.FileItem;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.LedgerInfo;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.PreparedV2Request;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ProtocolException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Offline protocol tests for {@link LakalaOnboardingProtocol} and the envelope families of
 * {@link LakalaV2Protocol} (CCR-PAY-ONBOARD-001 §5), pinned to the authoritative Lakala documents.
 */
class LakalaOnboardingProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TS = 1764039600123L;
    private static final String REQ_ID = "baff59de4694438ca9089862253517a5";
    private static final String NOTIFY = "https://pet.example/api/v1/callbacks/lakala/onboard";

    @Test
    void ledgerFamilySendsReqIdVersionReqTimeAsOfficialExamplesShow() throws Exception {
        PreparedV2Request req = LakalaV2Protocol.prepareApplyReceiver(new ApplyReceiverInput(
                "KFPT20230223182029463089445", "1950836", "分账接收方测试008", "111111", "", "", "", "", "",
                "1111155555", "张三", "58", "17", "1111155555", "03050000", "民生银行", "03050000",
                List.of()), TS, REQ_ID);
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals(LakalaV2Protocol.APPLY_RECEIVER_PATH, req.path());
        assertEquals(REQ_ID, root.path("reqId").textValue());
        assertEquals("2.0", root.path("version").textValue());
        assertEquals("20251125110000", root.path("reqTime").textValue());
        assertEquals("1.0", root.path("reqData").path("version").textValue());
        assertEquals("KFPT20230223182029463089445", root.path("reqData").path("orderNo").textValue());
        assertFalse(root.has("ver"));
        assertFalse(root.has("timestamp"));
        assertEquals("20251125110000", DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
                .format(Instant.ofEpochMilli(TS).atZone(ZoneId.of("Asia/Shanghai"))));
    }

    @Test
    void mmsFamilyKeepsVerTimestampReqIdEnvelope() throws Exception {
        PreparedV2Request req = LakalaV2Protocol.prepareUploadFile(
                new UploadFileInput("1234567897312", "1", "BUSINESS_LICENCE", "png", new byte[] {1, 2, 3}),
                TS, REQ_ID);
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals("1.0.0", root.path("ver").textValue());
        assertEquals(Long.toString(TS), root.path("timestamp").textValue());
        assertEquals(REQ_ID, root.path("reqId").textValue());
        assertFalse(root.has("version"));
        assertFalse(root.has("reqTime"));

        assertThrows(ProtocolException.class, () -> LakalaV2Protocol.buildV2Request(
                "/api/v3/sacs/separate", Map.of(), TS, REQ_ID));
    }

    @Test
    void electronicContractApplyMatchesDocumentedSnakeCaseShape() throws Exception {
        EcContractText text = new EcContractText("示例科技有限公司", "宠物平台商家合作协议",
                "90.00", "示例科技有限公司", "2026/11/27", "张三");
        PreparedV2Request req = LakalaOnboardingProtocol.prepareEcApply(new EcApplyInput(
                "ec20261127110000001", "1950836", "张三", "342523199905298212", "13800001234",
                "91110108MA2G7LK368", "示例宠物医院", "102100000030", "中国工商银行北京市分行营业部",
                "58", "6212260200102400000", "张三", text, NOTIFY + "/ec"), TS, REQ_ID);
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals(LakalaV2Protocol.EC_APPLY_PATH, req.path());
        assertEquals("1.0.0", root.path("ver").textValue());
        assertEquals(Long.toString(TS), root.path("timestamp").textValue());
        assertEquals(REQ_ID, root.path("req_id").textValue());
        JsonNode data = root.path("req_data");
        assertEquals("1.0", data.path("version").textValue());
        assertEquals(1950836L, data.path("org_id").longValue());
        assertTrue(data.path("org_id").isIntegralNumber());
        assertEquals("EC011", data.path("ec_type_code").textValue());
        assertEquals("RESIDENT_ID", data.path("cert_type").textValue());
        assertEquals("张三", data.path("cert_name").textValue());
        assertEquals("13800001234", data.path("mobile").textValue());
        assertEquals("58", data.path("acct_type_code").textValue());
        assertEquals("WX_APPLET_FACE", data.path("sign_auth_type").textValue());
        assertEquals(NOTIFY + "/ec", data.path("ret_url").textValue());
        JsonNode domains = JSON.readTree(data.path("ec_content_parameters").textValue());
        assertEquals("示例科技有限公司", domains.path("E1").textValue());
        assertEquals("宠物平台商家合作协议", domains.path("E2").textValue());
        assertEquals("是", domains.path("E3").textValue());
        assertEquals("否", domains.path("E4").textValue());
        assertEquals("否", domains.path("E5").textValue());
        assertFalse(domains.has("E6"));
        assertEquals("是", domains.path("E7").textValue());
        assertEquals("90.00", domains.path("E8").textValue());
        assertEquals("示例科技有限公司", domains.path("E9").textValue());
        assertEquals("2026/11/27", domains.path("E10").textValue());
        assertEquals("张三", domains.path("E11").textValue());

        PreparedV2Request query = LakalaOnboardingProtocol.prepareEcQueryStatus(
                "ec20261127110000001", "1950836", "863797013367664640", TS, REQ_ID);
        JsonNode queryRoot = JSON.readTree(query.rawBody());
        assertEquals(LakalaV2Protocol.EC_QUERY_STATUS_PATH, query.path());
        assertEquals("req_data", queryRoot.fieldNames().next());
        assertEquals(863797013367664640L, queryRoot.path("req_data").path("ec_apply_id").longValue());
        assertTrue(queryRoot.path("req_data").path("ec_apply_id").isIntegralNumber());
        assertEquals("1950836", queryRoot.path("req_data").path("org_id").asText());

        assertThrows(ProtocolException.class, () -> LakalaOnboardingProtocol.prepareEcQueryStatus(
                "ec20261127110000001", "1950836", "9223372036854775808", TS, REQ_ID));
    }

    @Test
    void electronicContractResponsesAndNotificationAreParsed() throws Exception {
        EcEnvelope applied = LakalaOnboardingProtocol.parseEcEnvelope((
                "{\"code\":\"000000\",\"msg\":\"成功\",\"resp_data\":{\"order_no\":\"e0a1d6f8dbca4ee99dddf8a805778d37\","
                        + "\"ec_apply_id\":863797013367664640,\"result_url\":\"https://jrt.wsmsd.cn/signature/s/wl6392\"}}")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(applied.isSuccess());
        var applyResult = LakalaOnboardingProtocol.parseEcApplyRespData(
                applied.data(), "e0a1d6f8dbca4ee99dddf8a805778d37");
        assertEquals("863797013367664640", applyResult.ecApplyId());
        assertEquals("https://jrt.wsmsd.cn/signature/s/wl6392", applyResult.resultUrl());
        assertThrows(ProtocolException.class, () -> LakalaOnboardingProtocol.parseEcApplyRespData(
                applied.data(), "another-order"));

        String undone = "{\"code\":\"000000\",\"msg\":\"成功\",\"resp_data\":{\"order_no\":\"e0a1d6f8dbca4ee99dddf8a805778d37\","
                + "\"ec_apply_id\":863797013367664640,\"ec_status\":\"UNDONE\",\"ec_no\":\"\"}}";
        var pending = LakalaOnboardingProtocol.parseEcQueryRespData(
                LakalaOnboardingProtocol.parseEcEnvelope(undone.getBytes(StandardCharsets.UTF_8)).data(),
                "863797013367664640");
        assertEquals("UNDONE", pending.ecStatus());
        assertNull(pending.ecNo());
        assertFalse(pending.completed());

        BusinessRejected rejected = assertThrows(BusinessRejected.class, () -> {
            EcEnvelope envelope = LakalaOnboardingProtocol.parseEcEnvelope((
                    "{\"code\":\"087900\",\"message\":\"三四要素认证失败\"}").getBytes(StandardCharsets.UTF_8));
            if (!envelope.isSuccess()) throw new BusinessRejected(envelope.code(), envelope.message());
        });
        assertEquals("087900", rejected.code());
        assertEquals("三四要素认证失败", rejected.message());

        String notification = "{\"ecApplyId\":635798487769907200,\"ecName\":\"清分结算授权委托书\","
                + "\"ecNo\":\"QT20221021000157861\",\"ecStatus\":\"COMPLETED\",\"orderNo\":\"177212022102111161183863984\","
                + "\"orgId\":1,\"version\":\"1.0\"}";
        var signed = LakalaOnboardingProtocol.parseEcNotification(JSON.readTree(notification));
        assertTrue(signed.completed());
        assertEquals("QT20221021000157861", signed.ecNo());
        assertEquals("635798487769907200", signed.ecApplyId());
        assertThrows(ProtocolException.class, () -> LakalaOnboardingProtocol.parseEcNotification(
                JSON.readTree(notification.replace("\"QT20221021000157861\"", "\"\""))));
    }

    @Test
    void addMerCarriesEveryDocumentedFieldAndOmitsAbsentOptionals() throws Exception {
        AddMerInput input = addMer(true);
        PreparedV2Request req = LakalaOnboardingProtocol.prepareAddMer(input, TS, REQ_ID);
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals(LakalaV2Protocol.ADD_MER_PATH, req.path());
        assertEquals("1.0.0", root.path("ver").textValue());
        JsonNode data = root.path("reqData");
        assertEquals("1.0", data.path("version").textValue());
        assertEquals("2026112711000012345678", data.path("orderNo").textValue());
        assertEquals("CLOUD_DISTRIBUTION", data.path("posType").textValue());
        assertEquals("1950836", data.path("orgCode").textValue());
        assertEquals("示例宠物医院", data.path("merRegName").textValue());
        assertEquals("示例宠物医院高新店", data.path("merBizName").textValue());
        assertEquals("510104", data.path("merRegDistCode").textValue());
        assertEquals("天府大道1号", data.path("merRegAddr").textValue());
        assertEquals("0742", data.path("mccCode").textValue());
        assertEquals("654", data.path("merBusiContent").textValue());
        assertEquals("91110108MA2G7LK368", data.path("merBlis").textValue());
        assertEquals("2020-01-01", data.path("merBlisStDt").textValue());
        assertEquals("2040-01-01", data.path("merBlisExpDt").textValue());
        assertEquals("01", data.path("larIdType").textValue());
        assertEquals("342523199905298212", data.path("larIdcard").textValue());
        assertEquals("T+1", data.path("settlePeriod").textValue());
        assertEquals("58", data.path("acctTypeCode").textValue());
        assertEquals(NOTIFY, data.path("retUrl").textValue());
        assertEquals("QT20221021000157861", data.path("contractNo").textValue());
        // Non-legal-person settler: the resident-ID trio is present.
        assertEquals("01", data.path("acctIdType").textValue());
        assertEquals("510104199001011234", data.path("acctIdcard").textValue());
        assertEquals("2030-01-01", data.path("acctIdDt").textValue());
        assertEquals("302", data.path("feeData").get(0).path("feeRateTypeCode").textValue());
        assertEquals("0.60", data.path("feeData").get(0).path("feeRatePct").textValue());
        assertEquals(1, data.path("feeData").size());
        assertEquals("G1/M00/00/61/CrFdEl3IyceAVVd8AAA0ADuZsA0911.jpg",
                data.path("fileData").get(0).path("attFileId").textValue());
        assertEquals("ID_CARD_FRONT", data.path("fileData").get(0).path("attType").textValue());

        JsonNode sameAsLegal = JSON.readTree(LakalaOnboardingProtocol.prepareAddMer(addMer(false), TS, REQ_ID)
                .rawBody()).path("reqData");
        assertFalse(sameAsLegal.has("acctIdType"));
        assertFalse(sameAsLegal.has("acctIdcard"));
        assertFalse(sameAsLegal.has("acctIdDt"));
        assertFalse(sameAsLegal.has("merBizName"));
        assertFalse(sameAsLegal.has("contractNo"));

        AddMerInput longUrl = new AddMerInput("2026112711000012345678", "CLOUD_DISTRIBUTION", "1950836",
                "示例宠物医院", null, "510104", "天府大道1号", "0742", "示例宠物医院", "91110108MA2G7LK368",
                "2020-01-01", "2040-01-01", "654", "张三", "342523199905298212", "1999-05-29", "2039-05-29",
                "13900000000", "李四", "102100000030", "中国工商银行北京市分行营业部", "102100099996",
                "6212260200102400000", "张三", "58", null, null, "T+1",
                "https://pet-platform.nat100.top/api/v1/callbacks/lakala/onboarding/contract", null,
                List.of(new FeeRate("302", "微信", "0.60")),
                List.of(new FileItem("ID_CARD_FRONT", "G1/a.jpg")));
        assertThrows(ProtocolException.class,
                () -> LakalaOnboardingProtocol.prepareAddMer(longUrl, TS, REQ_ID));
    }

    @Test
    void addMerAndLedgerResponsesAreParsed() throws Exception {
        var added = LakalaOnboardingProtocol.parseAddMerRespData(JSON.readTree(
                "{\"orderNo\":\"2021031212000012345678\",\"orgCode\":\"1\",\"contractId\":\"202103152502679606\"}"),
                "2021031212000012345678");
        assertEquals("202103152502679606", added.contractId());
        assertThrows(ProtocolException.class, () -> LakalaOnboardingProtocol.parseAddMerRespData(
                JSON.readTree("{\"orderNo\":\"2021031212000012345678\",\"orgCode\":\"1\"}"),
                "2021031212000012345678"));

        PreparedV2Request ledger = LakalaOnboardingProtocol.prepareApplyLedgerMer(new ApplyLedgerInput(
                "KFPT20230223181025407788734", "1950836", "82229007011009E", "13900000000",
                new BigDecimal("90.5"), "QT20221021000157861", NOTIFY + "/ledger-open"), TS, REQ_ID);
        JsonNode root = JSON.readTree(ledger.rawBody());
        assertEquals("2.0", root.path("version").textValue());
        JsonNode data = root.path("reqData");
        assertEquals("82229007011009E", data.path("merCupNo").textValue());
        assertEquals("QT20221021000157861", data.path("eleContractNo").textValue());
        assertEquals("ALL", data.path("splitRange").textValue());
        assertEquals("TR", data.path("sepFundSource").textValue());
        assertEquals("MANUAL", data.path("splitLaunchMode").textValue());
        assertEquals("01", data.path("settleType").textValue());
        assertTrue(data.path("splitLowestRatio").isNumber());
        // The wire text must carry two decimals exactly as 1284 documents ("70 或 70.50").
        assertTrue(new String(ledger.rawBody(), StandardCharsets.UTF_8).contains("\"splitLowestRatio\":90.50"));
        assertFalse(data.has("splitEntrustFile"));
        assertFalse(data.has("splitEntrustFileName"));

        var applied = LakalaOnboardingProtocol.parseApplyLedgerRespData(JSON.readTree(
                "{\"version\":\"1.0\",\"orderNo\":\"KFPT20230223181025407788734\",\"orgCode\":\"1\","
                        + "\"applyId\":681201215598657536}"), "KFPT20230223181025407788734");
        assertEquals("681201215598657536", applied.applyId());
    }

    @Test
    void contractInfoAcceptsBothDocumentedEnvelopesAndBothCallbackShapes() throws Exception {
        String standard = "{\"retCode\":\"000000\",\"retMsg\":\"交易成功\",\"respData\":{\"orderNo\":\"APPLY1617673311306\","
                + "\"contractId\":\"202104062502898611\",\"contractStatus\":\"MANUAL_AUDIT\",\"contractMemo\":\"转人工审核\","
                + "\"merInnerNo\":\"\",\"merCupNo\":\"\"}}";
        ContractInfo manual = LakalaOnboardingProtocol.parseContractInfoResponse(standard.getBytes(StandardCharsets.UTF_8));
        assertEquals("MANUAL_AUDIT", manual.contractStatus());
        assertFalse(manual.approved());
        assertFalse(manual.rejected());

        String documented = "{\"code\":\"000000\",\"message\":\"成功\",\"data\":{\"orgCode\":\"1\",\"orderNo\":\"APPLY1617673311306\","
                + "\"contractId\":\"202104062502898611\",\"contractStatus\":\"WAIT_FOR_CONTACT\",\"contractMemo\":\"\","
                + "\"merInnerNo\":\"4002021040692311689\",\"merCupNo\":\"82229007011009E\",\"termDatas\":["
                + "{\"shopId\":\"431750371322392576\",\"termId\":\"431750371326586880\",\"termNo\":\"B0100551\","
                + "\"busiTypeName\":\"扫码\",\"productName\":\"云分销\",\"productCode\":\"CLOUD_DISTRIBUTION\"}]}}";
        ContractInfo approved = LakalaOnboardingProtocol.parseContractInfoResponse(
                documented.getBytes(StandardCharsets.UTF_8));
        assertTrue(approved.approved());
        assertEquals("82229007011009E", approved.merCupNo());
        assertEquals("B0100551", approved.firstTermNo());
        assertEquals("CLOUD_DISTRIBUTION", approved.termDatas().getFirst().productCode());

        BusinessRejected rejected = assertThrows(BusinessRejected.class,
                () -> LakalaOnboardingProtocol.parseContractInfoResponse(
                        "{\"retCode\":\"MMS_QUERYCONTRACT_ERROR\",\"retMsg\":\"进件不存在\"}"
                                .getBytes(StandardCharsets.UTF_8)));
        assertEquals("MMS_QUERYCONTRACT_ERROR", rejected.code());

        String callback = "{\"retUrl\":\"http://paycallback1.1000.com/callback\",\"dataPacket\":{\"code\":\"000000\","
                + "\"data\":{\"contractId\":\"202104062502898611\",\"contractStatus\":\"WAIT_FOR_CONTACT\","
                + "\"merCupNo\":\"82229007011009E\",\"merInnerNo\":\"4002021040692311689\",\"orderNo\":\"APPLY1617673311306\","
                + "\"orgCode\":\"1\",\"contractMemo\":\"\",\"termDatas\":[{\"busiTypeName\":\"扫码\","
                + "\"productCode\":\"SMALL_ROUTINE_ACQUIRER\",\"productName\":\"惠码线下支付\",\"shopId\":\"431750371322392576\","
                + "\"termId\":\"431750371574050816\",\"termNo\":\"B0100552\",\"activeNo\":\"789547854785\"}]},"
                + "\"message\":\"成功\"}}";
        ContractInfo notified = LakalaOnboardingProtocol.parseContractNotification(JSON.readTree(callback));
        assertTrue(notified.approved());
        assertEquals("B0100552", notified.firstTermNo());

        String submitted = "{\"retUrl\":\"http://10.177.93.135:8082/notify\",\"dataPacket\":{\"code\":\"000000\","
                + "\"data\":{\"orgCode\":\"1\",\"contractId\":\"202103182502719606\",\"merInnerNo\":\"\",\"contractMemo\":\"\","
                + "\"contractStatus\":\"COMMIT\",\"orderNo\":\"2021020112000012345678\"},\"message\":\"成功\",\"code\":\"000000\"}}";
        assertEquals("COMMIT", LakalaOnboardingProtocol.parseContractNotification(JSON.readTree(submitted))
                .contractStatus());

        String rejectedCallback = "{\"dataPacket\":{\"code\":\"000000\",\"data\":{\"contractId\":\"202104062502898611\","
                + "\"contractStatus\":\"INNER_CHECK_REJECTED\",\"contractMemo\":\"营业执照照片模糊\",\"orderNo\":\"APPLY1\"},"
                + "\"message\":\"成功\"}}";
        ContractInfo turnedDown = LakalaOnboardingProtocol.parseContractNotification(JSON.readTree(rejectedCallback));
        assertTrue(turnedDown.rejected());
        assertEquals("营业执照照片模糊", turnedDown.contractMemo());
        assertNull(turnedDown.firstTermNo());

        assertThrows(ProtocolException.class, () -> LakalaOnboardingProtocol.parseContractNotification(
                JSON.readTree("{\"retUrl\":\"x\",\"dataPacket\":{\"code\":\"000000\",\"data\":{\"orderNo\":\"APPLY1\"}}}")));
    }

    @Test
    void ledgerOpenNotificationAndLedgerInfoAreParsed() throws Exception {
        String approved = "{\"applyId\":681201215598657536,\"merCupNo\":\"82229007011009E\","
                + "\"retUrl\":\"http://run.mocky.io/v3/b02c9448\",\"entrustFileName\":\"授权委托书.pdf\","
                + "\"auditStatus\":\"1\",\"merInnerNo\":\"4002021033012340711\",\"remark\":\"审核通过\","
                + "\"auditStatusText\":\"审核通过\",\"uploadAttachType\":\"SPLIT_ENTRUST_FILE\"}";
        var open = LakalaOnboardingProtocol.parseLedgerOpenNotification(JSON.readTree(approved));
        assertTrue(open.approved());
        assertEquals("82229007011009E", open.merCupNo());
        assertEquals("681201215598657536", open.applyId());

        var declined = LakalaOnboardingProtocol.parseLedgerOpenNotification(JSON.readTree(
                "{\"respData\":{\"applyId\":\"681201215598657536\",\"merCupNo\":\"82229007011009E\",\"auditStatus\":2,"
                        + "\"remark\":\"委托书与立项标准不一致\"}}"));
        assertFalse(declined.approved());
        assertEquals("委托书与立项标准不一致", declined.remark());

        assertThrows(ProtocolException.class, () -> LakalaOnboardingProtocol.parseLedgerOpenNotification(
                JSON.readTree("{\"applyId\":\"1\",\"merCupNo\":\"822\",\"auditStatus\":\"3\"}")));

        LedgerInfo info = LakalaOnboardingProtocol.parseLedgerInfoRespData(JSON.readTree(
                "{\"merInnerNo\":\"111111111111111111\",\"merCupNo\":\"82229007011009E\",\"splitLowestRatio\":0.0,"
                        + "\"orgId\":\"1\",\"orgName\":\"测试机构名称\",\"splitStatus\":\"VALID\",\"splitStatusText\":\"启用\","
                        + "\"splitRange\":\"ALL\",\"sepFundSource\":\"TR\",\"bindRelations\":[{\"merInnerNo\":\"111\","
                        + "\"merCupNo\":\"822*\",\"receiverNo\":\"SR2022112217016\",\"receiverName\":\"平台\"}]}"),
                "82229007011009E");
        assertTrue(info.valid());
        assertTrue(info.boundTo("SR2022112217016"));
        assertFalse(info.boundTo("SR0000"));
        assertEquals("ALL", info.splitRange());
        assertThrows(ProtocolException.class, () -> LakalaOnboardingProtocol.parseLedgerInfoRespData(
                JSON.readTree("{\"merCupNo\":\"82229007011009F\",\"splitStatus\":\"VALID\"}"),
                "82229007011009E"));
    }

    @Test
    void toStringNeverLeaksSensitiveInput() {
        AddMerInput input = addMer(true);
        assertFalse(input.toString().contains("342523199905298212"));
        assertFalse(input.toString().contains("6212260200102400000"));
        ApplyLedgerInput ledger = new ApplyLedgerInput("KFPT1", "1", "82229007011009E", "13900000000",
                BigDecimal.TEN, "QT1", NOTIFY);
        assertFalse(ledger.toString().contains("13900000000"));
        assertThrows(ProtocolException.class, () -> new FeeRate("302", "微信", "0.605"));
        assertThrows(ProtocolException.class, () -> new FeeRate("302", "微信", "0.6.0"));
    }

    private static AddMerInput addMer(boolean settler) {
        return new AddMerInput("2026112711000012345678", "CLOUD_DISTRIBUTION", "1950836",
                "示例宠物医院", settler ? "示例宠物医院高新店" : null, "510104", "天府大道1号", "0742", "示例宠物医院",
                "91110108MA2G7LK368", "2020-01-01", "2040-01-01", "654", "张三", "342523199905298212",
                "1999-05-29", "2039-05-29", "13900000000", "李四", "102100000030",
                "中国工商银行北京市分行营业部", "102100099996", "6212260200102400000", "张三", "58",
                settler ? "510104199001011234" : null, settler ? "2030-01-01" : null, "T+1", NOTIFY,
                settler ? "QT20221021000157861" : null,
                List.of(new FeeRate("302", "微信", "0.60")),
                List.of(new FileItem("ID_CARD_FRONT", "G1/M00/00/61/CrFdEl3IyceAVVd8AAA0ADuZsA0911.jpg")));
    }
}
