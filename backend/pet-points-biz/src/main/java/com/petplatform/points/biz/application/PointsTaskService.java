package com.petplatform.points.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.points.api.command.PointsTaskCommandApi.BindReferralCommand;
import com.petplatform.points.api.command.PointsTaskCommandApi.ClaimRewardCommand;
import com.petplatform.points.api.command.PointsTaskCommandApi.SignInCommand;
import com.petplatform.points.api.dto.PointsTaskTypes.AwardClaimReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.ConfigView;
import com.petplatform.points.api.dto.PointsTaskTypes.InviteOverview;
import com.petplatform.points.api.dto.PointsTaskTypes.RewardRow;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInStatusView;
import com.petplatform.points.api.dto.PointsTaskTypes.TaskRow;
import com.petplatform.points.api.dto.PointsTaskTypes.TasksView;
import com.petplatform.points.api.dto.PointsTaskTypes.UpdateConfigCommand;
import com.petplatform.points.api.dto.PointsTaskTypes.WeekDay;
import com.petplatform.points.biz.domain.service.InviteCodeCodec;
import com.petplatform.points.biz.domain.service.PointsTaskFacts;
import com.petplatform.points.biz.infrastructure.persistence.PointsTaskStore;
import com.petplatform.points.biz.infrastructure.persistence.PointsTaskStore.Binding;
import com.petplatform.points.biz.infrastructure.persistence.entity.PointsReferralBindingEntity;
import com.petplatform.points.biz.infrastructure.persistence.entity.PointsTaskAwardEntity;
import com.petplatform.points.biz.infrastructure.persistence.entity.PointsTaskConfigEntity;
import com.petplatform.points.biz.infrastructure.persistence.mapper.PointsTaskMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sign-in + referral earn kernel (contract 58, CCR-POINTS-TASK-001). Conservative rule set:
 * one sign-in per Asia/Shanghai calendar day at a fixed ops-configured amount with no escalating
 * multipliers; referral rewards only for an invitee who had zero order history at binding time
 * and completed a payment strictly after the binding, capped per month; every reward except the
 * daily sign-in is claim-driven (A2, CCR-POINTS-TASK-001), and the first-order reward is bound
 * to the user's earliest paid order at claim time so contract 60 §7 refund clawback can find it
 * by order (CCR-POINTS-ORDER-001). All ledger writes are positive accruals here — V1 points are
 * earned and clawed back, never spent; the clawback side lives in the refund consumer.
 */
public final class PointsTaskService {
  private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
  private static final int STREAK_SCAN_DAYS = 400;
  private static final int BINDING_SCAN_LIMIT = 1_000;
  private static final String SCOPE = "GLOBAL";

  private static final ConfigView CONFIG_DEFAULTS = new ConfigView(
      5, 30, 50, 3, 100, 100, 20, 0, null);

  /** Task/milestone codes (contract 58 §3.3/§3.4). */
  public static final String CLAIM_INVITE_PER_HEAD = "INVITE_PER_HEAD";
  public static final String CLAIM_FIRST_ORDER = "FIRST_ORDER";
  public static final String CLAIM_SIGN_IN_STREAK_7 = "SIGN_IN_STREAK_7";
  public static final String CLAIM_INVITE_MILESTONE_3 = "INVITE_MILESTONE_3";
  public static final String CLAIM_PROFILE_COMPLETE = "PROFILE_COMPLETE";
  private static final List<String> CLAIM_TYPES = List.of(
      CLAIM_INVITE_PER_HEAD, CLAIM_FIRST_ORDER, CLAIM_SIGN_IN_STREAK_7,
      CLAIM_INVITE_MILESTONE_3, CLAIM_PROFILE_COMPLETE);

  private final PointsTaskStore store;
  private final PointsTaskFacts facts;
  private final Clock clock;

