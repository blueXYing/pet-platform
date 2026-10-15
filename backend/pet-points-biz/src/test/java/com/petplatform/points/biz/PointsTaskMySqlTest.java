package com.petplatform.points.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.points.api.command.PointsTaskCommandApi.BindReferralCommand;
import com.petplatform.points.api.command.PointsTaskCommandApi.ClaimRewardCommand;
import com.petplatform.points.api.command.PointsTaskCommandApi.SignInCommand;
import com.petplatform.points.api.command.PointsTaskCommandApi;
import com.petplatform.points.api.dto.PointsTaskTypes.AwardClaimReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.ConfigView;
import com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInStatusView;
import com.petplatform.points.api.dto.PointsTaskTypes.TasksView;
import com.petplatform.points.biz.apiimpl.PointsTaskCommandApiImpl;
import com.petplatform.points.biz.apiimpl.PointsTaskQueryApiImpl;
import com.petplatform.points.biz.domain.service.InviteCodeCodec;
import com.petplatform.points.biz.domain.service.PointsTaskFacts;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract-58 kernel acceptance over isolated real MySQL (SQL06 §9 + SQL14 + SQL58): the
 * sign-in daily rule with supplement-23 replay/conflict semantics, the streak reset on a gap,
 * the one-shot 7-day milestone, the conservative referral rules (code round-trip, self/invalid
 * rejection, zero-order-history invitee, paid-after-binding, monthly cap rollover), the
 * one-time first-order/profile awards and the ops-configured amounts including live re-reading.
 */
class PointsTaskMySqlTest {

  private static final long INVITER = 9_400_000_000_001_001L;
  private static final long INVITEE_A = 9_400_000_000_001_002L;
  private static final long INVITEE_B = 9_400_000_000_001_003L;
  private static final long INVITEE_C = 9_400_000_000_001_004L;
  private static final long INVITEE_D = 9_400_000_000_001_005L;
  private static final long OPERATOR = 9_100_000_000_000_001L;

  /** 2026-10-13 09:30 UTC+8 = 01:30 UTC — mid-day in the Shanghai calendar. */
  private static final Instant START = Instant.parse("2026-10-13T01:30:00.000Z");

  private static final class SettableClock extends Clock {
    private Instant now;

    SettableClock(Instant start) {
      this.now = start;
    }

    void set(Instant next) {
      this.now = next;
    }

