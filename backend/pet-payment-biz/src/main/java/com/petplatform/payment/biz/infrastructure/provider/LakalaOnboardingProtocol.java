package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.PreparedV2Request;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline Lakala protocol for merchant acquiring onboarding (CCR-PAY-ONBOARD-001 §5), transcribed on
 * 2026-11-27 from the authoritative documents: 1020 (addMer), 1082 (queryContract) and 1083 (callback),
 * 1284 (applyLedgerMer) and 1286 (queryLedgerMer), 1208 (electronic contract apply), 1212 (q_status)
 * and the EC011 text-domain sheet. Attachment upload (1084) and bind (1290) reuse {@link LakalaV2Protocol}.
 * No network call, key or certificate lookup occurs here; nothing here is inferred beyond those documents.
 */
public final class LakalaOnboardingProtocol {
    // Ruling 44 / Lakala 分账通流程: order split, manual instructions, active withdrawal.
    public static final String SPLIT_RANGE = "ALL";
    public static final String SEP_FUND_SOURCE = "TR";
    public static final String SPLIT_LAUNCH_MODE = "MANUAL";
    public static final String LEDGER_SETTLE_TYPE = "01";
    /** SSOT §二十七: only the mainland resident ID is accepted, for the legal person and the settler alike. */
    public static final String RESIDENT_ID_TYPE = "01";
    /** 1208: EC011 清分结算授权委托书 is the current version; legal person signs by WeChat mini-program face auth. */
    public static final String EC_TYPE_CODE = "EC011";
    public static final String EC_SIGN_AUTH_TYPE = "WX_APPLET_FACE";
    public static final String EC_CERT_TYPE_RESIDENT_ID = "RESIDENT_ID";
    public static final String EC_DATA_VERSION = "1.0";
    public static final String EC_STATUS_COMPLETED = "COMPLETED";
    public static final String EC_STATUS_UNDONE = "UNDONE";
    /** 1083: audit outcomes; every other contractStatus keeps the application pending. */
    public static final String CONTRACT_STATUS_APPROVED = "WAIT_FOR_CONTACT";
    public static final Set<String> CONTRACT_STATUS_REJECTED = Set.of("COMMIT_FAIL", "INNER_CHECK_REJECTED");
    public static final String LEDGER_STATUS_VALID = "VALID";
    private static final Set<String> VALID_ACCT_TYPES = Set.of("57", "58");
    private static final Set<String> VALID_LEDGER_STATUS = Set.of("VALID", "INVALID");
    private static final Set<String> VALID_AUDIT_STATUS = Set.of("1", "2");

    private LakalaOnboardingProtocol() {}

    /** Deterministic business rejection carried by a signed response ({@code retCode}/{@code code} ≠ 000000). */
    public static final class BusinessRejected extends RuntimeException {
        private final String code;
        private final String message;

        BusinessRejected(String code, String message) {
            super("Lakala rejected request: code=" + code);
            this.code = code;
            this.message = message;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }
    }

    // ---- 1208 Electronic contract apply ------------------------------------------------------

    /** EC011 text domains E1–E11 (official field sheet, 2025-10 revision). */
    public record EcContractText(String partnerName, String cooperationAgreementName,
            String merchantLowestRatio, String platformName, String signDate, String legalName) {}

    public record EcApplyInput(
            String orderNo,
            String orgId,
            String legalName,
            String legalIdCard,
            String legalMobile,
            String licenseNo,
            String licenseName,
            String openningBankCode,
            String openningBankName,
            String acctTypeCode,
            String acctNo,
            String acctName,
            EcContractText contractText,
            String retUrl) {
        @Override
        public String toString() {
            return "EcApplyInput[orderNo=" + orderNo + ",redacted]";
        }
    }

    public record EcApplyResult(String orderNo, String ecApplyId, String resultUrl) {}

    /** Parsed 1208 / 1212 body: {@code code}, {@code msg} or {@code message}, {@code resp_data} (or {@code data}). */
    public record EcEnvelope(String code, String message, JsonNode data) {
        public boolean isSuccess() {
            return LakalaV2Protocol.SUCCESS_CODE.equals(code);
        }
    }

