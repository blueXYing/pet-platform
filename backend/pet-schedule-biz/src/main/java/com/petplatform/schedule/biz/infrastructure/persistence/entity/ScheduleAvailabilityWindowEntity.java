package com.petplatform.schedule.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** Window row of schedule_availability_window (06 schema section 2). */
public final class ScheduleAvailabilityWindowEntity {
    private Long id;
    private Long merchantId;
    private Long storeId;
    private Long serviceId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer configuredCapacity;
    private String status;
    private Long version;

    public Long getId() { return id; }
    public Long getMerchantId() { return merchantId; }
    public Long getStoreId() { return storeId; }
    public Long getServiceId() { return serviceId; }
    public LocalDateTime getStartAt() { return startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public Integer getConfiguredCapacity() { return configuredCapacity; }
    public String getStatus() { return status; }
    public Long getVersion() { return version; }

    public void setId(Long value) { id = value; }
    public void setMerchantId(Long value) { merchantId = value; }
    public void setStoreId(Long value) { storeId = value; }
    public void setServiceId(Long value) { serviceId = value; }
    public void setStartAt(LocalDateTime value) { startAt = value; }
    public void setEndAt(LocalDateTime value) { endAt = value; }
    public void setConfiguredCapacity(Integer value) { configuredCapacity = value; }
    public void setStatus(String value) { status = value; }
    public void setVersion(Long value) { version = value; }
}