  public PointsTaskService(PointsTaskStore store, PointsTaskFacts facts, Clock clock) {
    this.store = Objects.requireNonNull(store, "store is required");
    this.facts = Objects.requireNonNull(facts, "facts are required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  // ------------------------------------------------------------------ reads

  /** Ops configuration view; the built-in defaults apply while no row exists. */
  public ConfigView config() {
    PointsTaskConfigEntity row = store.read(m -> m.selectConfig(SCOPE));
    return row == null ? CONFIG_DEFAULTS : view(row);
  }

  /** Sign-in state for the session user (S1/S2): signedToday, streak, amount, last-7-days. */
  public SignInStatusView signInStatus(QueryContext context) {
    long user = userSubject(context);
    ConfigView config = config();
    LocalDate today = today();
    List<LocalDate> dates = store.read(m -> m.selectRecentSignInDates(user, STREAK_SCAN_DAYS));
    boolean signedToday = !dates.isEmpty() && today.equals(dates.get(0));
    long streak = streak(dates, signedToday ? today : today.minusDays(1));
    List<WeekDay> week = new ArrayList<>(7);
    for (int back = 6; back >= 0; back--) {
      LocalDate date = today.minusDays(back);
      week.add(new WeekDay(date.toString(), dates.contains(date)));
    }
    return new SignInStatusView(signedToday, streak, Long.toString(config.signInDailyPoints()), week);
  }

  /** Task-center + milestone projection shared by the tasks (129:5854) and rewards (129:7677) pages. */
  public TasksView tasks(QueryContext context) {
    long user = userSubject(context);
    ConfigView config = config();
    SignInStatusView signIn = signInStatus(context);
    List<PointsReferralBindingEntity> bound =
        store.read(m -> m.selectBoundBindings(user, BINDING_SCAN_LIMIT));
    List<PointsReferralBindingEntity> rewarded =
        store.read(m -> m.selectRewardedBindings(user));
    String period = period(today());
    long rewardedThisMonth =
        rewarded.stream().filter(row -> period.equals(row.getRewardPeriod())).count();

    long claimable = 0;
    long remaining = Math.max(0, config.inviteMonthlyRewardCap() - rewardedThisMonth);
    for (PointsReferralBindingEntity binding : bound) {
      if (claimable >= remaining) break;
      if (paidAfterBinding(binding)) claimable++;
    }
    long milestoneRewarded = rewarded.size();
    boolean milestoneClaimed =
        store.read(m -> m.selectAward(user, CLAIM_INVITE_MILESTONE_3)) != null;

    InviteOverview invite = new InviteOverview(
        Long.toString(config.invitePerInviteePoints()),
        config.inviteMonthlyRewardCap(),
        rewardedThisMonth,
        claimable,
        milestoneRewarded,
        milestoneClaimed ? "CLAIMED" : milestoneRewarded >= 3 ? "CLAIMABLE" : "NOT_ACHIEVED",
        Math.min(milestoneRewarded, 3) + "/3");

    boolean firstOrderClaimed = store.read(m -> m.selectAward(user, CLAIM_FIRST_ORDER)) != null;
    boolean firstOrderPaid = facts.firstPaidAt(IDS.toApi(user)) != null;
    boolean profileClaimed = store.read(m -> m.selectAward(user, CLAIM_PROFILE_COMPLETE)) != null;
    boolean profileComplete = !facts.userNickname(IDS.toApi(user)).isBlank();

    List<TaskRow> tasks = List.of(
        new TaskRow(CLAIM_INVITE_PER_HEAD, "邀请好友注册",
            "每邀请 1 位好友注册并下单，立得 " + config.invitePerInviteePoints() + " 积分",
            Long.toString(config.invitePerInviteePoints()),
            claimable > 0 ? "CLAIMABLE" : "NOT_ACHIEVED",
            rewardedThisMonth + "/" + config.inviteMonthlyRewardCap()),
        new TaskRow(CLAIM_FIRST_ORDER, "完成首次下单",
            "首次在平台完成服务支付，即可领取",
            Long.toString(config.firstOrderPoints()),
            firstOrderClaimed ? "CLAIMED" : firstOrderPaid ? "CLAIMABLE" : "NOT_ACHIEVED",
            firstOrderPaid ? "1/1" : "0/1"),
        new TaskRow("SIGN_IN", "每日签到",
            "每天来签到，固定 " + config.signInDailyPoints() + " 积分/天",
            Long.toString(config.signInDailyPoints()),
            signIn.signedToday() ? "DONE_TODAY" : "NOT_ACHIEVED",
            signIn.signedToday() ? "1/1" : "0/1"),
        new TaskRow("COMMUNITY_POST", "发表社区动态",
            "在宠友圈分享养宠日常（暂未开放）", "0", "NOT_OPEN", "-"));

    List<RewardRow> rewards = List.of(
        new RewardRow(CLAIM_SIGN_IN_STREAK_7, "连续签到 7 天",
            "坚持每天签到，养成好习惯",
            Long.toString(config.signInStreakBonusPoints()),
            signIn.streak() >= 7 ? "CLAIMABLE" : "NOT_ACHIEVED",
            Math.min(signIn.streak(), 7) + "/7"),
        new RewardRow(CLAIM_INVITE_MILESTONE_3, "邀请 3 位好友",
            "把好平台分享给身边养宠的朋友",
            Long.toString(config.inviteMilestoneBonusPoints()),
            invite.milestoneStatus(), invite.milestoneProgress()),
        new RewardRow(CLAIM_PROFILE_COMPLETE, "完善个人资料",
            "填写昵称，让宠友认识你",
            Long.toString(config.profileCompletePoints()),
            profileComplete ? "CLAIMABLE" : "NOT_ACHIEVED", "-"));
    // 已领取的里程碑显示终局状态（领取后即 CLAIMED，进度保留）。
    if (store.read(m -> m.selectAward(user, CLAIM_SIGN_IN_STREAK_7)) != null) {
      rewards = withClaimed(rewards, CLAIM_SIGN_IN_STREAK_7);
    }
    if (profileClaimed) {
      rewards = withClaimed(rewards, CLAIM_PROFILE_COMPLETE);
    }

    return new TasksView(InviteCodeCodec.encode(user), signIn, invite, tasks, rewards);
  }

  // ------------------------------------------------------------------ commands

  /** POST /c/points/sign-in (S1~S3): once per Asia/Shanghai day, fixed amount, immediate grant. */
  public SignInReceipt signIn(SignInCommand command) {
    CommandContext context = userCommand(command == null ? null : command.context());
    Map<String, Object> params = new LinkedHashMap<>();
    PointsTaskCanonicalParams.Canonical canonical = PointsTaskCanonicalParams.of(params);
    String key = storeKey("points.task.sign-in", context, "USER_SELF");
    admitWithRecovery(key, canonical);
    SignInReceipt[] out = new SignInReceipt[1];
    runBound(key, canonical, () -> out[0] = applySignIn(context, key, canonical));
    return out[0];
  }

  private SignInReceipt applySignIn(
      CommandContext context, String key, PointsTaskCanonicalParams.Canonical canonical) {
    return store.execute(m -> {
      Binding binding = PointsTaskStore.require(m, key);
      PointsTaskStore.same(binding, canonical);
      if ("SUCCEEDED".equals(binding.status())) {
        return receipt(binding.receiptJson(), PointsTaskService::signInReceipt);
      }
      long user = userId(context);
      LocalDate today = today();
      if (m.selectSignInByDate(user, today) != null) {
        throw new ApiException(CommonApiCodes.CONFLICT, "每日仅可签到一次");
      }
      ConfigView config = configInView(m);
      List<LocalDate> dates = m.selectRecentSignInDates(user, STREAK_SCAN_DAYS);
      long streak = streak(dates, today.minusDays(1)) + 1;
      if (streak < 1 || streak > STREAK_SCAN_DAYS + 1) {
        throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "签到连签计算非法");
      }
      long points = config.signInDailyPoints();
      long signInId = store.nextId();
      long ledgerId = store.nextId();
      LocalDateTime now = nowUtc();
      long balanceAfter = accrue(m, user, "SIGN_IN", signInId, points,
          context.requestId(), ledgerId, now);
      if (m.insertSignIn(signInId, user, today, points, streak, ledgerId, now) != 1) {
        throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "签到写入失败");
      }
      SignInReceipt receipt =
          new SignInReceipt(today.toString(), Long.toString(points), streak,
              IDS.toApi(ledgerId), Long.toString(balanceAfter));
      if (m.markBindingSucceeded(key, json(receipt)) != 1) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "签到幂等标记失败");
      }
      return receipt;
    });
  }

  /** POST /c/points/referral-bindings (R1~R4): invitee binds an inviter's code, once ever. */
  public com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt bindReferral(
      BindReferralCommand command) {
    if (command == null) invalid("command is required");
    String code = command.inviteCode();
    if (code == null || !code.matches("[A-Za-z0-9]{1,32}")) invalid("邀请码不合法");
    Long inviter = InviteCodeCodec.decode(code);
    if (inviter == null) invalid("邀请码不合法");
    CommandContext context = userCommand(command.context());
    long user = userId(context);
    if (inviter == user) invalid("不能使用自己的邀请码");
    if (!facts.userEnabled(IDS.toApi(inviter))) invalid("邀请码不合法");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("inviteCode", code);
    PointsTaskCanonicalParams.Canonical canonical = PointsTaskCanonicalParams.of(params);
    String key = storeKey("points.task.referral-bind", context, "USER_SELF");
    admitWithRecovery(key, canonical);
    var out = new com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt[1];
    runBound(key, canonical,
        () -> out[0] = applyBind(context, user, inviter, code, key, canonical));
    return out[0];
  }

  private com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt applyBind(
      CommandContext context, long user, long inviter, String code, String key,
      PointsTaskCanonicalParams.Canonical canonical) {
    return store.execute(m -> {
      Binding binding = PointsTaskStore.require(m, key);
      PointsTaskStore.same(binding, canonical);
      if ("SUCCEEDED".equals(binding.status())) {
        return receipt(binding.receiptJson(), PointsTaskService::bindingReceipt);
      }
      if (m.selectBindingByInvitee(user) != null) {
        throw new ApiException(CommonApiCodes.CONFLICT, "已绑定邀请人，终身仅可绑定一次");
      }
      if (facts.firstPaidAt(IDS.toApi(user)) != null) {
        throw new ApiException(CommonApiCodes.CONFLICT, "已有订单记录，不符合新用户条件");
      }
      long id = store.nextId();
      LocalDateTime now = nowUtc();
      if (m.insertReferralBinding(id, inviter, user, code, now) != 1) {
        throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "邀请绑定写入失败");
      }
      var value = new com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt(
          IDS.toApi(id), now.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)
              .atOffset(ZoneOffset.UTC));
      if (m.markBindingSucceeded(key, json(value)) != 1) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "邀请绑定幂等标记失败");
      }
      return value;
    });
  }

  /** POST /c/points/claims (M1, A2): claim-driven grant of every reward except daily sign-in. */
  public AwardClaimReceipt claimReward(ClaimRewardCommand command) {
    if (command == null) invalid("command is required");
    String claimType = command.claimType();
    if (claimType == null || !CLAIM_TYPES.contains(claimType)) invalid("claimType is invalid");
    CommandContext context = userCommand(command.context());
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("claimType", claimType);
    PointsTaskCanonicalParams.Canonical canonical = PointsTaskCanonicalParams.of(params);
    String key = storeKey("points.task.claim", context, "USER_SELF");
    admitWithRecovery(key, canonical);
    AwardClaimReceipt[] out = new AwardClaimReceipt[1];
    runBound(key, canonical, () -> out[0] = applyClaim(context, claimType, key, canonical));
    return out[0];
  }

  private AwardClaimReceipt applyClaim(
      CommandContext context, String claimType, String key,
      PointsTaskCanonicalParams.Canonical canonical) {
    return store.execute(m -> {
      Binding binding = PointsTaskStore.require(m, key);
      PointsTaskStore.same(binding, canonical);
      if ("SUCCEEDED".equals(binding.status())) {
        return receipt(binding.receiptJson(), PointsTaskService::claimReceipt);
      }
      long user = userId(context);
      ConfigView config = configInView(m);
      List<Long> ledgerIds = new ArrayList<>();
      long granted = 0;
      long balanceAfter = -1;
      switch (claimType) {
        case CLAIM_SIGN_IN_STREAK_7 -> {
          requireNoAward(m, user, claimType);
          List<LocalDate> dates = m.selectRecentSignInDates(user, STREAK_SCAN_DAYS);
          boolean signedToday = !dates.isEmpty() && today().equals(dates.get(0));
          long streak = streak(dates, signedToday ? today() : today().minusDays(1));
          if (streak < 7) conflict("连续签到未满 7 天，暂不可领取");
          balanceAfter = grantAward(m, user, claimType, config.signInStreakBonusPoints(),
              context.requestId(), ledgerIds);
          granted = config.signInStreakBonusPoints();
        }
        case CLAIM_INVITE_MILESTONE_3 -> {
          requireNoAward(m, user, claimType);
          long rewarded = m.selectRewardedBindings(user).size();
          if (rewarded < 3) conflict("邀请好友数量未达标，暂不可领取");
          balanceAfter = grantAward(m, user, claimType, config.inviteMilestoneBonusPoints(),
              context.requestId(), ledgerIds);
          granted = config.inviteMilestoneBonusPoints();
        }
        case CLAIM_PROFILE_COMPLETE -> {
          requireNoAward(m, user, claimType);
          if (facts.userNickname(IDS.toApi(user)).isBlank()) {
            conflict("完善个人资料后可领取");
          }
          balanceAfter = grantAward(m, user, claimType, config.profileCompletePoints(),
              context.requestId(), ledgerIds);
          granted = config.profileCompletePoints();
        }
        case CLAIM_FIRST_ORDER -> {
          requireNoAward(m, user, claimType);
          if (facts.firstPaidAt(IDS.toApi(user)) == null) {
            conflict("完成首次下单后可领取");
          }
          // Contract 60 §7 (PTS-001): the first-order reward is granted once per user and is
          // bound to the earliest paid order so the refund clawback can find it by order.
          com.petplatform.points.biz.domain.service.PointsTaskFacts.FirstPaidOrder firstPaid =
              facts.firstPaidOrder(IDS.toApi(user));
          if (firstPaid == null) {
            unavailable("首笔支付订单不可定位");
          }
          long orderId;
          try {
            orderId = IDS.fromApi(firstPaid.orderId());
          } catch (IllegalArgumentException malformedOrderId) {
            throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "首付订单标识非法");
          }
          if (orderId <= 0) {
            throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "首付订单标识非法");
          }
          balanceAtLeastZero(m, user);
          balanceAfter = grantOrderReward(m, user, orderId, config.firstOrderPoints(),
              context.requestId(), ledgerIds);
          granted = config.firstOrderPoints();
        }
        case CLAIM_INVITE_PER_HEAD -> {
          requireNoAwardConfig(m, user, config);
          String period = period(today());
          long rewardedThisMonth = m.selectRewardedBindings(user).stream()
              .filter(row -> period.equals(row.getRewardPeriod())).count();
          long remaining = config.inviteMonthlyRewardCap() - rewardedThisMonth;
          if (remaining <= 0) conflict("本月拉新奖励已达上限");
          List<PointsReferralBindingEntity> bound =
              m.selectBoundBindings(user, BINDING_SCAN_LIMIT);
          List<PointsReferralBindingEntity> eligible = new ArrayList<>();
          for (PointsReferralBindingEntity candidate : bound) {
            if (eligible.size() >= remaining) break;
            if (paidAfterBinding(candidate)) eligible.add(candidate);
          }
          if (eligible.isEmpty()) conflict("暂无可领取的拉新奖励");
          LocalDateTime now = nowUtc();
          for (PointsReferralBindingEntity candidate : eligible) {
            long ledgerId = store.nextId();
            long after = accrue(m, user, "INVITE", candidate.getId(),
                config.invitePerInviteePoints(),
                context.requestId() + "|" + candidate.getId(), ledgerId, now);
            if (m.markBindingRewarded(candidate.getId(), period, ledgerId, now) != 1) {
              throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "拉新奖励标记失败");
            }
            ledgerIds.add(ledgerId);
            granted += config.invitePerInviteePoints();
            balanceAfter = after;
          }
        }
        default -> invalid("claimType is invalid");
      }
      if (granted <= 0 || balanceAfter < 0 || ledgerIds.isEmpty()) {
        throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "奖励领取结果非法");
      }
      AwardClaimReceipt value = new AwardClaimReceipt(
          claimType, Long.toString(granted),
          ledgerIds.stream().map(IDS::toApi).toList(), Long.toString(balanceAfter));
      if (m.markBindingSucceeded(key, json(value)) != 1) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "奖励领取幂等标记失败");
      }
      return value;
    });
  }

  /** PUT /admin/points-task-config (32号 ruling): full-put of the seven grant amounts. */
  public ConfigView updateConfig(UpdateConfigCommand command) {
    if (command == null) invalid("command is required");
    validateConfig(
        command.signInDailyPoints(), command.signInStreakBonusPoints(),
        command.invitePerInviteePoints(), command.inviteMonthlyRewardCap(),
        command.inviteMilestoneBonusPoints(), command.firstOrderPoints(),
        command.profileCompletePoints());
    CommandContext context = command.context();
    if (context == null || context.operatorType() == null
        || context.operatorId() == null || context.operatorId().isBlank()) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
    }
    if (context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "points task config belongs to operators");
    }
    try {
      PublicContractChecks.requireCommandRequestId(context);
    } catch (IllegalArgumentException invalidContext) {
      invalid("requestId is invalid");
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("signInDailyPoints", command.signInDailyPoints());
    params.put("signInStreakBonusPoints", command.signInStreakBonusPoints());
    params.put("invitePerInviteePoints", command.invitePerInviteePoints());
    params.put("inviteMonthlyRewardCap", command.inviteMonthlyRewardCap());
    params.put("inviteMilestoneBonusPoints", command.inviteMilestoneBonusPoints());
    params.put("firstOrderPoints", command.firstOrderPoints());
    params.put("profileCompletePoints", command.profileCompletePoints());
    PointsTaskCanonicalParams.Canonical canonical = PointsTaskCanonicalParams.of(params);
    String key = storeKey("points.task.config.update", context, "OPS_CONFIG");
    admitWithRecovery(key, canonical);
    ConfigView[] out = new ConfigView[1];
    runBound(key, canonical, () -> out[0] = applyConfig(command, key, canonical));
    return out[0];
  }

  private ConfigView applyConfig(
      UpdateConfigCommand command, String key, PointsTaskCanonicalParams.Canonical canonical) {
    return store.execute(m -> {
      Binding binding = PointsTaskStore.require(m, key);
      PointsTaskStore.same(binding, canonical);
      if ("SUCCEEDED".equals(binding.status())) {
        return receipt(binding.receiptJson(), PointsTaskService::configReceipt);
      }
      LocalDateTime now = nowUtc();
      if (m.upsertConfig(store.nextId(), SCOPE,
          command.signInDailyPoints(), command.signInStreakBonusPoints(),
          command.invitePerInviteePoints(), command.inviteMonthlyRewardCap(),
          command.inviteMilestoneBonusPoints(), command.firstOrderPoints(),
          command.profileCompletePoints(), now) != 1) {
        throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分配置写入失败");
      }
      ConfigView value = view(Objects.requireNonNull(m.selectConfig(SCOPE)));
      if (m.markBindingSucceeded(key, configJson(value)) != 1) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "积分配置幂等标记失败");
      }
      return value;
    });
  }

  // ------------------------------------------------------------------ internals

  /** Writes one positive ledger line and atomically bumps the account balance (A1). */
  private long accrue(PointsTaskMapper m, long user, String bizType, long bizId, long delta,
      String ledgerRequestId, long ledgerId, LocalDateTime now) {
    if (delta <= 0 || delta > 10_000) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分发放数额非法");
    }
    if (ledgerRequestId == null || ledgerRequestId.length() > 64) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分流水幂等键非法");
    }
    // Insert-or-increment; the affected-row count is 1 on the insert path and 2 on the MySQL
    // ON DUPLICATE KEY UPDATE path, so it is not a success signal — the balance read-back
    // below plus the ledger insert carry the integrity (same convention as the notification
    // preference upsert).
    m.upsertAccountBalance(store.nextId(), user, delta, now);
    Long balance = m.selectBalance(user);
    if (balance == null || balance < 0) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分账户余额非法");
    }
    if (m.insertLedger(ledgerId, user, bizType, bizId, delta, balance, ledgerRequestId, now) != 1) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分流水写入失败");
    }
    return balance;
  }

  /** Order-bound accrual variant: identical discipline, ledger line carries order_id (60号 §7). */
  private long accrueOrderReward(PointsTaskMapper m, long user, long bizId, long orderId,
      long delta, String ledgerRequestId, long ledgerId, LocalDateTime now) {
    if (delta <= 0 || delta > 10_000) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分发放数额非法");
    }
    if (ledgerRequestId == null || ledgerRequestId.length() > 64) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分流水幂等键非法");
    }
    if (orderId <= 0) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分流水的订单绑定非法");
    }
    m.upsertAccountBalance(store.nextId(), user, delta, now);
    Long balance = m.selectBalance(user);
    if (balance == null || balance < 0) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分账户余额非法");
    }
    if (m.insertOrderLedger(ledgerId, user, "ORDER_REWARD", bizId, orderId, delta, balance,
        ledgerRequestId, now) != 1) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分流水写入失败");
    }
    return balance;
  }

  private long grantAward(PointsTaskMapper m, long user, String taskCode, long points,
      String requestId, List<Long> ledgerIds) {
    long awardId = store.nextId();
    long ledgerId = store.nextId();
    long balanceAfter = accrue(m, user, "TASK", awardId, points, requestId, ledgerId, nowUtc());
    if (m.insertAward(awardId, user, taskCode, points, ledgerId, nowUtc()) != 1) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "任务奖励写入失败");
    }
    ledgerIds.add(ledgerId);
    return balanceAfter;
  }

  /**
   * First-order reward (contract 60 §7 / CCR-POINTS-ORDER-001 P1): one TASK_AWARD row keyed by
   * the user (uk user+code, once per user) plus one order-bound ORDER_REWARD ledger line keyed
   * by the first paid order — the fact contract 60 §7 clawback scans by order.
   */
  private long grantOrderReward(PointsTaskMapper m, long user, long orderId, long points,
      String requestId, List<Long> ledgerIds) {
    long awardId = store.nextId();
    long ledgerId = store.nextId();
    long balanceAfter = accrueOrderReward(m, user, awardId, orderId, points, requestId,
        ledgerId, nowUtc());
    if (m.insertAward(awardId, user, CLAIM_FIRST_ORDER, points, ledgerId, nowUtc()) != 1) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "任务奖励写入失败");
    }
    ledgerIds.add(ledgerId);
    return balanceAfter;
  }

  private void requireNoAward(PointsTaskMapper m, long user, String claimType) {
    if (m.selectAward(user, claimType) != null) conflict("该奖励已领取过，不可重复领取");
  }

  private void requireNoAwardConfig(PointsTaskMapper m, long user, ConfigView config) {
    if (config.invitePerInviteePoints() <= 0) conflict("拉新奖励未开放");
  }

  private void balanceAtLeastZero(PointsTaskMapper m, long user) {
    Long balance = m.selectBalance(user);
    if (balance != null && balance < 0) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分账户余额非法");
    }
  }

  private boolean paidAfterBinding(PointsReferralBindingEntity binding) {
    java.time.OffsetDateTime firstPaid = facts.firstPaidAt(IDS.toApi(binding.getInviteeId()));
    return firstPaid != null
        && firstPaid.toInstant().isAfter(binding.getCreatedAt().toInstant(ZoneOffset.UTC));
  }

  private ConfigView configInView(PointsTaskMapper m) {
    PointsTaskConfigEntity row = m.selectConfig(SCOPE);
    return row == null ? CONFIG_DEFAULTS : view(row);
  }

  private static ConfigView view(PointsTaskConfigEntity row) {
    if (row == null || row.getSignInDailyPoints() == null || row.getSignInStreakBonusPoints() == null
        || row.getInvitePerInviteePoints() == null || row.getInviteMonthlyRewardCap() == null
        || row.getInviteMilestoneBonusPoints() == null || row.getFirstOrderPoints() == null
        || row.getProfileCompletePoints() == null || row.getVersion() == null
        || row.getUpdatedAt() == null || !SCOPE.equals(row.getScope())) {
      throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分任务配置投影非法");
    }
    return new ConfigView(
        row.getSignInDailyPoints(), row.getSignInStreakBonusPoints(),
        row.getInvitePerInviteePoints(), row.getInviteMonthlyRewardCap(),
        row.getInviteMilestoneBonusPoints(), row.getFirstOrderPoints(),
        row.getProfileCompletePoints(), row.getVersion(),
        row.getUpdatedAt().toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC));
  }

  private static void validateConfig(long daily, long streak, long perHead, long cap,
      long milestone, long firstOrder, long profile) {
    if (daily < 1 || daily > 1_000) invalid("signInDailyPoints 超出范围");
    if (streak < 0 || streak > 1_000) invalid("signInStreakBonusPoints 超出范围");
    if (perHead < 0 || perHead > 1_000) invalid("invitePerInviteePoints 超出范围");
    if (cap < 0 || cap > 100) invalid("inviteMonthlyRewardCap 超出范围");
    if (milestone < 0 || milestone > 1_000) invalid("inviteMilestoneBonusPoints 超出范围");
    if (firstOrder < 0 || firstOrder > 1_000) invalid("firstOrderPoints 超出范围");
    if (profile < 0 || profile > 1_000) invalid("profileCompletePoints 超出范围");
  }

  /** Consecutive signed days ending exactly at {@code anchor} (S2: gap resets, no backfill). */
  private static long streak(List<LocalDate> dates, LocalDate anchor) {
    long streak = 0;
    LocalDate expected = anchor;
    for (LocalDate date : dates) {
      if (date.equals(expected)) {
        streak++;
        expected = expected.minusDays(1);
      } else if (date.isBefore(expected)) {
        break;
      }
    }
    return streak;
  }

  private static List<RewardRow> withClaimed(List<RewardRow> rows, String code) {
    List<RewardRow> next = new ArrayList<>(rows.size());
    for (RewardRow row : rows) {
      next.add(row.rewardCode().equals(code)
          ? new RewardRow(row.rewardCode(), row.title(), row.description(),
              row.points(), "CLAIMED", row.progress())
          : row);
    }
    return next;
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), CN);
  }

  private String period(LocalDate date) {
    return date.getYear() + "-" + String.format("%02d", date.getMonthValue());
  }

  private LocalDateTime nowUtc() {
    return LocalDateTime.ofInstant(
        clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private String storeKey(String namespace, CommandContext context, String scope) {
    return PointsTaskStore.requestKey(
        namespace, context.operatorType().name(), context.operatorId(), scope,
        context.requestId());
  }

  private void admitWithRecovery(String key, PointsTaskCanonicalParams.Canonical canonical) {
    try {
      store.admit(key, canonical);
    } catch (PointsTaskStore.CommitUnknown firstUnknown) {
      try {
        store.admit(key, canonical);
      } catch (PointsTaskStore.CommitUnknown secondUnknown) {
        unavailable("idempotency admission result remains unknown");
      }
    }
  }

  private interface BoundWork {
    void run();
  }

  private void runBound(String key, PointsTaskCanonicalParams.Canonical canonical, BoundWork work) {
    try {
      work.run();
    } catch (PointsTaskStore.CommitUnknown first) {
      try {
        work.run();
      } catch (PointsTaskStore.CommitUnknown second) {
        unavailable("points task commit result remains unknown");
      }
    }
  }

  // The admission already proved parameter equality before execution re-reads the binding;
  // execution only re-checks the status and replays the first success receipt when present.

  private static CommandContext userCommand(CommandContext context) {
    if (context == null || context.operatorType() == null
        || context.operatorId() == null || context.operatorId().isBlank()) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
    }
    if (context.operatorType() != OperatorType.USER) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "points tasks belong to miniapp users");
    }
    try {
      PublicContractChecks.requireCommandRequestId(context);
    } catch (IllegalArgumentException invalidContext) {
      invalid("requestId is invalid");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "points task command admission must not run inside an existing transaction");
    }
    return context;
  }

  private static long userId(CommandContext context) {
    try {
      return IDS.fromApi(context.operatorId());
    } catch (IllegalArgumentException invalidPrincipal) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
    }
  }

  private static long userSubject(QueryContext context) {
    if (context == null || context.operatorType() == null
        || context.operatorId() == null || context.operatorId().isBlank()) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context is required");
    }
    if (context.operatorType() != OperatorType.USER) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "points tasks belong to miniapp users");
    }
    try {
      return IDS.fromApi(context.operatorId());
    } catch (IllegalArgumentException invalidPrincipal) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
    }
  }

  // ------------------------------------------------------------------ receipts

  private static String json(SignInReceipt receipt) {
    return "{\"signedDate\":\"" + receipt.signedDate() + "\""
        + ",\"points\":\"" + receipt.points() + "\""
        + ",\"streakAfter\":" + receipt.streakAfter()
        + ",\"ledgerId\":\"" + receipt.ledgerId() + "\""
        + ",\"balanceAfter\":\"" + receipt.balanceAfter() + "\"}";
  }

  private static SignInReceipt signInReceipt(JsonNode root) {
    if (root == null || !root.isObject() || root.size() != 5
        || !text(root, "signedDate", "^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        || !digits(root, "points") || !digits(root, "ledgerId") || !digits(root, "balanceAfter")
        || !root.has("streakAfter") || !root.get("streakAfter").isInt()
        || root.get("streakAfter").asLong() < 1) {
      unavailable("sign-in receipt is damaged");
    }
    return new SignInReceipt(root.get("signedDate").asText(), root.get("points").asText(),
        root.get("streakAfter").asLong(), root.get("ledgerId").asText(),
        root.get("balanceAfter").asText());
  }

  private static String json(
      com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt receipt) {
    return "{\"bindingId\":\"" + receipt.bindingId() + "\""
        + ",\"createdAt\":" + (receipt.createdAt() == null ? "null"
            : "\"" + format(receipt.createdAt()) + "\"") + "}";
  }

  private static com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt
      bindingReceipt(JsonNode root) {
    if (root == null || !root.isObject() || root.size() != 2
        || !digits(root, "bindingId")
        || !root.has("createdAt") || !root.get("createdAt").isNull()
            && !text(root, "createdAt",
                "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")) {
      unavailable("referral binding receipt is damaged");
    }
    JsonNode at = root.get("createdAt");
    return new com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt(
        root.get("bindingId").asText(),
        at.isNull() ? null : java.time.Instant.parse(at.asText()).atOffset(ZoneOffset.UTC));
  }

  private static String json(AwardClaimReceipt receipt) {
    StringBuilder ids = new StringBuilder("[");
    for (int index = 0; index < receipt.ledgerIds().size(); index++) {
      if (index > 0) ids.append(',');
      ids.append('"').append(receipt.ledgerIds().get(index)).append('"');
    }
    ids.append(']');
    return "{\"claimType\":\"" + receipt.claimType() + "\""
        + ",\"pointsGranted\":\"" + receipt.pointsGranted() + "\""
        + ",\"ledgerIds\":" + ids
        + ",\"balanceAfter\":\"" + receipt.balanceAfter() + "\"}";
  }

  private static AwardClaimReceipt claimReceipt(JsonNode root) {
    if (root == null || !root.isObject() || root.size() != 4
        || !text(root, "claimType", "^[A-Z][A-Z0-9_]{0,63}$")
        || !digits(root, "pointsGranted") || !digits(root, "balanceAfter")
        || !root.has("ledgerIds") || !root.get("ledgerIds").isArray()
        || root.get("ledgerIds").isEmpty()) {
      unavailable("claim receipt is damaged");
    }
    List<String> ledgerIds = new ArrayList<>();
    for (JsonNode id : root.get("ledgerIds")) {
      if (!id.isTextual() || !id.asText().matches("^(0|[1-9][0-9]{0,18})$")) {
        unavailable("claim receipt is damaged");
      }
      ledgerIds.add(id.asText());
    }
    return new AwardClaimReceipt(root.get("claimType").asText(),
        root.get("pointsGranted").asText(), List.copyOf(ledgerIds),
        root.get("balanceAfter").asText());
  }

  private static String configJson(ConfigView view) {
    return "{\"signInDailyPoints\":\"" + view.signInDailyPoints() + "\""
        + ",\"signInStreakBonusPoints\":\"" + view.signInStreakBonusPoints() + "\""
        + ",\"invitePerInviteePoints\":\"" + view.invitePerInviteePoints() + "\""
        + ",\"inviteMonthlyRewardCap\":\"" + view.inviteMonthlyRewardCap() + "\""
        + ",\"inviteMilestoneBonusPoints\":\"" + view.inviteMilestoneBonusPoints() + "\""
        + ",\"firstOrderPoints\":\"" + view.firstOrderPoints() + "\""
        + ",\"profileCompletePoints\":\"" + view.profileCompletePoints() + "\""
        + ",\"version\":\"" + view.version() + "\""
        + ",\"updatedAt\":" + (view.updatedAt() == null ? "null"
            : "\"" + format(view.updatedAt()) + "\"") + "}";
  }

  private static ConfigView configReceipt(JsonNode root) {
    if (root == null || !root.isObject() || root.size() != 9
        || !digits(root, "signInDailyPoints") || !digits(root, "signInStreakBonusPoints")
        || !digits(root, "invitePerInviteePoints") || !digits(root, "inviteMonthlyRewardCap")
        || !digits(root, "inviteMilestoneBonusPoints") || !digits(root, "firstOrderPoints")
        || !digits(root, "profileCompletePoints") || !digits(root, "version")
        || !root.has("updatedAt") || !root.get("updatedAt").isNull()
            && !text(root, "updatedAt",
                "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")) {
      unavailable("config receipt is damaged");
    }
    JsonNode at = root.get("updatedAt");
    return new ConfigView(
        Long.parseLong(root.get("signInDailyPoints").asText()),
        Long.parseLong(root.get("signInStreakBonusPoints").asText()),
        Long.parseLong(root.get("invitePerInviteePoints").asText()),
        Long.parseLong(root.get("inviteMonthlyRewardCap").asText()),
        Long.parseLong(root.get("inviteMilestoneBonusPoints").asText()),
        Long.parseLong(root.get("firstOrderPoints").asText()),
        Long.parseLong(root.get("profileCompletePoints").asText()),
        Long.parseLong(root.get("version").asText()),
        at.isNull() ? null : java.time.Instant.parse(at.asText()).atOffset(ZoneOffset.UTC));
  }

  private static <T> T receipt(String receiptJson, java.util.function.Function<JsonNode, T> parse) {
    try {
      return parse.apply(JSON.readTree(receiptJson));
    } catch (ApiException failure) {
      throw failure;
    } catch (Exception failure) {
      unavailable("points task receipt is damaged");
      return null;
    }
  }

  private static boolean digits(JsonNode root, String field) {
    return root.has(field) && root.get(field).isTextual()
        && root.get(field).asText().matches("^(0|[1-9][0-9]{0,18})$");
  }

  private static boolean text(JsonNode root, String field, String pattern) {
    return root.has(field) && root.get(field).isTextual()
        && root.get(field).asText().matches(pattern);
  }

  private static String format(java.time.OffsetDateTime value) {
    return new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        .format(value.toInstant());
  }

  private static void invalid(String message) {
    throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
  }

  private static void conflict(String message) {
    throw new ApiException(CommonApiCodes.CONFLICT, message);
  }

  private static void unavailable(String message) {
    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
  }
}
