package com.petplatform.payment.api.command;

import com.petplatform.payment.api.dto.SettlementTypes.MarkSettlementVerifiedCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RegisterSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;

/** Internal trigger entry for registering settlement orders and starting T+d (used by ORDER events & DEMO loop). */
public interface SettlementTriggerApi {
    SettlementOrderView registerForPayment(RegisterSettlementCommand command);
    SettlementOrderView markVerified(MarkSettlementVerifiedCommand command);
}
