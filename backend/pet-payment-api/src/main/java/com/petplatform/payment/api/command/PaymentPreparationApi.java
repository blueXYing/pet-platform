package com.petplatform.payment.api.command;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.*;
/** Internal durable intent only. This is not createPayment and returns no usable payment parameters. */
public interface PaymentPreparationApi { PreparedPayment prepare(PreparePaymentCommand command); }
