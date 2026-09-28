package com.petplatform.payment.api.query;
import com.petplatform.common.QueryContext;
/** Internal guarded proof for the no-payment/no-coupon booking slice. Unknown facts throw 503. */
public interface BookingPaymentExposureApi {
    void requireNoPayment(String orderId, String storeId, QueryContext context);
}
