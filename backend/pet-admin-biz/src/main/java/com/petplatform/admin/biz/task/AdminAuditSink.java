package com.petplatform.admin.biz.task;

import java.time.Instant;

/** Append acknowledges durable receipt of this exact ID/payload; retry must be idempotent. */
public interface AdminAuditSink {
  void append(Entry entry);

  record Entry(
      long id,
      Long actorId,
      Long attemptId,
      String action,
      Long resourceId,
      String outcome,
      String reason,
      Instant occurredAt,
      String actorReference) {
    public Entry(
        long id,
        Long actorId,
        Long attemptId,
        String action,
        Long resourceId,
        String outcome,
        String reason,
        Instant occurredAt) {
      this(
          id,
          actorId,
          attemptId,
          action,
          resourceId,
          outcome,
          reason,
          occurredAt,
          actorId == null ? "ANONYMOUS" : "OPERATOR:" + actorId);
    }

    @Override
    public String toString() {
      return "AdminAuditEntry[id=" + id + ",action=" + action + ",outcome=" + outcome + "]";
    }
  }
}
