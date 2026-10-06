package com.petplatform.coupon.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/**
 * Read-only join row of coupon_instance x coupon_template (schema 06 sections 8). ruleJson is the
 * raw template rule document; display projection happens in the application service (D1).
 */
public final class CouponInstanceViewEntity {
    private Long id;
    private Long userId;
    private String status;
    private LocalDateTime usedAt;
    private LocalDateTime expireAt;
    private String templateName;
    private LocalDateTime templateValidEndAt;
    private String ruleJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public LocalDateTime getTemplateValidEndAt() { return templateValidEndAt; }
    public void setTemplateValidEndAt(LocalDateTime templateValidEndAt) { this.templateValidEndAt = templateValidEndAt; }
    public String getRuleJson() { return ruleJson; }
    public void setRuleJson(String ruleJson) { this.ruleJson = ruleJson; }
}
