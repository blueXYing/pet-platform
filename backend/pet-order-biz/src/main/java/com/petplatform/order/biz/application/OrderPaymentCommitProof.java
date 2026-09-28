package com.petplatform.order.biz.application;

import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** In-process proof of the current transaction's ORDER paid transition. */
public final class OrderPaymentCommitProof {
    private OrderPaymentCommitProof() {}
    public static void record(DataSource source, String orderId, String reservationId) {
        Object connection = TransactionSynchronizationManager.getResource(source);
        if (connection == null || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("No ORDER payment transaction");
        TransactionSynchronizationManager.registerSynchronization(
                new Proof(source, connection, orderId, reservationId));
    }
    public static void require(DataSource source, String orderId, String reservationId) {
        Object connection = TransactionSynchronizationManager.getResource(source);
        if (connection == null || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(item ->
                        item instanceof Proof p && p.source() == source && p.connection() == connection
                                && p.orderId().equals(orderId)
                                && p.reservationId().equals(reservationId))) {
            throw new IllegalStateException("No paid ORDER transition in this transaction");
        }
    }
    private record Proof(DataSource source, Object connection, String orderId,
            String reservationId) implements TransactionSynchronization {}
}
