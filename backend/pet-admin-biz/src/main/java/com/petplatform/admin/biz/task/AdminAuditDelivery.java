package com.petplatform.admin.biz.task;

import com.petplatform.admin.biz.infrastructure.persistence.AdminAuthStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Same-Owner persistent intent delivery, independent of login availability. */
public final class AdminAuditDelivery {
  private static final System.Logger LOG = System.getLogger(AdminAuditDelivery.class.getName());
  private final AdminAuthStore store;
  private final AdminAuditSink sink;

  public AdminAuditDelivery(DataSource source, AdminAuditSink sink) {
    store = new AdminAuthStore(source);
    this.sink = Objects.requireNonNull(sink);
  }

  public int deliverPending(int limit) {
    if (limit < 1 || limit > 1000)
      throw new IllegalArgumentException("Audit batch 1..1000 required");
    var ids = store.read(tx -> tx.auth().selectPendingAuditIds(limit));
    int count = 0;
    for (var id : ids) {
      try {
        Boolean delivered =
            store.write(
                tx -> {
                  var row = tx.auth().selectAuditIntentForUpdate(id);
                  if (row == null) throw com.petplatform.admin.biz.application.AdminAuthFailure.unavailable();
                  if (!"PENDING".equals(row.deliveryState)) return false;
                  try {
                    sink.append(
                        new AdminAuditSink.Entry(
                            row.id,
                            row.actorId,
                            row.attemptId,
                            row.actionCode,
                            row.resourceId,
                            row.outcome,
                            row.reason,
                            row.occurredAt,
                            row.actorReference));
                    tx.auth().markAuditDelivered(row.id);
                    return true;
                  } catch (RuntimeException e) {
                    tx.auth().deferAuditRetry(row.id);
                    LOG.log(
                        System.Logger.Level.WARNING,
                        "Admin audit delivery unavailable; persistent intent retained");
                    return false;
                  }
                });
        if (delivered) count++;
      } catch (RuntimeException e) {
        LOG.log(
            System.Logger.Level.WARNING,
            "Admin audit acknowledgement unavailable; exact ID will be retried");
      }
    }
    return count;
  }
}
