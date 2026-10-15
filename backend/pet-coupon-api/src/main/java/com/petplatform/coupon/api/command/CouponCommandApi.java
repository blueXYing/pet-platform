package com.petplatform.coupon.api.command;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Coupon lifecycle write face (contract 61, CPN-001/CPN-002): freeze at booking creation,
 * consume at payment success, release at payment-timeout close. Every method must run inside the
 * caller's already-guarded writable transaction (ScheduleCapacityGuard requireHeld is enforced
 * against the command storeId); all writes are idempotent through the coupon_ledger request
 * uniqueness. Refund restore is NOT here — it consumes RefundSucceededEvent.v1 (consumer
 * COUPON_REFUND_RESTORE) per contract 61 section 6. Assembled behind
 * pet.coupon.lifecycle.enabled (default false): with the provider unassembled the 38 booking
 * kernel keeps its legacy fail-closed 503 for coupon-bearing creates.
 */
public interface CouponCommandApi {

    /**
     * Freeze one own AVAILABLE instance for an order inside the 38 kernel transaction.
     * Ineligible, expired, threshold-failing, non-discountable or concurrently taken coupons
     * answer 409 COUPON_NOT_AVAILABLE with no write side effect; the row-level CAS makes the
     * same-coupon concurrent race a one-winner decision (QA-002/CPN-004).
     */
    CouponFreezeResult freeze(CouponFreezeCommand command);

    /**
     * FROZEN -> USED for the order's coupon inside the ORDER payment-result transaction.
     * usedAt is the proven channel paidAt. A missing or non-frozen binding is 503 (uncertain),
     * never a silent pass.
     */
    CouponConsumeResult consume(CouponConsumeCommand command);

    /**
     * FROZEN -> AVAILABLE (still valid) or EXPIRED (frozen past validity) inside the 39 close
     * transaction; expire_at itself never moves (the 24h extension belongs to refund restore
     * only). Validity is evaluated against the caller-supplied closedAt database instant.
     */
    CouponReleaseResult release(CouponReleaseCommand command);

    /** Freeze request; discountable order amount is the current OWNER service price snapshot. */
    record CouponFreezeCommand(CommandContext context, String orderId, String couponInstanceId,
            String userId, String storeId, BigDecimal orderAmount, OffsetDateTime freezeExpireAt) {}

    /** Frozen instance id plus the discount the order must book (payAmount = orderAmount - discount). */
    record CouponFreezeResult(String couponInstanceId, BigDecimal discountAmount) {}

    /** Consume request; consumedAt carries the proven channel paidAt for coupon_instance.used_at. */
    record CouponConsumeCommand(CommandContext context, String orderId, String storeId,
            OffsetDateTime consumedAt) {}

    /** Consumed instance id (echoed into OrderPaidEvent.v1 couponInstanceId). */
    record CouponConsumeResult(String couponInstanceId) {}

    /** Release request; closedAt is the caller's authoritative database now instant. */
    record CouponReleaseCommand(CommandContext context, String orderId, String storeId,
            OffsetDateTime closedAt) {}

    /** Released instance id and the actual landing status (AVAILABLE or EXPIRED). */
    record CouponReleaseResult(String couponInstanceId, String toStatus) {}
}
