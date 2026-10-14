package com.petplatform.points.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** points_referral_binding row (SQL58 §3). */
public final class PointsReferralBindingEntity {
    private Long id;
    private Long inviterId;
    private Long inviteeId;
    private String inviteCode;
    private String state;
    private String rewardPeriod;
    private LocalDateTime rewardedAt;
    private Long rewardLedgerId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getInviterId() { return inviterId; }
    public void setInviterId(Long inviterId) { this.inviterId = inviterId; }
    public Long getInviteeId() { return inviteeId; }
    public void setInviteeId(Long inviteeId) { this.inviteeId = inviteeId; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getRewardPeriod() { return rewardPeriod; }
    public void setRewardPeriod(String rewardPeriod) { this.rewardPeriod = rewardPeriod; }
    public LocalDateTime getRewardedAt() { return rewardedAt; }
    public void setRewardedAt(LocalDateTime rewardedAt) { this.rewardedAt = rewardedAt; }
    public Long getRewardLedgerId() { return rewardLedgerId; }
    public void setRewardLedgerId(Long rewardLedgerId) { this.rewardLedgerId = rewardLedgerId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
