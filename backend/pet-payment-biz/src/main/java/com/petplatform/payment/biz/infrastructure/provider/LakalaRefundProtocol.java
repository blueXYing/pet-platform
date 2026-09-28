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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Offline V3 refund-front wire contract. The response is never a business refund fact until its
 * signature, identity, amount and trade state have all been checked.
 *
 * <p>Official sources: https://o.lakala.com/open/document/1826 (refund, updated 2025-12-08),
 * /1827 (refund query, updated 2026-03-30), /1828 (common envelope), /1004 (V3 signing).
 */
public final class LakalaRefundProtocol {
    public static final String REFUND_PATH = "/api/v3/rfd/refund_front/refund";
    public static final String QUERY_PATH = "/api/v3/rfd/refund_front/refund_query";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> SUBMIT_STATES = Set.of("INIT", "SUCCESS", "FAIL", "DEAL",
            "PROCESSING", "TIMEOUT", "EXCEPTION");
    private static final Set<String> QUERY_STATES = Set.of("INIT", "SUCCESS", "FAIL", "DEAL",
            "TIMEOUT", "EXCEPTION");

    private LakalaRefundProtocol() {}

    public enum State { INIT, SUCCESS, FAIL, DEAL, PROCESSING, TIMEOUT, EXCEPTION, UNKNOWN }

    public record PreparedRequest(String path, byte[] rawBody) {
        public PreparedRequest { rawBody = rawBody.clone(); }
        @Override public byte[] rawBody() { return rawBody.clone(); }
    }

    public record RefundInput(LocalDateTime requestTime, String merchantNo, String termNo,
            String refundNo, BigDecimal amount, String originOutTradeNo, String originTradeNo,
            String requestIp, String reason, String notifyUrl) {
        @Override public String toString() { return "RefundInput[redacted]"; }
    }

    public record QueryInput(LocalDateTime requestTime, String merchantNo, String termNo,
            String refundNo) {}

    public record ExpectedRefund(String merchantNo, String refundNo, long requestedCents,
            String originOutTradeNo, String originTradeNo) {
        public ExpectedRefund {
            required(merchantNo, 15);
            required(refundNo, 32);
            if (requestedCents <= 0 || requestedCents > 999_999_999_999L) invalid();
            // This adapter serves preorder payments only: both original identifiers are known.
            required(originOutTradeNo, 32);
            required(originTradeNo, 32);
        }
    }

    /** UNKNOWN also covers signed RFD11105/RFD11112 replies that require a later query. */
    public record Result(State state, String refundNo, String channelRefundNo,
            long requestedCents, Long actualRefundCents, LocalDateTime channelTime) {
        public boolean isSuccess() { return state == State.SUCCESS; }
        @Override public String toString() { return "Result[redacted,state=" + state + "]"; }
    }

    public static PreparedRequest prepareRefund(RefundInput input) {
        if (input == null) invalid();
        String originOut = required(input.originOutTradeNo(), 32);
        String originTrade = required(input.originTradeNo(), 32);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", required(input.merchantNo(), 15));
        data.put("term_no", required(input.termNo(), 8));
        data.put("out_trade_no", required(input.refundNo(), 32));
        data.put("refund_amount", cents(input.amount()));
        if (input.reason() != null) data.put("refund_reason", required(input.reason(), 32));
        data.put("origin_out_trade_no", originOut);
        data.put("origin_trade_no", originTrade);
        data.put("location_info", Map.of("request_ip", ip(input.requestIp())));
        if (input.notifyUrl() != null) data.put("notify_url", required(input.notifyUrl(), 128));
        return prepare(REFUND_PATH, input.requestTime(), data);
    }

