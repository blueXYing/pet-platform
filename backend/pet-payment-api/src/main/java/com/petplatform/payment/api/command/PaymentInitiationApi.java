package com.petplatform.payment.api.command;

import com.petplatform.payment.api.dto.PaymentInitiationTypes.InitiatedPayment;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparePaymentCommand;

/** Internal synchronous create-payment kernel. A success includes usable WeChat parameters. */
public interface PaymentInitiationApi {
    InitiatedPayment create(PreparePaymentCommand command);
}
