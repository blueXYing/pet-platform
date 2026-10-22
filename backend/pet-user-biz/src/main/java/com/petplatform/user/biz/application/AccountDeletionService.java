package com.petplatform.user.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.user.biz.infrastructure.persistence.CommandIdempotencyStore;
import com.petplatform.user.biz.infrastructure.persistence.SessionControl;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import com.petplatform.user.biz.infrastructure.persistence.UserDeletionStore;
import com.petplatform.user.biz.infrastructure.persistence.mapper.UserDeletionMapper.UserDeletionRequestRow;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 66 account-deletion kernel (PRD 5.1.29; CCR-USR-DELETION-001 v0.2 cooling period,
 * user ruling 2026-10-19 "继续扩展CCR"). The precheck composes cross-domain leaf facts
 * (order blocking display statuses, open aftersale cases, OWNER store scopes) without
 * deriving any display state locally. The deletion write no longer flips the account: it
 * creates the cooling-period request (PENDING, at most one per user, effectiveAt =
 * now + configurable cooldown days, default 7), schedules the durable SQL13 task
 * ACCOUNT_DELETION_EXEC at effectiveAt inside the same transaction, and the account stays
 * ACTIVE throughout the cooling window — login keeps working and the revocation route CASes
 * the request row PENDING → CANCELED. At expiry the durable task re-locks the request row,
 * re-runs the precheck and only then flips user_account to the existing CANCELED word
 * (login refusal / session death stay the auth slice's existing semantics); a blocker or a
 * frozen account at expiry auto-cancels the request with the blocker snapshot — never a
 * partial deletion. Facts unavailable is a 503 fail-closed: nothing ever reads as "nothing
 * blocks". All business data is retained (zero deletion/anonymization — CCR §2).
 */
public final class AccountDeletionService {

    public static final String NAMESPACE = "account.deletion";
    public static final String CANCEL_NAMESPACE = "account.deletion.cancel";
    /** PRD 5.1.29 blocker codes, fixed order; the wire vocabulary of the precheck face. */
    private static final List<Blocker> BLOCKER_CATALOG = List.of(
            new Blocker("USER_ORDERS_UNSETTLED", "存在未完结订单",
                    "您有待支付、待确认、待服务、退款确认中或退款中的订单，完结后才能注销"),
            new Blocker("USER_AFTERSALES_OPEN", "存在处理中售后工单",
                    "您有处理中的售后工单，完结后才能注销"),
            new Blocker("OWNER_STORE_ORDERS_UNSETTLED", "名下商家存在未完结订单",
                    "您名下商家的门店仍有未完结订单，完结后才能注销"),
            new Blocker("OWNER_STORE_AFTERSALES_OPEN", "名下商家存在处理中售后工单",
                    "您名下商家的门店仍有处理中的售后工单，完结后才能注销"));
    /** OWNER membership enumeration cap (20 pages x 50 = 1000 stores) — V1 safety valve. */
    private static final int MEMBERSHIP_PAGE_LIMIT = 20;
    static final int COOLDOWN_DAYS_DEFAULT = 7;
    private static final ObjectMapper CODEC = new ObjectMapper();

    public record Blocker(String code, String label, String detail) {}

    public record PrecheckView(boolean blocked, List<Blocker> blockers) {}

    /** v0.2 receipt: the request enters the cooling period; the account stays ACTIVE. */
    public record DeletionReceipt(String userId, String status, String requestedAt,
                                  String effectiveAt, int cooldownDays) {}

    public record DeletionOutcome(DeletionReceipt receipt, boolean replayed) {}

    public record DeletionStatusView(String status, String requestedAt, String effectiveAt,
                                     int cooldownDays) {}

    public record CancelReceipt(String userId, String status, String canceledAt) {}

    public record CancelOutcome(CancelReceipt receipt, boolean replayed) {}

    public record DeleteAccountCommand(CommandContext context, boolean acknowledged) {}

    public record CancelDeletionCommand(CommandContext context) {}

    /** Due-task execution outcomes (the task registration maps them to task results). */
    public enum DueExecution {
        EXECUTED, ALREADY_RESOLVED, AUTO_CANCELED_BLOCKED, AUTO_CANCELED_FROZEN
    }

    private final UserAuthStore auth;
    private final UserDeletionStore deletions;
    private final CommandIdempotencyStore idempotency;
    private final OrderDeletionFactsApi orderFacts;
    private final AfterSaleDeletionFactsApi aftersaleFacts;
    private final MerchantAdmissionQueryApi memberships;
    private final SnowflakeIdGenerator ids;
    private final java.time.Clock clock;
    private final SessionControl sessionControl;
    private final TransactionTemplate execution;
    private final int cooldownDays;
    private final JdbcAsyncTaskSubmitter tasks;

    public AccountDeletionService(DataSource dataSource, SnowflakeIdGenerator ids,
                                  OrderDeletionFactsApi orderFacts,
                                  AfterSaleDeletionFactsApi aftersaleFacts,
                                  MerchantAdmissionQueryApi memberships) {
        this(dataSource, ids, orderFacts, aftersaleFacts, memberships, COOLDOWN_DAYS_DEFAULT,
                java.time.Clock.systemUTC());
    }

    public AccountDeletionService(DataSource dataSource, SnowflakeIdGenerator ids,
                                  OrderDeletionFactsApi orderFacts,
                                  AfterSaleDeletionFactsApi aftersaleFacts,
                                  MerchantAdmissionQueryApi memberships, int cooldownDays,
                                  java.time.Clock clock) {
        this.auth = new UserAuthStore(dataSource);
        this.deletions = new UserDeletionStore(dataSource);
        this.idempotency = new CommandIdempotencyStore(dataSource, ids);
        this.orderFacts = Objects.requireNonNull(orderFacts, "order deletion facts port is required");
        this.aftersaleFacts = Objects.requireNonNull(aftersaleFacts, "aftersale deletion facts port is required");
        this.memberships = Objects.requireNonNull(memberships, "merchant membership port is required");
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
        this.clock = Objects.requireNonNull(clock);
        if (cooldownDays < 1 || cooldownDays > 30) {
            throw new IllegalArgumentException("Deletion cooldown days must be within 1..30");
        }
        this.cooldownDays = cooldownDays;
        this.sessionControl = new SessionControl(dataSource);
        this.tasks = new JdbcAsyncTaskSubmitter(dataSource, ids);
        this.execution = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        execution.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        execution.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        execution.setTimeout(10);
    }

    /** §3 precheck projection: PRD-named blockers in fixed catalog order, fail-closed ports. */
    public PrecheckView precheck(String ownerUserId) {
        long userId = PetService.numericId(ownerUserId, "userId");
        return precheck(userId, ownerUserId);
    }

    private PrecheckView precheck(long userId, String ownerUserId) {
        List<Blocker> blockers = new ArrayList<>();
        List<String> ownerStores = ownerStoreIds(userId);
        if (orderFacts.userHasBlockingOrder(new OrderDeletionFactsApi.UserDeletionFactQuery(ownerUserId))) {
            blockers.add(BLOCKER_CATALOG.get(0));
        }
        if (aftersaleFacts.userHasOpenCase(new AfterSaleDeletionFactsApi.UserDeletionFactQuery(ownerUserId))) {
            blockers.add(BLOCKER_CATALOG.get(1));
        }
        if (!ownerStores.isEmpty()) {
            var storeQuery = new OrderDeletionFactsApi.StoreDeletionFactQuery(ownerStores);
            if (orderFacts.storesHaveBlockingOrder(storeQuery)) blockers.add(BLOCKER_CATALOG.get(2));
            var caseQuery = new AfterSaleDeletionFactsApi.StoreDeletionFactQuery(ownerStores);
            if (aftersaleFacts.storesHaveOpenCase(caseQuery)) blockers.add(BLOCKER_CATALOG.get(3));
        }
        return new PrecheckView(!blockers.isEmpty(), List.copyOf(blockers));
    }

    /**
     * §4 deletion write (v0.2): idempotent, row-locked; opens the cooling period instead of
     * flipping the account — request row PENDING + the durable due task land in one commit.
     */
    public DeletionOutcome deleteAccount(DeleteAccountCommand command) {
        CommandContext context = trustedUserContext(command.context());
        if (!command.acknowledged()) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
        long userId = actorUserId(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("acknowledged", true);
        String requestKey = NAMESPACE + "|USER|" + context.operatorId() + "|USER_SELF|" + context.requestId();
        CanonicalParams.Canonical canonical = CanonicalParams.of(params);
        Optional<String> replay;
        try {
            replay = idempotency.admit(requestKey, canonical);
        } catch (CommandIdempotencyStore.ParamsConflict conflict) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "相同 requestId 对应不同参数");
        }
        if (replay.isPresent()) {
            return new DeletionOutcome(deserialize(replay.orElseThrow()), true);
        }
        DeletionReceipt receipt = execution.execute(status -> {
            sessionControl.applyExecutionDefaults();
            idempotency.lockForExecution(requestKey);
            UserAuthStore.AccountRow account = auth.findAccount(userId, true);
            if (account == null) throw unauthorized();
            if ("CANCELED".equals(account.status())) {
                throw new ApiException(CommonApiCodes.CONFLICT, "账号已注销");
            }
            if ("FROZEN".equals(account.status())) throw frozen();
            // The account row lock serializes concurrent requests: the second waiter re-reads
            // the first transaction's committed PENDING row and answers 409 instead.
            if (deletions.work(mapper -> mapper.findPendingByUser(userId)) != null) {
                throw new ApiException(CommonApiCodes.CONFLICT, "已有处理中的注销申请");
            }
            PrecheckView precheck = precheck(userId, context.operatorId());
            if (precheck.blocked()) {
                throw new ApiException("DELETION_BLOCKED", precheck.blockers().get(0).label() + "，暂时无法注销");
            }
            long requestId = freshId();
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    .withNano((clock.instant().getNano() / 1_000_000) * 1_000_000);
            LocalDateTime effectiveAt = now.plusDays(cooldownDays);
            deletions.work(mapper -> mapper.insertDeletionRequest(requestId, userId,
                    context.requestId(), true, "PENDING", null, cooldownDays, effectiveAt));
            tasks.enqueueAt(AccountDeletionTaskSpec.key(requestId), "USER",
                    AccountDeletionTaskSpec.TYPE, "ACCOUNT_DELETION", requestId, 0L,
                    AccountDeletionTaskSpec.payload(requestId, userId, effectiveAt), 8,
                    AccountDeletionTaskSpec.RETRY_POLICY, effectiveAt.atOffset(ZoneOffset.UTC));
            DeletionReceipt accepted = new DeletionReceipt(context.operatorId(), "PENDING",
                    iso(now), iso(effectiveAt), cooldownDays);
            idempotency.succeed(requestKey, serialize(accepted));
            return accepted;
        });
        return new DeletionOutcome(receipt, false);
    }

    /** Cooling-period read: NONE or the single PENDING request with its deadline facts. */
    public DeletionStatusView deletionStatus(String ownerUserId) {
        long userId = PetService.numericId(ownerUserId, "userId");
        UserDeletionRequestRow row = deletions.work(mapper -> mapper.findPendingByUser(userId));
        if (row == null) return new DeletionStatusView("NONE", null, null, 0);
        return new DeletionStatusView("PENDING", iso(row.createdAt), iso(row.effectiveAt),
                row.cooldownDays == null ? cooldownDays : row.cooldownDays);
    }

    /**
     * §4.2 revocation: CAS PENDING → CANCELED on the user's cooling-period row; the account
     * never left ACTIVE during the window, so no account flip exists on this path.
     */
    public CancelOutcome cancelDeletion(CancelDeletionCommand command) {
        CommandContext context = trustedUserContext(command.context());
        long userId = actorUserId(context);
        String requestKey = CANCEL_NAMESPACE + "|USER|" + context.operatorId() + "|USER_SELF|"
                + context.requestId();
        CanonicalParams.Canonical canonical = CanonicalParams.of(Map.of("cancel", true));
        Optional<String> replay;
        try {
            replay = idempotency.admit(requestKey, canonical);
        } catch (CommandIdempotencyStore.ParamsConflict conflict) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "相同 requestId 对应不同参数");
        }
        if (replay.isPresent()) {
            return new CancelOutcome(deserializeCancel(replay.orElseThrow()), true);
        }
        CancelReceipt receipt = execution.execute(status -> {
            sessionControl.applyExecutionDefaults();
            idempotency.lockForExecution(requestKey);
            UserAuthStore.AccountRow account = auth.findAccount(userId, true);
            if (account == null) throw unauthorized();
            if ("CANCELED".equals(account.status())) {
                throw new ApiException(CommonApiCodes.CONFLICT, "账号已注销");
            }
            UserDeletionRequestRow row = deletions.work(mapper -> mapper.findPendingByUser(userId));
            if (row == null) {
                throw new ApiException(CommonApiCodes.CONFLICT, "没有处理中的注销申请");
            }
            if (deletions.work(mapper -> mapper.cancelPending(row.id, context.requestId())) != 1) {
                throw new ApiException(CommonApiCodes.CONFLICT, "没有处理中的注销申请");
            }
            CancelReceipt canceled = new CancelReceipt(context.operatorId(), "CANCELED", isoNow());
            idempotency.succeed(requestKey, serializeCancel(canceled));
            return canceled;
        });
        return new CancelOutcome(receipt, false);
    }

    /**
     * Due-task execution (durable ACCOUNT_DELETION_EXEC): row-locked re-verification, then
     * the guarded CANCELED flip plus the ACCEPTED completion in one commit — or the
     * auto-cancel with the blocker snapshot. Idempotent through the request-row state.
     */
    public DueExecution executeDueDeletion(long requestRowId) {
        return execution.execute(status -> {
            sessionControl.applyExecutionDefaults();
            UserDeletionRequestRow row = deletions.work(mapper -> mapper.selectByIdForUpdate(requestRowId));
            if (row == null || !"PENDING".equals(row.status)) return DueExecution.ALREADY_RESOLVED;
            long userId = row.userId;
            UserAuthStore.AccountRow account = auth.findAccount(userId, true);
            if (account != null && "FROZEN".equals(account.status())) {
                deletions.work(mapper -> mapper.autoCancelPending(row.id,
                        blockerSnapshotJson(List.of("ACCOUNT_FROZEN"))));
                return DueExecution.AUTO_CANCELED_FROZEN;
            }
            PrecheckView precheck = precheck(userId, Long.toUnsignedString(userId));
            if (precheck.blocked()) {
                deletions.work(mapper -> mapper.autoCancelPending(row.id,
                        blockerSnapshotJson(precheck.blockers().stream().map(Blocker::code).toList())));
                return DueExecution.AUTO_CANCELED_BLOCKED;
            }
            String expectedStatus = account == null ? "ACTIVE" : account.status();
            if (deletions.work(mapper -> mapper.cancelAccount(userId, expectedStatus)) != 1) {
                throw new ApiException(CommonApiCodes.CONFLICT, "账号状态冲突，注销执行已中止");
            }
            if (deletions.work(mapper -> mapper.markExecuted(row.id)) != 1) {
                throw new IllegalStateException("Deletion request lost the execution race; rolled back");
            }
            return DueExecution.EXECUTED;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static String blockerSnapshotJson(List<String> codes) {
        try {
            return CODEC.writeValueAsString(Map.of("codes", codes, "phase", "DUE_EXECUTION"));
        } catch (Exception failure) {
            throw new IllegalStateException("Deletion blocker snapshot serialization failed", failure);
        }
    }

    /** OWNER store ids through the frozen membership surface (fail-closed on port trouble). */
    private List<String> ownerStoreIds(long userId) {
        List<String> storeIds = new ArrayList<>();
        for (int page = 1; page <= MEMBERSHIP_PAGE_LIMIT; page++) {
            var membershipPage = memberships.listMemberships(new MerchantMembershipQuery(
                    page, 50, new QueryContext("deletion-precheck", OperatorType.USER,
                            Long.toUnsignedString(userId))));
            membershipPage.items().stream()
                    .filter(item -> "OWNER".equals(item.membershipKind()))
                    .forEach(item -> storeIds.add(item.storeId()));
            if (storeIds.size() >= membershipPage.total() || membershipPage.items().isEmpty()) break;
        }
        return List.copyOf(storeIds);
    }

    private static CommandContext trustedUserContext(CommandContext context) {
        CommandContext checked = PublicContractChecks.requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.USER) {
            throw new IllegalArgumentException("Account deletion requires a USER operator");
        }
        return checked;
    }

    private static long actorUserId(CommandContext context) {
        return PetService.numericId(context.operatorId(), "operatorId");
    }

    private long freshId() {
        long id = ids.nextId();
        if (id <= 0) throw new IllegalStateException("Invalid ID from provider");
        return id;
    }

    private static ApiException unauthorized() {
        return new ApiException(CommonApiCodes.UNAUTHORIZED, "验证失败，请重试");
    }

    private static ApiException frozen() {
        return new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
    }

    private static String serialize(DeletionReceipt receipt) {
        try {
            return CODEC.writeValueAsString(receipt);
        } catch (Exception failure) {
            throw new IllegalStateException("Deletion receipt serialization failed", failure);
        }
    }

    private static DeletionReceipt deserialize(String raw) {
        try {
            return CODEC.readValue(raw, DeletionReceipt.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Deletion receipt deserialization failed", failure);
        }
    }

    private static String serializeCancel(CancelReceipt receipt) {
        try {
            return CODEC.writeValueAsString(receipt);
        } catch (Exception failure) {
            throw new IllegalStateException("Deletion receipt serialization failed", failure);
        }
    }

    private static CancelReceipt deserializeCancel(String raw) {
        try {
            return CODEC.readValue(raw, CancelReceipt.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Deletion receipt deserialization failed", failure);
        }
    }

    private static String iso(LocalDateTime value) {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(value.toInstant(ZoneOffset.UTC));
    }

    private String isoNow() {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(clock.instant());
    }
}
