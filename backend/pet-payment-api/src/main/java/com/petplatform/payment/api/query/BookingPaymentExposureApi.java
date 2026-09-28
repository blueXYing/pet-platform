package com.petplatform.payment.api.query;
import com.petplatform.common.QueryContext;
/** Internal guarded proof for the no-payment/no-coupon booking slice. Unknown facts throw 503. */
public interface BookingPaymentExposureApi {
    /** Current, same-transaction proof; legacy implementations retain the no-payment restriction. */
    default void requireSafeToExpire(String orderId, String storeId,
            java.time.OffsetDateTime expectedDeadline, com.petplatform.common.QueryContext context) {
        requireNoPayment(orderId, storeId, context);
    }
    void requireNoPayment(String orderId, String storeId, QueryContext context);
}
