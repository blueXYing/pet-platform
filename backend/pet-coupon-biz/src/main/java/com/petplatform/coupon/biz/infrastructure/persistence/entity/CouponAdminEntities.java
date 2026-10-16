package com.petplatform.coupon.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * coupon_template / coupon_instance / coupon_ledger admin rows (SQL06, contract 65 §4).
 * Full-column projections for the operator face; rule_json stays String and is parsed only by
 * the coupon kernel (61 frozen vocabulary).
 */
public class CouponAdminEntities {

    /** coupon_template row (SQL06 §8). */
    public static class AdminTemplateEntity {
        private Long id;
        private String name;
        private String status;
        private Long totalStock;
        private Long issuedCount;
        private LocalDateTime validStartAt;
        private LocalDateTime validEndAt;
        private String ruleJson;
        private Long version;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getTotalStock() { return totalStock; }
        public void setTotalStock(Long totalStock) { this.totalStock = totalStock; }
        public Long getIssuedCount() { return issuedCount; }
        public void setIssuedCount(Long issuedCount) { this.issuedCount = issuedCount; }
        public LocalDateTime getValidStartAt() { return validStartAt; }
        public void setValidStartAt(LocalDateTime validStartAt) { this.validStartAt = validStartAt; }
        public LocalDateTime getValidEndAt() { return validEndAt; }
        public void setValidEndAt(LocalDateTime validEndAt) { this.validEndAt = validEndAt; }
        public String getRuleJson() { return ruleJson; }
        public void setRuleJson(String ruleJson) { this.ruleJson = ruleJson; }
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    /** coupon_instance row (SQL06 §8) joined with the template name for the ledger card. */
    public static class AdminInstanceEntity {
        private Long id;
        private Long templateId;
        private String templateName;
        private Long userId;
        private String status;
        private Long orderId;
        private LocalDateTime frozenAt;
        private LocalDateTime freezeExpireAt;
        private LocalDateTime usedAt;
        private LocalDateTime originalExpireAt;
        private LocalDateTime expireAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private BigDecimal amountOff;
        private BigDecimal thresholdAmount;
        private String ruleJson;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
        public String getTemplateName() { return templateName; }
        public void setTemplateName(String templateName) { this.templateName = templateName; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public LocalDateTime getFrozenAt() { return frozenAt; }
        public void setFrozenAt(LocalDateTime frozenAt) { this.frozenAt = frozenAt; }
        public LocalDateTime getFreezeExpireAt() { return freezeExpireAt; }
        public void setFreezeExpireAt(LocalDateTime freezeExpireAt) { this.freezeExpireAt = freezeExpireAt; }
        public LocalDateTime getUsedAt() { return usedAt; }
        public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
        public LocalDateTime getOriginalExpireAt() { return originalExpireAt; }
        public void setOriginalExpireAt(LocalDateTime originalExpireAt) { this.originalExpireAt = originalExpireAt; }
        public LocalDateTime getExpireAt() { return expireAt; }
        public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        public BigDecimal getAmountOff() { return amountOff; }
        public void setAmountOff(BigDecimal amountOff) { this.amountOff = amountOff; }
        public BigDecimal getThresholdAmount() { return thresholdAmount; }
        public void setThresholdAmount(BigDecimal thresholdAmount) { this.thresholdAmount = thresholdAmount; }
        public String getRuleJson() { return ruleJson; }
        public void setRuleJson(String ruleJson) { this.ruleJson = ruleJson; }
    }

    /** coupon_ledger row (SQL06 §8, append-only timeline). */
    public static class AdminLedgerEntity {
        private Long id;
        private Long couponInstanceId;
        private Long userId;
        private Long orderId;
        private String action;
        private String fromStatus;
        private String toStatus;
        private String requestId;
        private String remark;
        private LocalDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getCouponInstanceId() { return couponInstanceId; }
        public void setCouponInstanceId(Long couponInstanceId) { this.couponInstanceId = couponInstanceId; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getFromStatus() { return fromStatus; }
        public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
        public String getToStatus() { return toStatus; }
        public void setToStatus(String toStatus) { this.toStatus = toStatus; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    private CouponAdminEntities() {}
}
