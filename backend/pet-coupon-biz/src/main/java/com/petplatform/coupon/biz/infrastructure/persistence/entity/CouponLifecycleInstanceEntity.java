package com.petplatform.coupon.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lifecycle row of coupon_instance joined with its template (schema 06 section 8). DATETIME(3)
 * reads/writes as UTC wall time (session default '+00:00'). ruleJson feeds the frozen CouponRule
 * parse at freeze time; later actions never re-read it.
 */
public final class CouponLifecycleInstanceEntity {
    private Long id;
    private Long userId;
    private String status;
    private Long orderId;
    private LocalDateTime expireAt;
    private LocalDateTime freezeExpireAt;
    private BigDecimal amountOff;
    private BigDecimal thresholdAmount;
    private String templateStatus;
    private LocalDateTime templateValidStartAt;
    private LocalDateTime templateValidEndAt;
    private String ruleJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getFreezeExpireAt() { return freezeExpireAt; }
    public void setFreezeExpireAt(LocalDateTime freezeExpireAt) { this.freezeExpireAt = freezeExpireAt; }
    public BigDecimal getAmountOff() { return amountOff; }
    public void setAmountOff(BigDecimal amountOff) { this.amountOff = amountOff; }
    public BigDecimal getThresholdAmount() { return thresholdAmount; }
    public void setThresholdAmount(BigDecimal thresholdAmount) { this.thresholdAmount = thresholdAmount; }
    public String getTemplateStatus() { return templateStatus; }
    public void setTemplateStatus(String templateStatus) { this.templateStatus = templateStatus; }
    public LocalDateTime getTemplateValidStartAt() { return templateValidStartAt; }
    public void setTemplateValidStartAt(LocalDateTime templateValidStartAt) { this.templateValidStartAt = templateValidStartAt; }
    public LocalDateTime getTemplateValidEndAt() { return templateValidEndAt; }
    public void setTemplateValidEndAt(LocalDateTime templateValidEndAt) { this.templateValidEndAt = templateValidEndAt; }
    public String getRuleJson() { return ruleJson; }
    public void setRuleJson(String ruleJson) { this.ruleJson = ruleJson; }
}
