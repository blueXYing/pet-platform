package com.petplatform.coupon.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.PageResult;
import com.petplatform.coupon.api.dto.CouponInstanceDTO;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponListQuery;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponQuery;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponQueryStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponInstanceViewEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * C-end "my coupons" read model (CCR-C006-COUPON-POINTS-READ-001 P1). Read-only: every query is
 * scoped to the QueryContext subject; D2 keeps FROZEN/RISK_FROZEN out of every bucket, and a
 * foreign or absent coupon reads as the same COMMON_NOT_FOUND so ids cannot be enumerated.
 */
public final class CouponQueryService {

    /** D2: the only selectable buckets; FROZEN/RISK_FROZEN never appear in C reads. */
    private static final Set<String> VISIBLE_BUCKETS = Set.of("AVAILABLE", "USED", "EXPIRED");
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final CouponQueryStore store;

    public CouponQueryService(CouponQueryStore store) {
        this.store = Objects.requireNonNull(store, "store is required");
    }

    public PageResult<CouponInstanceDTO> listMyCoupons(MyCouponListQuery query) {
        Objects.requireNonNull(query, "query is required");
        String bucket = query.status() == null ? "AVAILABLE" : query.status();
        if (!VISIBLE_BUCKETS.contains(bucket)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "status is invalid");
        }
        long user = subject(query);
        int page = Math.max(1, query.page());
        int pageSize = query.pageSize() <= 0 ? 20 : query.pageSize();
        List<String> statuses = List.of(bucket);
        long total = store.read(mapper -> mapper.countMine(user, statuses));
        List<CouponInstanceDTO> items;
        if (total == 0) {
            items = List.of();
        } else {
            items = store.read(mapper ->
                            mapper.selectMinePage(user, statuses, pageSize, (page - 1) * pageSize))
                    .stream().map(CouponQueryService::project).toList();
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    public CouponInstanceDTO getMyCoupon(MyCouponQuery query) {
        Objects.requireNonNull(query, "query is required");
        long id = parse(query.couponId());
        // D2: detail keeps the same visible buckets as the list, so frozen states are not
        // confirmed here either; a foreign or absent id reads as the same single 404.
        CouponInstanceViewEntity row = store.read(
                mapper -> mapper.selectMineById(id, subject(query), List.copyOf(VISIBLE_BUCKETS)));
        if (row == null) {
            // Anti-enumeration: absent and foreign ids share one indistinguishable answer.
            throw new ApiException(CommonApiCodes.NOT_FOUND, "优惠券不存在");
        }
        return project(row);
    }

    private static long subject(MyCouponListQuery query) {
        return requireUser(query.context());
    }

    private static long subject(MyCouponQuery query) {
        return requireUser(query.context());
    }

    private static long requireUser(com.petplatform.common.QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        return IDS.fromApi(context.operatorId());
    }

    private static long parse(String couponId) {
        try {
            return IDS.fromApi(couponId);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "优惠券不存在");
        }
    }

    /** D1 display projection; see {@link RuleProjection}. DATETIME(3) reads as UTC wall time. */
    private static CouponInstanceDTO project(CouponInstanceViewEntity row) {
        RuleProjection rule = RuleProjection.of(row.getRuleJson());
        return new CouponInstanceDTO(
                IDS.toApi(row.getId()),
                row.getTemplateName(),
                rule.amountOff(),
                rule.thresholdAmount(),
                rule.scopeSummary(),
                rule.typeLabel(),
                row.getExpireAt().toLocalDate(),
                row.getStatus(),
                row.getUsedAt() == null ? null : row.getUsedAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * D1: amountOff/thresholdAmount/scopeSummary/typeLabel are server-side projections of
     * coupon_template.rule_json. The rule_json structure is NOT frozen (pending CPN-001), so
     * every field is read defensively: a missing, wrong-typed or unparsable member yields null
     * and never fails the read. Clients must not parse rule_json themselves. Key names follow
     * the CCR-C006 projection vocabulary and stay the single point CPN-001 will freeze.
     */
    static final class RuleProjection {
        private static final ObjectMapper JSON = new ObjectMapper();

        private final String amountOff;
        private final String thresholdAmount;
        private final String scopeSummary;
        private final String typeLabel;

        private RuleProjection(String amountOff, String thresholdAmount, String scopeSummary,
                String typeLabel) {
            this.amountOff = amountOff;
            this.thresholdAmount = thresholdAmount;
            this.scopeSummary = scopeSummary;
            this.typeLabel = typeLabel;
        }

        static RuleProjection of(String ruleJson) {
            if (ruleJson == null || ruleJson.isBlank()) return new RuleProjection(null, null, null, null);
            JsonNode root;
            try {
                root = JSON.readTree(ruleJson);
            } catch (Exception unreadable) {
                return new RuleProjection(null, null, null, null);
            }
            if (root == null || !root.isObject()) return new RuleProjection(null, null, null, null);
            return new RuleProjection(
                    amount(root.path("amountOff")),
                    amount(root.path("thresholdAmount")),
                    text(root.path("scopeSummary"), 64),
                    text(root.path("typeLabel"), 16));
        }

        String amountOff() { return amountOff; }
        String thresholdAmount() { return thresholdAmount; }
        String scopeSummary() { return scopeSummary; }
        String typeLabel() { return typeLabel; }

        /** Amounts render as two-decimal plain strings; anything else is absent (null). */
        private static String amount(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) return null;
            BigDecimal value = null;
            if (node.isNumber()) {
                value = node.decimalValue();
            } else if (node.isTextual()) {
                try {
                    value = new BigDecimal(node.asText().trim());
                } catch (NumberFormatException notANumber) {
                    return null;
                }
            }
            if (value == null || value.signum() < 0
                    || value.scale() > 2 || value.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
                return null;
            }
            return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }

        private static String text(JsonNode node, int maxLength) {
            if (node == null || !node.isTextual()) return null;
            String value = node.asText().trim();
            if (value.isEmpty() || value.length() > maxLength) return null;
            return value;
        }
    }
}
