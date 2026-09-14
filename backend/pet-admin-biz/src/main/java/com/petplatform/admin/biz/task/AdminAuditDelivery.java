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
    var rows =
        store.read(
            tx ->
                tx.rows(
                    "SELECT id FROM admin_audit_intent WHERE delivery_state='PENDING' AND"
                        + " next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY id LIMIT ?",
                    limit));
    int count = 0;
    for (var candidate : rows) {
      try {
        Boolean delivered =
            store.write(
                tx -> {
                  var row =
                      tx.one(
                          "SELECT * FROM admin_audit_intent WHERE id=? FOR UPDATE",
                          candidate.number("id"));
                  if (!"PENDING".equals(row.text("delivery_state"))) return false;
                  try {
                    sink.append(
                        new AdminAuditSink.Entry(
                            row.number("id"),
                            row.number("actor_id") == 0 ? null : row.number("actor_id"),
                            row.number("attempt_id") == 0 ? null : row.number("attempt_id"),
                            row.text("action_code"),
                            row.number("resource_id") == 0 ? null : row.number("resource_id"),
                            row.text("outcome"),
                            row.text("reason"),
                            row.time("occurred_at"),
                            row.text("actor_reference")));
                    tx.update(
                        "UPDATE admin_audit_intent SET"
                            + " delivery_state='DELIVERED',delivered_at=UTC_TIMESTAMP(3),attempt_count=attempt_count+1"
                            + " WHERE id=?",
                        row.number("id"));
                    return true;
                  } catch (RuntimeException e) {
                    tx.update(
                        "UPDATE admin_audit_intent SET"
                            + " attempt_count=attempt_count+1,next_attempt_at=TIMESTAMPADD(SECOND,5,UTC_TIMESTAMP(3))"
                            + " WHERE id=?",
                        row.number("id"));
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
