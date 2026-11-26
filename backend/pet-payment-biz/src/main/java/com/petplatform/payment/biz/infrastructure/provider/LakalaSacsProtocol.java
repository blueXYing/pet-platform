package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline Lakala v3 SACS (order split / settlement) protocol primitives (Contract 78 §4).
 *
 * <p>Verified against authoritative local Lakala docs (2026-11-25) and SDK 1.0.8 field reference:
 * /2396 (queryAmt), /1293 (separate), /1296 (query_plus), /1297 (split notification), /1004 &
 * /1009 (signature security). No network call or credential lookup happens here.
 */
public final class LakalaSacsProtocol {
    public static final String QUERY_AMT_PATH = "/api/v3/sacs/queryAmt";
    public static final String SEPARATE_PATH = "/api/v3/sacs/separate";
    public static final String FALLBACK_PATH = "/api/v3/sacs/fallback";
    public static final String QUERY_PLUS_PATH = "/api/v3/sacs/query_plus";
    public static final String SUCCESS_CODE = "SACS0000";
    public static final String NOTIFICATION_ACK_JSON = "{\"code\":\"SUCCESS\",\"message\":\"执行成功\"}";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DateTimeFormatter REQ_TIME = DateTimeFormatter
            .ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE_8 = DateTimeFormatter
            .ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern LOG_NO = Pattern.compile("[0-9A-Za-z]{1,32}");
    private static final Pattern NON_NEGATIVE_CENTS = Pattern.compile("0|[1-9][0-9]{0,14}");
    private static final Pattern POSITIVE_CENTS = Pattern.compile("[1-9][0-9]{0,14}");
    private static final Set<String> SUBMIT_STATES = Set.of("ACCEPTED", "PROCESSING", "SUCCESS", "FAIL");
    private static final Set<String> FINAL_STATES = Set.of("ACCEPTED", "PROCESSING", "SUCCESS", "FAIL");
    private static final Set<String> ALL_CHANNEL_STATES = Set.of(
            "ACCEPTED", "PROCESSING", "FAIL", "SUCCESS",
            "CANCELING", "CANCELED", "CANCEL_FAIL", "FALLBACKING", "FALLBACK_END");

    private LakalaSacsProtocol() {}

    public enum CmdType { SEPARATE, CANCEL, FALLBACK }

    public enum SplitStatus {
        ACCEPTED, PROCESSING, SUCCESS, FAIL;
        public boolean isTerminal() { return this == SUCCESS || this == FAIL; }
    }

    public record PreparedRequest(String path, byte[] rawBody) {
        public PreparedRequest { rawBody = rawBody.clone(); }
        @Override public byte[] rawBody() { return rawBody.clone(); }
    }

    public record QueryAmtInput(LocalDateTime requestTime, String merchantNo, String logNo, LocalDate logDate) {}

    public record QueryAmtResult(String merchantNo, String logNo, LocalDate logDate,
            long totalSeparateCents, long canSeparateCents) {
        public BigDecimal totalSeparateAmount() { return BigDecimal.valueOf(totalSeparateCents, 2); }
        public BigDecimal canSeparateAmount() { return BigDecimal.valueOf(canSeparateCents, 2); }
    }

    /**
     * One entry in 1293 {@code req_data.recv_datas}: either {@code recv_merchant_no} (self-split to
     * {@code req_data.merchant_no}) or {@code recv_no} (external split receiver {@code SR...}).
     */
    public record ReceiverShare(String recvMerchantNo, String recvNo, long amountCents) {
        public ReceiverShare {
            boolean hasMerchant = recvMerchantNo != null && !recvMerchantNo.isBlank();
            boolean hasRecv = recvNo != null && !recvNo.isBlank();
            if (hasMerchant == hasRecv || amountCents <= 0) invalid();
            if (hasMerchant) required(recvMerchantNo, 32);
            if (hasRecv) required(recvNo, 32);
        }

        /** Self-split share back to the acquiring {@code req_data.merchant_no} ({@code recv_merchant_no}). */
        public static ReceiverShare toSelf(String merchantNo, long amountCents) {
            return new ReceiverShare(merchantNo, null, amountCents);
        }

