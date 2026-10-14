package com.petplatform.order.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** Read-only order_reschedule_record row (schema 46) for the contract-63 detail face. */
public final class AdminRescheduleEntity {
    private Long id;
    private Long orderId;
    private LocalDateTime oldStart;
    private LocalDateTime oldEnd;
    private LocalDateTime newStart;
    private LocalDateTime newEnd;
    private LocalDateTime rescheduledAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public LocalDateTime getOldStart() { return oldStart; }
    public void setOldStart(LocalDateTime oldStart) { this.oldStart = oldStart; }
    public LocalDateTime getOldEnd() { return oldEnd; }
    public void setOldEnd(LocalDateTime oldEnd) { this.oldEnd = oldEnd; }
    public LocalDateTime getNewStart() { return newStart; }
    public void setNewStart(LocalDateTime newStart) { this.newStart = newStart; }
    public LocalDateTime getNewEnd() { return newEnd; }
    public void setNewEnd(LocalDateTime newEnd) { this.newEnd = newEnd; }
    public LocalDateTime getRescheduledAt() { return rescheduledAt; }
    public void setRescheduledAt(LocalDateTime rescheduledAt) { this.rescheduledAt = rescheduledAt; }
}
