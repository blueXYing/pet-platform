package com.petplatform.payment.biz.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure domain math for order settlement (Ruling 40 §2/§3, CCR-PAY-CENTER-001 §5, Contract 75 §5.5,
 * Contract 78 §3). Zero framework or infrastructure dependencies.
 */
public final class SettlementAmountCalculator {
    public static final int DEFAULT_SETTLE_DAYS = 7;
    public static final int MIN_SETTLE_DAYS = 7;
    public static final int MAX_SETTLE_DAYS = 30;
    /** Lakala 2395 fallback window: 180 days from original split completion (Ruling 40 §6.2). */
    public static final int FALLBACK_WINDOW_DAYS = 180;
    /** Default external merchant fee rate: 0.6% stored as percentage with 4 decimals (0.6000). */
    public static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.6000");
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private SettlementAmountCalculator() {}

    public record ShareCalculation(BigDecimal splitBase, BigDecimal merchantShare, boolean fullyRefunded) {}

    public record SplitAllocation(
            BigDecimal splitAmount,
            BigDecimal platformRetainedAmount,
            BigDecimal platformSubsidyDue,
            BigDecimal minPlatformAmount) {}

    public static void validateSettleDays(int settleDays) {
        if (settleDays < MIN_SETTLE_DAYS || settleDays > MAX_SETTLE_DAYS) {
            throw new IllegalArgumentException("settle_days must be between 7 and 30");
        }
    }

    /** External fee rate percentage: [0.0000, 100.0000), scale <= 4. */
    public static BigDecimal validateFeeRate(BigDecimal feeRate) {
        if (feeRate == null || feeRate.scale() > 4 || feeRate.signum() < 0
                || feeRate.compareTo(HUNDRED) >= 0) {
            throw new IllegalArgumentException("fee_rate must be in [0, 100) with at most 4 decimals");
        }
        return feeRate.setScale(4, RoundingMode.UNNECESSARY);
    }

    /** Platform minimum share ratio (decimal in [0, 1), scale <= 4; e.g. 0 or 0.05 for 5%). */
    public static BigDecimal validateLowestRatio(BigDecimal lowestRatio) {
        if (lowestRatio == null || lowestRatio.scale() > 4 || lowestRatio.signum() < 0
                || lowestRatio.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("lowest_ratio must be in [0, 1) with at most 4 decimals");
        }
        return lowestRatio.setScale(4, RoundingMode.UNNECESSARY);
    }

    /**
     * Computes effective split base and merchant share before channel split execution.
     *
     * <p>Contract 75 §5.5: {@code base0 = originalPrice - flashDiscount - merchantDiscount} (platform
     * coupons are never deducted from merchant base). Before split, full refund cancels settlement;
     * partial refund scales {@code base0} proportionally by {@code (paidAmount - refundedAmount) / paidAmount}
     * floored to cents. {@code merchantShare = floor(splitBase * (1 - feeRate / 100))} to cents.
     */
    public static ShareCalculation computeBaseAndShare(
            BigDecimal originalPrice,
            BigDecimal flashDiscount,
            BigDecimal merchantDiscount,
            BigDecimal paidAmount,
            BigDecimal refundedAmount,
            BigDecimal feeRatePercent) {
        BigDecimal orig = requireNonNegativeMoney(originalPrice, "originalPrice");
        BigDecimal flash = requireNonNegativeMoney(flashDiscount, "flashDiscount");
        BigDecimal merchantDisc = requireNonNegativeMoney(merchantDiscount, "merchantDiscount");
        BigDecimal paid = requirePositiveMoney(paidAmount, "paidAmount");
        BigDecimal refunded = requireNonNegativeMoney(refundedAmount, "refundedAmount");
        BigDecimal ratePercent = validateFeeRate(feeRatePercent);

        BigDecimal base0 = orig.subtract(flash).subtract(merchantDisc);
        if (base0.signum() < 0) {
            throw new IllegalArgumentException("split base cannot be negative");
        }
        if (refunded.compareTo(paid) >= 0) {
            return new ShareCalculation(ZERO, ZERO, true);
        }
        BigDecimal splitBase;
        if (refunded.signum() == 0) {
            splitBase = base0.setScale(2, RoundingMode.UNNECESSARY);
        } else {
            BigDecimal remainingPaid = paid.subtract(refunded);
            splitBase = base0.multiply(remainingPaid).divide(paid, 2, RoundingMode.FLOOR);
        }
        // exact: ratePercent / 100 has scale 6
        BigDecimal keepRatio = BigDecimal.ONE.subtract(ratePercent.movePointLeft(2));
        BigDecimal merchantShare = splitBase.multiply(keepRatio).setScale(2, RoundingMode.FLOOR);
        return new ShareCalculation(splitBase, merchantShare, false);
    }