    void plusHours(long hours) {
      now = now.plus(java.time.Duration.ofHours(hours));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Per-user fact source (the fixed() factory is global; tests need per-invitee truth). */
  private static final class MapFacts implements PointsTaskFacts {
    final Map<Long, OffsetDateTime> firstPaid = new HashMap<>();
    final Map<Long, String> firstPaidOrders = new HashMap<>();
    final Map<Long, Boolean> enabled = new HashMap<>();
    final Map<Long, String> nicknames = new HashMap<>();

    @Override
    public OffsetDateTime firstPaidAt(String userId) {
      return firstPaid.get(Long.parseLong(userId));
    }

    @Override
    public PointsTaskFacts.FirstPaidOrder firstPaidOrder(String userId) {
      long user = Long.parseLong(userId);
      String orderId = firstPaidOrders.get(user);
      if (orderId == null && firstPaid.containsKey(user)) {
        orderId = Long.toString(6_000_000_000_000_000L + user);
      }
      return orderId == null ? null
          : new PointsTaskFacts.FirstPaidOrder(orderId, firstPaid.get(user));
    }

    @Override
    public boolean userEnabled(String userId) {
      return enabled.getOrDefault(Long.parseLong(userId), true);
    }

    @Override
    public String userNickname(String userId) {
      return nicknames.getOrDefault(Long.parseLong(userId), "");
    }
  }

  private PointsTaskMySqlTestDatabase database;
  private PointsTaskCommandApiImpl commands;
  private PointsTaskQueryApiImpl queries;
  private SettableClock clock;
  private MapFacts facts;
  private AtomicLong ids;

  @BeforeEach
  void start() throws Exception {
    database = new PointsTaskMySqlTestDatabase();
    clock = new SettableClock(START);
    facts = new MapFacts();
    ids = new AtomicLong(9_400_000_000_100_000L);
    commands = new PointsTaskCommandApiImpl(database.dataSource(), ids::incrementAndGet, facts, clock);
    queries = new PointsTaskQueryApiImpl(database.dataSource(), ids::incrementAndGet, facts, clock);
  }

  @AfterEach
  void stop() {
    if (database != null) {
      database.close();
    }
  }

  // ------------------------------------------------------------------ sign-in (S1~S3)

  @Test
  void signInIsOncePerShanghaiDayWithReplayAndFreshRequestConflict() {
    String requestId = uuid();
    SignInReceipt first = commands.signIn(signIn(INVITER, requestId));
    assertEquals("2026-10-13", first.signedDate());
    assertEquals("5", first.points());
    assertEquals(1, first.streakAfter());
    assertEquals("5", first.balanceAfter());

    SignInReceipt replay = commands.signIn(signIn(INVITER, requestId));
    assertEquals(first.ledgerId(), replay.ledgerId());
    assertEquals(first.balanceAfter(), replay.balanceAfter());
    assertEquals(1, database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM points_sign_in WHERE user_id=?", Integer.class, INVITER));
    assertEquals(1, database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM points_ledger WHERE user_id=? AND biz_type='SIGN_IN'",
        Integer.class, INVITER));

    ApiException sameDay = assertThrows(ApiException.class,
        () -> commands.signIn(signIn(INVITER, uuid())));
    assertEquals(CommonApiCodes.CONFLICT, sameDay.code());

    // Next Shanghai calendar day (02:30Z on 10-14 = 10:30 UTC+8 on 10-14).
    clock.set(Instant.parse("2026-10-14T02:30:00.000Z"));
    SignInReceipt next = commands.signIn(signIn(INVITER, uuid()));
    assertEquals("2026-10-14", next.signedDate());
    assertEquals(2, next.streakAfter());
    assertEquals("10", next.balanceAfter());
  }

  @Test
  void streakGrowsAccrossConsecutiveDaysAndResetsAfterAGap() {
    // Seed five earlier consecutive days through the kernel itself.
    for (int day = 8; day <= 12; day++) {
      clock.set(Instant.parse(String.format("2026-10-%02dT01:30:00.000Z", day)));
      commands.signIn(signIn(INVITEE_A, "seed-" + day));
    }
    SignInStatusView status = queries.signInStatus(status(query(INVITEE_A)));
    assertTrue(status.signedToday());
    assertEquals(5, status.streak());
    assertEquals(7, status.week().size());
    assertEquals("2026-10-12", status.week().get(6).date());
    assertTrue(status.week().get(6).signed());

    // Skip 10-13/10-14 entirely, sign on 10-15: the streak resets to 1 (gap resets, no backfill).
    clock.set(Instant.parse("2026-10-15T01:30:00.000Z"));
    SignInReceipt after = commands.signIn(signIn(INVITEE_A, "after-gap"));
    assertEquals(1, after.streakAfter());
    assertEquals(1, queries.signInStatus(status(query(INVITEE_A))).streak());
  }

  @Test
  void sevenDayMilestoneIsClaimDrivenAndOneShot() {
    for (int day = 10; day <= 16; day++) {
      clock.set(Instant.parse("2026-10-" + day + "T01:30:00.000Z"));
      commands.signIn(signIn(INVITEE_A, "streak-" + day));
    }
    TasksView before = queries.tasks(tasks(query(INVITEE_A)));
    assertEquals("CLAIMABLE", reward(before, "SIGN_IN_STREAK_7").status());
    assertEquals("7/7", reward(before, "SIGN_IN_STREAK_7").progress());

    ApiException tooEarly = assertThrows(ApiException.class,
        () -> commands.claimReward(claim(INVITEE_A, uuid(), "INVITE_MILESTONE_3")));
    assertEquals(CommonApiCodes.CONFLICT, tooEarly.code());

    String requestId = uuid();
    AwardClaimReceipt first = commands.claimReward(claim(INVITEE_A, requestId, "SIGN_IN_STREAK_7"));
    assertEquals("30", first.pointsGranted());
    assertEquals(1, first.ledgerIds().size());
    // 7 signed days x5 = 35 accrued, then the milestone adds 30.
    assertEquals("65", first.balanceAfter());

    AwardClaimReceipt replay = commands.claimReward(claim(INVITEE_A, requestId, "SIGN_IN_STREAK_7"));
    assertEquals(first.pointsGranted(), replay.pointsGranted());
    assertEquals(first.ledgerIds(), replay.ledgerIds());

    ApiException again = assertThrows(ApiException.class,
        () -> commands.claimReward(claim(INVITEE_A, uuid(), "SIGN_IN_STREAK_7")));
    assertEquals(CommonApiCodes.CONFLICT, again.code());
    assertEquals("CLAIMED", reward(queries.tasks(tasks(query(INVITEE_A))), "SIGN_IN_STREAK_7").status());
  }

  // ------------------------------------------------------------------ referral (R1~R4)

  @Test
  void inviteCodeRoundTripsAndSelfOrInvalidCodesAreRejected() {
    String code = InviteCodeCodec.encode(INVITER);
    assertEquals(15, code.length());
    assertEquals(Long.valueOf(INVITER), InviteCodeCodec.decode(code));
    assertEquals(code, queries.tasks(tasks(query(INVITER))).inviteCode());

    ApiException self = assertThrows(ApiException.class,
        () -> commands.bindReferral(binding(INVITER, uuid(), code)));
    assertEquals(CommonApiCodes.INVALID_ARGUMENT, self.code());

    ApiException invalid = assertThrows(ApiException.class,
        () -> commands.bindReferral(binding(INVITEE_A, uuid(), "P0000000000000X")));
    assertEquals(CommonApiCodes.INVALID_ARGUMENT, invalid.code());

    facts.enabled.put(INVITEE_C, false);
    ApiException dead = assertThrows(ApiException.class,
        () -> commands.bindReferral(binding(INVITEE_A, uuid(), InviteCodeCodec.encode(INVITEE_C))));
    assertEquals(CommonApiCodes.INVALID_ARGUMENT, dead.code());
  }

  @Test
  void bindingIsOnceEverAndRequiresZeroOrderHistory() {
    facts.firstPaid.put(INVITEE_B, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    ApiException hasOrders = assertThrows(ApiException.class,
        () -> commands.bindReferral(binding(INVITEE_B, uuid(), InviteCodeCodec.encode(INVITER))));
    assertEquals(CommonApiCodes.CONFLICT, hasOrders.code());

    String requestId = uuid();
    ReferralBindingReceipt bound = commands.bindReferral(
        binding(INVITEE_A, requestId, InviteCodeCodec.encode(INVITER)));
    assertNotNull(bound.bindingId());
    assertEquals(bound.bindingId(), commands.bindReferral(
        binding(INVITEE_A, requestId, InviteCodeCodec.encode(INVITER))).bindingId());

    ApiException repeat = assertThrows(ApiException.class,
        () -> commands.bindReferral(binding(INVITEE_A, uuid(), InviteCodeCodec.encode(INVITEE_D))));
    assertEquals(CommonApiCodes.CONFLICT, repeat.code());
  }

  @Test
  void perHeadRewardRequiresPaymentAfterBindingAndRespectsMonthlyCap() {
    // Three invitees bind on 10-13; C already paid before binding (disqualified forever).
    commands.bindReferral(binding(INVITEE_A, uuid(), InviteCodeCodec.encode(INVITER)));
    commands.bindReferral(binding(INVITEE_B, uuid(), InviteCodeCodec.encode(INVITER)));
    // R2 zero-order-history: C already paid once, so the binding itself is refused.
    facts.firstPaid.put(INVITEE_C, OffsetDateTime.parse("2026-10-10T00:00:00Z"));
    ApiException paidBefore =
        assertThrows(ApiException.class,
            () -> commands.bindReferral(
                binding(INVITEE_C, uuid(), InviteCodeCodec.encode(INVITER))));
    assertEquals(CommonApiCodes.CONFLICT, paidBefore.code());

    ApiException nothingYet = assertThrows(ApiException.class,
        () -> commands.claimReward(claim(INVITER, uuid(), "INVITE_PER_HEAD")));
    assertEquals(CommonApiCodes.CONFLICT, nothingYet.code());

    // A and B complete their first payments strictly after the binding instant.
    facts.firstPaid.put(INVITEE_A, OffsetDateTime.parse("2026-10-13T02:00:00Z"));
    facts.firstPaid.put(INVITEE_B, OffsetDateTime.parse("2026-10-13T03:00:00Z"));

    // Configure the monthly cap down to 1 and the per-head amount to 7.
    commands.updateConfig(config(uuid(), 5, 30, 7, 1, 100, 100, 20));

    AwardClaimReceipt first = commands.claimReward(claim(INVITER, uuid(), "INVITE_PER_HEAD"));
    assertEquals("7", first.pointsGranted());
    assertEquals(1, first.ledgerIds().size());
    assertEquals("7", first.balanceAfter());

    ApiException capped = assertThrows(ApiException.class,
        () -> commands.claimReward(claim(INVITER, uuid(), "INVITE_PER_HEAD")));
    assertEquals(CommonApiCodes.CONFLICT, capped.code());

    // November: the same inviter can claim the remaining head under the new month bucket.
    clock.set(Instant.parse("2026-11-02T01:30:00.000Z"));
    AwardClaimReceipt november = commands.claimReward(claim(INVITER, uuid(), "INVITE_PER_HEAD"));
    assertEquals("7", november.pointsGranted());
    assertEquals(1, november.ledgerIds().size());
    assertEquals("14", november.balanceAfter());

    // The 3-head milestone counts rewarded heads (2 by now): still not claimable.
    assertEquals("NOT_ACHIEVED", invite(queries.tasks(tasks(query(INVITER)))).milestoneStatus());
  }

  @Test
  void inviteMilestoneClaimsOnceAtThreeRewardedHeads() {
    for (long invitee : new long[] {INVITEE_A, INVITEE_B, INVITEE_D}) {
      commands.bindReferral(binding(invitee, uuid(), InviteCodeCodec.encode(INVITER)));
      facts.firstPaid.put(invitee, clock.instant().atOffset(ZoneOffset.UTC).plusMinutes(1));
      commands.claimReward(claim(INVITER, uuid(), "INVITE_PER_HEAD"));
    }
    AwardClaimReceipt milestone = commands.claimReward(
        claim(INVITER, uuid(), "INVITE_MILESTONE_3"));
    assertEquals("100", milestone.pointsGranted());
    assertEquals("250", milestone.balanceAfter());

    ApiException again = assertThrows(ApiException.class,
        () -> commands.claimReward(claim(INVITER, uuid(), "INVITE_MILESTONE_3")));
    assertEquals(CommonApiCodes.CONFLICT, again.code());
  }

  // ------------------------------------------------------------------ one-time tasks

  @Test
  void firstOrderAndProfileAwardsAreOneShotAndConfigDriven() {
    ApiException noOrder = assertThrows(ApiException.class,
        () -> commands.claimReward(claim(INVITEE_A, uuid(), "FIRST_ORDER")));
    assertEquals(CommonApiCodes.CONFLICT, noOrder.code());
    ApiException noProfile = assertThrows(ApiException.class,
        () -> commands.claimReward(claim(INVITEE_A, uuid(), "PROFILE_COMPLETE")));
    assertEquals(CommonApiCodes.CONFLICT, noProfile.code());

    facts.firstPaid.put(INVITEE_A, OffsetDateTime.parse("2026-10-13T02:00:00Z"));
    facts.nicknames.put(INVITEE_A, "豆豆妈");
    AwardClaimReceipt order = commands.claimReward(claim(INVITEE_A, uuid(), "FIRST_ORDER"));
    assertEquals("100", order.pointsGranted());
    // Contract 60 §7 (PTS-001): the first-order reward is order-bound (ORDER_REWARD ledger line
    // carrying the resolved first paid order id), not a plain TASK line.
    String firstOrderId = Long.toString(6_000_000_000_000_000L + INVITEE_A);
    assertEquals(1, database.jdbc().queryForObject(
        "SELECT COUNT(*) FROM points_ledger WHERE user_id=? AND biz_type='ORDER_REWARD' "
            + "AND order_id=? AND delta=100",
        Integer.class, INVITEE_A, Long.parseLong(firstOrderId)));
    AwardClaimReceipt profile = commands.claimReward(claim(INVITEE_A, uuid(), "PROFILE_COMPLETE"));
    assertEquals("20", profile.pointsGranted());
    assertEquals("120", profile.balanceAfter());

    for (String type : new String[] {"FIRST_ORDER", "PROFILE_COMPLETE"}) {
      ApiException repeat = assertThrows(ApiException.class,
          () -> commands.claimReward(claim(INVITEE_A, uuid(), type)));
      assertEquals(CommonApiCodes.CONFLICT, repeat.code());
    }
  }

  // ------------------------------------------------------------------ ops config (32号)

  @Test
  void configDefaultsReadWithoutRowAndFullPutValidatesReplaysAndTakesEffect() {
    ConfigView defaults = queries.config(
        new com.petplatform.points.api.query.PointsTaskQueryApi.ConfigQuery(null));
    assertEquals(5, defaults.signInDailyPoints());
    assertEquals(30, defaults.signInStreakBonusPoints());
    assertEquals(50, defaults.invitePerInviteePoints());
    assertEquals(3, defaults.inviteMonthlyRewardCap());
    assertEquals(100, defaults.inviteMilestoneBonusPoints());
    assertEquals(100, defaults.firstOrderPoints());
    assertEquals(20, defaults.profileCompletePoints());
    assertEquals(0, defaults.version());

    ApiException outOfRange = assertThrows(ApiException.class,
        () -> commands.updateConfig(config(uuid(), 1001, 30, 50, 3, 100, 100, 20)));
    assertEquals(CommonApiCodes.INVALID_ARGUMENT, outOfRange.code());
    ApiException badCap = assertThrows(ApiException.class,
        () -> commands.updateConfig(config(uuid(), 5, 30, 50, 101, 100, 100, 20)));
    assertEquals(CommonApiCodes.INVALID_ARGUMENT, badCap.code());

    String requestId = uuid();
    ConfigView updated = commands.updateConfig(config(requestId, 8, 40, 60, 2, 90, 80, 10));
    assertEquals(8, updated.signInDailyPoints());
    // Same convention as the notification preference upsert: the row inserts at version 0.
    assertEquals(0, updated.version());
    ConfigView replay = commands.updateConfig(config(requestId, 8, 40, 60, 2, 90, 80, 10));
    assertEquals(0, replay.version());
    ApiException different = assertThrows(ApiException.class,
        () -> commands.updateConfig(config(requestId, 9, 40, 60, 2, 90, 80, 10)));
    assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, different.code());

    ApiException notOperator = assertThrows(ApiException.class,
        () -> commands.updateConfig(new com.petplatform.points.api.dto.PointsTaskTypes
            .UpdateConfigCommand(8, 40, 60, 2, 90, 80, 10,
                new CommandContext(uuid(), "t", OperatorType.USER, "1", "C_MINIAPP"))));
    assertEquals(CommonApiCodes.FORBIDDEN, notOperator.code());

    // Live re-reading: the next sign-in and profile claim use the new amounts.
    SignInReceipt signIn = commands.signIn(signIn(INVITEE_B, uuid()));
    assertEquals("8", signIn.points());
    facts.nicknames.put(INVITEE_B, "n");
    AwardClaimReceipt profile = commands.claimReward(claim(INVITEE_B, uuid(), "PROFILE_COMPLETE"));
    assertEquals("10", profile.pointsGranted());
    assertEquals("18", profile.balanceAfter());
  }

  // ------------------------------------------------------------------ projection & isolation

  @Test
  void tasksProjectionIsSubjectScopedAndCarriesTheClosedEnum() {
    TasksView mine = queries.tasks(tasks(query(INVITER)));
    assertEquals(4, mine.tasks().size());
    assertEquals("COMMUNITY_POST", mine.tasks().get(3).taskCode());
    assertEquals("NOT_OPEN", mine.tasks().get(3).status());
    assertEquals(3, mine.rewards().size());
    assertEquals(0, invite(mine).claimableCount());

    ApiException wrongSubject = assertThrows(ApiException.class,
        () -> queries.tasks(tasks(new QueryContext("t", OperatorType.PLATFORM_OPERATOR, "5"))));
    assertEquals(CommonApiCodes.FORBIDDEN, wrongSubject.code());
  }

  // ------------------------------------------------------------------ helpers

  private static SignInCommand signIn(long user, String requestId) {
    return new SignInCommand(command(user, requestId));
  }

  private static ClaimRewardCommand claim(long user, String requestId, String claimType) {
    return new ClaimRewardCommand(claimType, command(user, requestId));
  }

  private static BindReferralCommand binding(long invitee, String requestId, String code) {
    return new BindReferralCommand(code, command(invitee, requestId));
  }

  private static com.petplatform.points.api.dto.PointsTaskTypes.UpdateConfigCommand config(
      String requestId, long daily, long streak, long perHead, long cap, long milestone,
      long firstOrder, long profile) {
    return new com.petplatform.points.api.dto.PointsTaskTypes.UpdateConfigCommand(
        daily, streak, perHead, cap, milestone, firstOrder, profile,
        new CommandContext(requestId, "t", OperatorType.PLATFORM_OPERATOR,
            String.valueOf(OPERATOR), "ADMIN_WEB"));
  }

  private static CommandContext command(long user, String requestId) {
    return new CommandContext(requestId, "t", OperatorType.USER,
        String.valueOf(user), "C_MINIAPP");
  }

  private static QueryContext query(long user) {
    return new QueryContext("t", OperatorType.USER, String.valueOf(user));
  }

  private static com.petplatform.points.api.query.PointsTaskQueryApi.SignInStatusQuery status(
      QueryContext context) {
    return new com.petplatform.points.api.query.PointsTaskQueryApi.SignInStatusQuery(context);
  }

  private static com.petplatform.points.api.query.PointsTaskQueryApi.TasksQuery tasks(
      QueryContext context) {
    return new com.petplatform.points.api.query.PointsTaskQueryApi.TasksQuery(context);
  }

  private static com.petplatform.points.api.dto.PointsTaskTypes.RewardRow reward(
      TasksView view, String code) {
    return view.rewards().stream()
        .filter(row -> row.rewardCode().equals(code)).findFirst().orElseThrow();
  }

  private static com.petplatform.points.api.dto.PointsTaskTypes.InviteOverview invite(
      TasksView view) {
    return view.invite();
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }
}
