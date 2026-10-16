package com.petplatform.points.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/**
 * points_ledger admin read row (SQL06 §9, contract 65 §5). delta is signed (positive accrual,
 * negative clawback); balance_after is the balance right after the entry was written.
 */
public class AdminPointsLedgerEntity {
    private Long id;
    private Long userId;
    private String bizType;
    private Long bizId;
    private Long orderId;
    private Long delta;
    private Long balanceAfter;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getDelta() { return delta; }
    public void setDelta(Long delta) { this.delta = delta; }
    public Long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Long balanceAfter) { this.balanceAfter = balanceAfter; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
