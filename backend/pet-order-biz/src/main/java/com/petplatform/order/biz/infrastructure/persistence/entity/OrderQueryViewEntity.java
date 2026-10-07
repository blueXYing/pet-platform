package com.petplatform.order.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only {@code pet_order} projection row for the C-004 query slice. DATETIME(3) columns
 * read as UTC wall time; the refund/application/aftersale columns are the order-domain's own
 * transactionally maintained projections (schemas 48/49).
 */
public final class OrderQueryViewEntity {
    private Long id;
    private Long orderNo;
    private Long userId;
    private Long merchantId;
    private Long storeId;
    private Long serviceId;
    private Long reservationId;
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
    private LocalDateTime paymentExpireAt;
    private String cancelReason;
    private Integer rescheduleCount;
    private Long version;

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
    public Long getReservationId() { return reservationId; }
    public void setReservationId(Long reservationId) { this.reservationId = reservationId; }
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
    public LocalDateTime getPaymentExpireAt() { return paymentExpireAt; }
    public void setPaymentExpireAt(LocalDateTime paymentExpireAt) { this.paymentExpireAt = paymentExpireAt; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Integer getRescheduleCount() { return rescheduleCount; }
    public void setRescheduleCount(Integer rescheduleCount) { this.rescheduleCount = rescheduleCount; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
