package com.petplatform.order.biz.application;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Private ORDER proof bound to the current transaction, never a reusable historical audit token. */
public final class OrderExpiryCommitProof {
    private OrderExpiryCommitProof() {}
    public static void record(DataSource source,String orderId,String reservationId) {
        Object connection=TransactionSynchronizationManager.getResource(source);
        if(connection==null || !TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException();
        TransactionSynchronizationManager.registerSynchronization(new Proof(source,connection,orderId,reservationId));
    }
    public static void require(DataSource source,String orderId,String reservationId) {
        Object connection=TransactionSynchronizationManager.getResource(source);
        if(connection==null || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(item -> item instanceof Proof p
                && p.source()==source && p.connection()==connection && p.orderId().equals(orderId)
                && p.reservationId().equals(reservationId))) throw new IllegalStateException("No cancellation in this transaction");
    }
    private record Proof(DataSource source,Object connection,String orderId,String reservationId) implements TransactionSynchronization {}
}
