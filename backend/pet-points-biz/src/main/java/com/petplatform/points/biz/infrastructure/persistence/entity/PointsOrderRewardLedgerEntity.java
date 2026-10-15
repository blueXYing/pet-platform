package com.petplatform.points.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/**
 * Order-bound reward ledger projection for the contract 60 §7 refund clawback (PTS-002):
 * the positive ORDER_REWARD lines of one order, read FOR UPDATE by the clawback consumer.
 * userId comes from the ledger row itself — the refund event payload carries no owner.
 */
public final class PointsOrderRewardLedgerEntity {
    private Long id;
    private Long userId;
    private Long bizId;
    private Long delta;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public Long getDelta() { return delta; }
    public void setDelta(Long delta) { this.delta = delta; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
