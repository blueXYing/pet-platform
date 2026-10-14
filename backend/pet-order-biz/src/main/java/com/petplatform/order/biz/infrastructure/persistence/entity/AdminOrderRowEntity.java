package com.petplatform.order.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only platform row for the contract-63 admin face: the pet_order projection joined with
 * its one service snapshot row (order_service_snapshot, schema 06). Snapshot columns are
 * nullable when the snapshot row is absent; DATETIME(3) columns read as UTC wall time.
 */
public final class AdminOrderRowEntity {
    private Long id;
    private Long orderNo;
    private Long userId;
    private Long merchantId;
    private Long storeId;
    private Long serviceId;
    private String orderStage;
    private String paymentStatus;
    private String verificationStatus;
    private String refundApplicationStatus;
    private String aftersaleStatus;
    private Long refundOrderId;
    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    private BigDecimal refundedAmount;
    private LocalDateTime appointmentStartAt;
    private LocalDateTime appointmentEndAt;
    private LocalDateTime paidAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime canceledAt;
    private LocalDateTime createdAt;
    private Integer rescheduleCount;
    private Long version;
    private String serviceName;
    private String categoryName;
    private String merchantName;
    private String storeName;
    private String storeAddress;
    private BigDecimal servicePrice;
    private Integer serviceDurationMinutes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderNo() { return orderNo; }
    public void setOrderNo(Long orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public String getOrderStage() { return orderStage; }
    public void setOrderStage(String orderStage) { this.orderStage = orderStage; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
    public String getRefundApplicationStatus() { return refundApplicationStatus; }
    public void setRefundApplicationStatus(String refundApplicationStatus) { this.refundApplicationStatus = refundApplicationStatus; }
    public String getAftersaleStatus() { return aftersaleStatus; }
    public void setAftersaleStatus(String aftersaleStatus) { this.aftersaleStatus = aftersaleStatus; }
    public Long getRefundOrderId() { return refundOrderId; }
    public void setRefundOrderId(Long refundOrderId) { this.refundOrderId = refundOrderId; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(BigDecimal payAmount) { this.payAmount = payAmount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public LocalDateTime getAppointmentStartAt() { return appointmentStartAt; }
    public void setAppointmentStartAt(LocalDateTime appointmentStartAt) { this.appointmentStartAt = appointmentStartAt; }
    public LocalDateTime getAppointmentEndAt() { return appointmentEndAt; }
    public void setAppointmentEndAt(LocalDateTime appointmentEndAt) { this.appointmentEndAt = appointmentEndAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    public LocalDateTime getCanceledAt() { return canceledAt; }
    public void setCanceledAt(LocalDateTime canceledAt) { this.canceledAt = canceledAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getRescheduleCount() { return rescheduleCount; }
    public void setRescheduleCount(Integer rescheduleCount) { this.rescheduleCount = rescheduleCount; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getStoreAddress() { return storeAddress; }
    public void setStoreAddress(String storeAddress) { this.storeAddress = storeAddress; }
    public BigDecimal getServicePrice() { return servicePrice; }
    public void setServicePrice(BigDecimal servicePrice) { this.servicePrice = servicePrice; }
    public Integer getServiceDurationMinutes() { return serviceDurationMinutes; }
    public void setServiceDurationMinutes(Integer serviceDurationMinutes) { this.serviceDurationMinutes = serviceDurationMinutes; }
}
