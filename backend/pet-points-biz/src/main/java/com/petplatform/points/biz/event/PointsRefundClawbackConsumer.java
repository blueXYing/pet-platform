package com.petplatform.points.biz.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.points.biz.infrastructure.persistence.PointsTaskStore;
import com.petplatform.points.biz.infrastructure.persistence.entity.PointsOrderRewardLedgerEntity;
import com.petplatform.points.biz.infrastructure.persistence.mapper.PointsTaskMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Contract 60 §7 refund clawback (PTS-002, CCR-POINTS-ORDER-001), modeled on the coupon
 * restore consumer (#168). Consumes the REFUND-owned {@code RefundSucceededEvent.v1} and
 * reverses the order reward the way the event catalog §8 points matrix and SSOT §10.1 fix it:
 * <ul>
 *   <li>FULL — claw back all still-unclawed order reward of the order;</li>
 *   <li>PARTIAL — round HALF_UP of (awarded × refundAmount / originalPaidAmount) per refund,
 *       each booking capped by the still-unclawed remainder so cumulative clawbacks never
 *       exceed the award;</li>
 *   <li>no order-reward ledger line for the order — authoritative NOOP (never issued; covers
 *       the late-payment source, which earns nothing when the user never claimed, 09号 §29.2).</li>
 * </ul>
 * Only ORDER_REWARD lines are touched — sign-in / invite / task balances stay intact. The
 * owner comes from the points ledger itself (the refund payload carries no userId). Exactly
 * once: outbox claim + ledger {@code PTS:CLAWBACK:{refundOrderId}} (uk_points_request) over
 * FOR UPDATE-locked reward lines; divergence is fail-closed 503 so the dispatcher retries and
 * finally parks the event for operations.
 */
public final class PointsRefundClawbackConsumer implements IntegrationEventConsumer {
    public static final String CONSUMER = "POINTS_REFUND_CLAWBACK";
    static final String EVENT_TYPE = "RefundSucceededEvent.v1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> PAYLOAD_FIELDS = Set.of("refundOrderId", "refundNo",
            "orderId", "refundType", "refundSource", "refundAmount", "originalPaidAmount",
            "channelRefundNo", "succeededAt");
    private static final String LEDGER_REQUEST_PREFIX = "PTS:CLAWBACK:";
    private static final String FULL = "FULL";
    private static final String PARTIAL = "PARTIAL";

    private final PointsTaskStore store;
    private final SnowflakeIdGenerator ids;
    private final JdbcOutboxConsumeGuard claims;

    public PointsRefundClawbackConsumer(javax.sql.DataSource dataSource,
            SnowflakeIdGenerator ids, JdbcOutboxConsumeGuard claims) {
        this(new PointsTaskStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                Objects.requireNonNull(ids, "ids are required")),
                ids, claims);
    }

    PointsRefundClawbackConsumer(PointsTaskStore store, SnowflakeIdGenerator ids,
            JdbcOutboxConsumeGuard claims) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.ids = Objects.requireNonNull(ids, "ids are required");
        this.claims = Objects.requireNonNull(claims, "claims are required");
    }

    @Override public String consumerName() {
        return CONSUMER;
    }

    @Override public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override public void consume(DispatchedEvent event) {
        Objects.requireNonNull(event);
        Decoded decoded = decode(event);
        // PointsTaskStore.execute opens its own REQUIRES_NEW READ_COMMITTED transaction, so the
        // consume-log claim and the clawback commit or roll back together (Event Catalog §15/§21).
        store.execute(mapper -> apply(event, decoded, mapper));
    }

    private Void apply(DispatchedEvent event, Decoded decoded, PointsTaskMapper mapper) {
        if (!claims.tryClaim(CONSUMER, event)) {
            return null; // already consumed by an earlier delivery
        }
        List<PointsOrderRewardLedgerEntity> rewards = mapper.lockOrderRewardLedger(decoded.orderId());
        if (rewards.isEmpty()) {
            return null; // never issued: no reward ledger for this order — authoritative NOOP
        }
        long userId = rewards.get(0).getUserId();
        long awarded = 0;
        for (PointsOrderRewardLedgerEntity row : rewards) {
            if (row.getUserId() == null || !row.getUserId().equals(userId) || row.getDelta() == null
                    || row.getDelta() <= 0) {
                throw unavailable("order reward ledger projection is inconsistent");
            }
            awarded += row.getDelta();
        }
        Long clawed = mapper.selectClawedTotal(decoded.orderId());
        if (clawed == null || clawed < 0) {
            throw unavailable("order clawback total is invalid");
        }
        long remaining = awarded - clawed;
        if (remaining <= 0) {
            return null; // fully clawed already: idempotent NOOP
        }
        long claw = clawFor(decoded, awarded, remaining);
        if (claw <= 0) {
            return null; // proportional rounding lands on zero for this refund
        }
        LocalDateTime now = nowUtc();
        // Mirrors the earn-side accrual: atomic decrement plus read-back; the claw is bounded by
        // the order's own award, so a negative balance means divergence and fails closed.
        mapper.upsertAccountBalance(nextId(), userId, -claw, now);
        Long balance = mapper.selectBalance(userId);
        if (balance == null || balance < 0) {
            throw unavailable("points account balance is invalid after clawback");
        }
        if (mapper.insertOrderLedger(nextId(), userId, "REFUND_CLAWBACK", decoded.refundOrderId(),
                decoded.orderId(), -claw, balance,
                LEDGER_REQUEST_PREFIX + decoded.refundOrderId(), now) != 1) {
            throw unavailable("points clawback ledger write failed");
        }
        return null;
    }

    /**
     * Contract 60 §7 matrix: FULL reverses the rest; PARTIAL books round HALF_UP of
     * (awarded × refundAmount / originalPaidAmount) per refund (SSOT §10.1), each booking
     * capped by the still-unclawed remainder so cumulative clawbacks never exceed the award.
     */
    private static long clawFor(Decoded decoded, long awarded, long remaining) {
        if (FULL.equals(decoded.refundType())) {
            return remaining;
        }
        if (decoded.originalPaidAmount().signum() <= 0
                || decoded.refundAmount().signum() < 0
                || decoded.refundAmount().compareTo(decoded.originalPaidAmount()) > 0) {
            throw unavailable("refund amounts are outside the clawback ratio range");
        }
        BigDecimal gross = BigDecimal.valueOf(awarded).multiply(decoded.refundAmount())
                .divide(decoded.originalPaidAmount(), 0, RoundingMode.HALF_UP);
        return Math.min(Math.max(gross.longValueExact(), 0), remaining);
    }

    Decoded decode(DispatchedEvent event) {
        if (!EVENT_TYPE.equals(event.eventType()) || event.eventVersion() != 1
                || !"REFUND".equals(event.aggregateType())) {
            throw unavailable("unexpected refund event");
        }
        JsonNode node;
        try {
            node = JSON.readTree(event.payloadJson());
        } catch (Exception malformed) {
            throw unavailable("RefundSucceeded payload is unreadable");
        }
        if (node == null || !node.isObject()) {
            throw unavailable("RefundSucceeded payload is not an object");
        }
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(PAYLOAD_FIELDS)) {
            throw unavailable("RefundSucceeded payload keys changed");
        }
        long refundOrderId;
        long orderId;
        try {
            refundOrderId = IDS.fromApi(requireText(node, "refundOrderId"));
            IDS.fromApi(requireText(node, "refundNo"));
            orderId = IDS.fromApi(requireText(node, "orderId"));
            // channelRefundNo is a channel reference, not a platform id: presence only.
        } catch (RuntimeException malformed) {
            throw unavailable("RefundSucceeded payload ids are invalid");
        }
        if (orderId <= 0 || refundOrderId != event.aggregateId()) {
            throw unavailable("RefundSucceeded payload binding is inconsistent");
        }
        String refundType = requireText(node, "refundType");
        if (!FULL.equals(refundType) && !PARTIAL.equals(refundType)) {
            // The refund contract fixes the type vocabulary (FULL/PARTIAL); anything else is a
            // corrupted envelope — fail closed instead of guessing a clawback ratio.
            throw unavailable("RefundSucceeded refundType is outside the registered matrix");
        }
        String refundSource = requireText(node, "refundSource");
        BigDecimal refundAmount = amount(node.path("refundAmount"));
        BigDecimal originalPaidAmount = amount(node.path("originalPaidAmount"));
        try {
            OffsetDateTime succeededAt = OffsetDateTime.parse(requireText(node, "succeededAt"));
            PublicContractChecks.requireMillisecondPrecision(succeededAt);
        } catch (RuntimeException malformed) {
            throw unavailable("RefundSucceeded succeededAt is invalid");
        }
        return new Decoded(refundOrderId, orderId, refundType, refundSource,
                refundAmount, originalPaidAmount);
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw unavailable("RefundSucceeded payload field is invalid: " + field);
        }
        return value.asText();
    }

    private static BigDecimal amount(JsonNode value) {
        if (!value.isNumber()) {
            throw unavailable("RefundSucceeded payload amount is invalid");
        }
        BigDecimal parsed = value.decimalValue();
        if (parsed.signum() < 0 || parsed.scale() > 2) {
            throw unavailable("RefundSucceeded payload amount is out of range");
        }
        return parsed;
    }

    private long nextId() {
        long id = ids.nextId();
        if (id <= 0) {
            throw unavailable("ID provider returned an invalid value");
        }
        return id;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    record Decoded(long refundOrderId, long orderId, String refundType, String refundSource,
            BigDecimal refundAmount, BigDecimal originalPaidAmount) {}

    private static ApiException unavailable(String detail) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, detail);
    }
}
