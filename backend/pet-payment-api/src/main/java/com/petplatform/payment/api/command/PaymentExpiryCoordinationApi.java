package com.petplatform.payment.api.command;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/** Network reconciliation before ORDER's expiry transaction; every result needs owner proof reread. */
public interface PaymentExpiryCoordinationApi {
    ExpiryEvidence reconcileForExpiry(ReconcilePaymentExpiryCommand command);

    record ReconcilePaymentExpiryCommand(CommandContext context, String orderId,
            String storeId, OffsetDateTime expectedDeadline) {}

    enum ExpiryEvidence { NO_PAYMENT, FENCED_UNSENT, VERIFIED_TERMINAL_CLOSED, HOLD }
}
