package com.petplatform.schedule.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

public class StaffAvailabilityEntity {
    private Long id;
    private Long storeId;
    private Long staffId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getStaffId() { return staffId; }
    public void setStaffId(Long staffId) { this.staffId = staffId; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