        public static ReceiverShare toPlatform(String merchantNo, long amountCents) {
            return toSelf(merchantNo, amountCents);
        }

        public static ReceiverShare toReceiver(String receiverNo, long amountCents) {
            return new ReceiverShare(null, receiverNo, amountCents);
        }
    }

    public record SeparateInput(LocalDateTime requestTime, String merchantNo, String logNo,
            LocalDate logDate, String outSeparateNo, long totalAmountCents, String lklOrgNo,
            String notifyUrl, List<ReceiverShare> recvDatas, String remark) {
        public SeparateInput {
            recvDatas = recvDatas == null ? List.of() : List.copyOf(recvDatas);
        }
        @Override public String toString() { return "SeparateInput[outSeparateNo=" + outSeparateNo + "]"; }
    }

    public record SeparateSubmitResult(String outSeparateNo, String separateNo, SplitStatus status,
            String rawStatus, String logNo, LocalDate logDate, Long totalAmountCents) {
        @Override public String toString() {
            return "SeparateSubmitResult[outSeparateNo=" + outSeparateNo + ",status=" + status + "]";
        }
    }

    /** Lakala 2395 /api/v3/sacs/fallback input: single merchant receiver per request. */
    public record FallbackInput(LocalDateTime requestTime, String merchantNo,
            String originSeparateNo, String originOutSeparateNo, String outSeparateNo,
            long totalAmountCents, String receiverNo, String fallbackReason) {
        @Override public String toString() {
            return "FallbackInput[outSeparateNo=" + outSeparateNo + ",totalAmountCents=" + totalAmountCents + "]";
        }
    }

    public record FallbackSubmitResult(String outSeparateNo, String separateNo,
            String originSeparateNo, String originOutSeparateNo, long totalAmountCents,
            SplitStatus status, String rawStatus) {
        @Override public String toString() {
            return "FallbackSubmitResult[outSeparateNo=" + outSeparateNo + ",status=" + status + "]";
        }
    }

    public record QueryPlusInput(LocalDateTime requestTime, String merchantNo,
            String separateNo, String outSeparateNo) {}

    public record ExpectedSplit(String outSeparateNo, String separateNo, CmdType expectedCmdType,
            String expectedLogNo, LocalDate expectedLogDate) {
        public ExpectedSplit {
            required(outSeparateNo, 32);
            if (separateNo != null && !separateNo.isBlank()) required(separateNo, 32);
            if (expectedLogNo != null && !LOG_NO.matcher(expectedLogNo).matches()) invalid();
        }
    }

    public record SplitDetail(String recvMerchantNo, String recvNo, long amountCents,
            Long actualAmountCents, Long feeAmountCents, String remark) {
        public BigDecimal amount() { return BigDecimal.valueOf(amountCents, 2); }
        public BigDecimal actualAmount() {
            return actualAmountCents == null ? null : BigDecimal.valueOf(actualAmountCents, 2);
        }
        public BigDecimal feeAmount() {
            return feeAmountCents == null ? null : BigDecimal.valueOf(feeAmountCents, 2);
        }
    }

    public record SplitOutcome(String separateNo, String outSeparateNo, CmdType cmdType,
            String logNo, LocalDate logDate, String calType, LocalDate separateDate,
            LocalDate finishDate, Long totalAmountCents, String rawStatus, String rawFinalStatus,
            SplitStatus effectiveStatus, Long actualSeparateCents, Long totalFeeCents,
            String accResultDesc, List<SplitDetail> details) {
        public SplitOutcome {
            details = details == null ? List.of() : List.copyOf(details);
        }

        public boolean isTerminal() { return effectiveStatus.isTerminal(); }
        public boolean isSuccess() { return effectiveStatus == SplitStatus.SUCCESS; }
        public boolean isFail() { return effectiveStatus == SplitStatus.FAIL; }

        public Optional<SplitDetail> findReceiverDetail(String receiverNo) {
            if (receiverNo == null || receiverNo.isBlank()) return Optional.empty();
            return details.stream().filter(d -> receiverNo.equals(d.recvNo())).findFirst();
        }

        public Optional<SplitDetail> findMerchantDetail(String merchantNo) {
            if (merchantNo == null || merchantNo.isBlank()) return Optional.empty();
            return details.stream().filter(d -> merchantNo.equals(d.recvMerchantNo())).findFirst();
        }
    }

