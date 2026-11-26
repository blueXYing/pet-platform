package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.PreparedRequest;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline Lakala v3 SACS reconciliation protocol primitives for Stage 6:
 * <ul>
 *   <li>1879 ({@code /api/v3/sacs/queryResultList}): split result list query (span <= 7d, page_size <= 50)</li>
 *   <li>1877 ({@code /api/v3/sacs/queryDetail}): split detail query (span <= 30d, page_size <= 50)</li>
 * </ul>
 *
 * <p>Verified against authoritative Lakala PDFs 1879 and 1877. Reuses 1004 response signature
 * verification from {@link LakalaProtocol#verifyResponse}. No network call happens here.
 */
public final class LakalaSacsReconcileProtocol {
    public static final String QUERY_RESULT_LIST_PATH = "/api/v3/sacs/queryResultList";
    public static final String QUERY_DETAIL_PATH = "/api/v3/sacs/queryDetail";
    public static final String SUCCESS_CODE = "SACS0000";
    public static final int MAX_PAGE_SIZE = 50;
    public static final long MAX_RESULT_LIST_SPAN_DAYS = 7L;
    public static final long MAX_DETAIL_SPAN_DAYS = 30L;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DateTimeFormatter REQ_TIME = DateTimeFormatter
            .ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE_8 = DateTimeFormatter
            .ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern LOG_NO = Pattern.compile("[0-9A-Za-z]{1,32}");
    private static final Pattern NON_NEGATIVE_CENTS = Pattern.compile("0|[1-9][0-9]{0,14}");
    private static final Pattern NON_NEGATIVE_INT = Pattern.compile("0|[1-9][0-9]{0,8}");
    private static final Set<String> CMD_TYPES = Set.of("SEPARATE", "CANCEL", "FALLBACK");
    private static final Set<String> CHANNEL_STATES = Set.of(
            "ACCEPTED", "PROCESSING", "ACC_ACCEPTED", "FAIL", "SUCCESS",
            "CANCELING", "CANCELED", "CANCEL_FAIL", "FALLBACKING", "FALLBACK_END",
            "REVERSE_FAIL", "REVERSE_SUCCESS");

    private LakalaSacsReconcileProtocol() {}

    // ---- 1879 DTOs -------------------------------------------------------------------------

    public record QueryResultListInput(
            LocalDateTime requestTime,
            String merchantNo,
            String lklOrgNo,
            String cmdType,
            String outSeparateNo,
            String separateNo,
            String logNo,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            int pageNo,
            int pageSize) {

        public static QueryResultListInput byDateRange(
                LocalDateTime requestTime, String merchantNo,
                LocalDate startDate, LocalDate endDate, int pageNo, int pageSize) {
            return new QueryResultListInput(
                    requestTime, merchantNo, null, null, null, null, null, null,
                    startDate, endDate, pageNo, pageSize);
        }
    }

    public record ResultListItem(
            String merchantNo,
            String outSeparateNo,
            String separateNo,
            String logNo,
            long amountCents,
            LocalDate separateDate,
            CmdType cmdType,
            List<String> recvNoList,
            String failReason,
            String status,
            String frontRuleId,
            Long actualAmountCents,
            Long totalFeeCents) {
        public ResultListItem {
            recvNoList = recvNoList == null ? List.of() : List.copyOf(recvNoList);
        }

        public BigDecimal amount() { return BigDecimal.valueOf(amountCents, 2); }
        public BigDecimal actualAmount() {
            return actualAmountCents == null ? null : BigDecimal.valueOf(actualAmountCents, 2);
        }
        public BigDecimal totalFeeAmount() {
            return totalFeeCents == null ? null : BigDecimal.valueOf(totalFeeCents, 2);
        }
    }

    public record QueryResultListPage(
            int totalPageNum,
            int totalDataNum,
            int dataNum,
            List<ResultListItem> datas) {
        public QueryResultListPage {
            datas = datas == null ? List.of() : List.copyOf(datas);
        }
    }

    // ---- 1877 DTOs -------------------------------------------------------------------------

    public record QueryDetailInput(
            LocalDateTime requestTime,
            String merchantNo,
            String recvNo,
            String cmdType,
            String separateNo,
            String outSeparateNo,
            LocalDate startDate,
            LocalDate endDate,
            String logNo,
            int pageNo,
            int pageSize) {

        public static QueryDetailInput byOutSeparateNo(
                LocalDateTime requestTime, String merchantNo,
                String outSeparateNo, int pageNo, int pageSize) {
            return new QueryDetailInput(
                    requestTime, merchantNo, null, null, null, outSeparateNo,
                    null, null, null, pageNo, pageSize);
        }
    }

    public record DetailItem(
            String merchantNo,
            String separateNo,
            String outSeparateNo,
            String merchantName,
            CmdType cmdType,
            String recvNo,
            long amountCents,
            String logNo,
            LocalDate logDate,
            String status,
            LocalDate finishDate,
            LocalDateTime requestTime,
            String frontRuleId,
            Long actualAmountCents,
            Long feeAmountCents,
            String remark) {

        public BigDecimal amount() { return BigDecimal.valueOf(amountCents, 2); }
        public BigDecimal actualAmount() {
            return actualAmountCents == null ? null : BigDecimal.valueOf(actualAmountCents, 2);
        }
        public BigDecimal feeAmount() {
            return feeAmountCents == null ? null : BigDecimal.valueOf(feeAmountCents, 2);
        }
    }

    public record QueryDetailPage(
            int totalPageNum,
            int totalDataNum,
            int dataNum,
            List<DetailItem> datas) {
        public QueryDetailPage {
            datas = datas == null ? List.of() : List.copyOf(datas);
        }
    }

    // ---- Request builders ------------------------------------------------------------------

    /** Builds Lakala 1879 (/api/v3/sacs/queryResultList) request envelope. */
    public static PreparedRequest prepareQueryResultList(QueryResultListInput input) {
        if (input == null) invalid();
        validatePaging(input.pageNo(), input.pageSize());
        validateDateSpan(input.startDate(), input.endDate(), MAX_RESULT_LIST_SPAN_DAYS);

        boolean hasOutSep = hasText(input.outSeparateNo());
        boolean hasSep = hasText(input.separateNo());
        boolean hasLogNo = hasText(input.logNo());
        boolean hasDates = input.startDate() != null && input.endDate() != null;
        boolean hasMch = hasText(input.merchantNo());
        boolean hasOrg = hasText(input.lklOrgNo());
        // Official 1879 requirement: at least one of 5 query conditions must hold.
        if (!hasOutSep && !hasSep && !hasLogNo && !(hasDates && (hasMch || hasOrg))) {
            invalid();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        if (hasMch) data.put("merchant_no", required(input.merchantNo(), 32));
        if (hasOrg) data.put("lkl_org_no", required(input.lklOrgNo(), 32));
        if (hasText(input.cmdType())) {
            String cmd = required(input.cmdType(), 32);
            if (!CMD_TYPES.contains(cmd)) invalid();
            data.put("cmd_type", cmd);
        }
        if (hasOutSep) data.put("out_separate_no", required(input.outSeparateNo(), 32));
        if (hasSep) data.put("separate_no", required(input.separateNo(), 32));
        if (hasLogNo) data.put("log_no", logNo(input.logNo()));
        if (hasText(input.status())) {
            String st = required(input.status(), 16);
            if (!CHANNEL_STATES.contains(st)) invalid();
            data.put("status", st);
        }
        if (hasDates) {
            data.put("start_date", DATE_8.format(input.startDate()));
            data.put("end_date", DATE_8.format(input.endDate()));
        }
        data.put("page_no", input.pageNo());
        data.put("page_size", input.pageSize());
        return prepare(QUERY_RESULT_LIST_PATH, input.requestTime(), data);
    }

    /** Builds Lakala 1877 (/api/v3/sacs/queryDetail) request envelope. */
    public static PreparedRequest prepareQueryDetail(QueryDetailInput input) {
        if (input == null) invalid();
        validatePaging(input.pageNo(), input.pageSize());
        validateDateSpan(input.startDate(), input.endDate(), MAX_DETAIL_SPAN_DAYS);

        boolean hasMch = hasText(input.merchantNo());
        boolean hasRecv = hasText(input.recvNo());
        boolean hasSep = hasText(input.separateNo());
        boolean hasOutSep = hasText(input.outSeparateNo());
        boolean hasLogNo = hasText(input.logNo());
        boolean hasDates = input.startDate() != null && input.endDate() != null;
        if (!hasSep && !hasOutSep && !hasLogNo && !hasRecv && !(hasDates && hasMch)) {
            invalid();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        if (hasMch) data.put("merchant_no", required(input.merchantNo(), 32));
        if (hasRecv) data.put("recv_no", required(input.recvNo(), 32));
        if (hasText(input.cmdType())) {
            String cmd = required(input.cmdType(), 32);
            if (!CMD_TYPES.contains(cmd)) invalid();
            data.put("cmd_type", cmd);
        }
        if (hasSep) data.put("separate_no", required(input.separateNo(), 32));
        if (hasOutSep) data.put("out_separate_no", required(input.outSeparateNo(), 32));
        if (hasDates) {
            data.put("start_date", DATE_8.format(input.startDate()));
            data.put("end_date", DATE_8.format(input.endDate()));
        }
        if (hasLogNo) data.put("log_no", logNo(input.logNo()));
        data.put("page_no", input.pageNo());
        data.put("page_size", input.pageSize());
        return prepare(QUERY_DETAIL_PATH, input.requestTime(), data);
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
            throw new ProtocolException("Lakala SACS reconcile request serialization failed", failure);
        }
    }

    // ---- Response verifiers ----------------------------------------------------------------

    /** Verifies 1004 signature and parses Lakala 1879 queryResultList response. */
    public static QueryResultListPage verifyQueryResultListResponse(
            Map<String, String> headers, byte[] rawBody,
            String appId, String platformSerial, PublicKey platformKey,
            String expectedMerchantNo) {
        if (expectedMerchantNo != null && !expectedMerchantNo.isBlank()) {
            required(expectedMerchantNo, 32);
        }
        LakalaProtocol.verifyResponse(headers, rawBody, appId, platformSerial, platformKey);
        JsonNode data = successData(rawBody);
        int totalPageNum = nonNegativeIntRequired(data, "total_page_num");
        int totalDataNum = nonNegativeIntRequired(data, "total_data_num");
        int dataNum = nonNegativeIntRequired(data, "data_num");
        List<ResultListItem> items = parseResultListItems(data.get("datas"), expectedMerchantNo);
        if (items.size() != dataNum) invalid();
        return new QueryResultListPage(totalPageNum, totalDataNum, dataNum, items);
    }

    /** Verifies 1004 signature and parses Lakala 1877 queryDetail response. */
    public static QueryDetailPage verifyQueryDetailResponse(
            Map<String, String> headers, byte[] rawBody,
            String appId, String platformSerial, PublicKey platformKey,
            String expectedMerchantNo, String expectedOutSeparateNo) {
        if (expectedMerchantNo != null && !expectedMerchantNo.isBlank()) {
            required(expectedMerchantNo, 32);
        }
        if (expectedOutSeparateNo != null && !expectedOutSeparateNo.isBlank()) {
            required(expectedOutSeparateNo, 32);
        }
        LakalaProtocol.verifyResponse(headers, rawBody, appId, platformSerial, platformKey);
        JsonNode data = successData(rawBody);
        int totalPageNum = nonNegativeIntRequired(data, "total_page_num");
        int totalDataNum = nonNegativeIntRequired(data, "total_data_num");
        int dataNum = nonNegativeIntRequired(data, "data_num");
        List<DetailItem> items = parseDetailItems(data.get("datas"), expectedMerchantNo, expectedOutSeparateNo);
        if (items.size() != dataNum) invalid();
        return new QueryDetailPage(totalPageNum, totalDataNum, dataNum, items);
    }

    private static List<ResultListItem> parseResultListItems(JsonNode node, String expectedMerchantNo) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) invalid();
        List<ResultListItem> items = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            if (entry == null || !entry.isObject()) invalid();
            String merchantNo = field(entry, "merchant_no", 32);
            if (hasText(expectedMerchantNo) && !expectedMerchantNo.equals(merchantNo)) invalid();
            String outSeparateNo = field(entry, "out_separate_no", 32);
            String separateNo = field(entry, "separate_no", 32);
            String logNo = logNo(field(entry, "log_no", 32));
            long amt = centsRequired(entry, "amt");
            LocalDate separateDate = optionalDate(entry, "separate_date");
            CmdType cmdType = parseCmdType(field(entry, "cmd_type", 32));
            List<String> recvNoList = parseStringList(entry.get("recv_no_list"), 32);
            String failReason = optionalFreeText(entry, "fail_reason", 256);
            String status = field(entry, "status", 16);
            if (!CHANNEL_STATES.contains(status)) invalid();
            String frontRuleId = optionalField(entry, "front_rule_id", 64);
            Long actualAmt = centsOptional(entry, "actual_amt");
            Long totalFeeAmt = centsOptional(entry, "total_fee_amt");
            items.add(new ResultListItem(
                    merchantNo, outSeparateNo, separateNo, logNo, amt, separateDate, cmdType,
                    recvNoList, failReason, status, frontRuleId, actualAmt, totalFeeAmt));
        }
        return items;
    }

    private static List<DetailItem> parseDetailItems(
            JsonNode node, String expectedMerchantNo, String expectedOutSeparateNo) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) invalid();
        List<DetailItem> items = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            if (entry == null || !entry.isObject()) invalid();
            String merchantNo = field(entry, "merchant_no", 32);
            if (hasText(expectedMerchantNo) && !expectedMerchantNo.equals(merchantNo)) invalid();
            String separateNo = field(entry, "separate_no", 32);
            String outSeparateNo = field(entry, "out_separate_no", 32);
            if (hasText(expectedOutSeparateNo) && !expectedOutSeparateNo.equals(outSeparateNo)) invalid();
            String merchantName = optionalFreeText(entry, "merchant_name", 64);
            CmdType cmdType = parseCmdType(field(entry, "cmd_type", 32));
            String recvNo = optionalField(entry, "recv_no", 32);
            if (recvNo == null) {
                recvNo = optionalField(entry, "recv_merchant_no", 32);
            }
            if (recvNo == null) invalid();
            long amt = centsRequired(entry, "amt");
            String logNo = logNo(field(entry, "log_no", 32));
            LocalDate logDate = optionalDate(entry, "log_date");
            String status = field(entry, "status", 16);
            if (!CHANNEL_STATES.contains(status)) invalid();
            LocalDate finishDate = optionalDate(entry, "finish_date");
            LocalDateTime requestTime = optionalDateTime14(entry, "request_time");
            String frontRuleId = optionalField(entry, "front_rule_id", 64);
            Long actualAmt = centsOptional(entry, "actual_amt");
            Long feeAmt = centsOptional(entry, "fee_amt");
            String remark = optionalFreeText(entry, "remark", 256);
            items.add(new DetailItem(
                    merchantNo, separateNo, outSeparateNo, merchantName, cmdType, recvNo,
                    amt, logNo, logDate, status, finishDate, requestTime, frontRuleId,
                    actualAmt, feeAmt, remark));
        }
        return items;
    }

    // ---- Validation & parsing helpers ------------------------------------------------------

    private static void validatePaging(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) invalid();
    }

    private static void validateDateSpan(LocalDate startDate, LocalDate endDate, long maxDays) {
        if (startDate == null && endDate == null) return;
        if (startDate == null || endDate == null) invalid();
        if (endDate.isBefore(startDate)) invalid();
        if (ChronoUnit.DAYS.between(startDate, endDate) > maxDays) invalid();
    }

    private static JsonNode successData(byte[] rawBody) {
        JsonNode envelope = json(rawBody);
        if (!SUCCESS_CODE.equals(field(envelope, "code", 32))) invalid();
        JsonNode snake = envelope.get("resp_data");
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
            throw new ProtocolException("Lakala SACS reconcile JSON is invalid", failure);
        }
    }

    private static CmdType parseCmdType(String raw) {
        try { return CmdType.valueOf(raw); }
        catch (IllegalArgumentException failure) { throw new ProtocolException("Invalid SACS cmd_type"); }
    }

    private static List<String> parseStringList(JsonNode node, int itemMax) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) invalid();
        List<String> list = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) invalid();
            list.add(required(item.textValue(), itemMax));
        }
        return list;
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

    /** Accepts both integer and numeric string because Lakala 1879/1877 examples quote integer fields. */
    private static int nonNegativeIntRequired(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) invalid();
        String text;
        if (node.isTextual()) {
            text = node.textValue();
        } else if (node.isIntegralNumber() && node.canConvertToInt()) {
            text = Integer.toString(node.intValue());
        } else {
            invalid();
            return 0;
        }
        if (!NON_NEGATIVE_INT.matcher(text).matches()) invalid();
        try { return Integer.parseInt(text); }
        catch (NumberFormatException failure) { throw new ProtocolException("Invalid SACS integer field"); }
    }

    private static long centsRequired(JsonNode parent, String name) {
        Long value = centsOptional(parent, name);
        if (value == null) invalid();
        return value;
    }

    private static Long centsOptional(JsonNode parent, String name) {
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
        if (!NON_NEGATIVE_CENTS.matcher(text).matches()) invalid();
        try { return Long.parseLong(text); }
        catch (NumberFormatException failure) { throw new ProtocolException("Invalid SACS cents value"); }
    }

    private static LocalDate optionalDate(JsonNode parent, String name) {
        String text = optionalField(parent, name, 8);
        if (text == null) return null;
        if (!text.matches("[0-9]{8}")) invalid();
        try { return LocalDate.parse(text, DATE_8); }
        catch (DateTimeParseException failure) { throw new ProtocolException("Invalid SACS date"); }
    }

    private static LocalDateTime optionalDateTime14(JsonNode parent, String name) {
        String text = optionalField(parent, name, 14);
        if (text == null) return null;
        if (!text.matches("[0-9]{14}")) invalid();
        try { return LocalDateTime.parse(text, REQ_TIME); }
        catch (DateTimeParseException failure) { throw new ProtocolException("Invalid SACS request_time"); }
    }

    private static String logNo(String value) {
        if (value == null || !LOG_NO.matcher(value).matches()) invalid();
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) invalid();
        return value;
    }

    private static void invalid() { throw new ProtocolException("Lakala SACS reconcile protocol fact is invalid"); }

    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String message) { super(message); }
        public ProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
