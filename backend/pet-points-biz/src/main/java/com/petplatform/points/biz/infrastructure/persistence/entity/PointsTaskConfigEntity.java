package com.petplatform.points.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** points_task_config singleton row (SQL58 §1). */
public final class PointsTaskConfigEntity {
    private Long id;
    private String scope;
    private Long signInDailyPoints;
    private Long signInStreakBonusPoints;
    private Long invitePerInviteePoints;
    private Long inviteMonthlyRewardCap;
    private Long inviteMilestoneBonusPoints;
    private Long firstOrderPoints;
    private Long profileCompletePoints;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Long getSignInDailyPoints() { return signInDailyPoints; }
    public void setSignInDailyPoints(Long v) { this.signInDailyPoints = v; }
    public Long getSignInStreakBonusPoints() { return signInStreakBonusPoints; }
    public void setSignInStreakBonusPoints(Long v) { this.signInStreakBonusPoints = v; }
    public Long getInvitePerInviteePoints() { return invitePerInviteePoints; }
    public void setInvitePerInviteePoints(Long v) { this.invitePerInviteePoints = v; }
    public Long getInviteMonthlyRewardCap() { return inviteMonthlyRewardCap; }
    public void setInviteMonthlyRewardCap(Long v) { this.inviteMonthlyRewardCap = v; }
    public Long getInviteMilestoneBonusPoints() { return inviteMilestoneBonusPoints; }
    public void setInviteMilestoneBonusPoints(Long v) { this.inviteMilestoneBonusPoints = v; }
    public Long getFirstOrderPoints() { return firstOrderPoints; }
    public void setFirstOrderPoints(Long v) { this.firstOrderPoints = v; }
    public Long getProfileCompletePoints() { return profileCompletePoints; }
    public void setProfileCompletePoints(Long v) { this.profileCompletePoints = v; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
