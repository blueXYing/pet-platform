package com.petplatform.payment.api.command;

import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundProgress;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundQuery;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundSubmitCommand;

/** PAYMENT owns the channel call; no caller transaction or raw provider result is accepted. */
public interface PaymentRefundApi {
    ChannelRefundProgress submitRefund(ChannelRefundSubmitCommand command);
    ChannelRefundProgress queryRefund(ChannelRefundQuery query);
}
