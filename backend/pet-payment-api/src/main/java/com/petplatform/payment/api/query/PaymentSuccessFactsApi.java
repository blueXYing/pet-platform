package com.petplatform.payment.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.PaymentSuccessFact;
public interface PaymentSuccessFactsApi {
    PaymentSuccessFact requireSucceeded(String paymentId,String orderId,String storeId,QueryContext context);
}
