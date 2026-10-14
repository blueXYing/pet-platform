package com.petplatform.points.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contract 58 view/receipt types for the sign-in + referral earn slice. Every points amount
 * crosses the wire as a non-negative integer string (BIGINT transport safety, 10号 §2.5);
 * every timestamp is ISO-8601 with exact millisecond precision (§2.6).
 */
public final class PointsTaskTypes {
    private PointsTaskTypes() {}

    public record SignInReceipt(
            String signedDate,
            String points,
            long streakAfter,
            String ledgerId,
            String balanceAfter) {}

    public record ReferralBindingReceipt(
            String bindingId,
            OffsetDateTime createdAt) {}

    public record AwardClaimReceipt(
            String claimType,
            String pointsGranted,
            List<String> ledgerIds,
            String balanceAfter) {}

    /** Ops-configured grant amounts; defaults when no row exists are 5/30/50/3/100/100/20. */
    public record ConfigView(
            long signInDailyPoints,
            long signInStreakBonusPoints,
            long invitePerInviteePoints,
            long inviteMonthlyRewardCap,
            long inviteMilestoneBonusPoints,
            long firstOrderPoints,
            long profileCompletePoints,
            long version,
            OffsetDateTime updatedAt) {}

    public record UpdateConfigCommand(
            long signInDailyPoints,
            long signInStreakBonusPoints,
            long invitePerInviteePoints,
            long inviteMonthlyRewardCap,
            long inviteMilestoneBonusPoints,
            long firstOrderPoints,
            long profileCompletePoints,
            com.petplatform.common.CommandContext context) {}

    public record SignInStatusView(
            boolean signedToday,
            long streak,
            String dailyPoints,
            List<WeekDay> week) {}

    public record WeekDay(String date, boolean signed) {}

    public record InviteOverview(
            String perInviteePoints,
            long monthlyRewardCap,
            long rewardedThisMonth,
            long claimableCount,
            long milestoneRewarded,
            String milestoneStatus,
            String milestoneProgress) {}

    public record TaskRow(
            String taskCode,
            String title,
            String description,
            String points,
            String status,
            String progress) {}

    public record RewardRow(
            String rewardCode,
            String title,
            String description,
            String points,
            String status,
            String progress) {}

    public record TasksView(
            String inviteCode,
            SignInStatusView signIn,
            InviteOverview invite,
            List<TaskRow> tasks,
            List<RewardRow> rewards) {}
}
