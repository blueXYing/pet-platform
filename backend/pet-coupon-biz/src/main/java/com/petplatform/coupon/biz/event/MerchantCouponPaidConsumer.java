package com.petplatform.coupon.biz.event;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.biz.application.MerchantCouponBudgetLedger;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.MerchantCouponUsageRow;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import javax.sql.DataSource;

/**
 * Strict consumer of OrderPaidEvent.v1 (contract 79 section 6.3): the merchant coupon consumed
 * by the paid order is booked as merchant spend in the budget ledger (MCPB:USE:{orderId}).
 * Orders without a merchant coupon, platform coupons and replays are NOOPs. The consume-log
 * claim and the ledger entry commit together in the consumer's own transaction.
 */
public final class MerchantCouponPaidConsumer implements IntegrationEventConsumer {

    public static final String TYPE = "OrderPaidEvent.v1";
    private static final Set<String> FIELDS = Set.of(
            "orderId", "reservationId", "couponInstanceId", "paidAt", "confirmDeadline");
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final CouponAdminStore store;
    private final MerchantCouponBudgetLedger budget;
    private final BiPredicate<String, DispatchedEvent> consumeGuard;
    private final Clock clock;

    /** Composition entry for the boot assembler; no persistence type crosses the module edge. */
    public MerchantCouponPaidConsumer(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            BiPredicate<String, DispatchedEvent> consumeGuard,
            Clock clock) {
        this.store = new CouponAdminStore(
                Objects.requireNonNull(dataSource, "dataSource is required"),
                Objects.requireNonNull(ids, "ids are required"));
        this.budget = new MerchantCouponBudgetLedger(store);
        this.consumeGuard = Objects.requireNonNull(consumeGuard, "consumeGuard is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String consumerName() {
        return "coupon.merchant.order-paid.v1";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void consume(DispatchedEvent event) {
        if (event == null || !TYPE.equals(event.eventType()) || event.eventVersion() != 1
                || !"ORDER".equals(event.aggregateType()) || event.occurredAt() == null) {
            throw invalid();
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(event.payloadJson());
        } catch (Exception failure) {
            throw invalid(); // never retain parser exceptions carrying raw payloads
        }
        if (payload == null || !payload.isObject()) throw invalid();
        Set<String> fields = new HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(FIELDS)) throw invalid();
        long orderId = publicId(text(payload, "orderId"));
        publicId(text(payload, "reservationId")); // shape-checked; schedule domain owns it
        JsonNode coupon = payload.get("couponInstanceId");
        if (coupon == null || coupon.isNull()) return; // no coupon on this order
        publicId(text(payload, "couponInstanceId"));
        if (event.aggregateId() != orderId) throw invalid();
        store.execute(mapper -> {
            // Claim inside this transaction: consume_log and the ledger entry commit together.
            if (!consumeGuard.test(consumerName(), event)) return null;
            MerchantCouponUsageRow usage = mapper.selectMerchantCouponByOrder(orderId);
            if (usage == null || !"MERCHANT".equals(usage.fundingType)) return null;
            CouponRule rule = CouponRule.parse(usage.ruleJson);
            if (rule == null) throw unavailable("merchant coupon rule is unreadable");
            budget.consume(mapper, usage, orderId, rule.amountOff(), now());
            return null;
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static long publicId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw invalid();
            return id;
        } catch (NumberFormatException tooLarge) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "OrderPaidEvent payload is not the contract shape");
    }

    private static ApiException unavailable(String detail) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, detail);
    }
}