    public static PreparedV2Request prepareEcApply(EcApplyInput input, long timestampMillis, String reqId) {
        if (input == null || input.contractText() == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", EC_DATA_VERSION);
        data.put("order_no", req(input.orderNo(), 32));
        data.put("org_id", orgId(input.orgId()));
        data.put("ec_type_code", EC_TYPE_CODE);
        data.put("cert_type", EC_CERT_TYPE_RESIDENT_ID);
        data.put("cert_name", req(input.legalName(), 32));
        data.put("cert_no", req(input.legalIdCard(), 32));
        data.put("mobile", req(input.legalMobile(), 16));
        data.put("business_license_no", req(input.licenseNo(), 32));
        data.put("business_license_name", req(input.licenseName(), 32));
        data.put("openning_bank_code", req(input.openningBankCode(), 32));
        data.put("openning_bank_name", req(input.openningBankName(), 128));
        data.put("acct_type_code", acctType(input.acctTypeCode()));
        data.put("acct_no", req(input.acctNo(), 32));
        data.put("acct_name", req(input.acctName(), 64));
        data.put("ec_content_parameters", ecContentParameters(input.contractText()));
        data.put("sign_auth_type", EC_SIGN_AUTH_TYPE);
        data.put("ret_url", req(input.retUrl(), 128));
        return LakalaV2Protocol.buildV2Request(LakalaV2Protocol.EC_APPLY_PATH, data, timestampMillis, reqId);
    }

    /**
     * Serializes the EC011 text domains as a JSON string. E1 partner and E9 platform are the platform company,
     * E2 names the platform–merchant cooperation agreement, E3/E7 select "按订单清分结算" and "指令清分结算"
     * while E4/E5 are declined, E8 is the merchant's lowest split ratio, E10 the signing date, E11 the legal person.
     * The selected/declined literals ("是"/"否") follow the EC004 example convention and are a 联调 verification item
     * (CCR §13.2-4).
     */
    static String ecContentParameters(EcContractText text) {
        Map<String, String> domains = new LinkedHashMap<>();
        domains.put("E1", req(text.partnerName(), 128));
        domains.put("E2", req(text.cooperationAgreementName(), 128));
        domains.put("E3", "是");
        domains.put("E4", "否");
        domains.put("E5", "否");
        domains.put("E7", "是");
        domains.put("E8", req(text.merchantLowestRatio(), 16));
        domains.put("E9", req(text.platformName(), 128));
        domains.put("E10", req(text.signDate(), 16));
        domains.put("E11", req(text.legalName(), 32));
        try {
            return new String(LakalaV2Protocol.toJsonBytes(domains), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new LakalaV2Protocol.ProtocolException("EC text domains serialization failed", failure);
        }
    }

    public static EcEnvelope parseEcEnvelope(byte[] rawBody) {
        JsonNode root = LakalaV2Protocol.json(rawBody);
        String code = LakalaV2Protocol.optionalStringOrNumber(root, "code", 16);
        if (code == null) invalid();
        String message = LakalaV2Protocol.optionalFreeText(root, "msg", 256);
        if (message == null) message = LakalaV2Protocol.optionalFreeText(root, "message", 256);
        JsonNode data = firstObject(root, "resp_data", "respData", "data");
        if (LakalaV2Protocol.SUCCESS_CODE.equals(code) && data == null) invalid();
        return new EcEnvelope(code, message, data);
    }

    public static EcApplyResult parseEcApplyRespData(JsonNode data, String expectedOrderNo) {
        if (data == null || !data.isObject()) invalid();
        String orderNo = LakalaV2Protocol.optionalField(data, "order_no", 32);
        if (orderNo != null && expectedOrderNo != null && !expectedOrderNo.equals(orderNo)) invalid();
        return new EcApplyResult(orderNo == null ? expectedOrderNo : orderNo,
                LakalaV2Protocol.requiredStringOrNumber(data, "ec_apply_id", 64),
                req(LakalaV2Protocol.optionalField(data, "result_url", 4096), 4096));
    }

    /** 1212 q_status result; also the shape of the signing notification (1208 异步签约结果通知). */
    public record EcStatusResult(String ecApplyId, String orderNo, String ecStatus, String ecNo) {
        public boolean completed() {
            return EC_STATUS_COMPLETED.equals(ecStatus) && ecNo != null;
        }
    }

    public static PreparedV2Request prepareEcQueryStatus(String orderNo, String orgId, String ecApplyId,
            long timestampMillis, String reqId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", EC_DATA_VERSION);
        data.put("order_no", req(orderNo, 32));
        data.put("org_id", orgId(orgId));
        data.put("ec_apply_id", ecApplyId(ecApplyId));
        return LakalaV2Protocol.buildV2Request(LakalaV2Protocol.EC_QUERY_STATUS_PATH, data, timestampMillis, reqId);
    }

    public static EcStatusResult parseEcQueryRespData(JsonNode data, String expectedApplyId) {
        if (data == null || !data.isObject()) invalid();
        String applyId = LakalaV2Protocol.requiredStringOrNumber(data, "ec_apply_id", 64);
        if (expectedApplyId != null && !expectedApplyId.equals(applyId)) invalid();
        return ecStatus(data, applyId);
    }

    /** Flat 1208 notification ({@code ecApplyId, orderNo, ecNo, ecStatus, ...}), possibly wrapped. */
    public static EcStatusResult parseEcNotification(JsonNode root) {
        JsonNode data = unwrap(root);
        String applyId = LakalaV2Protocol.requiredStringOrNumber(data, "ecApplyId", 64);
        String status = LakalaV2Protocol.requiredStringOrNumber(data, "ecStatus", 32);
        String ecNo = LakalaV2Protocol.optionalField(data, "ecNo", 32);
        if (EC_STATUS_COMPLETED.equals(status)) req(ecNo, 32);
        return new EcStatusResult(applyId, LakalaV2Protocol.optionalField(data, "orderNo", 32), status, ecNo);
    }

    /** 1212 body uses snake_case ({@code ec_apply_id, ec_status, ec_no}); blanks become null. */
    private static EcStatusResult ecStatus(JsonNode data, String applyId) {
        String status = LakalaV2Protocol.requiredStringOrNumber(data, "ec_status", 32);
        if (!EC_STATUS_UNDONE.equals(status) && !EC_STATUS_COMPLETED.equals(status)) invalid();
        String ecNo = LakalaV2Protocol.optionalField(data, "ec_no", 32);
        if (EC_STATUS_COMPLETED.equals(status)) req(ecNo, 32);
        return new EcStatusResult(applyId, LakalaV2Protocol.optionalField(data, "order_no", 32), status, ecNo);
    }

    // ---- 1020 addMer ---------------------------------------------------------------------------

    public record FeeRate(String feeRateTypeCode, String feeRateTypeName, String feeRatePct) {
        public FeeRate {
            req(feeRateTypeCode, 8);
            req(feeRateTypeName, 64);
            rate(feeRatePct);
        }
    }

    /** One uploaded attachment: {@code attFileId} is transient (24h) and never persisted by the caller. */
    public record FileItem(String attType, String attFileId) {
        public FileItem {
            LakalaV2Protocol.requireAttType(attType);
            req(attFileId, 256);
        }
    }

    public record AddMerInput(
            String orderNo,
            String posType,
            String orgCode,
            String merRegName,
            String merBizName,
            String merRegDistCode,
            String merRegAddr,
            String mccCode,
            String merBlisName,
            String merBlis,
            String merBlisStDt,
            String merBlisExpDt,
            String merBusiContent,
            String larName,
            String larIdcard,
            String larIdcardStDt,
            String larIdcardExpDt,
            String merContactMobile,
            String merContactName,
            String openningBankCode,
            String openningBankName,
            String clearingBankCode,
            String acctNo,
            String acctName,
            String acctTypeCode,
            String acctIdcard,
            String acctIdDt,
            String settlePeriod,
            String retUrl,
            String contractNo,
            List<FeeRate> feeData,
            List<FileItem> fileData) {
        public AddMerInput {
            feeData = feeData == null ? List.of() : List.copyOf(feeData);
            fileData = fileData == null ? List.of() : List.copyOf(fileData);
        }

        @Override
        public String toString() {
            return "AddMerInput[orderNo=" + orderNo + ",redacted]";
        }
    }

    public record AddMerResult(String orderNo, String orgCode, String contractId) {}

    public static PreparedV2Request prepareAddMer(AddMerInput input, long timestampMillis, String reqId) {
        if (input == null || input.feeData().isEmpty() || input.fileData().isEmpty()) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", LakalaV2Protocol.MMS_DATA_VERSION);
        data.put("orderNo", req(input.orderNo(), 32));
        data.put("posType", req(input.posType(), 32));
        data.put("orgCode", req(input.orgCode(), 32));
        data.put("merRegName", req(input.merRegName(), 80));
        put(data, "merBizName", input.merBizName(), 64);
        data.put("merRegDistCode", req(input.merRegDistCode(), 8));
        data.put("merRegAddr", req(input.merRegAddr(), 80));
        data.put("mccCode", req(input.mccCode(), 8));
        data.put("merBlisName", req(input.merBlisName(), 80));
        data.put("merBlis", req(input.merBlis(), 40));
        data.put("merBlisStDt", req(input.merBlisStDt(), 10));
        data.put("merBlisExpDt", req(input.merBlisExpDt(), 10));
        data.put("merBusiContent", req(input.merBusiContent(), 64));
        data.put("larName", req(input.larName(), 20));
        data.put("larIdType", RESIDENT_ID_TYPE);
        data.put("larIdcard", req(input.larIdcard(), 40));
        data.put("larIdcardStDt", req(input.larIdcardStDt(), 10));
        data.put("larIdcardExpDt", req(input.larIdcardExpDt(), 10));
        data.put("merContactMobile", req(input.merContactMobile(), 20));
        data.put("merContactName", req(input.merContactName(), 32));
        data.put("openningBankCode", req(input.openningBankCode(), 20));
        data.put("openningBankName", req(input.openningBankName(), 40));
        data.put("clearingBankCode", req(input.clearingBankCode(), 20));
        data.put("acctNo", req(input.acctNo(), 40));
        data.put("acctName", req(input.acctName(), 40));
        data.put("acctTypeCode", acctType(input.acctTypeCode()));
        if (nonBlank(input.acctIdcard())) {
            // Non-legal-person settlement: the settler's own ID; type is always the resident ID (SSOT §二十七).
            data.put("acctIdType", RESIDENT_ID_TYPE);
            data.put("acctIdcard", req(input.acctIdcard(), 40));
            data.put("acctIdDt", req(input.acctIdDt(), 10));
        }
        data.put("settlePeriod", req(input.settlePeriod(), 8));
        // 1020 declares retUrl as String(64); longer bases are rejected here instead of at Lakala.
        data.put("retUrl", req(input.retUrl(), 64));
        put(data, "contractNo", input.contractNo(), 64);
        List<Map<String, String>> fees = new ArrayList<>();
        for (FeeRate fee : input.feeData()) {
            fees.add(ordered("feeRateTypeCode", fee.feeRateTypeCode(),
                    "feeRateTypeName", fee.feeRateTypeName(), "feeRatePct", fee.feeRatePct()));
        }
        data.put("feeData", fees);
        List<Map<String, String>> files = new ArrayList<>();
        for (FileItem file : input.fileData()) {
            files.add(ordered("attFileId", file.attFileId(), "attType", file.attType()));
        }
        data.put("fileData", files);
        return LakalaV2Protocol.buildV2Request(LakalaV2Protocol.ADD_MER_PATH, data, timestampMillis, reqId);
    }

    public static AddMerResult parseAddMerRespData(JsonNode data, String expectedOrderNo) {
        if (data == null || !data.isObject()) invalid();
        String orderNo = LakalaV2Protocol.optionalField(data, "orderNo", 32);
        if (orderNo != null && expectedOrderNo != null && !expectedOrderNo.equals(orderNo)) invalid();
        return new AddMerResult(orderNo == null ? expectedOrderNo : orderNo,
                LakalaV2Protocol.optionalField(data, "orgCode", 32),
                req(LakalaV2Protocol.optionalField(data, "contractId", 32), 32));
    }

    // ---- 1082 queryContract & 1083 callback -----------------------------------------------------

    public record TermData(String termId, String termNo, String busiTypeCode, String busiTypeName,
            String productName, String productCode) {
        @Override
        public String toString() {
            return "TermData[termNo=" + termNo + "]";
        }
    }

    /** One onboarding application as Lakala reports it; shared by the query response and the callback. */
    public record ContractInfo(String contractId, String contractStatus, String contractMemo,
            String merInnerNo, String merCupNo, List<TermData> termDatas) {
        public ContractInfo {
            termDatas = termDatas == null ? List.of() : List.copyOf(termDatas);
        }

        public boolean approved() {
            return CONTRACT_STATUS_APPROVED.equals(contractStatus);
        }

        public boolean rejected() {
            return CONTRACT_STATUS_REJECTED.contains(contractStatus);
        }

        /** The acquiring terminal; Lakala issues one terminal per business type for an add-merchant application. */
        public String firstTermNo() {
            return termDatas.isEmpty() || termDatas.getFirst().termNo() == null ? null : termDatas.getFirst().termNo();
        }
    }

    public static PreparedV2Request prepareQueryContract(String orderNo, String orgCode, String contractId,
            long timestampMillis, String reqId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", LakalaV2Protocol.MMS_DATA_VERSION);
        data.put("orderNo", req(orderNo, 32));
        data.put("orgCode", req(orgCode, 32));
        data.put("contractId", req(contractId, 32));
        return LakalaV2Protocol.buildV2Request(LakalaV2Protocol.QUERY_CONTRACT_PATH, data, timestampMillis, reqId);
    }

    /**
     * Accepts both documented shapes: the standard {@code retCode/retMsg/respData} envelope and the
     * "审核通过" example of 1082 ({@code code/message/data}); CCR §13.2-2.
     */
    public static ContractInfo parseContractInfoResponse(byte[] rawBody) {
        JsonNode root = LakalaV2Protocol.json(rawBody);
        JsonNode retCode = root.get("retCode");
        if (retCode != null && retCode.isTextual()) {
            LakalaV2Protocol.V2Envelope envelope = LakalaV2Protocol.parseV2Envelope(rawBody);
            if (!envelope.isSuccess()) throw new BusinessRejected(envelope.retCode(), envelope.retMsg());
            return parseContractInfo(envelope.respData());
        }
        String code = LakalaV2Protocol.requiredStringOrNumber(root, "code", 16);
        if (!LakalaV2Protocol.SUCCESS_CODE.equals(code)) {
            throw new BusinessRejected(code, LakalaV2Protocol.optionalFreeText(root, "message", 256));
        }
        return parseContractInfo(firstObject(root, "data", "resp_data", "respData"));
    }

    /** 1083 notification: {@code {retUrl, dataPacket:{code, message, data:{...}}}}; flat and wrapped forms accepted. */
    public static ContractInfo parseContractNotification(JsonNode root) {
        return parseContractInfo(unwrap(root));
    }

    public static ContractInfo parseContractInfo(JsonNode data) {
        if (data == null || !data.isObject()) invalid();
        List<TermData> terminals = new ArrayList<>();
        JsonNode termDatas = data.get("termDatas");
        if (termDatas != null && !termDatas.isNull()) {
            if (!termDatas.isArray()) invalid();
            for (JsonNode term : termDatas) {
                if (term == null || !term.isObject()) invalid();
                terminals.add(new TermData(
                        LakalaV2Protocol.optionalField(term, "termId", 64),
                        LakalaV2Protocol.optionalField(term, "termNo", 32),
                        LakalaV2Protocol.optionalField(term, "busiTypeCode", 32),
                        LakalaV2Protocol.optionalFreeText(term, "busiTypeName", 64),
                        LakalaV2Protocol.optionalFreeText(term, "productName", 64),
                        LakalaV2Protocol.optionalField(term, "productCode", 64)));
            }
        }
        return new ContractInfo(
                req(LakalaV2Protocol.optionalField(data, "contractId", 32), 32),
                req(LakalaV2Protocol.optionalField(data, "contractStatus", 32), 32),
                LakalaV2Protocol.optionalFreeText(data, "contractMemo", 512),
                LakalaV2Protocol.optionalField(data, "merInnerNo", 32),
                LakalaV2Protocol.optionalField(data, "merCupNo", 32),
                terminals);
    }

    // ---- 1284 applyLedgerMer & audit notification -----------------------------------------------

    public record ApplyLedgerInput(String orderNo, String orgCode, String merCupNo, String contactMobile,
            BigDecimal splitLowestRatio, String eleContractNo, String retUrl) {
        @Override
        public String toString() {
            return "ApplyLedgerInput[orderNo=" + orderNo + ",merCupNo=" + merCupNo + "]";
        }
    }

    public record ApplyLedgerResult(String orderNo, String orgCode, String applyId) {}

    public static PreparedV2Request prepareApplyLedgerMer(ApplyLedgerInput input, long timestampMillis, String reqId) {
        if (input == null || input.splitLowestRatio() == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", LakalaV2Protocol.MMS_DATA_VERSION);
        data.put("orderNo", req(input.orderNo(), 32));
        data.put("orgCode", req(input.orgCode(), 32));
        data.put("merCupNo", req(input.merCupNo(), 32));
        data.put("contactMobile", req(input.contactMobile(), 32));
        BigDecimal ratio = input.splitLowestRatio().setScale(2, RoundingMode.UNNECESSARY);
        if (ratio.signum() < 0 || ratio.compareTo(new BigDecimal("100")) > 0) invalid();
        data.put("splitLowestRatio", ratio);
        // 1284: a signed electronic contract replaces the stamped 《结算委托授权书》 attachment.
        data.put("eleContractNo", req(input.eleContractNo(), 32));
        data.put("splitRange", SPLIT_RANGE);
        data.put("sepFundSource", SEP_FUND_SOURCE);
        data.put("splitLaunchMode", SPLIT_LAUNCH_MODE);
        data.put("settleType", LEDGER_SETTLE_TYPE);
        data.put("retUrl", req(input.retUrl(), 128));
        return LakalaV2Protocol.buildV2Request(LakalaV2Protocol.APPLY_LEDGER_MER_PATH, data, timestampMillis, reqId);
    }

    public static ApplyLedgerResult parseApplyLedgerRespData(JsonNode data, String expectedOrderNo) {
        if (data == null || !data.isObject()) invalid();
        String orderNo = LakalaV2Protocol.optionalField(data, "orderNo", 32);
        if (orderNo != null && expectedOrderNo != null && !expectedOrderNo.equals(orderNo)) invalid();
        return new ApplyLedgerResult(orderNo == null ? expectedOrderNo : orderNo,
                LakalaV2Protocol.optionalField(data, "orgCode", 32),
                LakalaV2Protocol.requiredStringOrNumber(data, "applyId", 64));
    }

    /** 1284 asynchronous audit result: {@code auditStatus} 1 approved / 2 rejected. */
    public record LedgerOpenNotification(String applyId, String merCupNo, String merInnerNo,
            boolean approved, String auditStatusText, String remark) {}

    public static LedgerOpenNotification parseLedgerOpenNotification(JsonNode root) {
        JsonNode data = unwrap(root);
        String audit = LakalaV2Protocol.requiredStringOrNumber(data, "auditStatus", 8);
        if (!VALID_AUDIT_STATUS.contains(audit)) invalid();
        return new LedgerOpenNotification(
                LakalaV2Protocol.requiredStringOrNumber(data, "applyId", 64),
                req(LakalaV2Protocol.optionalField(data, "merCupNo", 32), 32),
                LakalaV2Protocol.optionalField(data, "merInnerNo", 32),
                "1".equals(audit),
                LakalaV2Protocol.optionalFreeText(data, "auditStatusText", 64),
                LakalaV2Protocol.optionalFreeText(data, "remark", 512));
    }

    // ---- 1286 queryLedgerMer ----------------------------------------------------------------------

    public record BindRelation(String merCupNo, String receiverNo, String receiverName) {}

    public record LedgerInfo(String merInnerNo, String merCupNo, String splitLowestRatio, String splitStatus,
            String splitRange, String sepFundSource, List<BindRelation> bindRelations) {
        public LedgerInfo {
            bindRelations = bindRelations == null ? List.of() : List.copyOf(bindRelations);
        }

        public boolean valid() {
            return LEDGER_STATUS_VALID.equals(splitStatus);
        }

        public boolean boundTo(String receiverNo) {
            return receiverNo != null && bindRelations.stream().anyMatch(r -> receiverNo.equals(r.receiverNo()));
        }
    }

    public static PreparedV2Request prepareQueryLedgerMer(String orderNo, String orgCode, String merCupNo,
            long timestampMillis, String reqId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", LakalaV2Protocol.MMS_DATA_VERSION);
        data.put("orderNo", req(orderNo, 32));
        data.put("orgCode", req(orgCode, 32));
        data.put("merCupNo", req(merCupNo, 32));
        return LakalaV2Protocol.buildV2Request(LakalaV2Protocol.QUERY_LEDGER_MER_PATH, data, timestampMillis, reqId);
    }

    public static LedgerInfo parseLedgerInfoRespData(JsonNode data, String expectedMerCupNo) {
        if (data == null || !data.isObject()) invalid();
        String merCupNo = req(LakalaV2Protocol.optionalField(data, "merCupNo", 32), 32);
        if (expectedMerCupNo != null && !expectedMerCupNo.equals(merCupNo)) invalid();
        String splitStatus = req(LakalaV2Protocol.optionalField(data, "splitStatus", 16), 16);
        if (!VALID_LEDGER_STATUS.contains(splitStatus)) invalid();
        List<BindRelation> relations = new ArrayList<>();
        JsonNode binds = data.get("bindRelations");
        if (binds != null && !binds.isNull()) {
            if (!binds.isArray()) invalid();
            for (JsonNode bind : binds) {
                if (bind == null || !bind.isObject()) invalid();
                relations.add(new BindRelation(
                        LakalaV2Protocol.optionalField(bind, "merCupNo", 32),
                        LakalaV2Protocol.optionalField(bind, "receiverNo", 32),
                        LakalaV2Protocol.optionalFreeText(bind, "receiverName", 128)));
            }
        }
        return new LedgerInfo(
                LakalaV2Protocol.optionalField(data, "merInnerNo", 32),
                merCupNo,
                decimalText(data.get("splitLowestRatio"), 16),
                splitStatus,
                LakalaV2Protocol.optionalField(data, "splitRange", 32),
                LakalaV2Protocol.optionalField(data, "sepFundSource", 32),
                relations);
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /** Digs the business object out of {@code dataPacket.data}, {@code respData}, {@code data} or the flat body. */
    static JsonNode unwrap(JsonNode root) {
        if (root == null || !root.isObject()) invalid();
        JsonNode packet = root.get("dataPacket");
        if (packet != null && packet.isObject()) {
            JsonNode inner = packet.get("data");
            if (inner != null && inner.isObject()) return inner;
        }
        JsonNode wrapped = firstObject(root, "respData", "resp_data", "data");
        return wrapped == null ? root : wrapped;
    }

    private static JsonNode firstObject(JsonNode parent, String... names) {
        for (String name : names) {
            JsonNode node = parent.get(name);
            if (node != null && node.isObject()) return node;
        }
        return null;
    }

    /** 1286 reports ratios as JSON floats (e.g. {@code 0.0}); keep the exact decimal text. */
    private static String decimalText(JsonNode node, int max) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return req(node.decimalValue().toPlainString(), max);
        if (node.isTextual()) {
            String text = node.textValue().trim();
            return text.isEmpty() ? null : req(text, max);
        }
        invalid();
        return null;
    }

    private static String acctType(String value) {
        String checked = req(value, 8);
        if (!VALID_ACCT_TYPES.contains(checked)) invalid();
        return checked;
    }

    /** 1208 declares {@code org_id} as Integer; sent as a JSON number exactly as the examples show. */
    private static Long orgId(String value) {
        String checked = req(value, 32);
        try {
            return Long.parseLong(checked);
        } catch (NumberFormatException overflow) {
            invalid();
            throw new AssertionError();
        }
    }

    /** 1212 declares {@code ec_apply_id} as Long; a Java long keeps all 19 digits exact. */
    private static Long ecApplyId(String value) {
        String checked = req(value, 64);
        try {
            return Long.parseLong(checked);
        } catch (NumberFormatException overflow) {
            invalid();
            throw new AssertionError();
        }
    }

    private static void rate(String value) {
        String checked = req(value, 16);
        if (!checked.matches("(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,2})?")) invalid();
    }

    private static Map<String, String> ordered(String... entries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put(entries[i], entries[i + 1]);
        return map;
    }

    private static void put(Map<String, Object> data, String key, String value, int max) {
        if (nonBlank(value)) data.put(key, LakalaV2Protocol.required(value, max));
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String req(String value, int max) {
        return LakalaV2Protocol.required(value, max);
    }

    private static void invalid() {
        LakalaV2Protocol.invalid();
    }
}
