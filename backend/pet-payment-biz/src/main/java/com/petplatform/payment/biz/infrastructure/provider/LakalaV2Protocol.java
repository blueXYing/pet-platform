package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline Lakala Open Platform v2 protocol primitives shared by Stage 4 MMS split-receiver
 * onboarding (1084/1287/1288/1289/1290/1872) and reusable by Stage 5 LAEP wallet/withdrawal
 * (1269/1319/1271/1272).
 *
 * <p>Verified against local authoritative Lakala PDFs (2026-11-25):
 * /1084 (uploadFile), /1287 (applyLedgerReceiver, settleType="01"), /1288 (modifyLedgerReceiver),
 * /1289 (queryReceiverDetail), /1290 (applyBind & async callback), /1872 (listRelationApply),
 * /1004 (request/response signature) & /1009 (notification signature).
 * No network call, key or certificate lookup occurs here.
 *
 * <p>Envelope families (CCR-PAY-ONBOARD-001 §5), chosen by endpoint path in {@link #buildV2Request}:
 * {@code reqData + ver + timestamp + reqId} for mms (1084/1020/1082), and
 * {@code reqData + reqId + version + reqTime} for the ledger family (1284/1286/1287/1288/1289/1290/1872)
 * exactly as the authoritative Lakala document examples show.
 */
public final class LakalaV2Protocol {
    public static final String UPLOAD_FILE_PATH = "/api/v2/mms/openApi/uploadFile";
    public static final String APPLY_RECEIVER_PATH = "/api/v2/mms/openApi/ledger/applyLedgerReceiver";
    public static final String MODIFY_RECEIVER_PATH = "/api/v2/mms/openApi/ledger/modifyLedgerReceiver";
    public static final String QUERY_RECEIVER_PATH = "/api/v2/mms/openApi/ledger/queryReceiverDetail";
    public static final String APPLY_BIND_PATH = "/api/v2/mms/openApi/ledger/applyBind";
    public static final String LIST_RELATION_APPLY_PATH = "/api/v2/mms/openApi/ledger/openWeb/listRelationApply";

    // CCR-PAY-ONBOARD-001 paths: 1020 addMer / 1082 queryContract / 1284 applyLedgerMer / 1286 queryLedgerMer.
    public static final String ADD_MER_PATH = "/api/v2/mms/openApi/addMer";
    public static final String QUERY_CONTRACT_PATH = "/api/v2/mms/openApi/queryContract";
    public static final String APPLY_LEDGER_MER_PATH = "/api/v2/mms/openApi/ledger/applyLedgerMer";
    public static final String QUERY_LEDGER_MER_PATH = "/api/v2/mms/openApi/ledger/queryLedgerMer";
    /** 1208 / 1212 electronic-contract paths use {@code req_data + ver + timestamp + req_id}. */
    public static final String EC_APPLY_PATH = "/api/v3/mms/open_api/ec/apply";
    public static final String EC_QUERY_STATUS_PATH = "/api/v3/mms/open_api/ec/q_status";
    private static final String EC_PATH_PREFIX = "/api/v3/mms/open_api/ec/";

    // Stage 5 LAEP paths declared here so LakalaV2HttpClient can serve Stage 4 & Stage 5 uniformly.
    public static final String EWALLET_BALANCE_QUERY_PATH = "/api/v2/laep/industry/ewalletBalanceQuery";
    public static final String EWALLET_WITHDRAW_D1_PATH = "/api/v2/laep/industry/ewalletWithdrawD1";
    public static final String EWALLET_WITHDRAW_QUERY_PATH = "/api/v2/laep/industry/ewalletWithdrawQuery";

    public static final String V2_VER = "1.0.0";
    /** Ledger family envelope: official examples send {@code version: "2.0"} plus {@code reqTime} (yyyyMMddHHmmss). */
    static final String LEDGER_PATH_PREFIX = "/api/v2/mms/openApi/ledger/";
    static final String LEDGER_ENVELOPE_VERSION = "2.0";
    private static final DateTimeFormatter LEDGER_REQ_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");
    private static final ZoneId CHANNEL_ZONE = ZoneId.of("Asia/Shanghai");
    public static final String MMS_DATA_VERSION = "1.0";
    public static final String SUCCESS_CODE = "000000";
    /** Ruling 40 §5.4: split receivers MUST always use active withdrawal ("01"), never auto-settle. */
    public static final String ACTIVE_WITHDRAW_SETTLE_TYPE = "01";
    public static final String NOTIFICATION_ACK_JSON = "{\"code\":\"SUCCESS\",\"message\":\"执行成功\"}";
    public static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;

    public static final Set<String> VALID_ATT_TYPES = Set.of(
            "FR_ID_CARD_FRONT", "FR_ID_CARD_BEHIND",
            "ID_CARD_FRONT", "ID_CARD_BEHIND",
            "BANK_CARD", "BUSINESS_LICENCE",
            "MERCHANT_PHOTO", "SHOPINNER",
            "XY", "NETWORK_XY", "HT",
            "COOPERATION_QUALIFICATION_PROOF", "FOOD_QUALIFICATION_PROOF",
            "NO_LEGAL_PERSON_SETT_AUTH_LETTER",
            "SPLIT_ENTRUST_FILE", "RENTAL_AGREEMENT",
            "SPLIT_COOPERATION_FILE", "OTHERS");
    public static final Set<String> VALID_EXT_NAMES = Set.of("jpg", "png", "pdf");
    public static final Set<String> VALID_CERT_TYPES = Set.of("17", "18", "19", "20", "22", "23", "10");
    public static final Set<String> VALID_ACCT_TYPES = Set.of("57", "58");
    private static final Set<String> VALID_RECEIVER_STATUSES = Set.of("VALID", "INVALID");
    private static final Set<String> VALID_OPT_TYPES = Set.of("ADD", "DEL");

    private static final Pattern REQ_ID = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern NUMERIC_LONG = Pattern.compile("[0-9]{1,19}");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private LakalaV2Protocol() {}

    public enum BindAuditStatus {
        PENDING, APPROVED, REJECTED;

        public boolean isTerminal() {
            return this == APPROVED || this == REJECTED;
        }
    }

    public record PreparedV2Request(String path, byte[] rawBody) {
        public PreparedV2Request {
            if (path == null || !isSupportedPath(path)) invalid();
            if (rawBody == null || rawBody.length == 0) invalid();
            rawBody = rawBody.clone();
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }

    /** Parsed Lakala v2 response envelope ({@code retCode}, {@code retMsg}, {@code respData}). */
    public record V2Envelope(String retCode, String retMsg, JsonNode respData) {
        public boolean isSuccess() {
            return SUCCESS_CODE.equals(retCode);
        }
    }

    /** Attachment item sent inside 1287 / 1288 / 1290 requests (references transient 1084 attFileId). */
    public record AttachItem(String attachType, String attachName, String attachStorePath) {
        public AttachItem {
            requireAttType(attachType);
            required(attachName, 64);
            required(attachStorePath, 256);
        }
    }

    // ---- 1084 Upload File ------------------------------------------------------------------

    public record UploadFileInput(String orderNo, String orgCode, String attType,
            String attExtName, byte[] content) {
        public UploadFileInput {
            if (content == null || content.length == 0 || content.length > MAX_ATTACHMENT_BYTES) {
                invalid();
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        @Override
        public String toString() {
            return "UploadFileInput[orderNo=" + orderNo + ",attType=" + attType
                    + ",bytes=" + content.length + "]";
        }
    }

    public record UploadFileResult(String orgCode, String orderNo, String attFileId, String attType) {}

    // ---- 1287 Create Receiver --------------------------------------------------------------

    public record ApplyReceiverInput(
            String orderNo,
            String orgCode,
            String receiverName,
            String contactMobile,
            String licenseNo,
            String licenseName,
            String legalPersonName,
            String legalPersonCertificateType,
            String legalPersonCertificateNo,
            String acctNo,
            String acctName,
            String acctTypeCode,
            String acctCertificateType,
            String acctCertificateNo,
            String acctOpenBankCode,
            String acctOpenBankName,
            String acctClearBankCode,
            List<AttachItem> attachList) {
        public ApplyReceiverInput {
            attachList = attachList == null ? List.of() : List.copyOf(attachList);
        }

        @Override
        public String toString() {
            return "ApplyReceiverInput[orderNo=" + orderNo + ",acctTypeCode=" + acctTypeCode + ",redacted]";
        }
    }

    // ---- 1288 Modify Receiver --------------------------------------------------------------

    public record ModifyReceiverInput(
            String orderNo,
            String orgCode,
            String receiverNo,
            String receiverName,
            String contactMobile,
            String acctNo,
            String acctTypeCode,
            String acctOpenBankCode,
            String acctOpenBankName,
            String acctClearBankCode,
            List<AttachItem> attachList,
            String status) {
        public ModifyReceiverInput {
            attachList = attachList == null ? List.of() : List.copyOf(attachList);
        }

        @Override
        public String toString() {
            return "ModifyReceiverInput[orderNo=" + orderNo + ",receiverNo=" + receiverNo + ",redacted]";
        }
    }

    public record ReceiverActionResult(String orderNo, String orgCode, String orgId,
            String orgName, String receiverNo) {}

    // ---- 1289 Query Receiver Detail --------------------------------------------------------

    public record QueryReceiverInput(String orderNo, String orgCode, String receiverNo) {}

    public record ReceiverDetailResult(
            String receiverNo,
            String receiverName,
            String contactMobile,
            String licenseNo,
            String licenseName,
            String legalPersonName,
            String legalPersonCertificateType,
            String legalPersonCertificateNo,
            String acctNo,
            String acctName,
            String acctTypeCode,
            String acctCertificateType,
            String acctCertificateNo,
            String acctOpenBankCode,
            String acctOpenBankName,
            String acctClearBankCode,
            String ownerNo) {
        @Override
        public String toString() {
            return "ReceiverDetailResult[receiverNo=" + receiverNo + ",redacted]";
        }
    }

    // ---- 1290 Apply Bind & Async Callback --------------------------------------------------

    public record ApplyBindInput(
            String orderNo,
            String orgCode,
            String merInnerNo,
            String merCupNo,
            String receiverNo,
            String entrustFileName,
            String entrustFilePath,
            String retUrl,
            List<AttachItem> attachments) {
        public ApplyBindInput {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    public record ApplyBindResult(String orderNo, String orgCode, String applyId) {}

    public record BindNotification(
            String applyId,
            String optType,
            String merCupNo,
            String merInnerNo,
            String receiverNo,
            BindAuditStatus auditStatus,
            String auditStatusText,
            String remark,
            String entrustFileName,
            String entrustFilePath) {}

    // ---- 1872 List Relation Apply ----------------------------------------------------------

    public record ListRelationApplyInput(
            String orderNo,
            String orgCode,
            String merInnerNo,
            String merCupNo,
            String receiverNo,
            String applyId,
            String optType,
            String auditStatus,
            int pageSize,
            int pageNum) {}

    public record RelationApplyItem(
            String applyId,
            String optType,
            String orgId,
            String orgName,
            String merInnerNo,
            String merCupNo,
            String receiverNo,
            String receiverName,
            BindAuditStatus auditStatus,
            String remark,
            String rowCreateTm) {}

    public record RelationApplyPageResult(
            int total,
            int pages,
            int pageSize,
            int pageNum,
            List<RelationApplyItem> list) {
        public RelationApplyPageResult {
            list = list == null ? List.of() : List.copyOf(list);
        }
    }

    // ---- Generic v2 Envelope Builders & Parsers (reusable by Stage 5) ----------------------

    /** Endpoint path families this protocol may serialize; anything else is rejected before signing. */
    static boolean isSupportedPath(String path) {
        return path != null && (path.startsWith("/api/v2/") || path.startsWith(EC_PATH_PREFIX));
    }

    /**
     * Wraps {@code reqData} in the Lakala envelope of the endpoint's family (see class comment):
     * mms {@code {"reqData":{...},"ver":"1.0.0","timestamp":"<millis>","reqId":"<32hex>"}},
     * ledger {@code {"reqData":{...},"reqId":"<32hex>","version":"2.0","reqTime":"yyyyMMddHHmmss"}},
     * electronic contract {@code {"req_data":{...},"ver":"1.0.0","timestamp":"<millis>","req_id":"<32hex>"}}.
     */
    public static PreparedV2Request buildV2Request(String path, Object reqData,
            long timestampMillis, String reqId) {
        if (!isSupportedPath(path) || reqData == null || timestampMillis <= 0) {
            invalid();
        }
        requireReqId(reqId);
        boolean electronicContract = path.startsWith(EC_PATH_PREFIX);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(electronicContract ? "req_data" : "reqData", reqData);
        if (path.startsWith(LEDGER_PATH_PREFIX)) {
            envelope.put("reqId", reqId);
            envelope.put("version", LEDGER_ENVELOPE_VERSION);
            envelope.put("reqTime", LEDGER_REQ_TIME.format(
                    Instant.ofEpochMilli(timestampMillis).atZone(CHANNEL_ZONE)));
        } else {
            envelope.put("ver", V2_VER);
            envelope.put("timestamp", Long.toString(timestampMillis));
            // 1212 q_status example: req_data + ver + timestamp + req_id.
            envelope.put(electronicContract ? "req_id" : "reqId", reqId);
        }
        try {
            return new PreparedV2Request(path, JSON.writeValueAsBytes(envelope));
        } catch (IOException failure) {
            throw new ProtocolException("Lakala v2 request serialization failed", failure);
        }
    }

    /** Parses a signed Lakala v2 response body into {@code (retCode, retMsg, respData)}. */
    public static V2Envelope parseV2Envelope(byte[] rawBody) {
        JsonNode root = json(rawBody);
        String retCode = requiredField(root, "retCode", 32);
        String retMsg = optionalFreeText(root, "retMsg", 256);
        JsonNode respData = root.get("respData");
        if (SUCCESS_CODE.equals(retCode)) {
            if (respData == null || !respData.isObject()) {
                invalid();
            }
        } else if (respData != null && respData.isNull()) {
            respData = null;
        }
        return new V2Envelope(retCode, retMsg, respData);
    }

    /**
     * Verifies 1009 3-line callback signature via {@link LakalaProtocol#verifyNotificationSignature}
     * and returns the parsed payload object (unwrapping {@code respData} if wrapped).
     * Reusable by both Stage 4 (1290 ledger bind) and Stage 5 (1272 withdrawal) callbacks.
     */
    public static JsonNode verifyV2Notification(Map<String, String> headers, byte[] rawBody,
            PublicKey trustedNotificationKey) {
        LakalaProtocol.verifyNotificationSignature(headers, rawBody, trustedNotificationKey);
        JsonNode root = json(rawBody);
        JsonNode wrapped = root.get("respData");
        if (wrapped != null && !wrapped.isNull()) {
            if (!wrapped.isObject()) invalid();
            return wrapped;
        }
        return root;
    }

    // ---- MMS Request Builders --------------------------------------------------------------

    public static PreparedV2Request prepareUploadFile(UploadFileInput input,
            long timestampMillis, String reqId) {
        if (input == null) invalid();
        String ext = normalizeExt(input.attExtName());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", MMS_DATA_VERSION);
        data.put("orderNo", required(input.orderNo(), 32));
        data.put("orgCode", required(input.orgCode(), 32));
        data.put("attType", requireAttType(input.attType()));
        data.put("attExtName", ext);
        data.put("attContext", Base64.getEncoder().encodeToString(input.content()));
        return buildV2Request(UPLOAD_FILE_PATH, data, timestampMillis, reqId);
    }

    public static PreparedV2Request prepareApplyReceiver(ApplyReceiverInput input,
            long timestampMillis, String reqId) {
        if (input == null) invalid();
        String acctType = requireIn(input.acctTypeCode(), VALID_ACCT_TYPES);
        String acctCertType = requireIn(input.acctCertificateType(), VALID_CERT_TYPES);
        boolean corporate = "57".equals(acctType);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", MMS_DATA_VERSION);
        data.put("orderNo", required(input.orderNo(), 32));
        data.put("orgCode", required(input.orgCode(), 32));
        data.put("receiverName", required(input.receiverName(), 64));
        data.put("contactMobile", required(input.contactMobile(), 16));
        if (corporate) {
            data.put("licenseNo", required(input.licenseNo(), 32));
            data.put("licenseName", required(input.licenseName(), 128));
            data.put("legalPersonName", required(input.legalPersonName(), 32));
            data.put("legalPersonCertificateType",
                    requireIn(input.legalPersonCertificateType(), VALID_CERT_TYPES));
            data.put("legalPersonCertificateNo", required(input.legalPersonCertificateNo(), 32));
        } else {
            putIfPresent(data, "licenseNo", input.licenseNo(), 32);
            putIfPresent(data, "licenseName", input.licenseName(), 128);
            putIfPresent(data, "legalPersonName", input.legalPersonName(), 32);
            if (nonBlank(input.legalPersonCertificateType())) {
                data.put("legalPersonCertificateType",
                        requireIn(input.legalPersonCertificateType(), VALID_CERT_TYPES));
            }
            putIfPresent(data, "legalPersonCertificateNo", input.legalPersonCertificateNo(), 32);
        }
        data.put("acctNo", required(input.acctNo(), 32));
        data.put("acctName", required(input.acctName(), 64));
        data.put("acctTypeCode", acctType);
        data.put("acctCertificateType", acctCertType);
        data.put("acctCertificateNo", required(input.acctCertificateNo(), 32));
        data.put("acctOpenBankCode", required(input.acctOpenBankCode(), 32));
        data.put("acctOpenBankName", required(input.acctOpenBankName(), 64));
        data.put("acctClearBankCode", required(input.acctClearBankCode(), 32));
        if (!input.attachList().isEmpty()) {
            data.put("attachList", serializeAttachList(input.attachList()));
        }
        // Ruling 40 §5.4: ALWAYS force active withdrawal ("01"); Lakala defaults to "04" if omitted.
        data.put("settleType", ACTIVE_WITHDRAW_SETTLE_TYPE);
        return buildV2Request(APPLY_RECEIVER_PATH, data, timestampMillis, reqId);
    }

    public static PreparedV2Request prepareModifyReceiver(ModifyReceiverInput input,
            long timestampMillis, String reqId) {
        if (input == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", MMS_DATA_VERSION);
        data.put("orderNo", required(input.orderNo(), 32));
        data.put("orgCode", required(input.orgCode(), 32));
        data.put("receiverNo", required(input.receiverNo(), 32));
        putIfPresent(data, "receiverName", input.receiverName(), 64);
        putIfPresent(data, "contactMobile", input.contactMobile(), 16);
        putIfPresent(data, "acctNo", input.acctNo(), 32);
        if (nonBlank(input.acctTypeCode())) {
            data.put("acctTypeCode", requireIn(input.acctTypeCode(), VALID_ACCT_TYPES));
        }
        putIfPresent(data, "acctOpenBankCode", input.acctOpenBankCode(), 32);
        putIfPresent(data, "acctOpenBankName", input.acctOpenBankName(), 64);
        putIfPresent(data, "acctClearBankCode", input.acctClearBankCode(), 32);
        if (!input.attachList().isEmpty()) {
            data.put("attachList", serializeAttachList(input.attachList()));
        }
        if (nonBlank(input.status())) {
            data.put("status", requireIn(input.status(), VALID_RECEIVER_STATUSES));
        }
        // Contract 80 §4.2 / Ruling 40 §5.4: always keep active withdrawal ("01") on modify as well.
        data.put("settleType", ACTIVE_WITHDRAW_SETTLE_TYPE);
        return buildV2Request(MODIFY_RECEIVER_PATH, data, timestampMillis, reqId);
    }

    public static PreparedV2Request prepareQueryReceiver(QueryReceiverInput input,
            long timestampMillis, String reqId) {
        if (input == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", MMS_DATA_VERSION);
        data.put("orderNo", required(input.orderNo(), 32));
        data.put("orgCode", required(input.orgCode(), 32));
        data.put("receiverNo", required(input.receiverNo(), 32));
        return buildV2Request(QUERY_RECEIVER_PATH, data, timestampMillis, reqId);
    }

    public static PreparedV2Request prepareApplyBind(ApplyBindInput input,
            long timestampMillis, String reqId) {
        if (input == null) invalid();
        boolean hasInner = nonBlank(input.merInnerNo());
        boolean hasCup = nonBlank(input.merCupNo());
        if (!hasInner && !hasCup) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", MMS_DATA_VERSION);
        data.put("orderNo", required(input.orderNo(), 32));
        data.put("orgCode", required(input.orgCode(), 32));
        if (hasInner) data.put("merInnerNo", required(input.merInnerNo(), 32));
        if (hasCup) data.put("merCupNo", required(input.merCupNo(), 32));
        data.put("receiverNo", required(input.receiverNo(), 32));
        data.put("entrustFileName", required(input.entrustFileName(), 64));
        data.put("entrustFilePath", required(input.entrustFilePath(), 256));
        data.put("retUrl", required(input.retUrl(), 128));
        if (!input.attachments().isEmpty()) {
            data.put("attachments", serializeAttachList(input.attachments()));
        }
        return buildV2Request(APPLY_BIND_PATH, data, timestampMillis, reqId);
    }

    public static PreparedV2Request prepareListRelationApply(ListRelationApplyInput input,
            long timestampMillis, String reqId) {
        if (input == null || input.pageSize() < 1 || input.pageSize() > 99 || input.pageNum() < 1) {
            invalid();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", MMS_DATA_VERSION);
        data.put("orderNo", required(input.orderNo(), 32));
        data.put("orgCode", required(input.orgCode(), 32));
        putIfPresent(data, "merInnerNo", input.merInnerNo(), 32);
        putIfPresent(data, "merCupNo", input.merCupNo(), 32);
        putIfPresent(data, "receiverNo", input.receiverNo(), 32);
        if (nonBlank(input.applyId())) {
            String apply = required(input.applyId(), 64);
            if (NUMERIC_LONG.matcher(apply).matches()) {
                try {
                    data.put("applyId", Long.parseLong(apply));
                } catch (NumberFormatException overflow) {
                    data.put("applyId", apply);
                }
            } else {
                data.put("applyId", apply);
            }
        }
        if (nonBlank(input.optType())) {
            data.put("optType", requireIn(input.optType(), VALID_OPT_TYPES));
        }
        if (nonBlank(input.auditStatus())) {
            parseAuditStatus(input.auditStatus(), true);
            data.put("auditStatus", input.auditStatus());
        }
        data.put("pageSize", input.pageSize());
        data.put("pageNum", input.pageNum());
        return buildV2Request(LIST_RELATION_APPLY_PATH, data, timestampMillis, reqId);
    }

    // ---- MMS Response & Notification Parsers -----------------------------------------------

    public static UploadFileResult parseUploadFileRespData(JsonNode data,
            String expectedOrderNo, String expectedAttType) {
        if (data == null || !data.isObject()) invalid();
        String orgCode = optionalField(data, "orgCode", 32);
        String orderNo = optionalField(data, "orderNo", 32);
        if (orderNo != null && expectedOrderNo != null && !expectedOrderNo.equals(orderNo)) {
            invalid();
        }
        String attType = optionalField(data, "attType", 32);
        if (attType != null && expectedAttType != null && !expectedAttType.equals(attType)) {
            invalid();
        }
        String attFileId = requiredField(data, "attFileId", 256);
        return new UploadFileResult(orgCode, orderNo == null ? expectedOrderNo : orderNo,
                attFileId, attType == null ? expectedAttType : attType);
    }

    public static ReceiverActionResult parseApplyReceiverRespData(JsonNode data, String expectedOrderNo) {
        if (data == null || !data.isObject()) invalid();
        String orderNo = optionalField(data, "orderNo", 32);
        if (orderNo != null && expectedOrderNo != null && !expectedOrderNo.equals(orderNo)) {
            invalid();
        }
        String orgCode = optionalField(data, "orgCode", 32);
        String orgId = optionalStringOrNumber(data, "orgId", 32);
        String orgName = optionalFreeText(data, "orgName", 128);
        String receiverNo = requiredField(data, "receiverNo", 32);
        return new ReceiverActionResult(orderNo == null ? expectedOrderNo : orderNo,
                orgCode, orgId, orgName, receiverNo);
    }

    public static ReceiverActionResult parseModifyReceiverRespData(JsonNode data, String expectedReceiverNo) {
        if (data == null || !data.isObject()) invalid();
        String orderNo = optionalField(data, "orderNo", 32);
        String orgCode = optionalField(data, "orgCode", 32);
        String orgId = optionalStringOrNumber(data, "orgId", 32);
        String orgName = optionalFreeText(data, "orgName", 128);
        String receiverNo = requiredField(data, "receiverNo", 32);
        if (expectedReceiverNo != null && !expectedReceiverNo.equals(receiverNo)) {
            invalid();
        }
        return new ReceiverActionResult(orderNo, orgCode, orgId, orgName, receiverNo);
    }

    public static ReceiverDetailResult parseQueryReceiverRespData(JsonNode data, String expectedReceiverNo) {
        if (data == null || !data.isObject()) invalid();
        String receiverNo = requiredField(data, "receiverNo", 32);
        if (expectedReceiverNo != null && !expectedReceiverNo.equals(receiverNo)) {
            invalid();
        }
        return new ReceiverDetailResult(
                receiverNo,
                optionalFreeText(data, "receiverName", 128),
                optionalFreeText(data, "contactMobile", 32),
                optionalFreeText(data, "licenseNo", 64),
                optionalFreeText(data, "licenseName", 128),
                optionalFreeText(data, "legalPersonName", 64),
                optionalFreeText(data, "legalPersonCertificateType", 32),
                optionalFreeText(data, "legalPersonCertificateNo", 64),
                optionalFreeText(data, "acctNo", 64),
                optionalFreeText(data, "acctName", 64),
                optionalFreeText(data, "acctTypeCode", 32),
                optionalFreeText(data, "acctCertificateType", 32),
                optionalFreeText(data, "acctCertificateNo", 64),
                optionalFreeText(data, "acctOpenBankCode", 32),
                optionalFreeText(data, "acctOpenBankName", 128),
                optionalFreeText(data, "acctClearBankCode", 32),
                optionalFreeText(data, "ownerNo", 128));
    }

    public static ApplyBindResult parseApplyBindRespData(JsonNode data, String expectedOrderNo) {
        if (data == null || !data.isObject()) invalid();
        String orderNo = optionalField(data, "orderNo", 32);
        if (orderNo != null && expectedOrderNo != null && !expectedOrderNo.equals(orderNo)) {
            invalid();
        }
        String orgCode = optionalField(data, "orgCode", 32);
        String applyId = requiredStringOrNumber(data, "applyId", 64);
        return new ApplyBindResult(orderNo == null ? expectedOrderNo : orderNo, orgCode, applyId);
    }

    /** Verifies 1009 signature and parses a 1290 bind audit callback ({@code auditStatus} 1/2). */
    public static BindNotification verifyBindNotification(Map<String, String> headers, byte[] rawBody,
            PublicKey trustedNotificationKey) {
        JsonNode data = verifyV2Notification(headers, rawBody, trustedNotificationKey);
        String applyId = requiredStringOrNumber(data, "applyId", 64);
        String receiverNo = requiredField(data, "receiverNo", 32);
        String rawAudit = requiredStringOrNumber(data, "auditStatus", 8);
        BindAuditStatus auditStatus = parseAuditStatus(rawAudit, false);
        return new BindNotification(
                applyId,
                optionalFreeText(data, "optType", 32),
                optionalField(data, "merCupNo", 32),
                optionalField(data, "merInnerNo", 32),
                receiverNo,
                auditStatus,
                optionalFreeText(data, "auditStatusText", 64),
                optionalFreeText(data, "remark", 512),
                optionalFreeText(data, "entrustFileName", 64),
                optionalFreeText(data, "entrustFilePath", 256));
    }

    public static RelationApplyPageResult parseListRelationApplyRespData(JsonNode respData) {
        if (respData == null || !respData.isObject()) invalid();
        // 1872 docs show total/pages/pageSize/pageNum/list directly in respData or under respData.data.
        JsonNode page = respData;
        if (!respData.has("list") && respData.has("data") && respData.get("data").isObject()) {
            page = respData.get("data");
        }
        int total = optionalInt(page, "total", 0);
        int pages = optionalInt(page, "pages", 0);
        int pageSize = optionalInt(page, "pageSize", 0);
        int pageNum = optionalInt(page, "pageNum", 1);
        JsonNode listNode = page.get("list");
        List<RelationApplyItem> items = new ArrayList<>();
        if (listNode != null && !listNode.isNull()) {
            if (!listNode.isArray()) invalid();
            for (JsonNode entry : listNode) {
                if (entry == null || !entry.isObject()) invalid();
                String applyId = requiredStringOrNumber(entry, "applyId", 64);
                String receiverNo = requiredField(entry, "receiverNo", 32);
                String rawAudit = requiredStringOrNumber(entry, "auditStatus", 8);
                BindAuditStatus auditStatus = parseAuditStatus(rawAudit, true);
                items.add(new RelationApplyItem(
                        applyId,
                        optionalFreeText(entry, "optType", 32),
                        optionalStringOrNumber(entry, "orgId", 32),
                        optionalFreeText(entry, "orgName", 128),
                        optionalField(entry, "merInnerNo", 32),
                        optionalField(entry, "merCupNo", 32),
                        receiverNo,
                        optionalFreeText(entry, "receiverName", 128),
                        auditStatus,
                        optionalFreeText(entry, "remark", 512),
                        optionalFreeText(entry, "rowCreateTm", 64)));
            }
        }
        return new RelationApplyPageResult(total, pages, pageSize, pageNum, items);
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private static List<Map<String, Object>> serializeAttachList(List<AttachItem> items) {
        List<Map<String, Object>> list = new ArrayList<>(items.size());
        for (AttachItem item : items) {
            if (item == null) invalid();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("attachType", item.attachType());
            entry.put("attachName", item.attachName());
            entry.put("attachStorePath", item.attachStorePath());
            list.add(entry);
        }
        return list;
    }

    private static BindAuditStatus parseAuditStatus(String raw, boolean allowPending) {
        return switch (raw) {
            case "0" -> {
                if (!allowPending) invalid();
                yield BindAuditStatus.PENDING;
            }
            case "1" -> BindAuditStatus.APPROVED;
            case "2" -> BindAuditStatus.REJECTED;
            default -> {
                invalid();
                throw new AssertionError();
            }
        };
    }

    /** Serializes with the strict shared mapper (same configuration as every envelope). */
    static byte[] toJsonBytes(Object value) throws IOException {
        if (value == null) invalid();
        return JSON.writeValueAsBytes(value);
    }

    /** Validates the 32-hex request id shared by every envelope family. */
    static String requireReqId(String reqId) {
        if (reqId == null || !REQ_ID.matcher(reqId).matches()) invalid();
        return reqId;
    }

    static String normalizeExt(String raw) {
        String ext = required(raw, 16).trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) ext = ext.substring(1);
        if ("jpeg".equals(ext)) ext = "jpg";
        if (!VALID_EXT_NAMES.contains(ext)) invalid();
        return ext;
    }

    static String requireAttType(String value) {
        String checked = required(value, 64);
        if (!VALID_ATT_TYPES.contains(checked)) invalid();
        return checked;
    }

    private static String requireIn(String value, Set<String> allowed) {
        String checked = required(value, 32);
        if (!allowed.contains(checked)) invalid();
        return checked;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value, int max) {
        if (nonBlank(value)) {
            map.put(key, required(value, max));
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    static JsonNode json(byte[] body) {
        if (body == null || body.length == 0) invalid();
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            JsonNode parsed = JSON.readTree(utf8);
            if (parsed == null || !parsed.isObject()) invalid();
            return parsed;
        } catch (IOException failure) {
            throw new ProtocolException("Lakala v2 JSON is invalid", failure);
        }
    }

    static String requiredField(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isTextual()) invalid();
        return required(node.textValue(), max);
    }

    static String optionalField(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) invalid();
        String text = node.textValue();
        if (text.isEmpty()) return null;
        return required(text, max);
    }

    static String requiredStringOrNumber(JsonNode parent, String name, int max) {
        String value = optionalStringOrNumber(parent, name, max);
        if (value == null) invalid();
        return value;
    }

    static String optionalStringOrNumber(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            String text = node.textValue().trim();
            return text.isEmpty() ? null : required(text, max);
        }
        if (node.isIntegralNumber()) {
            return required(node.bigIntegerValue().toString(), max);
        }
        invalid();
        return null;
    }

    static String optionalFreeText(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) invalid();
        String text = node.textValue().trim();
        if (text.isEmpty()) return null;
        if (text.codePoints().anyMatch(Character::isISOControl)) invalid();
        return text.length() > max ? text.substring(0, max) : text;
    }

    static int optionalInt(JsonNode parent, String name, int fallback) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return fallback;
        if (node.isIntegralNumber() && node.canConvertToInt()) return node.intValue();
        if (node.isTextual() && node.textValue().matches("[0-9]{1,9}")) {
            return Integer.parseInt(node.textValue());
        }
        invalid();
        return fallback;
    }

    static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            invalid();
        }
        return value;
    }

    static void invalid() {
        throw new ProtocolException("Lakala v2 protocol fact is invalid");
    }

    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
