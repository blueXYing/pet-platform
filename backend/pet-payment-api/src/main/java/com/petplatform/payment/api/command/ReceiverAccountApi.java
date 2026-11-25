package com.petplatform.payment.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.payment.api.dto.ReceiverTypes.BindReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAccountView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverSensitiveView;
import com.petplatform.payment.api.dto.ReceiverTypes.RevealReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.UpsertReceiverCommand;

/**
 * Contract 80 §4.2 admin split-receiver onboarding commands (operator-filled per Ruling 40 §4).
 * Merchants have no write API for receiver onboarding.
 */
public interface ReceiverAccountApi {
    ReceiverAccountView upsertOrCreate(UpsertReceiverCommand command);
    ReceiverAccountView modify(UpsertReceiverCommand command);
    ReceiverAccountView bind(BindReceiverCommand command);
    ReceiverAccountView syncFromChannel(String merchantId, CommandContext context);
    ReceiverSensitiveView revealSensitive(RevealReceiverCommand command);
}
