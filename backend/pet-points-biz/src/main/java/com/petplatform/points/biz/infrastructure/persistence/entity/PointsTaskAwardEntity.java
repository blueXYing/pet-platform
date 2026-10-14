package com.petplatform.points.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** points_task_award row (SQL58 §4). */
public final class PointsTaskAwardEntity {
    private Long id;
    private Long userId;
    private String taskCode;
    private Long points;
    private Long ledgerId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public Long getPoints() { return points; }
    public void setPoints(Long points) { this.points = points; }
    public Long getLedgerId() { return ledgerId; }
    public void setLedgerId(Long ledgerId) { this.ledgerId = ledgerId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