    public static PreparedRequest prepareQuery(QueryInput input) {
        if (input == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", required(input.merchantNo(), 15));
        data.put("term_no", required(input.termNo(), 8));
        data.put("out_trade_no", required(input.refundNo(), 32));
        return prepare(QUERY_PATH, input.requestTime(), data);
    }

    private static PreparedRequest prepare(String path, LocalDateTime time, Map<String, Object> data) {
        if (time == null || time.getNano() != 0) invalid();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("req_time", TIME.format(time));
        envelope.put("version", "3.0");
        envelope.put("req_data", data);
        try { return new PreparedRequest(path, JSON.writeValueAsBytes(envelope)); }
        catch (IOException failure) { throw new ProtocolException(); }
    }

    public static Result verifySubmit(Map<String, String> headers, byte[] rawBody,
            String appId, String platformSerial, PublicKey platformKey, ExpectedRefund expected) {
        LakalaProtocol.verifyResponse(headers, rawBody, appId, platformSerial, platformKey);
        JsonNode envelope = json(rawBody);
        String code = field(envelope, "code", 8);
        if (code.equals("RFD11105") || code.equals("RFD11112")) {
            return new Result(State.UNKNOWN, expected.refundNo(), null, expected.requestedCents(), null, null);
        }
        if (!code.equals("000000")) {
            return new Result(State.FAIL, expected.refundNo(), null, expected.requestedCents(), null, null);
        }
        JsonNode data = object(envelope, "resp_data");
        if (!field(data, "merchant_no", 20).equals(expected.merchantNo())) invalid();
        return parse(data, expected, SUBMIT_STATES);
    }

    public static Result verifyQuery(Map<String, String> headers, byte[] rawBody,
            String appId, String platformSerial, PublicKey platformKey, ExpectedRefund expected) {
        LakalaProtocol.verifyResponse(headers, rawBody, appId, platformSerial, platformKey);
        JsonNode envelope = json(rawBody);
        String code = field(envelope, "code", 8);
        if (code.equals("RFD11105") || code.equals("RFD11112")) {
            return new Result(State.UNKNOWN, expected.refundNo(), null, expected.requestedCents(), null, null);
        }
        // Query not-found/error is not evidence that a prior refund submission failed.
        if (!code.equals("000000")) {
            return new Result(State.UNKNOWN, expected.refundNo(), null, expected.requestedCents(), null, null);
        }
        return parse(object(envelope, "resp_data"), expected, QUERY_STATES);
    }

    private static Result parse(JsonNode data, ExpectedRefund expected, Set<String> states) {
        String refundNo = field(data, "out_trade_no", 32);
        if (!refundNo.equals(expected.refundNo())) invalid();
        String stateText = field(data, "trade_state", 16);
        if (!states.contains(stateText)) invalid();
        long amount = amount(data, "refund_amount");
        if (amount != expected.requestedCents()) invalid();
        matchOrigin(data, "origin_out_trade_no", expected.originOutTradeNo());
        matchOrigin(data, "origin_trade_no", expected.originTradeNo());
        Long actual = optionalAmount(data, "payer_amount");
        if (actual != null && actual > amount) invalid();
        State state = State.valueOf(stateText);
        LocalDateTime time = optionalTime(data, "trade_time");
        if (state == State.SUCCESS && (actual == null || actual == 0 || time == null)) invalid();
        return new Result(State.valueOf(stateText), refundNo, field(data, "trade_no", 32),
                amount, actual, time);
    }

    private static void matchOrigin(JsonNode data, String name, String expected) {
        JsonNode node = data.get(name);
        // The query document marks these mandatory; submit says both are returned when sent.
        // This adapter always sends both, so every parsed state must retain the original binding.
        if (node == null || !node.isTextual() || !node.textValue().equals(expected)) invalid();
    }

    private static JsonNode json(byte[] body) {
        if (body == null || body.length == 0) invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) invalid();
            return node;
        } catch (IOException failure) { throw new ProtocolException(); }
    }

    private static JsonNode object(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isObject()) invalid();
        return node;
    }

    private static String field(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isTextual()) invalid();
        return required(node.textValue(), max);
    }

    private static long amount(JsonNode data, String name) {
        String value = field(data, name, 12);
        if (!value.matches("[1-9][0-9]{0,11}")) invalid();
        return Long.parseLong(value);
    }

    private static Long optionalAmount(JsonNode data, String name) {
        JsonNode node = data.get(name);
        if (node == null || node.isNull() || node.isTextual() && node.textValue().isEmpty()) return null;
        String value = field(data, name, 12);
        if (!value.matches("0|[1-9][0-9]{0,11}")) invalid();
        return Long.parseLong(value);
    }

    private static LocalDateTime optionalTime(JsonNode data, String name) {
        JsonNode node = data.get(name);
        if (node == null || node.isNull() || node.isTextual() && node.textValue().isEmpty()) return null;
        String value = field(data, name, 14);
        try { return LocalDateTime.parse(value, TIME); }
        catch (DateTimeParseException failure) { throw new ProtocolException(); }
    }

    private static String cents(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) invalid();
        try {
            String value = amount.movePointRight(2).toBigIntegerExact().toString();
            if (!value.matches("[1-9][0-9]{0,11}")) invalid();
            return value;
        } catch (ArithmeticException failure) { throw new ProtocolException(); }
    }

    private static String ip(String value) {
        required(value, 64);
        if (!value.matches("[0-9A-Fa-f:.]{3,64}") || !value.contains(".") && !value.contains(":")) invalid();
        return value;
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim())) invalid();
        return value;
    }

    private static void invalid() { throw new ProtocolException(); }

    public static final class ProtocolException extends IllegalArgumentException {
        private ProtocolException() { super("Invalid Lakala refund protocol data"); }
    }
}
