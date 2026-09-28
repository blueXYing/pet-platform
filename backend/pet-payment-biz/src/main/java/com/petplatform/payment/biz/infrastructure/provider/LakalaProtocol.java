package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline Lakala v3 protocol primitives. No endpoint, key or certificate is bundled and no HTTP
 * request is sent. Callers must supply separately approved credentials and trusted public keys.
 *
 * <p>Sources: https://o.lakala.com/open/document/1004 (request/response security), /1009
 * (notification security), /1075 (preorder), /1111 (query), /1071 (close), /1112 (trade notice).
 */
public final class LakalaProtocol {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9]{12}");
    private static final Pattern TIMESTAMP = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern MONEY = Pattern.compile("[1-9][0-9]{0,11}");
    private static final Pattern NOTIFICATION_AUTH = Pattern.compile(
            "^LKLAPI-SHA256withRSA[ ]*timestamp=\"([1-9][0-9]{0,18})\",[ ]*"
                    + "nonce_str=\"([A-Za-z0-9]{12})\",[ ]*"
                    + "signature=\"([A-Za-z0-9+/]+={0,2})\"$");
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter
            .ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String SUCCESS_CODE = "BBS00000";
    private static final Set<String> NOTICE_STATES = Set.of("INIT", "CREATE", "SUCCESS", "FAIL",
            "DEAL", "UNKNOWN", "CLOSE", "PART_REFUND", "REFUND", "REVOKED");
    private static final Set<String> QUERY_STATES = Set.of("INIT", "CREATE", "SUCCESS", "FAIL",
            "DEAL", "UNKNOWN", "CLOSE", "PART_REFUND", "REFUND");

    private LakalaProtocol() {}

    public enum TradeState {
        INIT, CREATE, SUCCESS, FAIL, DEAL, UNKNOWN, CLOSE, PART_REFUND, REFUND, REVOKED
    }

    public record ExpectedPayment(String merchantNo, String outTradeNo, long expectedTotalCents) {
        public ExpectedPayment {
            required(merchantNo, 32, "merchant_no");
            required(outTradeNo, 64, "out_trade_no");
            if (expectedTotalCents <= 0 || Long.toString(expectedTotalCents).length() > 12) invalid();
        }
    }

    /** A local wall-clock value: the channel document does not specify a timezone. */
    public record NotificationReceipt(String merchantNo, String outTradeNo,
            String channelTradeNo, TradeState tradeStatus, long totalAmountCents,
            Long payerAmountCents, LocalDateTime channelTradeTime, String accountType) {
        public boolean isSuccess() { return TradeState.SUCCESS == tradeStatus; }
    }

    public record QueryResult(String merchantNo, String outTradeNo, String channelTradeNo,
            TradeState tradeState, long totalAmountCents, Long payerAmountCents,
            LocalDateTime channelTradeTime, String accountType) {
        public boolean isSuccess() { return TradeState.SUCCESS == tradeState; }
    }

    /** A successful close response only acknowledges the command; query confirms final state. */
    public record CloseAcknowledgement(String originOutTradeNo, String originTradeNo,
            LocalDateTime channelTradeTime) {}

    public record PreorderResult(String merchantNo, String outTradeNo, String channelTradeNo,
            String appId, String prepayId, String paySign, String timeStamp,
            String nonceStr, String packageValue, String signType) {
        @Override public String toString() { return "PreorderResult[redacted]"; }
    }

    public record PreparedRequest(String path, byte[] rawBody) {
        public PreparedRequest { rawBody = rawBody.clone(); }
        @Override public byte[] rawBody() { return rawBody.clone(); }
    }

    public record PreorderInput(String outOrgCode, LocalDateTime requestTime,
            String merchantNo, String termNo, String outTradeNo, BigDecimal amount,
            String subject, String subAppId, String userId, String requestIp, String notifyUrl,
            Integer timeoutExpressMinutes) {
        public PreorderInput(String outOrgCode, LocalDateTime requestTime,
                String merchantNo, String termNo, String outTradeNo, BigDecimal amount,
                String subject, String subAppId, String userId, String requestIp,
                String notifyUrl) {
            this(outOrgCode, requestTime, merchantNo, termNo, outTradeNo, amount, subject,
                    subAppId, userId, requestIp, notifyUrl, null);
        }
        @Override public String toString() { return "PreorderInput[redacted]"; }
    }

    public record QueryInput(String outOrgCode, LocalDateTime requestTime,
            String merchantNo, String termNo, String outTradeNo, LocalDate tradeRequestDate) {}

    public record CloseInput(String outOrgCode, LocalDateTime requestTime,
            String merchantNo, String termNo, String originOutTradeNo, String requestIp) {}

    public static PreparedRequest preparePreorder(PreorderInput input) {
        if (input == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", required(input.merchantNo(), 32, "merchant_no"));
        data.put("term_no", required(input.termNo(), 32, "term_no"));
        data.put("out_trade_no", required(input.outTradeNo(), 32, "out_trade_no"));
        data.put("account_type", "WECHAT");
        data.put("trans_type", "71");
        data.put("total_amount", cents(input.amount()));
        if (input.timeoutExpressMinutes() != null) {
            if (input.timeoutExpressMinutes() < 1 || input.timeoutExpressMinutes() > 10) invalid();
            data.put("timeout_express", input.timeoutExpressMinutes().toString());
        }
        data.put("location_info", Map.of("request_ip", ip(input.requestIp())));
        data.put("subject", required(input.subject(), 42, "subject"));
        data.put("notify_url", required(input.notifyUrl(), 128, "notify_url"));
        data.put("acc_busi_fields", Map.of(
                "sub_appid", required(input.subAppId(), 32, "sub_appid"),
                "user_id", required(input.userId(), 64, "user_id")));
        return prepare("/api/v3/labs/trans/preorder", input.outOrgCode(), input.requestTime(), data);
    }

    public static PreparedRequest prepareQuery(QueryInput input) {
        if (input == null || input.tradeRequestDate() == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", required(input.merchantNo(), 32, "merchant_no"));
        data.put("term_no", required(input.termNo(), 32, "term_no"));
        data.put("out_trade_no", required(input.outTradeNo(), 32, "out_trade_no"));
        data.put("trade_req_date", REQUEST_DATE.format(input.tradeRequestDate()));
        return prepare("/api/v3/labs/query/tradequery", input.outOrgCode(), input.requestTime(), data);
    }

    public static PreparedRequest prepareClose(CloseInput input) {
        if (input == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_no", required(input.merchantNo(), 32, "merchant_no"));
        data.put("term_no", required(input.termNo(), 32, "term_no"));
        data.put("origin_out_trade_no", required(input.originOutTradeNo(), 32,
                "origin_out_trade_no"));
        data.put("location_info", Map.of("request_ip", ip(input.requestIp())));
        return prepare("/api/v3/labs/relation/close", input.outOrgCode(), input.requestTime(), data);
    }

    private static PreparedRequest prepare(String path, String org, LocalDateTime at,
            Map<String, Object> data) {
        if (at == null || at.getNano() != 0) invalid();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("req_time", REQUEST_TIME.format(at));
        envelope.put("version", "3.0");
        envelope.put("out_org_code", required(org, 32, "out_org_code"));
        envelope.put("req_data", data);
        try {
            return new PreparedRequest(path, JSON.writeValueAsBytes(envelope));
        } catch (IOException failure) {
            throw new ProtocolException("Lakala request serialization failed", failure);
        }
    }

    /** The exact body bytes transmitted are signed; callers must not serialize them again. */
    public static String authorization(String appId, String merchantSerial,
            String timestampSeconds, String nonce, byte[] rawBody, PrivateKey privateKey) {
        String app = headerToken(appId, 64);
        String serial = headerToken(merchantSerial, 64);
        timestamp(timestampSeconds);
        nonce(nonce);
        byte[] signature = sign(fiveLines(app, serial, timestampSeconds, nonce, rawBody), privateKey);
        return "LKLAPI-SHA256withRSA appid=\"" + app + "\",serial_no=\"" + serial
                + "\",timestamp=\"" + timestampSeconds + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + Base64.getEncoder().encodeToString(signature) + "\"";
    }

    /** Official 1004 ordinary response: five lines and configured app/certificate identity. */
    public static void verifyResponse(Map<String, String> headers, byte[] rawBody,
            String expectedAppId, String expectedPlatformSerial, PublicKey trustedPlatformKey) {
        String app = header(headers, "Lklapi-Appid");
        String serial = header(headers, "Lklapi-Serial");
        if (!app.equals(headerToken(expectedAppId, 64))
                || !serial.equals(headerToken(expectedPlatformSerial, 64))) invalid();
        String time = timestamp(header(headers, "Lklapi-Timestamp"));
        String nonce = nonce(header(headers, "Lklapi-Nonce"));
        verify(fiveLines(app, serial, time, nonce, rawBody),
                header(headers, "Lklapi-Signature"), trustedPlatformKey);
    }

    /** Official 1009 callback: three lines, no invented appId or serial line. */
    public static NotificationReceipt verifyNotification(Map<String, String> headers,
            byte[] rawBody, PublicKey trustedNotificationKey, ExpectedPayment expected) {
        if (expected == null) invalid();
        Matcher auth = NOTIFICATION_AUTH.matcher(header(headers, "Authorization"));
        if (!auth.matches()) invalid();
        verify(threeLines(auth.group(1), auth.group(2), rawBody),
                auth.group(3), trustedNotificationKey);
        JsonNode data = json(rawBody);
        String merchant = field(data, "merchant_no", 32);
        String out = field(data, "out_trade_no", 64);
        match(expected, merchant, out);
        TradeState state = state(field(data, "trade_status", 16), NOTICE_STATES);
        JsonNode alternate = data.get("trade_state");
        if (alternate != null && !alternate.isNull()
                && (!alternate.isTextual() || !state.name().equals(alternate.textValue()))) invalid();
        long total = amount(data, "total_amount", true);
        if (total != expected.expectedTotalCents()) invalid();
        Long payer = amountOptional(data, "payer_amount", TradeState.SUCCESS == state);
        if (payer != null && payer > total) invalid();
        return new NotificationReceipt(merchant, out, field(data, "trade_no", 32), state,
                total, payer, tradeTime(data, "trade_time", TradeState.SUCCESS == state),
                wallet(data, "account_type"));
    }

    /** A BBS00000 query means found; only resp_data.trade_state is the payment outcome. */
    public static QueryResult verifyQueryResponse(Map<String, String> headers, byte[] rawBody,
            String expectedAppId, String expectedPlatformSerial, PublicKey trustedPlatformKey,
            ExpectedPayment expected) {
        verifyResponse(headers, rawBody, expectedAppId, expectedPlatformSerial, trustedPlatformKey);
        JsonNode data = successData(rawBody);
        String merchant = field(data, "merchant_no", 32);
        String out = field(data, "out_trade_no", 64);
        match(expected, merchant, out);
        TradeState state = state(field(data, "trade_state", 16), QUERY_STATES);
        long total = amount(data, "total_amount", true);
        if (total != expected.expectedTotalCents()) invalid();
        Long payer = amountOptional(data, "payer_amount", TradeState.SUCCESS == state);
        if (payer != null && payer > total) invalid();
        return new QueryResult(merchant, out, field(data, "trade_no", 32), state, total,
                payer, tradeTime(data, "trade_time", TradeState.SUCCESS == state),
                wallet(data, "account_type"));
    }

    public static PreorderResult verifyPreorderResponse(Map<String, String> headers, byte[] rawBody,
            String expectedAppId, String expectedPlatformSerial, PublicKey trustedPlatformKey,
            ExpectedPayment expected, String expectedSubAppId) {
        verifyResponse(headers, rawBody, expectedAppId, expectedPlatformSerial, trustedPlatformKey);
        JsonNode data = successData(rawBody);
        String merchant = field(data, "merchant_no", 32);
        String out = field(data, "out_trade_no", 32);
        match(expected, merchant, out);
        JsonNode fields = object(data, "acc_resp_fields");
        String signType = field(fields, "sign_type", 32);
        if (!"RSA".equals(signType)) invalid();
        String appId = field(fields, "app_id", 32);
        String prepayId = field(fields, "prepay_id", 128);
        String packageValue = field(fields, "package", 160);
        if (!appId.equals(required(expectedSubAppId, 32, "sub_appid"))
                || !packageValue.equals("prepay_id=" + prepayId)) invalid();
        return new PreorderResult(merchant, out, field(data, "trade_no", 32),
                appId, prepayId,
                // 1075 lists String(256); a 2048-bit RSA Base64 value is 344 chars.
                // Bound compatibility at 512 pending a real merchant response fixture.
                field(fields, "pay_sign", 512), field(fields, "time_stamp", 32),
                field(fields, "nonce_str", 32), packageValue, signType);
    }

    public static CloseAcknowledgement verifyCloseResponse(Map<String, String> headers,
            byte[] rawBody, String expectedAppId, String expectedPlatformSerial,
            PublicKey trustedPlatformKey, String expectedOutTradeNo) {
        verifyResponse(headers, rawBody, expectedAppId, expectedPlatformSerial, trustedPlatformKey);
        JsonNode data = successData(rawBody);
        String out = field(data, "origin_out_trade_no", 32);
        if (!out.equals(required(expectedOutTradeNo, 32, "origin_out_trade_no"))) invalid();
        return new CloseAcknowledgement(out, field(data, "origin_trade_no", 32),
                tradeTime(data, "trade_time", true));
    }

    private static JsonNode successData(byte[] rawBody) {
        JsonNode envelope = json(rawBody);
        if (!SUCCESS_CODE.equals(field(envelope, "code", 32))) invalid();
        return object(envelope, "resp_data");
    }

    private static JsonNode json(byte[] body) {
        if (body == null || body.length == 0) invalid();
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString();
            JsonNode parsed = JSON.readTree(utf8);
            if (parsed == null || !parsed.isObject()) invalid();
            return parsed;
        } catch (IOException failure) {
            throw new ProtocolException("Lakala JSON is invalid", failure);
        }
    }

    private static JsonNode object(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isObject()) invalid();
        return value;
    }

    private static String field(JsonNode parent, String name, int max) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isTextual()) invalid();
        return required(value.textValue(), max, name);
    }

    private static String wallet(JsonNode parent, String name) {
        String value = field(parent, name, 32);
        if (!"WECHAT".equals(value)) invalid();
        return value;
    }

    private static TradeState state(String value, Set<String> allowed) {
        if (!allowed.contains(value)) invalid();
        return TradeState.valueOf(value);
    }

    private static void match(ExpectedPayment expected, String merchant, String out) {
        if (!expected.merchantNo().equals(merchant) || !expected.outTradeNo().equals(out)) invalid();
    }

    private static long amount(JsonNode parent, String name, boolean positive) {
        String value = field(parent, name, 12);
        if (!(positive ? MONEY.matcher(value).matches() : value.matches("0|[1-9][0-9]{0,11}"))) invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw new ProtocolException("Lakala amount is invalid"); }
    }

    private static Long amountOptional(JsonNode parent, String name, boolean required) {
        JsonNode value = parent.get(name);
        if (value == null || value.isNull()
                || (!required && value.isTextual() && value.textValue().isEmpty())) {
            if (required) invalid();
            return null;
        }
        long amount = amount(parent, name, required);
        return amount;
    }

    private static LocalDateTime tradeTime(JsonNode parent, String name, boolean required) {
        JsonNode value = parent.get(name);
        if (value == null || value.isNull()
                || (!required && value.isTextual() && value.textValue().isEmpty())) {
            if (required) invalid();
            return null;
        }
        String text = field(parent, name, 14);
        if (!text.matches("[0-9]{14}")) invalid();
        try { return LocalDateTime.parse(text, REQUEST_TIME); }
        catch (DateTimeParseException failure) { throw new ProtocolException("Lakala trade time is invalid"); }
    }

    private static String cents(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) invalid();
        try {
            String value = amount.movePointRight(2).toBigIntegerExact().toString();
            if (!MONEY.matcher(value).matches()) invalid();
            return value;
        } catch (ArithmeticException failure) { throw new ProtocolException("Lakala amount is invalid"); }
    }

    private static String ip(String value) {
        required(value, 64, "request_ip");
        if (!value.matches("[0-9A-Fa-f:.]{3,64}") || value.indexOf('.') < 0 && value.indexOf(':') < 0) invalid();
        return value;
    }

    private static String required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) invalid();
        return value;
    }

    private static String headerToken(String value, int max) {
        required(value, max, "header token");
        if (value.indexOf('"') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf(',') >= 0 || value.indexOf(' ') >= 0) invalid();
        return value;
    }

    private static String timestamp(String value) {
        if (value == null || !TIMESTAMP.matcher(value).matches()) invalid();
        return value;
    }

    private static String nonce(String value) {
        if (value == null || !NONCE.matcher(value).matches()) invalid();
        return value;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) invalid();
        String found = null;
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                if (found != null) invalid();
                found = entry.getValue();
            }
        }
        if (found == null) invalid();
        // Official examples quote some response-header values; accept one balanced pair only.
        if (found.length() >= 2 && found.startsWith("\"") && found.endsWith("\"")) {
            found = found.substring(1, found.length() - 1);
        }
        if ("Authorization".equalsIgnoreCase(name)) {
            if (found.isBlank() || found.length() > 1024
                    || found.codePoints().anyMatch(Character::isISOControl)) invalid();
            return found;
        }
        return required(found, 1024, name);
    }

    private static byte[] fiveLines(String app, String serial, String time, String nonce, byte[] body) {
        return lines(new String[] {app, serial, time, nonce}, body);
    }

    private static byte[] threeLines(String time, String nonce, byte[] body) {
        timestamp(time);
        nonce(nonce);
        return lines(new String[] {time, nonce}, body);
    }

    private static byte[] lines(String[] fields, byte[] body) {
        if (body == null || body.length == 0) invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String field : fields) {
            bytes.writeBytes(field.getBytes(StandardCharsets.US_ASCII));
            bytes.write(10);
        }
        bytes.writeBytes(body);
        bytes.write(10);
        return bytes.toByteArray();
    }

    private static byte[] sign(byte[] message, PrivateKey key) {
        key(key);
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(message);
            return signer.sign();
        } catch (Exception failure) { throw new ProtocolException("Lakala request signing failed", failure); }
    }

    private static void verify(byte[] message, String encoded, PublicKey key) {
        key(key);
        try {
            byte[] signature = Base64.getDecoder().decode(encoded);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(message);
            if (!verifier.verify(signature)) invalid();
        } catch (IllegalArgumentException failure) { throw new ProtocolException("Lakala signature is invalid"); }
        catch (Exception failure) { throw new ProtocolException("Lakala signature verification failed", failure); }
    }

    private static void key(java.security.Key key) {
        if (!(key instanceof RSAKey rsa) || rsa.getModulus().bitLength() != 2048) invalid();
    }

    private static void invalid() { throw new ProtocolException("Lakala protocol fact is invalid"); }

    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String message) { super(message); }
        public ProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
