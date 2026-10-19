package com.petplatform.user.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.user.biz.infrastructure.persistence.CommandIdempotencyStore;
import com.petplatform.user.biz.infrastructure.persistence.SessionControl;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import com.petplatform.user.biz.infrastructure.persistence.UserDeletionStore;
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
 * Contract 66 account-deletion kernel (PRD 5.1.29; CCR-USR-DELETION-001 minimum): the
 * precheck composes cross-domain leaf facts (order blocking display statuses, open aftersale
 * cases, OWNER store scopes) without deriving any display state locally, and the deletion
 * write is a supplement-23 idempotent command that re-runs the precheck inside the row-locked
 * execution transaction, appends the immutable {@code user_deletion_request} audit row and
 * flips {@code user_account.status} to the existing CANCELED word — login refusal and session
 * death stay the auth slice's existing semantics (zero new code there). Facts unavailable is
 * a 503 fail-closed: nothing ever reads as "nothing blocks".
 */
public final class AccountDeletionService {

    public static final String NAMESPACE = "account.deletion";
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
    private static final ObjectMapper CODEC = new ObjectMapper();

    public record Blocker(String code, String label, String detail) {}

    public record PrecheckView(boolean blocked, List<Blocker> blockers) {}

    public record DeletionReceipt(String userId, String status, String deletedAt) {}

    public record DeletionOutcome(DeletionReceipt receipt, boolean replayed) {}

    public record DeleteAccountCommand(CommandContext context, boolean acknowledged) {}

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

    public AccountDeletionService(DataSource dataSource, SnowflakeIdGenerator ids,
                                  OrderDeletionFactsApi orderFacts,
                                  AfterSaleDeletionFactsApi aftersaleFacts,
                                  MerchantAdmissionQueryApi memberships) {
        this(dataSource, ids, orderFacts, aftersaleFacts, memberships, java.time.Clock.systemUTC());
    }

    AccountDeletionService(DataSource dataSource, SnowflakeIdGenerator ids,
                           OrderDeletionFactsApi orderFacts,
                           AfterSaleDeletionFactsApi aftersaleFacts,
                           MerchantAdmissionQueryApi memberships, java.time.Clock clock) {
        this.auth = new UserAuthStore(dataSource);
        this.deletions = new UserDeletionStore(dataSource);
        this.idempotency = new CommandIdempotencyStore(dataSource, ids);
        this.orderFacts = Objects.requireNonNull(orderFacts, "order deletion facts port is required");
        this.aftersaleFacts = Objects.requireNonNull(aftersaleFacts, "aftersale deletion facts port is required");
        this.memberships = Objects.requireNonNull(memberships, "merchant membership port is required");
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
        this.clock = Objects.requireNonNull(clock);
        this.sessionControl = new SessionControl(dataSource);
        this.execution = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        execution.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        execution.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        execution.setTimeout(10);
    }

    /** §3 precheck projection: PRD-named blockers in fixed catalog order, fail-closed ports. */
    public PrecheckView precheck(String ownerUserId) {
        long userId = PetService.numericId(ownerUserId, "userId");
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

    /** §4 deletion write: idempotent, row-locked, audit-row-plus-CAS-flip in one transaction. */
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
            PrecheckView precheck = precheck(context.operatorId());
            if (precheck.blocked()) {
                throw new ApiException("DELETION_BLOCKED", precheck.blockers().get(0).label() + "，暂时无法注销");
            }
            long requestId = freshId();
            deletions.work(mapper -> mapper.insertDeletionRequest(requestId, userId,
                    context.requestId(), true, "ACCEPTED", null));
            if (deletions.work(mapper -> mapper.cancelAccount(userId, account.status())) != 1) {
                throw new IllegalStateException("Account flip lost the race; transaction rolled back");
            }
            DeletionReceipt accepted = new DeletionReceipt(context.operatorId(), "CANCELED", isoNow());
            idempotency.succeed(requestKey, serialize(accepted));
            return accepted;
        });
        return new DeletionOutcome(receipt, false);
    }

    // ------------------------------------------------------------------ helpers

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

    private String isoNow() {
        return java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(clock.instant());
    }
}
