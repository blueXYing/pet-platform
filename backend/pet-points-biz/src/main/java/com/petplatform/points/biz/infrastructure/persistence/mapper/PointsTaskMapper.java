package com.petplatform.points.biz.infrastructure.persistence.mapper;

import com.petplatform.points.biz.infrastructure.persistence.entity.PointsReferralBindingEntity;
import com.petplatform.points.biz.infrastructure.persistence.entity.PointsSignInEntity;
import com.petplatform.points.biz.infrastructure.persistence.entity.PointsTaskAwardEntity;
import com.petplatform.points.biz.infrastructure.persistence.entity.PointsTaskConfigEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** MyBatis surface for the points task store; all production SQL lives in the module XML. */
public interface PointsTaskMapper {

    void setTimeZoneUtc();

    void setLockWaitTimeout2Seconds();

    // ---- command_idempotency (14号 shared physical table) ----

    com.petplatform.points.biz.infrastructure.persistence.entity.PointsTaskBindingEntity
    selectBindingForUpdate(@Param("requestKey") String requestKey);    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion,
            @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    int markBindingSucceeded(@Param("requestKey") String requestKey,
            @Param("receiptJson") String receiptJson);

    // ---- points_task_config (SQL58 §1) ----

    PointsTaskConfigEntity selectConfig(@Param("scope") String scope);

    int upsertConfig(@Param("id") long id, @Param("scope") String scope,
            @Param("signInDailyPoints") long signInDailyPoints,
            @Param("signInStreakBonusPoints") long signInStreakBonusPoints,
            @Param("invitePerInviteePoints") long invitePerInviteePoints,
            @Param("inviteMonthlyRewardCap") long inviteMonthlyRewardCap,
            @Param("inviteMilestoneBonusPoints") long inviteMilestoneBonusPoints,
            @Param("firstOrderPoints") long firstOrderPoints,
            @Param("profileCompletePoints") long profileCompletePoints,
            @Param("now") LocalDateTime now);

    // ---- points_sign_in (SQL58 §2) ----

    PointsSignInEntity selectSignInByDate(@Param("userId") long userId,
            @Param("signDate") LocalDate signDate);

    int insertSignIn(@Param("id") long id, @Param("userId") long userId,
            @Param("signDate") LocalDate signDate, @Param("points") long points,
            @Param("streakAfter") long streakAfter, @Param("ledgerId") long ledgerId,
            @Param("createdAt") LocalDateTime createdAt);

    List<LocalDate> selectRecentSignInDates(@Param("userId") long userId,
            @Param("limitRows") int limitRows);

    // ---- points_referral_binding (SQL58 §3) ----

    PointsReferralBindingEntity selectBindingByInvitee(@Param("inviteeId") long inviteeId);

    int insertReferralBinding(@Param("id") long id, @Param("inviterId") long inviterId,
            @Param("inviteeId") long inviteeId, @Param("inviteCode") String inviteCode,
            @Param("createdAt") LocalDateTime createdAt);

    List<PointsReferralBindingEntity> selectBoundBindings(@Param("inviterId") long inviterId,
            @Param("limitRows") int limitRows);

    List<PointsReferralBindingEntity> selectRewardedBindings(@Param("inviterId") long inviterId);

    int markBindingRewarded(@Param("id") long id, @Param("rewardPeriod") String rewardPeriod,
            @Param("rewardLedgerId") long rewardLedgerId,
            @Param("rewardedAt") LocalDateTime rewardedAt);

    // ---- points_task_award (SQL58 §4) ----

    PointsTaskAwardEntity selectAward(@Param("userId") long userId,
            @Param("taskCode") String taskCode);

    int insertAward(@Param("id") long id, @Param("userId") long userId,
            @Param("taskCode") String taskCode, @Param("points") long points,
            @Param("ledgerId") long ledgerId, @Param("createdAt") LocalDateTime createdAt);

    // ---- points_ledger / points_account (SQL06 §9, shared tables) ----

    int insertLedger(@Param("id") long id, @Param("userId") long userId,
            @Param("bizType") String bizType, @Param("bizId") Long bizId,
            @Param("delta") long delta, @Param("balanceAfter") long balanceAfter,
            @Param("requestId") String requestId, @Param("createdAt") LocalDateTime createdAt);

    int upsertAccountBalance(@Param("id") long id, @Param("userId") long userId,
            @Param("delta") long delta, @Param("now") LocalDateTime now);

    Long selectBalance(@Param("userId") long userId);
}
