package com.petplatform.points.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** Read-only points_ledger row (schema 06 section 9, CCR-C006 P1). */
public final class PointsLedgerRowEntity {
    private Long id;
    private String bizType;
    private Long delta;
    private Long balanceAfter;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getDelta() { return delta; }
    public void setDelta(Long delta) { this.delta = delta; }
    public Long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Long balanceAfter) { this.balanceAfter = balanceAfter; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