    // ---- Request builders ------------------------------------------------------------------

    public static PreparedRequest prepareQueryAmt(QueryAmtInput input) {
        if (input == null || input.logDate() == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", required(input.merchantNo(), 32));
        data.put("log_no", logNo(input.logNo()));
        data.put("log_date", DATE_8.format(input.logDate()));
        return prepare(QUERY_AMT_PATH, input.requestTime(), data);
    }

    public static PreparedRequest prepareSeparate(SeparateInput input) {
        if (input == null || input.logDate() == null || input.totalAmountCents() <= 0
                || input.recvDatas().isEmpty()) invalid();
        String merchantNo = required(input.merchantNo(), 32);
        long sum = 0;
        List<Map<String, Object>> recvList = new ArrayList<>(input.recvDatas().size());
        for (ReceiverShare share : input.recvDatas()) {
            if (share == null) invalid();
            if (share.recvMerchantNo() != null && !merchantNo.equals(share.recvMerchantNo())) invalid();
            sum = Math.addExact(sum, share.amountCents());
            Map<String, Object> entry = new LinkedHashMap<>();
            if (share.recvMerchantNo() != null) entry.put("recv_merchant_no", share.recvMerchantNo());
            else entry.put("recv_no", share.recvNo());
            entry.put("separate_value", Long.toString(share.amountCents()));
            recvList.add(entry);
        }
        if (sum != input.totalAmountCents()) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", merchantNo);
        data.put("log_no", logNo(input.logNo()));
        data.put("log_date", DATE_8.format(input.logDate()));
        data.put("out_separate_no", required(input.outSeparateNo(), 32));
        data.put("total_amt", positiveCents(input.totalAmountCents()));
        if (input.lklOrgNo() != null && !input.lklOrgNo().isBlank()) {
            data.put("lkl_org_no", required(input.lklOrgNo(), 16));
        }
        // Contract 78 §3 / Ruling 40 §2.5: always submit fixed amount mode (cal_type=0).
        data.put("cal_type", "0");
        if (input.notifyUrl() != null && !input.notifyUrl().isBlank()) {
            data.put("notify_url", required(input.notifyUrl(), 128));
        }
        data.put("recv_datas", recvList);
        if (input.remark() != null && !input.remark().isBlank()) {
            data.put("remark", required(input.remark(), 256));
        }
        return prepare(SEPARATE_PATH, input.requestTime(), data);
    }

    public static PreparedRequest prepareFallback(FallbackInput input) {
        if (input == null || input.totalAmountCents() <= 0) invalid();
        boolean hasOriginSep = input.originSeparateNo() != null && !input.originSeparateNo().isBlank();
        boolean hasOriginOut = input.originOutSeparateNo() != null && !input.originOutSeparateNo().isBlank();
        if (!hasOriginSep && !hasOriginOut) invalid();
        String amountStr = positiveCents(input.totalAmountCents());
        Map<String, Object> recv = new LinkedHashMap<>();
        recv.put("recv_no", required(input.receiverNo(), 32));
        recv.put("amt", amountStr);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", required(input.merchantNo(), 32));
        if (hasOriginSep) data.put("origin_separate_no", required(input.originSeparateNo(), 32));
        if (hasOriginOut) data.put("origin_out_separate_no", required(input.originOutSeparateNo(), 32));
        data.put("out_separate_no", required(input.outSeparateNo(), 32));
        if (input.fallbackReason() != null && !input.fallbackReason().isBlank()) {
            data.put("fallback_reason", required(input.fallbackReason().trim(), 128));
        }
        data.put("total_amt", amountStr);
        data.put("origin_recv_datas", List.of(recv));
        return prepare(FALLBACK_PATH, input.requestTime(), data);
    }

    public static PreparedRequest prepareQueryPlus(QueryPlusInput input) {
        if (input == null) invalid();
        boolean hasSep = input.separateNo() != null && !input.separateNo().isBlank();
        boolean hasOut = input.outSeparateNo() != null && !input.outSeparateNo().isBlank();
        if (!hasSep && !hasOut) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", required(input.merchantNo(), 32));
        if (hasSep) data.put("separate_no", required(input.separateNo(), 32));
        if (hasOut) data.put("out_separate_no", required(input.outSeparateNo(), 32));
        return prepare(QUERY_PLUS_PATH, input.requestTime(), data);
    }

    private static PreparedRequest prepare(String path, LocalDateTime time, Map<String, Object> data) {
        if (time == null || time.getNano() != 0) invalid();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("req_time", REQ_TIME.format(time));
        envelope.put("version", "3.0");
        envelope.put("req_data", data);
        try {
            return new PreparedRequest(path, JSON.writeValueAsBytes(envelope));
        } catch (IOException failure) {
            throw new ProtocolException("Lakala SACS request serialization failed", failure);
        }
    }

    // ---- Response & notification verifiers -------------------------------------------------

    public static QueryAmtResult verifyQueryAmtResponse(Map<String, String> headers, byte[] rawBody,
            String appId, String platformSerial, PublicKey platformKey,
            String expectedMerchantNo, String expectedLogNo, LocalDate expectedLogDate) {
        if (expectedLogDate == null) invalid();
        required(expectedMerchantNo, 32);
        logNo(expectedLogNo);
        LakalaProtocol.verifyResponse(headers, rawBody, appId, platformSerial, platformKey);
        JsonNode data = successData(rawBody);
        String merchantNo = field(data, "merchant_no", 32);
        String logNo = logNo(field(data, "log_no", 32));
        LocalDate logDate = requiredDate(data, "log_date");
        if (!merchantNo.equals(expectedMerchantNo) || !logNo.equals(expectedLogNo)
                || !logDate.equals(expectedLogDate)) invalid();
        long total = centsRequired(data, "total_separate_amt", false);
        long can = centsRequired(data, "can_separate_amt", false);
        return new QueryAmtResult(merchantNo, logNo, logDate, total, can);
    }

    public static SeparateSubmitResult verifySeparateResponse(Map<String, String> headers,
            byte[] rawBody, String appId, String platformSerial, PublicKey platformKey,
            String expectedOutSeparateNo) {
        required(expectedOutSeparateNo, 32);
        LakalaProtocol.verifyResponse(headers, rawBody, appId, platformSerial, platformKey);
        JsonNode data = successData(rawBody);
        String outSeparateNo = field(data, "out_separate_no", 32);
        if (!outSeparateNo.equals(expectedOutSeparateNo)) invalid();
        String separateNo = field(data, "separate_no", 32);
        String rawStatus = optionalField(data, "status", 16);
        SplitStatus status;
        if (rawStatus == null) {
            status = SplitStatus.PROCESSING;
        } else {
            if (!SUBMIT_STATES.contains(rawStatus)) invalid();
            status = SplitStatus.valueOf(rawStatus);
        }
        String logNo = optionalField(data, "log_no", 32);
        if (logNo != null) logNo(logNo);
        LocalDate logDate = optionalDate(data, "log_date");
        Long totalAmt = centsOptional(data, "total_amt", false);
        return new SeparateSubmitResult(outSeparateNo, separateNo, status, rawStatus, logNo, logDate, totalAmt);
    }

    public static FallbackSubmitResult verifyFallbackResponse(Map<String, String> headers,
            byte[] rawBody, String appId, String platformSerial, PublicKey platformKey,
            String expectedOutSeparateNo, long expectedTotalAmountCents) {
        required(expectedOutSeparateNo, 32);
        if (expectedTotalAmountCents <= 0) invalid();
        LakalaProtocol.verifyResponse(headers, rawBody, appId, platformSerial, platformKey);
        JsonNode data = successData(rawBody);
        String outSeparateNo = field(data, "out_separate_no", 32);
        if (!outSeparateNo.equals(expectedOutSeparateNo)) invalid();
        long totalAmt = centsRequired(data, "total_amt", true);
        if (totalAmt != expectedTotalAmountCents) invalid();
        String separateNo = field(data, "separate_no", 32);
        String originSeparateNo = optionalField(data, "origin_separate_no", 32);
        String originOutSeparateNo = optionalField(data, "origin_out_separate_no", 32);
        String rawStatus = optionalField(data, "status", 16);
        SplitStatus status;
        if (rawStatus == null) {
            status = SplitStatus.PROCESSING;
        } else {
            if (!SUBMIT_STATES.contains(rawStatus)) invalid();
            status = SplitStatus.valueOf(rawStatus);
        }
        return new FallbackSubmitResult(outSeparateNo, separateNo, originSeparateNo, originOutSeparateNo,
                totalAmt, status, rawStatus);
    }

    public static SplitOutcome verifyQueryPlusResponse(Map<String, String> headers, byte[] rawBody,
            String appId, String platformSerial, PublicKey platformKey, ExpectedSplit expected) {
        if (expected == null) invalid();
        LakalaProtocol.verifyResponse(headers, rawBody, appId, platformSerial, platformKey);
        JsonNode data = successData(rawBody);
        SplitOutcome outcome = parseOutcome(data, true);
        matchExpected(outcome, expected);
        return outcome;
    }

    /** Verifies 1009 signature and parses a 1297 split notification body. */
    public static SplitOutcome verifyNotification(Map<String, String> headers, byte[] rawBody,
            PublicKey trustedNotificationKey) {
        LakalaProtocol.verifyNotificationSignature(headers, rawBody, trustedNotificationKey);
        JsonNode data = json(rawBody);
        return parseOutcome(data, false);
    }

    private static SplitOutcome parseOutcome(JsonNode data, boolean requireLogPair) {
        String separateNo = field(data, "separate_no", 32);
        String outSeparateNo = field(data, "out_separate_no", 32);
        CmdType cmdType = parseCmdType(field(data, "cmd_type", 32));
        String logNo = requireLogPair ? logNo(field(data, "log_no", 32)) : optionalField(data, "log_no", 32);
        if (logNo != null) logNo(logNo);
        LocalDate logDate = requireLogPair ? requiredDate(data, "log_date") : optionalDate(data, "log_date");
        String calType = optionalField(data, "cal_type", 2);
        LocalDate separateDate = optionalDate(data, "separate_date");
        LocalDate finishDate = optionalDate(data, "finish_date");
        Long totalAmt = centsOptional(data, "total_amt", false);
        String rawStatus = field(data, "status", 32);
        if (!ALL_CHANNEL_STATES.contains(rawStatus)) invalid();
        String rawFinalStatus = optionalField(data, "final_status", 32);
        if (rawFinalStatus != null && !FINAL_STATES.contains(rawFinalStatus)) invalid();
        SplitStatus effective;
        if (rawFinalStatus != null) {
            effective = SplitStatus.valueOf(rawFinalStatus);
        } else if (FINAL_STATES.contains(rawStatus)) {
            effective = SplitStatus.valueOf(rawStatus);
        } else {
            invalid();
            throw new AssertionError();
        }
        Long actualSeparate = centsOptional(data, "actual_separate_amt", false);
        Long totalFee = centsOptional(data, "total_fee_amt", false);
        String accResultDesc = optionalFreeText(data, "acc_result_desc", 256);
        List<SplitDetail> details = parseDetails(data.get("detail_datas"));
        return new SplitOutcome(separateNo, outSeparateNo, cmdType, logNo, logDate, calType,
                separateDate, finishDate, totalAmt, rawStatus, rawFinalStatus, effective,
                actualSeparate, totalFee, accResultDesc, details);
    }

    private static List<SplitDetail> parseDetails(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) invalid();
        List<SplitDetail> items = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            if (entry == null || !entry.isObject()) invalid();
            String recvMerchantNo = optionalField(entry, "recv_merchant_no", 32);
            String recvNo = optionalField(entry, "recv_no", 32);
            if (recvMerchantNo == null && recvNo == null) invalid();
            long amt = centsRequired(entry, "amt", false);
            Long actualAmt = centsOptional(entry, "actual_amt", false);
            Long feeAmt = centsOptional(entry, "fee_amt", false);
            String remark = optionalFreeText(entry, "remark", 256);
            items.add(new SplitDetail(recvMerchantNo, recvNo, amt, actualAmt, feeAmt, remark));
        }
        return items;
    }

    private static void matchExpected(SplitOutcome outcome, ExpectedSplit expected) {
        if (!expected.outSeparateNo().equals(outcome.outSeparateNo())) invalid();
        if (expected.separateNo() != null && !expected.separateNo().isBlank()
                && !expected.separateNo().equals(outcome.separateNo())) invalid();
        if (expected.expectedCmdType() != null && expected.expectedCmdType() != outcome.cmdType()) invalid();
        if (expected.expectedLogNo() != null && !expected.expectedLogNo().equals(outcome.logNo())) invalid();
        if (expected.expectedLogDate() != null && !expected.expectedLogDate().equals(outcome.logDate())) invalid();
    }

    // ---- Parsing helpers -------------------------------------------------------------------

    private static JsonNode successData(byte[] rawBody) {
        JsonNode envelope = json(rawBody);
        if (!SUCCESS_CODE.equals(field(envelope, "code", 32))) invalid();
        JsonNode snake = envelope.get("resp_data");
        // 1296 failure example uses camelCase "respData" alongside code="SACS0000".
        JsonNode camel = envelope.get("respData");
        if (snake != null && camel != null) invalid();
        JsonNode chosen = snake != null ? snake : camel;
        if (chosen == null || !chosen.isObject()) invalid();
        return chosen;
    }

    private static JsonNode json(byte[] body) {
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
            throw new ProtocolException("Lakala SACS JSON is invalid", failure);
        }
    }

    private static CmdType parseCmdType(String raw) {
        try { return CmdType.valueOf(raw); }
        catch (IllegalArgumentException failure) { throw new ProtocolException("Invalid SACS cmd_type"); }
    }

    private static String field(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isTextual()) invalid();
        return required(node.textValue(), max);
    }

    private static String optionalField(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) invalid();
        String text = node.textValue();
        if (text.isEmpty()) return null;
        return required(text, max);
    }

    private static String optionalFreeText(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) invalid();
        String text = node.textValue().trim();
        if (text.isEmpty()) return null;
        if (text.codePoints().anyMatch(Character::isISOControl)) invalid();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static long centsRequired(JsonNode parent, String name, boolean positive) {
        Long value = centsOptional(parent, name, positive);
        if (value == null) invalid();
        return value;
    }

    /** Accepts string or integral JSON number because 1296 detail_datas uses numeric amt in samples. */
    private static Long centsOptional(JsonNode parent, String name, boolean positive) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return null;
        String text;
        if (node.isTextual()) {
            text = node.textValue();
            if (text.isEmpty()) return null;
        } else if (node.isIntegralNumber() && node.canConvertToLong()) {
            text = Long.toString(node.longValue());
        } else {
            invalid();
            return null;
        }
        Pattern pattern = positive ? POSITIVE_CENTS : NON_NEGATIVE_CENTS;
        if (!pattern.matcher(text).matches()) invalid();
        try { return Long.parseLong(text); }
        catch (NumberFormatException failure) { throw new ProtocolException("Invalid SACS cents value"); }
    }

    private static LocalDate requiredDate(JsonNode parent, String name) {
        LocalDate value = optionalDate(parent, name);
        if (value == null) invalid();
        return value;
    }

    private static LocalDate optionalDate(JsonNode parent, String name) {
        String text = optionalField(parent, name, 8);
        if (text == null) return null;
        if (!text.matches("[0-9]{8}")) invalid();
        try { return LocalDate.parse(text, DATE_8); }
        catch (DateTimeParseException failure) { throw new ProtocolException("Invalid SACS date"); }
    }

    private static String logNo(String value) {
        if (value == null || !LOG_NO.matcher(value).matches()) invalid();
        return value;
    }

    private static String positiveCents(long cents) {
        String text = Long.toString(cents);
        if (cents <= 0 || !POSITIVE_CENTS.matcher(text).matches()) invalid();
        return text;
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) invalid();
        return value;
    }

    private static void invalid() { throw new ProtocolException("Lakala SACS protocol fact is invalid"); }

    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String message) { super(message); }
        public ProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
