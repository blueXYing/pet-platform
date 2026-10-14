package com.petplatform.points.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.points.api.dto.PointsTaskTypes.AwardClaimReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.ConfigView;
import com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.UpdateConfigCommand;

/**
 * Sign-in + referral earn surface (contract 58, CCR-POINTS-TASK-001). V1 points only accrue and
 * get clawed back, never spent: every command here writes {@code points_ledger} plus the
 * {@code points_account} balance (biz types SIGN_IN/INVITE/TASK). All writes are supplement-23
 * bound commands (same requestId replays the first success receipt; different parameters are a
 * 409-class conflict). No order/community facts are mutated by this API.
 */
public interface PointsTaskCommandApi {

    /** Signs the session user in for the current Asia/Shanghai calendar day (S1~S3). */
    SignInReceipt signIn(SignInCommand command);

    /** Binds the session user (invitee) to an inviter's invite code (R1~R4). */
    ReferralBindingReceipt bindReferral(BindReferralCommand command);

    /** Claims a task/milestone reward for the session user (M1, A2 claim-driven). */
    AwardClaimReceipt claimReward(ClaimRewardCommand command);

    /** Full-put of the seven ops-configured grant amounts (32号 ruling: 后台可配置). */
    ConfigView updateConfig(UpdateConfigCommand command);

    record SignInCommand(CommandContext context) {}

    record BindReferralCommand(String inviteCode, CommandContext context) {}

    record ClaimRewardCommand(String claimType, CommandContext context) {}
}
