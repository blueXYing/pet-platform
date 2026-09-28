package com.petplatform.order.api.command;

import com.petplatform.order.api.dto.OrderPaymentResultTypes.ConsumePaymentResult;
import com.petplatform.order.api.dto.OrderPaymentResultTypes.ConsumePaymentSucceededCommand;

/** Internal-only payment-success result command, never exposed as a client payment callback. */
public interface OrderPaymentResultApi {
    ConsumePaymentResult consumePaymentSucceeded(ConsumePaymentSucceededCommand command);
}
