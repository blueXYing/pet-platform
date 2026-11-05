package com.petplatform.payment.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPaymentSuccessFact;

/**
 * Contract 75 §4.2. Callers must be inside a transaction on the PAYMENT DataSource; the implementation
 * locks the PAYMENT row and requires a matching verified SUCCESS receipt, otherwise 503. An event is not proof.
 */
public interface PaymentCenterFactsApi {
    CenterPaymentSuccessFact requireSucceeded(String bizType, String bizNo, QueryContext context);
}
