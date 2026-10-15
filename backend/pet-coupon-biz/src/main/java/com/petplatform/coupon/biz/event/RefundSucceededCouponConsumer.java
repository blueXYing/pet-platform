package com.petplatform.coupon.biz.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponLifecycleStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponLifecycleInstanceEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponLifecycleMapper;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 61 section 6 (CPN-002) refund restore. Consumes the REFUND-owned
 * RefundSucceededEvent.v1 (published inside REFUND's verified success transaction; the payload
 * carries no storeId, so the outbox fact is the authorization — disclosed in the PR).
 * Decisions follow SSOT section 9 and section 22:
 * - LATE_PAYMENT_TIMEOUT: NOOP — the late-payment source never consumed the coupon and the
 *   coupon was already released at close; never restore, never double-release (CPN-005).
 * - PARTIAL: NOOP — partial refunds never return coupons (SSOT section 9.2).
 * - FULL with a USED coupon of the order: restore AVAILABLE, clear the order binding, and extend
 *   expire_at to succeededAt+24h when the remaining validity is below 24h (SSOT section 9.1).
 *   A coupon already past validity stays USED (restore requires remaining validity).
 * - FULL without a USED coupon of the order: NOOP (the order had no coupon).
 * Exactly-once: outbox claim + ledger CPN:RESTORE:{refundOrderId} (uk) + row CAS; divergence is
 * fail-closed 503 so the dispatcher retries and finally parks the event for operations.
 */
public final class RefundSucceededCouponConsumer implements IntegrationEventConsumer {
    public static final String CONSUMER = "COUPON_REFUND_RESTORE";
    static final String EVENT_TYPE = "RefundSucceededEvent.v1";
    static final String LATE_PAYMENT_TIMEOUT = "LATE_PAYMENT_TIMEOUT";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> PAYLOAD_FIELDS = Set.of("refundOrderId", "refundNo",
            "orderId", "refundType", "refundSource", "refundAmount", "originalPaidAmount",
            "channelRefundNo", "succeededAt");

    private final JdbcOutboxConsumeGuard claims;
    private final CouponLifecycleStore store;
    private final SnowflakeIdGenerator ids;
    private final TransactionTemplate transaction;

    public RefundSucceededCouponConsumer(DataSource source, SnowflakeIdGenerator ids,
            JdbcOutboxConsumeGuard claims) {
        Objects.requireNonNull(source, "source is required");
        this.ids = Objects.requireNonNull(ids, "ids is required");
        this.claims = Objects.requireNonNull(claims, "claims is required");
        this.store = CouponLifecycleStore.joining(source);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    @Override public String consumerName() {
        return CONSUMER;
    }

    @Override public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override public void consume(DispatchedEvent event) {
        Objects.requireNonNull(event);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable("coupon restore requires its own transaction");
        }
        Decoded decoded = decode(event);
        try {
            transaction.executeWithoutResult(status -> apply(event, decoded));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable("coupon restore unavailable");
        }
    }

    private void apply(DispatchedEvent event, Decoded decoded) {
        store.sessionDefaults();
        if (!claims.tryClaim(CONSUMER, event)) return; // already consumed by an earlier delivery
        if (decoded.skip()) return; // late-payment source, partial refund: authoritative NOOP
        LocalDateTime succeededAt = utc(decoded.succeededAt());
        CouponLifecycleMapper mapper = store.mapper();
        CouponLifecycleInstanceEntity row = mapper.lockInstanceByOrder(decoded.orderId());
        if (row == null || !"USED".equals(row.getStatus())) return; // order had no consumed coupon
        if (row.getExpireAt() == null || !row.getExpireAt().isAfter(succeededAt)) {
            return; // validity ended before the refund succeeded: SSOT 9.1 precondition unmet
        }
        // Remaining validity below 24h extends to refund success + 24h; otherwise keep expire_at.
        LocalDateTime extended = succeededAt.plusHours(24);
        LocalDateTime newExpireAt = row.getExpireAt().isBefore(extended) ? extended
                : row.getExpireAt();
        long couponId = row.getId();
        if (mapper.restoreInstance(couponId, decoded.orderId(), newExpireAt, now()) != 1) {
            throw unavailable("coupon restore compare-and-set failed");
        }
        long ledgerId = ids.nextId();
        if (ledgerId <= 0) throw unavailable("coupon ledger ID provider unavailable");
        try {
            mapper.insertLedger(ledgerId, couponId, row.getUserId(), decoded.orderId(),
                    "REFUND_RESTORE", "USED", "AVAILABLE",
                    "CPN:RESTORE:" + decoded.refundOrderId(), "full refund restore", now());
        } catch (DuplicateKeyException replayed) {
            throw unavailable("coupon restore ledger request already exists");
        }
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
        if (node == null || !node.isObject()) throw unavailable("RefundSucceeded payload is not an object");
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(PAYLOAD_FIELDS)) throw unavailable("RefundSucceeded payload keys changed");
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
        String refundSource = requireText(node, "refundSource");
        amount(node.path("refundAmount"));
        amount(node.path("originalPaidAmount"));
        OffsetDateTime succeededAt;
        try {
            succeededAt = OffsetDateTime.parse(requireText(node, "succeededAt"));
            PublicContractChecks.requireMillisecondPrecision(succeededAt);
        } catch (RuntimeException malformed) {
            throw unavailable("RefundSucceeded succeededAt is invalid");
        }
        return new Decoded(refundOrderId, orderId, refundType, refundSource, succeededAt);
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw unavailable("RefundSucceeded payload field is invalid: " + field);
        }
        return value.asText();
    }

    private static void amount(JsonNode value) {
        if (!value.isNumber()) throw unavailable("RefundSucceeded payload amount is invalid");
        BigDecimal parsed = value.decimalValue();
        if (parsed.signum() < 0 || parsed.scale() > 2) {
            throw unavailable("RefundSucceeded payload amount is out of range");
        }
    }

    record Decoded(long refundOrderId, long orderId, String refundType, String refundSource,
            OffsetDateTime succeededAt) {
        boolean skip() {
            return LATE_PAYMENT_TIMEOUT.equals(refundSource) || !"FULL".equals(refundType);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private static ApiException unavailable(String detail) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, detail);
    }
}
