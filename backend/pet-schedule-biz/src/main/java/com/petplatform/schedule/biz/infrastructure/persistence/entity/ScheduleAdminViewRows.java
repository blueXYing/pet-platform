package com.petplatform.schedule.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** Read-only rows behind the contract-64 schedule view (schema 06/37 tables). */
public final class ScheduleAdminViewRows {
    private ScheduleAdminViewRows() {}

    public static final class AdminWindowRow {
        private Long id;
        private Long merchantId;
        private Long storeId;
        private Long serviceId;
        private String windowKind;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
        private Integer configuredCapacity;
        private String status;
        private Long version;
        private LocalDateTime updatedAt;
        private Integer confirmedCount;
        private Integer tempLockedCount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Long getServiceId() { return serviceId; }
        public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
        public String getWindowKind() { return windowKind; }
        public void setWindowKind(String windowKind) { this.windowKind = windowKind; }
        public LocalDateTime getStartAt() { return startAt; }
        public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
        public LocalDateTime getEndAt() { return endAt; }
        public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
        public Integer getConfiguredCapacity() { return configuredCapacity; }
        public void setConfiguredCapacity(Integer configuredCapacity) { this.configuredCapacity = configuredCapacity; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        public Integer getConfirmedCount() { return confirmedCount; }
        public void setConfirmedCount(Integer confirmedCount) { this.confirmedCount = confirmedCount; }
        public Integer getTempLockedCount() { return tempLockedCount; }
        public void setTempLockedCount(Integer tempLockedCount) { this.tempLockedCount = tempLockedCount; }
    }

    public static final class AdminStaffWindowRow {
        private Long id;
        private Long storeId;
        private Long staffId;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
        private String status;
        private Long version;
        private LocalDateTime updatedAt;

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
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    public static final class AdminReservationRow {
        private Long id;
        private Long orderId;
        private Long storeId;
        private Long serviceId;
        private String fulfillmentType;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
        private LocalDateTime pickupStartAt;
        private LocalDateTime returnStartAt;
        private String status;
        private LocalDateTime lockExpireAt;
        private Integer capacitySnapshot;
        private Integer qualifiedStaffCountSnapshot;
        private LocalDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Long getServiceId() { return serviceId; }
        public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
        public String getFulfillmentType() { return fulfillmentType; }
        public void setFulfillmentType(String fulfillmentType) { this.fulfillmentType = fulfillmentType; }
        public LocalDateTime getStartAt() { return startAt; }
        public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
        public LocalDateTime getEndAt() { return endAt; }
        public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
        public LocalDateTime getPickupStartAt() { return pickupStartAt; }
        public void setPickupStartAt(LocalDateTime pickupStartAt) { this.pickupStartAt = pickupStartAt; }
        public LocalDateTime getReturnStartAt() { return returnStartAt; }
        public void setReturnStartAt(LocalDateTime returnStartAt) { this.returnStartAt = returnStartAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getLockExpireAt() { return lockExpireAt; }
        public void setLockExpireAt(LocalDateTime lockExpireAt) { this.lockExpireAt = lockExpireAt; }
        public Integer getCapacitySnapshot() { return capacitySnapshot; }
        public void setCapacitySnapshot(Integer capacitySnapshot) { this.capacitySnapshot = capacitySnapshot; }
        public Integer getQualifiedStaffCountSnapshot() { return qualifiedStaffCountSnapshot; }
        public void setQualifiedStaffCountSnapshot(Integer qualifiedStaffCountSnapshot) { this.qualifiedStaffCountSnapshot = qualifiedStaffCountSnapshot; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
}
