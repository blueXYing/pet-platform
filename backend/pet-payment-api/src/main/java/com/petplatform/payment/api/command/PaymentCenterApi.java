package com.petplatform.payment.api.command;

import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPayment;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPaymentView;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CreateCenterPaymentCommand;
import com.petplatform.payment.api.dto.PaymentCenterTypes.QueryCenterPayment;

/**
 * Contract 75 §4.1 in-process payment center (bizType + bizNo). Not an HTTP contract.
 * {@code create} is requestId-idempotent; the same business object maps to at most one payment.
 */
public interface PaymentCenterApi {
    CenterPayment create(CreateCenterPaymentCommand command);
    CenterPaymentView query(QueryCenterPayment query);
}
