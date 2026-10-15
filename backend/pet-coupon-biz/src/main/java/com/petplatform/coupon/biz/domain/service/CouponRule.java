package com.petplatform.coupon.biz.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;

/**
 * Frozen rule_json vocabulary (contract 61 section 2, CCR-CPN-LIFECYCLE-RULES-001 section 1):
 * exactly the CCR-C006 projection keys. amountOff is mandatory and positive; thresholdAmount is
 * optional (absent/null = no threshold); scopeSummary/typeLabel are display-only and never gate
 * eligibility in V1. Members with the wrong type or a broken scale read as absent, never throw.
 */
public final class CouponRule {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal MAX = new BigDecimal("9999999999999999.99");

    private final BigDecimal amountOff;
    private final BigDecimal thresholdAmount;

    private CouponRule(BigDecimal amountOff, BigDecimal thresholdAmount) {
        this.amountOff = amountOff;
        this.thresholdAmount = thresholdAmount;
    }

    /** Parses the frozen four-key object; null when the document is unreadable or amountOff is absent. */
    public static CouponRule parse(String ruleJson) {
        if (ruleJson == null || ruleJson.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(ruleJson);
        } catch (Exception unreadable) {
            return null;
        }
        if (root == null || !root.isObject()) return null;
        BigDecimal amountOff = amount(root.path("amountOff"), true);
        // Without a usable positive amountOff the coupon can never discount; treat as unusable.
        if (amountOff == null || amountOff.signum() <= 0) return null;
        return new CouponRule(amountOff, amount(root.path("thresholdAmount"), false));
    }

    /** Discount applied at freeze; always positive and below the order amount (freeze gate 7). */
    public BigDecimal amountOff() {
        return amountOff;
    }

    /** Order-amount threshold; null means no threshold. */
    public BigDecimal thresholdAmount() {
        return thresholdAmount;
    }

    /**
     * Two-decimal plain string or absent. Numbers with more than two decimals, negatives and
     * out-of-range magnitudes are absent (defensive, mirroring the C-end read projection).
     */
    private static BigDecimal amount(JsonNode node, boolean required) {
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
                || value.scale() > 2 || value.compareTo(MAX) > 0) {
            return null;
        }
        return value;
    }
}
