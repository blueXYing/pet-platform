package com.petplatform.order.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only aggregate rows for the derived customer face (contract 69) and the business stats
 * face (contract 70). Order-domain-owned projections only; no other module's tables appear in
 * their SQL.
 */
public final class MerchantReadProjectionEntities {
    private MerchantReadProjectionEntities() {}

    /** One derived customer: the store's own order grouping plus the latest pet snapshot. */
    public static final class CustomerAggregate {
        private Long userId;
        private Long orderCount;
        private BigDecimal totalSpend;
        private LocalDateTime lastVisitAt;
        private String topPetName;
        private String topPetType;
        private String topBreedName;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getOrderCount() { return orderCount; }
        public void setOrderCount(Long orderCount) { this.orderCount = orderCount; }
        public BigDecimal getTotalSpend() { return totalSpend; }
        public void setTotalSpend(BigDecimal totalSpend) { this.totalSpend = totalSpend; }
        public LocalDateTime getLastVisitAt() { return lastVisitAt; }
        public void setLastVisitAt(LocalDateTime lastVisitAt) { this.lastVisitAt = lastVisitAt; }
        public String getTopPetName() { return topPetName; }
        public void setTopPetName(String topPetName) { this.topPetName = topPetName; }
        public String getTopPetType() { return topPetType; }
        public void setTopPetType(String topPetType) { this.topPetType = topPetType; }
        public String getTopBreedName() { return topBreedName; }
        public void setTopBreedName(String topBreedName) { this.topBreedName = topBreedName; }
    }

    /** Breed-level pet row from the store's own order_pet_snapshot (no health fields selected). */
    public static final class CustomerPet {
        private Long petId;
        private String petName;
        private String petType;
        private String breedName;

        public Long getPetId() { return petId; }
        public void setPetId(Long petId) { this.petId = petId; }
        public String getPetName() { return petName; }
        public void setPetName(String petName) { this.petName = petName; }
        public String getPetType() { return petType; }
        public void setPetType(String petType) { this.petType = petType; }
        public String getBreedName() { return breedName; }
        public void setBreedName(String breedName) { this.breedName = breedName; }
    }

    /** One-window order aggregate for the stats overview. */
    public static final class StatsTotals {
        private Long orderCount;
        private Long paidOrderCount;
        private Long verifiedOrderCount;
        private Long refundOrderCount;
        private Long rejectOrderCount;
        private BigDecimal revenue;

        public Long getOrderCount() { return orderCount; }
        public void setOrderCount(Long orderCount) { this.orderCount = orderCount; }
        public Long getPaidOrderCount() { return paidOrderCount; }
        public void setPaidOrderCount(Long paidOrderCount) { this.paidOrderCount = paidOrderCount; }
        public Long getVerifiedOrderCount() { return verifiedOrderCount; }
        public void setVerifiedOrderCount(Long verifiedOrderCount) { this.verifiedOrderCount = verifiedOrderCount; }
        public Long getRefundOrderCount() { return refundOrderCount; }
        public void setRefundOrderCount(Long refundOrderCount) { this.refundOrderCount = refundOrderCount; }
        public Long getRejectOrderCount() { return rejectOrderCount; }
        public void setRejectOrderCount(Long rejectOrderCount) { this.rejectOrderCount = rejectOrderCount; }
        public BigDecimal getRevenue() { return revenue; }
        public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
    }

    /** One drilled-down order row of a service (this store's own facts, no customer fields). */
    public static final class ServiceOrderRowEntity {
        private Long id;
        private Long orderNo;
        private String orderStage;
        private BigDecimal payAmount;
        private BigDecimal refundedAmount;
        private LocalDateTime createdAt;
        private LocalDateTime appointmentStartAt;
        private LocalDateTime verifiedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getOrderNo() { return orderNo; }
        public void setOrderNo(Long orderNo) { this.orderNo = orderNo; }
        public String getOrderStage() { return orderStage; }
        public void setOrderStage(String orderStage) { this.orderStage = orderStage; }
        public BigDecimal getPayAmount() { return payAmount; }
        public void setPayAmount(BigDecimal payAmount) { this.payAmount = payAmount; }
        public BigDecimal getRefundedAmount() { return refundedAmount; }
        public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getAppointmentStartAt() { return appointmentStartAt; }
        public void setAppointmentStartAt(LocalDateTime appointmentStartAt) { this.appointmentStartAt = appointmentStartAt; }
        public LocalDateTime getVerifiedAt() { return verifiedAt; }
        public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    }

    /** Per-customer counts for the repeat rate (window-scoped). */
    public static final class CustomerWindowCount {
        private Long customerCount;
        private Long repeatCustomerCount;

        public Long getCustomerCount() { return customerCount; }
        public void setCustomerCount(Long customerCount) { this.customerCount = customerCount; }
        public Long getRepeatCustomerCount() { return repeatCustomerCount; }
        public void setRepeatCustomerCount(Long repeatCustomerCount) { this.repeatCustomerCount = repeatCustomerCount; }
    }

    /** Top service row: order grouping plus the latest snapshot name. */
    public static final class ServiceTopRow {
        private Long serviceId;
        private String serviceName;
        private Long orderCount;
        private BigDecimal revenue;

        public Long getServiceId() { return serviceId; }
        public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public Long getOrderCount() { return orderCount; }
        public void setOrderCount(Long orderCount) { this.orderCount = orderCount; }
        public BigDecimal getRevenue() { return revenue; }
        public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
    }

    /** The store's single internal note for one derived customer (contract 69 expansion). */
    public static final class CustomerNoteRow {
        private Long storeId;
        private Long merchantId;
        private Long userId;
        private String note;
        private Long version;
        private LocalDateTime updatedAt;

        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    /** One UTC daily bucket of the revenue trend. */
    public static final class TrendRow {
        private String bucketDate;
        private BigDecimal revenue;
        private Long orderCount;

        public String getBucketDate() { return bucketDate; }
        public void setBucketDate(String bucketDate) { this.bucketDate = bucketDate; }
        public BigDecimal getRevenue() { return revenue; }
        public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
        public Long getOrderCount() { return orderCount; }
        public void setOrderCount(Long orderCount) { this.orderCount = orderCount; }
    }
}
