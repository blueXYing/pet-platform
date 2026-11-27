package com.petplatform.order.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * ORDER-owned authoritative pricing, refund and verification snapshot for settlement calculation
 * (Contract 75 §5.5 & Contract 78 §3). IDs are decimal strings across modules.
 * merchantCouponDiscount (contract 79 §6.2) is the part of discountAmount funded by the merchant;
 * it is 0.00 for orders without a merchant coupon, exactly like every pre-P3 row.
 */
public record OrderSettlementFact(
        String orderId,
        String merchantId,
        String storeId,
        boolean orderCanceled,
        boolean verified,
        BigDecimal originalPrice,
        BigDecimal flashDiscount,
        BigDecimal discountAmount,
        BigDecimal payAmount,
        BigDecimal refundedAmount,
        String currentRefundApplicationId,
        String refundOrderId,
        String currentAfterSaleId,
        OffsetDateTime verifiedAt,
        BigDecimal merchantCouponDiscount) {}