    /**
     * Computes actual split amounts against Lakala 2396 {@code can_separate_amt} and configured
     * platform minimum share ratio (Ruling 40 §3.2 / CCR §5).
     */
    public static SplitAllocation computeSplitAllocation(
            BigDecimal merchantShare,
            BigDecimal paidAmount,
            BigDecimal canSeparateAmount,
            BigDecimal lowestRatio) {
        BigDecimal share = requireNonNegativeMoney(merchantShare, "merchantShare");
        BigDecimal paid = requirePositiveMoney(paidAmount, "paidAmount");
        BigDecimal canSep = requireNonNegativeMoney(canSeparateAmount, "canSeparateAmount");
        BigDecimal ratio = validateLowestRatio(lowestRatio);

        BigDecimal minPlatform = paid.multiply(ratio).setScale(2, RoundingMode.CEILING);
        BigDecimal maxForMerchant = canSep.subtract(minPlatform);
        if (maxForMerchant.signum() < 0) {
            maxForMerchant = ZERO;
        }
        BigDecimal splitAmount = share.min(maxForMerchant).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal platformSubsidyDue = share.subtract(splitAmount).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal platformRetained = canSep.subtract(splitAmount).setScale(2, RoundingMode.UNNECESSARY);
        return new SplitAllocation(splitAmount, platformRetained, platformSubsidyDue, minPlatform);
    }

    /**
     * Computes merchant split fallback amount for a post-split refund (Ruling 40 §6.2/§6.3, Contract 78 §8.2).
     *
     * <p>Full or cumulative-final refunds reverse all remaining split merchant funds so no rounding dust
     * remains stranded; partial refunds reverse {@code min(remainingReversible, floor(refundAmount * (1 - feeRate/100)))}.
     */
    public static BigDecimal computeFallbackAmount(
            BigDecimal paidAmount,
            BigDecimal priorRefundedAmount,
            BigDecimal refundAmount,
            BigDecimal feeRatePercent,
            BigDecimal splitAmount,
            BigDecimal priorReversedAmount) {
        BigDecimal paid = requirePositiveMoney(paidAmount, "paidAmount");
        BigDecimal priorRefunded = requireNonNegativeMoney(priorRefundedAmount, "priorRefundedAmount");
        BigDecimal refund = requirePositiveMoney(refundAmount, "refundAmount");
        BigDecimal ratePercent = validateFeeRate(feeRatePercent);
        BigDecimal split = requireNonNegativeMoney(splitAmount, "splitAmount");
        BigDecimal reversed = requireNonNegativeMoney(priorReversedAmount, "priorReversedAmount");

        BigDecimal remainingReversible = split.subtract(reversed);
        if (remainingReversible.signum() <= 0) {
            return ZERO;
        }
        if (priorRefunded.add(refund).compareTo(paid) >= 0) {
            return remainingReversible.setScale(2, RoundingMode.UNNECESSARY);
        }
        BigDecimal keepRatio = BigDecimal.ONE.subtract(ratePercent.movePointLeft(2));
        BigDecimal rawFallback = refund.multiply(keepRatio).setScale(2, RoundingMode.FLOOR);
        return rawFallback.min(remainingReversible).setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Recomputes off-channel platform subsidy payable after a post-split refund so that
     * {@code (splitAmount - reversedAmount) + platformSubsidyDue == newMerchantShare} (floored at 0).
     */
    public static BigDecimal recomputePlatformSubsidyDue(
            BigDecimal newMerchantShare,
            BigDecimal splitAmount,
            BigDecimal reversedAmount) {
        BigDecimal share = requireNonNegativeMoney(newMerchantShare, "newMerchantShare");
        BigDecimal split = requireNonNegativeMoney(splitAmount, "splitAmount");
        BigDecimal reversed = requireNonNegativeMoney(reversedAmount, "reversedAmount");
        BigDecimal netOnChannel = split.subtract(reversed);
        if (netOnChannel.signum() < 0) {
            netOnChannel = ZERO;
        }
        BigDecimal due = share.subtract(netOnChannel);
        return due.signum() <= 0 ? ZERO : due.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** True iff {@code now} is within Lakala 2395's 180-day fallback window from {@code splitCompletedAt}. */
    public static boolean isWithinFallbackWindow(java.time.OffsetDateTime splitCompletedAt,
            java.time.OffsetDateTime now) {
        if (splitCompletedAt == null || now == null) return false;
        return !now.isAfter(splitCompletedAt.plusDays(FALLBACK_WINDOW_DAYS));
    }

    public static long toCents(BigDecimal amount) {
        BigDecimal normalized = requireNonNegativeMoney(amount, "amount");
        return normalized.movePointRight(2).longValueExact();
    }

    private static BigDecimal requireNonNegativeMoney(BigDecimal value, String name) {
        if (value == null || value.scale() > 2 || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be a non-negative amount with scale <= 2");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal requirePositiveMoney(BigDecimal value, String name) {
        BigDecimal normalized = requireNonNegativeMoney(value, name);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }
}
