package com.petplatform.coupon.api.query;
import com.petplatform.common.QueryContext;
/** Internal guarded proof for the no-payment/no-coupon booking slice. Unknown facts throw 503. */
public interface BookingCouponExposureApi {
    void requireNoCoupon(String orderId, String storeId, QueryContext context);
}
