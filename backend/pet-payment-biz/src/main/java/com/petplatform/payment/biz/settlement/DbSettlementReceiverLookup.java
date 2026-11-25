package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.persistence.PaymentReceiverStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReceiverMapper.ReceiverAccountRow;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Database-backed {@link SettlementReceiverLookup} (Contract 80 / Stage 4).
 * Returns an {@link ActiveReceiver} ONLY when {@code settlement_receiver_account.status='ACTIVE'}
 * and {@code receiver_no} is non-blank; otherwise fails closed with {@link Optional#empty()} so
 * due settlement orders pause cleanly in {@code ON_HOLD(RECEIVER_NOT_ACTIVE)} per Ruling 40 §4.3.
 */
public final class DbSettlementReceiverLookup implements SettlementReceiverLookup {
    private final PaymentReceiverStore store;

    public DbSettlementReceiverLookup(DataSource source) {
        this.store = new PaymentReceiverStore(Objects.requireNonNull(source, "source"));
    }

    @Override
    public Optional<ActiveReceiver> findActiveByMerchantId(long merchantId) {
        if (merchantId <= 0) return Optional.empty();
        ReceiverAccountRow row = store.receiver.selectActiveByMerchantId(merchantId);
        if (row == null || !"ACTIVE".equals(row.status)
                || row.receiverNo == null || row.receiverNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ActiveReceiver(merchantId, row.receiverNo));
    }
}
