package com.petplatform.review.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** Admin appeal list join row: the 06号 review_appeal columns plus the appealed order id. */
public final class AppealListRowEntity {
    private Long id;
    private Long reviewId;
    private Long merchantId;
    private Long storeId;
    private Long orderId;
    private String status;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long value) { reviewId = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { orderId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime value) { decidedAt = value; }
}
