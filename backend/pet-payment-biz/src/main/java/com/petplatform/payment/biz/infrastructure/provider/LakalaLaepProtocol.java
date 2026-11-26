package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.PreparedV2Request;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Offline Lakala Open Platform v2 LAEP wallet & D1 withdrawal protocol primitives (Stage 5):
 * <ul>
 *   <li>1269 ({@code /api/v2/laep/industry/ewalletBalanceQuery}): receiver account balance query ({@code payType="04"}, yuan)</li>
 *   <li>1319 ({@code /api/v2/laep/industry/ewalletWithdrawD1}): receiver D1 active withdrawal ({@code payType="04"}, yuan)</li>
 *   <li>1271 ({@code /api/v2/laep/industry/ewalletWithdrawQuery}): withdrawal status query</li>
 *   <li>1272: async withdrawal notification verified via 1009 signature ({@link LakalaV2Protocol#verifyV2Notification})</li>
 * </ul>
 *
 * <p>Verified against local authoritative Lakala PDFs 1269, 1319, 1271, and 1272.
 * All LAEP monetary fields are in <b>yuan</b> (unlike SACS v3 cents). No network call happens here.
 */
public final class LakalaLaepProtocol {
    /** Account type "01" = 收款账户 (merchant acquiring account, Mode A / Ruling 40 §10.4). */
    public static final String PAY_TYPE_MERCHANT_COLLECT = "01";
    /** Account type "04" = 分账接收方账户 (split-receiver account; legacy default). */
    public static final String PAY_TYPE_RECEIVER = "04";
    public static final String DRAW_MODE_D1 = "D1";

    private static final Pattern YUAN_AMOUNT = Pattern.compile("(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,2})?");
    private static final DateTimeFormatter CHANNEL_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private LakalaLaepProtocol() {}

    public enum DrawState {
        ACCEPTED("DRAW.ACCEPTED", false),
        FREEZE("DRAW.FREEZE", false),
        PROCESSING("DRAW.PROCESSING", false),
        SUCCESS("DRAW.SUCCESS", true),
        FAILED("DRAW.FAILED", true);

        private final String wire;
        private final boolean terminal;

        DrawState(String wire, boolean terminal) {
            this.wire = wire;
            this.terminal = terminal;
        }

        public String wire() { return wire; }
        public boolean isTerminal() { return terminal; }

        public static DrawState parse(String raw) {
            if (raw == null) invalid();
            for (DrawState candidate : values()) {
                if (candidate.wire.equals(raw)) return candidate;
            }
            invalid();
            throw new AssertionError();
        }
    }

    // ---- 1269 Balance Query ----------------------------------------------------------------

    public record BalanceQueryInput(String orgNo, String receiverNo, String payType) {
        public BalanceQueryInput(String orgNo, String receiverNo) {
            this(orgNo, receiverNo, PAY_TYPE_RECEIVER);
        }

        public BalanceQueryInput {
            payType = hasText(payType) ? payType.trim() : PAY_TYPE_RECEIVER;
        }
    }

    public record BalanceResult(
            String payNo,
            String payType,
            String acctSt,
            BigDecimal curBalance,
            BigDecimal reBalance,
            BigDecimal forceBalance) {
        public boolean isAccountNormal() {
            return "NORMAL".equalsIgnoreCase(acctSt);
        }
    }

    // ---- 1319 Withdraw D1 ------------------------------------------------------------------

    public record WithdrawD1Input(
            String orgNo,
            String receiverNo,
            BigDecimal drawAmt,
            String merOrderNo,
            String notifyUrl,
            String remark,
            String payType) {
        public WithdrawD1Input(
                String orgNo,
                String receiverNo,
                BigDecimal drawAmt,
                String merOrderNo,
                String notifyUrl,
                String remark) {
            this(orgNo, receiverNo, drawAmt, merOrderNo, notifyUrl, remark, PAY_TYPE_RECEIVER);
        }

        public WithdrawD1Input {
            payType = hasText(payType) ? payType.trim() : PAY_TYPE_RECEIVER;
        }
    }

    public record WithdrawD1Result(String drawJnl, String merOrderNo) {}

    // ---- 1271 Query & 1272 Notification ----------------------------------------------------

    public record WithdrawQueryInput(
            String orgNo,
            String receiverNo,
            String drawJnl,
            String merOrderNo) {}

    public record WithdrawOutcome(
            String drawJnl,
            String merOrderNo,
            String mercId,
            BigDecimal drawAmt,
            BigDecimal drawFee,
            String drawMode,
            DrawState drawState,
            String memo,
            String settleNo,
            LocalDateTime completeTime) {
        public BigDecimal netAmount() {
            BigDecimal fee = drawFee == null ? ZERO : drawFee;
            BigDecimal net = drawAmt.subtract(fee).setScale(2, RoundingMode.UNNECESSARY);
            return net.signum() < 0 ? ZERO : net;
        }
    }

    // ---- Request Builders ------------------------------------------------------------------

    public static PreparedV2Request prepareBalanceQuery(BalanceQueryInput input,
            long timestampMillis, String reqId) {
        if (input == null) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orgNo", required(input.orgNo(), 32));
        data.put("merchantNo", required(input.receiverNo(), 32));
        data.put("payType", validPayType(input.payType()));
        return LakalaV2Protocol.buildV2Request(
                LakalaV2Protocol.EWALLET_BALANCE_QUERY_PATH, data, timestampMillis, reqId);
    }

    public static PreparedV2Request prepareWithdrawD1(WithdrawD1Input input,
            long timestampMillis, String reqId) {
        if (input == null) invalid();
        BigDecimal amt = positiveYuan(input.drawAmt());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orgNo", required(input.orgNo(), 32));
        data.put("merchantNo", required(input.receiverNo(), 32));
        data.put("drawAmt", amt.toPlainString());
        if (hasText(input.notifyUrl())) {
            data.put("notifyUrl", required(input.notifyUrl(), 256));
        }
        data.put("merOrderNo", required(input.merOrderNo(), 32));
        data.put("payType", validPayType(input.payType()));
        if (hasText(input.remark())) {
            data.put("remark", required(input.remark(), 64));
        }
        return LakalaV2Protocol.buildV2Request(
                LakalaV2Protocol.EWALLET_WITHDRAW_D1_PATH, data, timestampMillis, reqId);
    }

    public static PreparedV2Request prepareWithdrawQuery(WithdrawQueryInput input,
            long timestampMillis, String reqId) {
        if (input == null) invalid();
        boolean hasJnl = hasText(input.drawJnl());
        boolean hasOrder = hasText(input.merOrderNo());
        if (!hasJnl && !hasOrder) invalid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orgNo", required(input.orgNo(), 32));
        data.put("merchantNo", required(input.receiverNo(), 32));
        if (hasJnl) data.put("drawJnl", required(input.drawJnl(), 64));
        if (hasOrder) data.put("merOrderNo", required(input.merOrderNo(), 32));
        return LakalaV2Protocol.buildV2Request(
                LakalaV2Protocol.EWALLET_WITHDRAW_QUERY_PATH, data, timestampMillis, reqId);
    }

    // ---- Response & Notification Parsers ---------------------------------------------------

    public static BalanceResult parseBalanceRespData(JsonNode data) {
        if (data == null || !data.isObject()) invalid();
        String payNo = optionalField(data, "payNo", 64);
        String payType = optionalField(data, "payType", 16);
        String acctSt = requiredField(data, "acctSt", 32);
        BigDecimal curBalance = requiredNonNegativeYuan(data, "curBalance");
        BigDecimal reBalance = optionalNonNegativeYuan(data, "reBalance");
        BigDecimal forceBalance = optionalNonNegativeYuan(data, "forceBalance");
        return new BalanceResult(payNo, payType, acctSt, curBalance, reBalance, forceBalance);
    }

    public static WithdrawD1Result parseWithdrawD1RespData(JsonNode data, String expectedMerOrderNo) {
        if (data == null || !data.isObject()) invalid();
        String drawJnl = requiredField(data, "drawJnl", 64);
        String merOrderNo = optionalField(data, "merOrderNo", 64);
        if (merOrderNo != null && expectedMerOrderNo != null && !expectedMerOrderNo.equals(merOrderNo)) {
            invalid();
        }
        return new WithdrawD1Result(drawJnl, merOrderNo == null ? expectedMerOrderNo : merOrderNo);
    }

    public static WithdrawOutcome parseWithdrawOutcomeRespData(JsonNode data,
            String expectedReceiverNo, String expectedMerOrderNo, String expectedDrawJnl) {
        WithdrawOutcome outcome = parseOutcomeObject(data);
        if (hasText(expectedReceiverNo) && hasText(outcome.mercId())
                && !expectedReceiverNo.equals(outcome.mercId())) {
            invalid();
        }
        if (hasText(expectedMerOrderNo) && hasText(outcome.merOrderNo())
                && !expectedMerOrderNo.equals(outcome.merOrderNo())) {
            invalid();
        }
        if (hasText(expectedDrawJnl) && !expectedDrawJnl.equals(outcome.drawJnl())) {
            invalid();
        }
        return outcome;
    }

    /** Verifies 1009 signature and parses a 1272 async withdrawal result notification. */
    public static WithdrawOutcome verifyWithdrawalNotification(Map<String, String> headers,
            byte[] rawBody, PublicKey trustedNotificationKey) {
        JsonNode data = LakalaV2Protocol.verifyV2Notification(headers, rawBody, trustedNotificationKey);
        return parseOutcomeObject(data);
    }

    private static WithdrawOutcome parseOutcomeObject(JsonNode data) {
        if (data == null || !data.isObject()) invalid();
        String drawJnl = requiredField(data, "drawJnl", 64);
        String merOrderNo = optionalField(data, "merOrderNo", 64);
        String mercId = optionalField(data, "mercId", 32);
        BigDecimal drawAmt = requiredPositiveYuan(data, "drawAmt");
        BigDecimal drawFee = optionalNonNegativeYuan(data, "drawFee");
        if (drawFee == null) drawFee = ZERO;
        if (drawFee.compareTo(drawAmt) > 0) invalid();
        String drawMode = optionalField(data, "drawMode", 16);
        DrawState drawState = DrawState.parse(requiredField(data, "drawState", 32));
        String memo = optionalFreeText(data, "memo", 256);
        String settleNo = optionalField(data, "settleNo", 64);
        LocalDateTime completeTime = optionalChannelTime(data, "completeTime");
        return new WithdrawOutcome(drawJnl, merOrderNo, mercId, drawAmt, drawFee,
                drawMode, drawState, memo, settleNo, completeTime);
    }

    // ---- Validation Helpers ----------------------------------------------------------------

    private static BigDecimal positiveYuan(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 2) invalid();
        BigDecimal scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        if (!YUAN_AMOUNT.matcher(scaled.toPlainString()).matches()) invalid();
        return scaled;
    }

    private static BigDecimal requiredPositiveYuan(JsonNode parent, String name) {
        BigDecimal value = requiredNonNegativeYuan(parent, name);
        if (value.signum() <= 0) invalid();
        return value;
    }

    private static BigDecimal requiredNonNegativeYuan(JsonNode parent, String name) {
        BigDecimal value = optionalNonNegativeYuan(parent, name);
        if (value == null) invalid();
        return value;
    }

    private static BigDecimal optionalNonNegativeYuan(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return null;
        String text;
        if (node.isTextual()) {
            text = node.textValue().trim();
            if (text.isEmpty()) return null;
        } else if (node.isNumber()) {
            text = node.decimalValue().toPlainString();
        } else {
            invalid();
            return null;
        }
        if (!YUAN_AMOUNT.matcher(text).matches()) invalid();
        return new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static LocalDateTime optionalChannelTime(JsonNode parent, String name) {
        String text = optionalField(parent, name, 32);
        if (text == null) return null;
        try {
            return LocalDateTime.parse(text, CHANNEL_TIME);
        } catch (DateTimeParseException failure) {
            throw new LakalaV2Protocol.ProtocolException("Invalid LAEP completeTime: " + text, failure);
        }
    }

    private static String requiredField(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isTextual()) invalid();
        return required(node.textValue(), max);
    }

    private static String optionalField(JsonNode parent, String name, int max) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) invalid();
        String text = node.textValue().trim();
        return text.isEmpty() ? null : required(text, max);
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

    private static String validPayType(String payType) {
        if (PAY_TYPE_MERCHANT_COLLECT.equals(payType) || PAY_TYPE_RECEIVER.equals(payType)) {
            return payType;
        }
        invalid();
        throw new AssertionError();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            invalid();
        }
        return value;
    }

    private static void invalid() {
        throw new LakalaV2Protocol.ProtocolException("Lakala LAEP protocol fact is invalid");
    }
}
