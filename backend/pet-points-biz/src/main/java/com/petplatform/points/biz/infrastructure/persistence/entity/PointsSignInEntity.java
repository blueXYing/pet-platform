package com.petplatform.points.biz.infrastructure.persistence.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** points_sign_in row (SQL58 §2). */
public final class PointsSignInEntity {
    private Long id;
    private Long userId;
    private LocalDate signDate;
    private Long points;
    private Long streakAfter;
    private Long ledgerId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDate getSignDate() { return signDate; }
    public void setSignDate(LocalDate signDate) { this.signDate = signDate; }
    public Long getPoints() { return points; }
    public void setPoints(Long points) { this.points = points; }
    public Long getStreakAfter() { return streakAfter; }
    public void setStreakAfter(Long streakAfter) { this.streakAfter = streakAfter; }
    public Long getLedgerId() { return ledgerId; }
    public void setLedgerId(Long ledgerId) { this.ledgerId = ledgerId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
