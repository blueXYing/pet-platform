package com.petplatform.payment.biz.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Offline domain math verification for Ruling 40 §2/§3, Contract 75 §5.5 & Contract 78 §3. */
class SettlementAmountCalculatorTest {

    @Test
    void normalOrderFloorsMerchantShareToCents() {
        var c1 = SettlementAmountCalculator.computeBaseAndShare(
                new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("0.6000"));
        assertFalse(c1.fullyRefunded());
        assertEquals(new BigDecimal("100.00"), c1.splitBase());
        assertEquals(new BigDecimal("99.40"), c1.merchantShare());

        // 99.99 * 0.994 = 99.39006 -> floor to 99.39
        var c2 = SettlementAmountCalculator.computeBaseAndShare(
                new BigDecimal("99.99"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("99.99"), BigDecimal.ZERO, new BigDecimal("0.6000"));
        assertEquals(new BigDecimal("99.39"), c2.merchantShare());
    }

    @Test
    void platformCouponCreatesSubsidyDueAndRespectsLowestRatio() {
        // Original 100, platform coupon 20, paid 80 -> splitBase=100, merchantShare=99.40
        var share = SettlementAmountCalculator.computeBaseAndShare(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("0.6000"));
        assertEquals(new BigDecimal("99.40"), share.merchantShare());

        // lowestRatio = 0, canSeparate = 79.70 -> splitAmount = 79.70, subsidyDue = 19.70, platformRetained = 0.00
        var alloc0 = SettlementAmountCalculator.computeSplitAllocation(
                share.merchantShare(), new BigDecimal("80.00"), new BigDecimal("79.70"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("79.70"), alloc0.splitAmount());
        assertEquals(new BigDecimal("0.00"), alloc0.platformRetainedAmount());
        assertEquals(new BigDecimal("19.70"), alloc0.platformSubsidyDue());

        // lowestRatio = 0.05 -> minPlatform = ceil(80 * 0.05) = 4.00 -> splitAmount = 75.70, subsidyDue = 23.70
        var alloc5 = SettlementAmountCalculator.computeSplitAllocation(
                share.merchantShare(), new BigDecimal("80.00"), new BigDecimal("79.70"), new BigDecimal("0.05"));
        assertEquals(new BigDecimal("4.00"), alloc5.minPlatformAmount());
        assertEquals(new BigDecimal("75.70"), alloc5.splitAmount());
        assertEquals(new BigDecimal("4.00"), alloc5.platformRetainedAmount());
        assertEquals(new BigDecimal("23.70"), alloc5.platformSubsidyDue());
    }

    @Test
    void flashPriceAndPreSplitPartialOrFullRefunds() {
        // Contract 75 §5.5: original 100, flashDiscount 20, platformCoupon 10, paid 70 -> base=80, share=79.52
        var flash = SettlementAmountCalculator.computeBaseAndShare(
                new BigDecimal("100.00"), new BigDecimal("20.00"), BigDecimal.ZERO,
                new BigDecimal("70.00"), BigDecimal.ZERO, new BigDecimal("0.6000"));
        assertEquals(new BigDecimal("80.00"), flash.splitBase());
        assertEquals(new BigDecimal("79.52"), flash.merchantShare());

        // Partial refund: 40 of 80 paid (50%) on base 100 -> effective splitBase = 50.00, share = 49.70
        var partial = SettlementAmountCalculator.computeBaseAndShare(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("80.00"), new BigDecimal("40.00"), new BigDecimal("0.6000"));
        assertFalse(partial.fullyRefunded());
        assertEquals(new BigDecimal("50.00"), partial.splitBase());
        assertEquals(new BigDecimal("49.70"), partial.merchantShare());

        // Full refund cancels settlement
        var full = SettlementAmountCalculator.computeBaseAndShare(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("80.00"), new BigDecimal("80.00"), new BigDecimal("0.6000"));
        assertTrue(full.fullyRefunded());
        assertEquals(new BigDecimal("0.00"), full.splitBase());
        assertEquals(new BigDecimal("0.00"), full.merchantShare());
    }

    @Test
    void validationBoundaries() {
        SettlementAmountCalculator.validateSettleDays(7);
        SettlementAmountCalculator.validateSettleDays(30);
        assertThrows(IllegalArgumentException.class, () -> SettlementAmountCalculator.validateSettleDays(6));
        assertThrows(IllegalArgumentException.class, () -> SettlementAmountCalculator.validateSettleDays(31));

        assertEquals(new BigDecimal("0.0000"), SettlementAmountCalculator.validateFeeRate(BigDecimal.ZERO));
        assertEquals(new BigDecimal("99.9999"), SettlementAmountCalculator.validateFeeRate(new BigDecimal("99.9999")));
        assertThrows(IllegalArgumentException.class, () -> SettlementAmountCalculator.validateFeeRate(new BigDecimal("-0.0001")));
        assertThrows(IllegalArgumentException.class, () -> SettlementAmountCalculator.validateFeeRate(new BigDecimal("100.0000")));
        assertThrows(IllegalArgumentException.class, () -> SettlementAmountCalculator.validateFeeRate(new BigDecimal("0.60001")));

        assertEquals(new BigDecimal("0.0000"), SettlementAmountCalculator.validateLowestRatio(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> SettlementAmountCalculator.validateLowestRatio(BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> SettlementAmountCalculator.validateLowestRatio(new BigDecimal("-0.01")));
    }

    @Test
    void fallbackAmountSubsidyRecomputationAnd180DayWindow() {
        // Normal order: paid 100.00, fee 0.6%, split 99.40
        // Partial refund 30.00 -> floor(30 * 0.994) = 29.82
        BigDecimal fb1 = SettlementAmountCalculator.computeFallbackAmount(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("30.00"),
                new BigDecimal("0.6000"), new BigDecimal("99.40"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("29.82"), fb1);

        // Final partial refund 70.00 (cumulative 100.00) -> exact remaining reversible 69.58
        BigDecimal fb2 = SettlementAmountCalculator.computeFallbackAmount(
                new BigDecimal("100.00"), new BigDecimal("30.00"), new BigDecimal("70.00"),
                new BigDecimal("0.6000"), new BigDecimal("99.40"), new BigDecimal("29.82"));
        assertEquals(new BigDecimal("69.58"), fb2);

        // Platform coupon order: orig 100, coupon 20, paid 80, split 80.00, subsidy_due 19.40
        // Partial refund 40.00 -> fallback = floor(40 * 0.994) = 39.76; remaining on-channel 40.24;
        // new merchantShare = floor(50 * 0.994) = 49.70 -> new subsidyDue = 49.70 - 40.24 = 9.46
        BigDecimal couponFb1 = SettlementAmountCalculator.computeFallbackAmount(
                new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("40.00"),
                new BigDecimal("0.6000"), new BigDecimal("80.00"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("39.76"), couponFb1);
        assertEquals(new BigDecimal("9.46"), SettlementAmountCalculator.recomputePlatformSubsidyDue(
                new BigDecimal("49.70"), new BigDecimal("80.00"), new BigDecimal("39.76")));

        // Full refund on coupon order -> reverse all remaining 40.24, subsidyDue = 0.00
        BigDecimal couponFb2 = SettlementAmountCalculator.computeFallbackAmount(
                new BigDecimal("80.00"), new BigDecimal("40.00"), new BigDecimal("40.00"),
                new BigDecimal("0.6000"), new BigDecimal("80.00"), new BigDecimal("39.76"));
        assertEquals(new BigDecimal("40.24"), couponFb2);
        assertEquals(new BigDecimal("0.00"), SettlementAmountCalculator.recomputePlatformSubsidyDue(
                BigDecimal.ZERO, new BigDecimal("80.00"), new BigDecimal("80.00")));

        // Zero reversible or tiny refund floored to 0 returns 0.00
        assertEquals(new BigDecimal("0.00"), SettlementAmountCalculator.computeFallbackAmount(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("10.00"),
                new BigDecimal("0.6000"), BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.00"), SettlementAmountCalculator.computeFallbackAmount(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("0.01"),
                new BigDecimal("1.0000"), new BigDecimal("99.00"), BigDecimal.ZERO));

        // 180-day window boundary
        OffsetDateTime splitAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        assertTrue(SettlementAmountCalculator.isWithinFallbackWindow(splitAt, splitAt.plusDays(180)));
        assertFalse(SettlementAmountCalculator.isWithinFallbackWindow(splitAt, splitAt.plusDays(180).plusSeconds(1)));
        assertFalse(SettlementAmountCalculator.isWithinFallbackWindow(null, splitAt));
    }

    @Test
    void modeAPlatformFallbackAmountProRatesAndClearsRemainderOnFinalRefund() {
        // Paid 100.00, fee 0.6%, platform split 0.60
        // Partial refund 50.00 -> floor(50 * 0.006) = 0.30
        BigDecimal fb1 = SettlementAmountCalculator.computePlatformFallbackAmount(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("50.00"),
                new BigDecimal("0.6000"), new BigDecimal("0.60"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("0.30"), fb1);

        // Final refund 50.00 -> returns all remaining 0.30
        BigDecimal fb2 = SettlementAmountCalculator.computePlatformFallbackAmount(
                new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("50.00"),
                new BigDecimal("0.6000"), new BigDecimal("0.60"), new BigDecimal("0.30"));
        assertEquals(new BigDecimal("0.30"), fb2);

        // Zero platform split (e.g. ¥0.01 or platform-coupon order) -> 0.00 (skip 2395)
        assertEquals(new BigDecimal("0.00"), SettlementAmountCalculator.computePlatformFallbackAmount(
                new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("80.00"),
                new BigDecimal("0.6000"), BigDecimal.ZERO, BigDecimal.ZERO));

        // Partial refund where floor(refund * feeRate) == 0 returns 0.00; final refund clears full remainder
        assertEquals(new BigDecimal("0.00"), SettlementAmountCalculator.computePlatformFallbackAmount(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("1.00"),
                new BigDecimal("0.6000"), new BigDecimal("0.60"), BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.60"), SettlementAmountCalculator.computePlatformFallbackAmount(
                new BigDecimal("100.00"), new BigDecimal("1.00"), new BigDecimal("99.00"),
                new BigDecimal("0.6000"), new BigDecimal("0.60"), BigDecimal.ZERO));
    }
}
