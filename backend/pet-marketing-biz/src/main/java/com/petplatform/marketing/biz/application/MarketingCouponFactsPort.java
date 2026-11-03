package com.petplatform.marketing.biz.application;

/**
 * Coupon-campaign facts used to validate a campaign's coupon binding at publish time. The C
 * detail hydration uses the coupon owner's query API directly in boot; this port keeps the
 * marketing kernel independent of the coupon biz module.
 */
public interface MarketingCouponFactsPort {

    record CouponFacts(boolean exists, boolean usable, String templateId) {}

    CouponFacts facts(long couponCampaignId);
}
