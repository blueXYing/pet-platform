package com.petplatform.schedule.biz.infrastructure.persistence.entity;

public class StaffCapabilityEntity {
    private Long id;
    private Long staffId;
    private Long serviceId;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStaffId() { return staffId; }
    public void setStaffId(Long staffId) { this.staffId = staffId; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
