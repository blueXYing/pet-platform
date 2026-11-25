package com.petplatform.payment.biz.settlement;

import java.util.Optional;

/**
 * Port for reading a merchant's ACTIVE Lakala split receiver number.
 *
 * <p>Stage 2 defaults to {@link #failClosed()} so due settlement orders pause cleanly in
 * {@code ON_HOLD(RECEIVER_NOT_ACTIVE)} per Ruling 40 §4.3 until Stage 4 delivers receiver onboarding.
 */
@FunctionalInterface
public interface SettlementReceiverLookup {
    Optional<ActiveReceiver> findActiveByMerchantId(long merchantId);

    record ActiveReceiver(long merchantId, String receiverNo) {
        public ActiveReceiver {
            if (merchantId <= 0 || receiverNo == null || receiverNo.isBlank() || receiverNo.length() > 32) {
                throw new IllegalArgumentException("invalid active receiver");
            }
        }
    }

    static SettlementReceiverLookup failClosed() {
        return merchantId -> Optional.empty();
    }
}
