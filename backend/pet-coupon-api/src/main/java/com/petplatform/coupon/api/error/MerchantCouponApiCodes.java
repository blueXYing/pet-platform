package com.petplatform.coupon.api.error;

/** Error codes of contract 79 section 7 (registered in docs/04-api/12). */
public final class MerchantCouponApiCodes {

    private MerchantCouponApiCodes() {}

    public static final String INVALID = "MERCHANT_COUPON_INVALID";
    public static final String NOT_FOUND = "MERCHANT_COUPON_NOT_FOUND";
    public static final String STATE_NOT_ALLOWED = "MERCHANT_COUPON_STATE_NOT_ALLOWED";
    public static final String NAME_DUPLICATE = "MERCHANT_COUPON_NAME_DUPLICATE";
    public static final String SCOPE_MISMATCH = "COUPON_SCOPE_MISMATCH";
}
