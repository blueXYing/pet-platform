package com.petplatform.merchant.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.command.CancelStaffMemberInvitationCommand;
import com.petplatform.merchant.api.command.ConfirmStaffMemberInvitationCommand;
import com.petplatform.merchant.api.command.GrantStaffMemberActionsCommand;
import com.petplatform.merchant.api.command.InviteStaffMemberCommand;
import com.petplatform.merchant.api.command.StaffMemberLifecycleCommand;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationCommandResult;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationDTO;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationDetailDTO;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationPageDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMemberCommandResult;
import com.petplatform.merchant.api.dto.MerchantStaffMemberDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMemberPageDTO;
import com.petplatform.merchant.api.query.MyStaffInvitationQuery;
import com.petplatform.merchant.api.query.StaffInvitationManagementQuery;
import com.petplatform.merchant.api.query.StaffMemberManagementQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStaffMemberStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantAgreementDocumentEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantMemberGrantScopeEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantMemberInvitationEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantMemberRowEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffScopeEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreFactEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAgreementMapper;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantStaffMemberMapper;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Contract 54 binding writes (binding CCR §5: D1-a invite-confirm, D2 verify-only catalog).
 * OWNER commands re-verify the 35号 admission on every execution and every replay; the
 * employee confirm re-proves phone equality inside its transaction and creates member +
 * store grant + actions atomically. D3 stays pending: cancel and revoke-store are terminal,
 * no member REVOKED exists and revoked grants are never revived. Guarded commands take the
 * shared store guard before locking member/grant rows, so the contract-52 action gate stays
 * fail-closed against in-flight verification (D4 approved mechanism).
 */
public final class MerchantStaffMemberService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PHONE = Pattern.compile("1[0-9]{10}");
    /** D2 approved minimal catalog; extension requires a contract-54 revision. */
    private static final Set<String> ACTION_CATALOG = Set.of("merchant.order.verify");
    private static final Set<String> MERCHANT_STATUSES =
            Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
    private static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "OFFLINE", "FROZEN");
    private static final Set<String> INVITATION_STATUSES = Set.of("INVITED", "CANCELED", "CONFIRMED");
    private static final Set<String> MEMBER_STATUSES = Set.of("ENABLED", "DISABLED", "REVOKED");
    private static final Set<String> GRANT_STATUSES = Set.of("ENABLED", "REVOKED");
    private static final Set<String> APPLICATION = Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");
    /** NTF slice: invitation/member lifecycle station-notification events (Event08 registration). */
    static final String INVITATION_EVENT_TYPE = "MerchantStaffInvitationLifecycleEvent.v1";
    static final String MEMBER_EVENT_TYPE = "MerchantStaffMemberLifecycleEvent.v1";
    /** NTF grant slice (2026-10-07 ruling): grant/revoke station-notification event. */
    static final String GRANT_EVENT_TYPE = "MerchantStaffGrantLifecycleEvent.v1";
    private static final String INVITATION_AGGREGATE = "MERCHANT_MEMBER_INVITATION";
    private static final String MEMBER_AGGREGATE = "MERCHANT_MEMBER";
    private static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");

    private record Intent(String action, long merchantId, long storeId, long memberId,
                          long invitationId, String phone, String memberName, List<String> actions,
                          Long expectedVersion, long actorId, String operatorId, String requestId,
                          String traceId, byte[] key, MerchantCanonicalParams.Canonical canonical) {}

    private record Principal(long actorId, String operatorId, String requestId, String traceId) {}

    @FunctionalInterface
    private interface MemberWork {
        MerchantStaffMemberCommandResult apply(MerchantStaffMemberMapper mapper,
                MerchantAgreementMapper agreements);
    }

    @FunctionalInterface
    private interface InvitationWork {
        MerchantStaffInvitationCommandResult apply(MerchantStaffMemberMapper mapper,
                MerchantAgreementMapper agreements);
    }

    private final MerchantStaffMemberStore store;
    private final ApplicationReviewFactsReader applicationFacts;
    private final ApplicationValidationPorts.ProtectedValuePort protection;
    private final StaffLoginPhonePort loginPhones;
    private final ScheduleCapacityGuardApi guard;
    private final Clock clock;
    /**
     * NTF slice: nullable Transactional Outbox producer. Null (outbox off) keeps every
     * contract-54 command byte-identical to the pre-notification behavior; when present the
     * lifecycle events append inside the same command transaction, so rollback leaves no event
     * and a 23号 replay never emits a second one.
     */
    private final IntegrationEventPublisher events;

    public MerchantStaffMemberService(MerchantStaffMemberStore store,
            ApplicationReviewFactsReader applicationFacts,
            ApplicationValidationPorts.ProtectedValuePort protection,
            StaffLoginPhonePort loginPhones, ScheduleCapacityGuardApi guard, Clock clock) {
        this(store, applicationFacts, protection, loginPhones, guard, clock, null);
    }

    public MerchantStaffMemberService(MerchantStaffMemberStore store,
            ApplicationReviewFactsReader applicationFacts,
            ApplicationValidationPorts.ProtectedValuePort protection,
            StaffLoginPhonePort loginPhones, ScheduleCapacityGuardApi guard, Clock clock,
            IntegrationEventPublisher events) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.applicationFacts = Objects.requireNonNull(applicationFacts, "applicationFacts is required");
        this.protection = Objects.requireNonNull(protection, "protection is required");
        this.loginPhones = Objects.requireNonNull(loginPhones, "loginPhones is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.events = events;
    }

    // ------------------------------------------------------------------ owner: invitations

    public MerchantStaffInvitationCommandResult inviteMember(InviteStaffMemberCommand command) {
        if (command == null) invalid();
        List<String> actions = catalogActions(command.actions());
        if (command.phone() == null || !PHONE.matcher(command.phone()).matches()) invalid();
        requireName(command.memberName());
        Intent intent = ownerIntent("merchant.staff-member.invite", command.merchantId(),
                command.storeId(), 0, 0, command.phone(), command.memberName(), actions, null,
                command.context());
        return runInvitation(intent, (mapper, agreements) -> inviteExecution(mapper, intent));
    }

    public MerchantStaffInvitationCommandResult cancelInvitation(
            CancelStaffMemberInvitationCommand command) {
        if (command == null) invalid();
        long invitationId = target(command.invitationId(), "invitationId");
        Intent intent = ownerIntent("merchant.staff-member.invitation.cancel", command.merchantId(),
                command.storeId(), 0, invitationId, null, null, null,
                version(command.expectedVersion()), command.context());
        return runInvitation(intent, (mapper, agreements) -> cancelExecution(mapper, intent));
    }

    private MerchantStaffInvitationCommandResult runInvitation(Intent intent, InvitationWork work) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("invitation command admission requires no outer transaction");
        store.read((mapper, agreements) -> {
            authorizeOwner(mapper, agreements, intent, false);
            return null;
        });
        admitWithRecovery(intent);
        try {
            return store.execute((mapper, agreements) -> {
                MerchantAgreementStore.Binding binding =
                        MerchantAgreementStore.requireBinding(agreements, intent.key());
                MerchantAgreementStore.requireSameParams(binding, intent.canonical());
                // 23号: authority is re-verified before a replayed receipt is served.
                authorizeOwner(mapper, agreements, intent, true);
                if ("SUCCEEDED".equals(binding.status()))
                    return invitationReplay(mapper, binding, intent);
                MerchantStaffInvitationCommandResult result = work.apply(mapper, agreements);
                return result;
            });
        } catch (MerchantAgreementStore.CommitUnknown first) {
            try {
                return store.execute((mapper, agreements) -> {
                    MerchantAgreementStore.Binding binding =
                            MerchantAgreementStore.requireBinding(agreements, intent.key());
                    MerchantAgreementStore.requireSameParams(binding, intent.canonical());
                    authorizeOwner(mapper, agreements, intent, true);
                    if ("SUCCEEDED".equals(binding.status()))
                        return invitationReplay(mapper, binding, intent);
                    return work.apply(mapper, agreements);
                });
            } catch (MerchantAgreementStore.CommitUnknown stillUnknown) {
                unavailable("staff member commit result is unknown");
                return null;
            }
        }
    }

    private MerchantStaffInvitationCommandResult inviteExecution(MerchantStaffMemberMapper mapper,
            Intent intent) {
        MerchantMemberInvitationEntity pending =
                mapper.lockPendingInvitation(intent.merchantId(), intent.phone());
        if (pending != null) conflict("an invitation for this phone is already pending");
        long invitationId = store.nextId();
        if (mapper.insertInvitation(invitationId, intent.merchantId(), intent.storeId(),
                intent.phone(), intent.memberName(), intent.actorId(), now()) != 1)
            unavailable("invitation insert failed");
        for (String action : intent.actions()) {
            if (mapper.insertInvitationAction(store.nextId(), invitationId, action, now()) != 1)
                unavailable("invitation action insert failed");
        }
        if (mapper.insertAudit(store.nextId(), intent.actorId(), intent.action(), intent.merchantId(),
                intent.storeId(), null, invitationId, null, "INVITED", null, 0L, intent.key(),
                requestIdBytes(intent), intent.traceId(), now()) != 1)
            unavailable("member audit insert failed");
        MerchantMemberInvitationEntity created = readInvitation(mapper, invitationId);
        publishInvitationLifecycle(created, "INVITED", null, null, intent.traceId());
        MerchantStaffInvitationDTO receipt = projectInvitation(created);
        markSucceeded(mapper, intent, receipt);
        return new MerchantStaffInvitationCommandResult(receipt, true, false);
    }

    private MerchantStaffInvitationCommandResult cancelExecution(MerchantStaffMemberMapper mapper,
            Intent intent) {
        MerchantMemberInvitationEntity invitation = mapper.lockInvitation(intent.invitationId());
        if (invitation == null || invitation.getMerchantId() != intent.merchantId
                || invitation.getStoreId() != intent.storeId) notFound();
        if (!"INVITED".equals(invitation.getStatus())) conflict("invitation is not pending");
        if (invitation.getVersion() != intent.expectedVersion()) conflict("invitation version changed");
        if (mapper.cancelInvitation(intent.invitationId(), intent.merchantId(),
                intent.expectedVersion(), now()) != 1)
            conflict("invitation write lost a concurrent state race");
        if (mapper.insertAudit(store.nextId(), intent.actorId(), intent.action(), intent.merchantId(),
                intent.storeId(), null, intent.invitationId(), "INVITED", "CANCELED",
                invitation.getVersion(), invitation.getVersion() + 1, intent.key(),
                requestIdBytes(intent), intent.traceId(), now()) != 1)
            unavailable("member audit insert failed");
        MerchantMemberInvitationEntity canceled = readInvitation(mapper, intent.invitationId());
        publishInvitationLifecycle(canceled, "CANCELED", null, null, intent.traceId());
        MerchantStaffInvitationDTO receipt = projectInvitation(canceled);
        markSucceeded(mapper, intent, receipt);
        return new MerchantStaffInvitationCommandResult(receipt, true, false);
    }

    private MerchantStaffInvitationCommandResult invitationReplay(MerchantStaffMemberMapper mapper,
            MerchantAgreementStore.Binding binding, Intent intent) {
        MerchantStaffInvitationDTO receipt = readReceipt(binding.receiptJson());
        if (intent.invitationId() != 0 && !receipt.invitationId()
                .equals(Long.toUnsignedString(intent.invitationId())))
            unavailable("invitation receipt scope is damaged");
        long invitationId = intent.invitationId() != 0
                ? intent.invitationId() : target(receipt.invitationId(), "invitationId");
        MerchantMemberInvitationEntity current =
                mapper.selectInvitationForOwner(invitationId, intent.merchantId(), intent.storeId());
        if (current == null) notFound();
        validateInvitationFacts(current);
        return new MerchantStaffInvitationCommandResult(projectInvitation(current), false, true);
    }

    // ------------------------------------------------------------------ owner: members

    public MerchantStaffMemberCommandResult disableMember(StaffMemberLifecycleCommand command) {
        return memberWrite(command, "merchant.staff-member.disable", "DISABLED");
    }

    public MerchantStaffMemberCommandResult enableMember(StaffMemberLifecycleCommand command) {
        return memberWrite(command, "merchant.staff-member.enable", "ENABLED");
    }

    public MerchantStaffMemberCommandResult revokeStoreGrant(StaffMemberLifecycleCommand command) {
        if (command == null) invalid();
        Intent intent = lifecycleIntent("merchant.staff-member.revoke-store", command);
        return runMember(intent, (mapper, agreements) -> revokeStoreExecution(mapper, intent));
    }

    public MerchantStaffMemberCommandResult grantActions(GrantStaffMemberActionsCommand command) {
        if (command == null) invalid();
        List<String> actions = catalogActions(command.actions());
        if (command.merchantId() == null || command.storeId() == null || command.memberId() == null)
            invalid();
        Intent intent = ownerIntent("merchant.staff-member.grant-actions", command.merchantId(),
                command.storeId(), target(command.memberId(), "memberId"), 0, null, null, actions,
                version(command.expectedVersion()), command.context());
        return runMember(intent, (mapper, agreements) -> grantActionsExecution(mapper, intent));
    }

    private MerchantStaffMemberCommandResult memberWrite(StaffMemberLifecycleCommand command,
            String action, String nextStatus) {
        if (command == null) invalid();
        Intent intent = lifecycleIntent(action, command);
        return runMember(intent, (mapper, agreements) -> switchStatusExecution(mapper, intent, nextStatus));
    }

    private Intent lifecycleIntent(String action, StaffMemberLifecycleCommand command) {
        if (command.merchantId() == null || command.storeId() == null || command.memberId() == null)
            invalid();
        return ownerIntent(action, command.merchantId(), command.storeId(),
                target(command.memberId(), "memberId"), 0, null, null, null,
                version(command.expectedVersion()), command.context());
    }

    private MerchantStaffMemberCommandResult runMember(Intent intent, MemberWork work) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("member command admission requires no outer transaction");
        store.read((mapper, agreements) -> {
            authorizeOwner(mapper, agreements, intent, false);
            return null;
        });
        admitWithRecovery(intent);
        try {
            return executeMember(intent, work);
        } catch (MerchantAgreementStore.CommitUnknown first) {
            try {
                return executeMember(intent, work);
            } catch (MerchantAgreementStore.CommitUnknown stillUnknown) {
                unavailable("staff member commit result is unknown");
                return null;
            }
        }
    }

    private MerchantStaffMemberCommandResult executeMember(Intent intent, MemberWork work) {
        return store.execute((mapper, agreements) -> {
            MerchantAgreementStore.Binding binding =
                    MerchantAgreementStore.requireBinding(agreements, intent.key());
            MerchantAgreementStore.requireSameParams(binding, intent.canonical());
            // D4: the store guard is held before this command locks any member/grant row.
            guard.acquire(List.of(Long.toUnsignedString(intent.storeId())), query(intent));
            authorizeOwner(mapper, agreements, intent, true);
            if ("SUCCEEDED".equals(binding.status())) return memberReplay(mapper, binding, intent);
            return work.apply(mapper, agreements);
        });
    }

    private MerchantStaffMemberCommandResult switchStatusExecution(MerchantStaffMemberMapper mapper,
            Intent intent, String nextStatus) {
        MerchantMemberGrantScopeEntity scope = mapper.lockMemberScope(
                intent.merchantId(), intent.memberId(), intent.storeId());
        requireMemberScope(scope, intent.memberId());
        if (scope.getGrantId() == null) notFound();
        requireGrantUsable(scope);
        if (nextStatus.equals(scope.getMemberStatus())) conflict("member status already " + nextStatus);
        if (scope.getMemberVersion() != intent.expectedVersion()) conflict("member version changed");
        if (mapper.updateMemberStatus(intent.memberId(), intent.merchantId(), nextStatus,
                intent.expectedVersion(), now()) != 1)
            conflict("member write lost a concurrent version race");
        if (mapper.insertAudit(store.nextId(), intent.actorId(), intent.action(), intent.merchantId(),
                intent.storeId(), intent.memberId(), null, scope.getMemberStatus(), nextStatus,
                scope.getMemberVersion(), scope.getMemberVersion() + 1, intent.key(),
                requestIdBytes(intent), intent.traceId(), now()) != 1)
            unavailable("member audit insert failed");
        publishMemberLifecycle(intent, scope, nextStatus);
        return memberReceipt(mapper, intent);
    }

    private MerchantStaffMemberCommandResult revokeStoreExecution(MerchantStaffMemberMapper mapper,
            Intent intent) {
        MerchantMemberGrantScopeEntity scope = mapper.lockMemberScope(
                intent.merchantId(), intent.memberId(), intent.storeId());
        requireMemberScope(scope, intent.memberId());
        if (scope.getGrantId() == null) notFound();
        requireGrantUsable(scope);
        if (scope.getGrantVersion() != intent.expectedVersion()) conflict("store grant version changed");
        // NTF grant slice: capture the withdrawn set before the rows go away, so the member
        // notification carries the action summary of what was revoked.
        List<String> revokedActions = catalogCodes(mapper.listGrantActions(
                intent.memberId(), intent.storeId()));
        if (mapper.updateGrantStatus(intent.memberId(), intent.storeId(), "REVOKED",
                intent.expectedVersion(), now()) != 1)
            conflict("store grant write lost a concurrent version race");
        mapper.deleteGrantActions(intent.memberId(), intent.storeId());
        if (mapper.insertAudit(store.nextId(), intent.actorId(), intent.action(), intent.merchantId(),
                intent.storeId(), intent.memberId(), null, "ENABLED", "REVOKED",
                scope.getGrantVersion(), scope.getGrantVersion() + 1, intent.key(),
                requestIdBytes(intent), intent.traceId(), now()) != 1)
            unavailable("member audit insert failed");
        publishGrantLifecycle(intent, scope, "REVOKED", revokedActions);
        return memberReceipt(mapper, intent);
    }

    private MerchantStaffMemberCommandResult grantActionsExecution(MerchantStaffMemberMapper mapper,
            Intent intent) {
        MerchantMemberGrantScopeEntity scope = mapper.lockMemberScope(
                intent.merchantId(), intent.memberId(), intent.storeId());
        requireMemberScope(scope, intent.memberId());
        if (scope.getGrantId() == null) {
            // First store grant happens here: version 0, expectedVersion must be 0.
            if (intent.expectedVersion() != 0) conflict("store grant version changed");
            if (mapper.insertGrant(store.nextId(), intent.memberId(), intent.storeId(), now()) != 1)
                unavailable("store grant insert failed");
            insertActions(mapper, intent.memberId(), intent.storeId(), intent.actions());
            if (mapper.insertAudit(store.nextId(), intent.actorId(), intent.action(),
                    intent.merchantId(), intent.storeId(), intent.memberId(), null, null,
                    "ENABLED", null, 0L, intent.key(), requestIdBytes(intent), intent.traceId(),
                    now()) != 1) unavailable("member audit insert failed");
            publishGrantLifecycle(intent, scope, "GRANTED", intent.actions());
            return memberReceipt(mapper, intent);
        }
        requireGrantUsable(scope);
        if (scope.getGrantVersion() != intent.expectedVersion()) conflict("store grant version changed");
        // Action-set replacement is a grant change: the version bump rides the same statement
        // pattern as a revoke (52号 storage §2: grant version feeds authzVersion).
        if (mapper.updateGrantStatus(intent.memberId(), intent.storeId(), "ENABLED",
                intent.expectedVersion(), now()) != 1)
            conflict("store grant write lost a concurrent version race");
        mapper.deleteGrantActions(intent.memberId(), intent.storeId());
        insertActions(mapper, intent.memberId(), intent.storeId(), intent.actions());
        if (mapper.insertAudit(store.nextId(), intent.actorId(), intent.action(), intent.merchantId(),
                intent.storeId(), intent.memberId(), null, "ENABLED", "ENABLED",
                scope.getGrantVersion(), scope.getGrantVersion() + 1, intent.key(),
                requestIdBytes(intent), intent.traceId(), now()) != 1)
            unavailable("member audit insert failed");
        publishGrantLifecycle(intent, scope, "GRANTED", intent.actions());
        return memberReceipt(mapper, intent);
    }

    private MerchantStaffMemberCommandResult memberReplay(MerchantStaffMemberMapper mapper,
            MerchantAgreementStore.Binding binding, Intent intent) {
        MerchantStaffMemberDTO receipt = readMemberReceipt(binding.receiptJson());
        if (!receipt.merchantId().equals(Long.toUnsignedString(intent.merchantId()))
                || !receipt.storeId().equals(Long.toUnsignedString(intent.storeId()))
                || !receipt.memberId().equals(Long.toUnsignedString(intent.memberId())))
            unavailable("member receipt scope is damaged");
        MerchantMemberRowEntity current =
                mapper.readMemberRow(intent.merchantId(), intent.memberId(), intent.storeId());
        if (current == null || current.getGrantStatus() == null) notFound();
        return new MerchantStaffMemberCommandResult(projectMember(mapper, current), false, true);
    }

    // ------------------------------------------------------------------ employee confirm (D1-a)

    public MerchantStaffMemberCommandResult confirmInvitation(
            ConfirmStaffMemberInvitationCommand command) {
        if (command == null) invalid();
        long invitationId = target(command.invitationId(), "invitationId");
        Principal principal = principal(command.context());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("command", "merchant.staff-member.confirm");
        fields.put("invitationId", command.invitationId());
        MerchantCanonicalParams.Canonical canonical = MerchantCanonicalParams.of(fields);
        byte[] key = MerchantRequestKey.encode("merchant.staff-member.confirm", "USER",
                principal.operatorId(), "INVITATION:" + command.invitationId(), principal.requestId());
        Intent intent = new Intent("merchant.staff-member.confirm", 0, 0, 0, invitationId, null,
                null, null, null, principal.actorId(), principal.operatorId(), principal.requestId(),
                principal.traceId(), key, canonical);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("confirm admission requires no outer transaction");
        admitWithRecovery(intent);
        try {
            return executeConfirm(intent);
        } catch (MerchantAgreementStore.CommitUnknown first) {
            try {
                return executeConfirm(intent);
            } catch (MerchantAgreementStore.CommitUnknown stillUnknown) {
                unavailable("staff member commit result is unknown");
                return null;
            }
        }
    }

    private MerchantStaffMemberCommandResult executeConfirm(Intent intent) {
        return store.execute((mapper, agreements) -> {
            MerchantAgreementStore.Binding binding =
                    MerchantAgreementStore.requireBinding(agreements, intent.key());
            MerchantAgreementStore.requireSameParams(binding, intent.canonical());
            if ("SUCCEEDED".equals(binding.status())) return confirmReplay(mapper, binding, intent);
            return confirmExecution(mapper, agreements, intent);
        });
    }

    private MerchantStaffMemberCommandResult confirmExecution(MerchantStaffMemberMapper mapper,
            MerchantAgreementMapper agreements, Intent intent) {
        MerchantMemberInvitationEntity invitation = mapper.lockInvitation(intent.invitationId());
        if (invitation == null) notFound();
        validateInvitationFacts(invitation);
        if ("CONFIRMED".equals(invitation.getStatus())) conflict("invitation is already confirmed");
        if ("CANCELED".equals(invitation.getStatus())) conflict("invitation was canceled");
        // The invitation row is the single source of the target merchant/store scope.
        long merchantId = invitation.getMerchantId();
        // Anti-enumeration: a non-matching phone reads exactly like a missing invitation.
        if (!loginPhones.matchesSessionUserPhone(intent.actorId(), invitation.getPhone())) notFound();
        // D4: guard before any member/grant lock of this command.
        guard.acquire(List.of(Long.toUnsignedString(invitation.getStoreId())), query(intent));
        MerchantStaffScopeEntity merchant = mapper.lockMerchant(merchantId);
        if (merchant == null || merchant.getOwnerUserId() == null
                || !Objects.equals(merchant.getMerchantId(), merchantId)
                || !MERCHANT_STATUSES.contains(merchant.getMerchantStatus()))
            unavailable("merchant facts are damaged");
        if ("CANCELED".equals(merchant.getMerchantStatus())) conflict("merchant is canceled");
        if (intent.actorId() == merchant.getOwnerUserId())
            conflict("the owner account cannot be bound as a staff member");
        MerchantStoreFactEntity fact = mapper.selectStoreFact(merchantId, invitation.getStoreId());
        if (fact == null || !Objects.equals(fact.getStoreId(), invitation.getStoreId())
                || !Objects.equals(fact.getMerchantId(), merchantId)
                || !STORE_STATUSES.contains(fact.getStoreStatus()))
            unavailable("store facts are damaged");
        if (mapper.lockApplication(merchantId) == null)
            unavailable("application review facts are missing");
        ApplicationReviewFactsReader.Facts application = readApplicationFacts(merchantId);
        if (!"APPROVED".equals(application.applicationStatus()))
            conflict("merchant application is not approved");
        List<MerchantAgreementDocumentEntity> accepted =
                MerchantAgreementService.acceptedAgreements(agreements, merchantId, true);
        if (accepted.isEmpty()) conflict("merchant agreement is not signed");
        MerchantAgreementService.validateDocument(accepted.getFirst(), true);
        MerchantMemberGrantScopeEntity existing =
                mapper.lockMemberByUser(merchantId, intent.actorId());
        if (existing != null) conflict("this account is already bound to the merchant");
        List<String> actions = catalogCodes(mapper.listInvitationActions(intent.invitationId()));
        if (actions.isEmpty()) unavailable("invitation actions are damaged");
        long memberId = store.nextId();
        if (mapper.insertMember(memberId, merchantId, intent.actorId(), now()) != 1)
            unavailable("member insert failed");
        if (mapper.insertGrant(store.nextId(), memberId, invitation.getStoreId(), now()) != 1)
            unavailable("store grant insert failed");
        for (String action : actions) {
            if (mapper.insertGrantAction(store.nextId(), memberId, invitation.getStoreId(), action,
                    now()) != 1) unavailable("store action insert failed");
        }
        if (mapper.confirmInvitation(intent.invitationId(), merchantId, intent.actorId(),
                memberId, invitation.getVersion(), now()) != 1)
            conflict("invitation state changed");
        if (mapper.insertAudit(store.nextId(), intent.actorId(), intent.action(), merchantId,
                invitation.getStoreId(), memberId, intent.invitationId(), "INVITED", "ENABLED",
                invitation.getVersion(), 0L, intent.key(), requestIdBytes(intent), intent.traceId(),
                now()) != 1) unavailable("member audit insert failed");
        publishInvitationLifecycle(invitation, "CONFIRMED", intent.actorId(), memberId,
                intent.traceId());
        MerchantStaffMemberDTO receipt = projectConfirmedMember(mapper, memberId, invitation);
        if (mapper.markBindingSucceeded(intent.key(), receiptJson(receipt)) != 1)
            unavailable("member idempotency result update failed");
        return new MerchantStaffMemberCommandResult(receipt, true, false);
    }

    private MerchantStaffMemberCommandResult confirmReplay(MerchantStaffMemberMapper mapper,
            MerchantAgreementStore.Binding binding, Intent intent) {
        MerchantMemberInvitationEntity invitation = mapper.lockInvitation(intent.invitationId());
        if (invitation == null || !"CONFIRMED".equals(invitation.getStatus())
                || !Objects.equals(invitation.getConfirmedBy(), intent.actorId()))
            conflict("invitation confirm state changed");
        validateInvitationFacts(invitation);
        MerchantStaffMemberDTO receipt = readMemberReceipt(binding.receiptJson());
        if (!receipt.memberId().equals(Long.toUnsignedString(invitation.getMemberId()))
                || !receipt.merchantId().equals(Long.toUnsignedString(invitation.getMerchantId()))
                || !receipt.storeId().equals(Long.toUnsignedString(invitation.getStoreId())))
            unavailable("confirm receipt scope is damaged");
        MerchantMemberRowEntity current = mapper.readMemberRow(invitation.getMerchantId(),
                invitation.getMemberId(), invitation.getStoreId());
        if (current == null || current.getGrantStatus() == null) notFound();
        return new MerchantStaffMemberCommandResult(projectMember(mapper, current), false, true);
    }

    // ------------------------------------------------------------------ reads

    public MerchantStaffMemberPageDTO listMembers(StaffMemberManagementQuery query) {
        if (query == null) invalid();
        long merchantId = target(query.merchantId(), "merchantId");
        long storeId = target(query.storeId(), "storeId");
        if (query.page() < 1 || query.page() > 10_000 || query.pageSize() < 1 || query.pageSize() > 100)
            invalid();
        Principal principal = readPrincipal(query.context());
        return store.read((mapper, agreements) -> {
            requireScope(mapper.selectOwnedScope(merchantId, storeId, principal.actorId()),
                    merchantId, storeId);
            long total = mapper.countMemberRows(merchantId, storeId);
            List<MerchantMemberRowEntity> rows = mapper.listMemberRows(merchantId, storeId,
                    query.pageSize(), (int) ((query.page() - 1L) * query.pageSize()));
            List<MerchantStaffMemberDTO> items = new ArrayList<>(rows.size());
            for (MerchantMemberRowEntity row : rows) items.add(projectMember(mapper, row));
            return new MerchantStaffMemberPageDTO(List.copyOf(items), query.page(), query.pageSize(),
                    total);
        });
    }

    public MerchantStaffInvitationPageDTO listInvitations(StaffInvitationManagementQuery query) {
        if (query == null) invalid();
        long merchantId = target(query.merchantId(), "merchantId");
        long storeId = target(query.storeId(), "storeId");
        if (query.page() < 1 || query.page() > 10_000 || query.pageSize() < 1 || query.pageSize() > 100)
            invalid();
        Principal principal = readPrincipal(query.context());
        return store.read((mapper, agreements) -> {
            requireScope(mapper.selectOwnedScope(merchantId, storeId, principal.actorId()),
                    merchantId, storeId);
            long total = mapper.countInvitationRows(merchantId, storeId);
            List<MerchantMemberInvitationEntity> rows = mapper.listInvitationRows(merchantId, storeId,
                    query.pageSize(), (int) ((query.page() - 1L) * query.pageSize()));
            List<MerchantStaffInvitationDTO> items = new ArrayList<>(rows.size());
            for (MerchantMemberInvitationEntity row : rows) {
                validateInvitationFacts(row);
                items.add(projectInvitation(row));
            }
            return new MerchantStaffInvitationPageDTO(List.copyOf(items), query.page(),
                    query.pageSize(), total);
        });
    }

    public MerchantStaffInvitationDetailDTO getMyInvitation(MyStaffInvitationQuery query) {
        if (query == null) invalid();
        long invitationId = target(query.invitationId(), "invitationId");
        Principal principal = readPrincipal(query.context());
        return store.read((mapper, agreements) -> {
            MerchantMemberInvitationEntity invitation = mapper.selectInvitation(invitationId);
            if (invitation == null) notFound();
            validateInvitationFacts(invitation);
            if (!loginPhones.matchesSessionUserPhone(principal.actorId(), invitation.getPhone()))
                notFound();
            MerchantStoreFactEntity fact =
                    mapper.selectStoreFact(invitation.getMerchantId(), invitation.getStoreId());
            if (fact == null || !Objects.equals(fact.getMerchantId(), invitation.getMerchantId())
                    || !Objects.equals(fact.getStoreId(), invitation.getStoreId())
                    || fact.getMerchantName() == null || fact.getMerchantName().isEmpty()
                    || fact.getStoreName() == null || fact.getStoreName().isEmpty())
                unavailable("invitation merchant facts are damaged");
            List<String> actions = catalogCodes(mapper.listInvitationActions(invitation.getId()));
            return new MerchantStaffInvitationDetailDTO(
                    Long.toUnsignedString(invitation.getId()),
                    Long.toUnsignedString(invitation.getMerchantId()), fact.getMerchantName(),
                    Long.toUnsignedString(invitation.getStoreId()), fact.getStoreName(),
                    invitation.getMemberName(), actions, invitation.getStatus());
        });
    }

    // ------------------------------------------------------------------ shared plumbing

    /**
     * NTF slice producer (Event08 registration): the authoritative invitation facts travel with
     * the event so the notification consumer stays self-contained (ARCH-002). The phone only
     * leaves the module in the pre-masked owner-visible form; no plaintext phone, no free text.
     */
    private void publishInvitationLifecycle(MerchantMemberInvitationEntity invitation,
            String changeType, Long confirmedUserId, Long memberId, String traceId) {
        if (events == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invitationId", Long.toUnsignedString(invitation.getId()));
        payload.put("merchantId", Long.toUnsignedString(invitation.getMerchantId()));
        payload.put("storeId", Long.toUnsignedString(invitation.getStoreId()));
        payload.put("ownerUserId", Long.toUnsignedString(invitation.getInvitedBy()));
        payload.put("memberName", invitation.getMemberName());
        payload.put("phoneMasked", mask(invitation.getPhone()));
        payload.put("changeType", changeType);
        payload.put("confirmedUserId",
                confirmedUserId == null ? null : Long.toUnsignedString(confirmedUserId));
        payload.put("memberId", memberId == null ? null : Long.toUnsignedString(memberId));
        payload.put("occurredAt", EVENT_TIME.format(now().atOffset(ZoneOffset.UTC)));
        events.publish(new IntegrationEvent<>(Long.toUnsignedString(store.nextId()),
                INVITATION_EVENT_TYPE, 1, now().atOffset(ZoneOffset.UTC), INVITATION_AGGREGATE,
                Long.toUnsignedString(invitation.getId()), traceId, payload));
    }

    /** Same-transaction member lifecycle event; the member account is the reachable receiver. */
    private void publishMemberLifecycle(Intent intent, MerchantMemberGrantScopeEntity scope,
            String changeType) {
        if (events == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", Long.toUnsignedString(intent.memberId()));
        payload.put("merchantId", Long.toUnsignedString(intent.merchantId()));
        payload.put("storeId", Long.toUnsignedString(intent.storeId()));
        payload.put("memberUserId", Long.toUnsignedString(scope.getUserId()));
        payload.put("changeType", changeType);
        payload.put("occurredAt", EVENT_TIME.format(now().atOffset(ZoneOffset.UTC)));
        events.publish(new IntegrationEvent<>(Long.toUnsignedString(store.nextId()),
                MEMBER_EVENT_TYPE, 1, now().atOffset(ZoneOffset.UTC), MEMBER_AGGREGATE,
                Long.toUnsignedString(intent.memberId()), intent.traceId(), payload));
    }

    /**
     * NTF grant slice producer (Event08 registration, 2026-10-07 ruling): the store-grant
     * lifecycle event for grant-actions (first grant and whole-set replacement) and
     * revoke-store. Only catalog action codes and coordinates travel — no name, no phone;
     * GRANTED carries the post-change action set, REVOKED the withdrawn set.
     */
    private void publishGrantLifecycle(Intent intent, MerchantMemberGrantScopeEntity scope,
            String changeType, List<String> actions) {
        if (events == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", Long.toUnsignedString(intent.memberId()));
        payload.put("merchantId", Long.toUnsignedString(intent.merchantId()));
        payload.put("storeId", Long.toUnsignedString(intent.storeId()));
        payload.put("memberUserId", Long.toUnsignedString(scope.getUserId()));
        payload.put("changeType", changeType);
        payload.put("actions", List.copyOf(actions));
        payload.put("occurredAt", EVENT_TIME.format(now().atOffset(ZoneOffset.UTC)));
        events.publish(new IntegrationEvent<>(Long.toUnsignedString(store.nextId()),
                GRANT_EVENT_TYPE, 1, now().atOffset(ZoneOffset.UTC), MEMBER_AGGREGATE,
                Long.toUnsignedString(intent.memberId()), intent.traceId(), payload));
    }

    private void admitWithRecovery(Intent intent) {
        try {
            store.admit(intent.key(), intent.canonical(), intent.traceId());
        } catch (MerchantAgreementStore.CommitUnknown firstUnknown) {
            try {
                store.admit(intent.key(), intent.canonical(), intent.traceId());
            } catch (MerchantAgreementStore.CommitUnknown secondUnknown) {
                unavailable("idempotency admission result remains unknown");
            }
        }
    }

    private void authorizeOwner(MerchantStaffMemberMapper mapper, MerchantAgreementMapper agreements,
            Intent intent, boolean lock) {
        MerchantStaffScopeEntity scope = lock
                ? mapper.lockOwnedScope(intent.merchantId(), intent.storeId(), intent.actorId())
                : mapper.selectOwnedScope(intent.merchantId(), intent.storeId(), intent.actorId());
        requireScope(scope, intent.merchantId(), intent.storeId());
        if (!"ACTIVE".equals(scope.getMerchantStatus()) || !"ACTIVE".equals(scope.getStoreStatus()))
            conflict("merchant or store is not operating");
        if (lock && mapper.lockApplication(intent.merchantId()) == null)
            unavailable("application review facts are missing");
        ApplicationReviewFactsReader.Facts application = readApplicationFacts(intent.merchantId());
        if (!"APPROVED".equals(application.applicationStatus()))
            conflict("merchant application is not approved");
        List<MerchantAgreementDocumentEntity> accepted =
                MerchantAgreementService.acceptedAgreements(agreements, intent.merchantId(), lock);
        if (accepted.isEmpty()) conflict("merchant agreement is not signed");
        MerchantAgreementService.validateDocument(accepted.getFirst(), true);
    }

    private ApplicationReviewFactsReader.Facts readApplicationFacts(long merchantId) {
        ApplicationReviewFactsReader.Facts facts;
        try {
            facts = applicationFacts.read(merchantId);
        } catch (RuntimeException failure) {
            unavailable("application review facts are unavailable");
            return null;
        }
        if (facts == null || facts.applicationStatus() == null
                || !APPLICATION.contains(facts.applicationStatus()))
            unavailable("application review facts are unknown");
        return facts;
    }

    private void markSucceeded(MerchantStaffMemberMapper mapper, Intent intent, Object receipt) {
        if (mapper.markBindingSucceeded(intent.key(), receiptJson(receipt)) != 1)
            unavailable("idempotency result update failed");
    }

    private MerchantMemberInvitationEntity readInvitation(MerchantStaffMemberMapper mapper,
            long invitationId) {
        MerchantMemberInvitationEntity invitation = mapper.selectInvitation(invitationId);
        if (invitation == null) unavailable("invitation row is missing after write");
        validateInvitationFacts(invitation);
        return invitation;
    }

    private MerchantStaffMemberCommandResult memberReceipt(MerchantStaffMemberMapper mapper,
            Intent intent) {
        MerchantMemberRowEntity row =
                mapper.readMemberRow(intent.merchantId(), intent.memberId(), intent.storeId());
        if (row == null || row.getGrantStatus() == null) notFound();
        MerchantStaffMemberDTO receipt = projectMember(mapper, row);
        markSucceeded(mapper, intent, receipt);
        return new MerchantStaffMemberCommandResult(receipt, true, false);
    }

    private MerchantStaffMemberDTO projectMember(MerchantStaffMemberMapper mapper,
            MerchantMemberRowEntity row) {
        if (row == null || row.getMemberId() == null || row.getMemberId() <= 0
                || row.getUserId() == null || row.getUserId() <= 0
                || row.getMemberStatus() == null || !MEMBER_STATUSES.contains(row.getMemberStatus())
                || row.getMemberVersion() == null || row.getMemberVersion() < 0)
            unavailable("member facts are damaged");
        if (row.getMemberName() == null || row.getMemberName().isEmpty()
                || row.getMemberName().codePointCount(0, row.getMemberName().length()) > 64
                || row.getPhone() == null || !PHONE.matcher(row.getPhone()).matches())
            unavailable("member naming facts are damaged");
        if (row.getGrantStatus() != null
                && (row.getGrantVersion() == null || row.getGrantVersion() < 0))
            unavailable("store grant facts are damaged");
        List<String> actions = row.getGrantStatus() == null || "REVOKED".equals(row.getGrantStatus())
                ? List.of() : catalogCodes(mapper.listGrantActions(row.getMemberId(), row.getStoreId()));
        return new MerchantStaffMemberDTO(Long.toUnsignedString(row.getMerchantId()),
                Long.toUnsignedString(row.getStoreId()), Long.toUnsignedString(row.getMemberId()),
                row.getMemberName(), mask(row.getPhone()), row.getMemberStatus(),
                row.getGrantStatus(), actions, Long.toUnsignedString(row.getMemberVersion()),
                Long.toUnsignedString(row.getGrantVersion() == null ? 0 : row.getGrantVersion()));
    }

    private MerchantStaffMemberDTO projectConfirmedMember(MerchantStaffMemberMapper mapper,
            long memberId, MerchantMemberInvitationEntity invitation) {
        MerchantMemberRowEntity row =
                mapper.readMemberRow(invitation.getMerchantId(), memberId, invitation.getStoreId());
        if (row == null || row.getGrantStatus() == null) unavailable("member row is missing after write");
        return projectMember(mapper, row);
    }

    private MerchantStaffInvitationDTO projectInvitation(MerchantMemberInvitationEntity invitation) {
        return new MerchantStaffInvitationDTO(Long.toUnsignedString(invitation.getMerchantId()),
                Long.toUnsignedString(invitation.getStoreId()),
                Long.toUnsignedString(invitation.getId()), invitation.getMemberName(),
                mask(invitation.getPhone()), invitation.getStatus(),
                Long.toUnsignedString(invitation.getVersion()));
    }

    private MerchantStaffInvitationDTO readReceipt(String value) {
        try {
            MerchantStaffInvitationDTO receipt = JSON.readValue(value, MerchantStaffInvitationDTO.class);
            target(receipt.merchantId(), "merchantId");
            target(receipt.storeId(), "storeId");
            target(receipt.invitationId(), "invitationId");
            if (receipt.phoneMasked() == null
                    || !receipt.phoneMasked().matches("1[0-9]{2}\\*{4}[0-9]{4}"))
                unavailable("invitation receipt is damaged");
            return receipt;
        } catch (ApiException known) {
            throw known;
        } catch (Exception failure) {
            unavailable("invitation receipt is damaged");
            return null;
        }
    }

    private MerchantStaffMemberDTO readMemberReceipt(String value) {
        try {
            MerchantStaffMemberDTO receipt = JSON.readValue(value, MerchantStaffMemberDTO.class);
            target(receipt.merchantId(), "merchantId");
            target(receipt.storeId(), "storeId");
            target(receipt.memberId(), "memberId");
            version(receipt.memberVersion());
            version(receipt.grantVersion());
            if (receipt.phoneMasked() != null
                    && !receipt.phoneMasked().matches("1[0-9]{2}\\*{4}[0-9]{4}"))
                unavailable("member receipt is damaged");
            return receipt;
        } catch (ApiException known) {
            throw known;
        } catch (Exception failure) {
            unavailable("member receipt is damaged");
            return null;
        }
    }

    private static void requireScope(MerchantStaffScopeEntity scope, long merchantId, long storeId) {
        if (scope == null) notFound();
        if (!Objects.equals(scope.getMerchantId(), merchantId)
                || !Objects.equals(scope.getStoreId(), storeId)
                || scope.getOwnerUserId() == null
                || !MERCHANT_STATUSES.contains(scope.getMerchantStatus())
                || !STORE_STATUSES.contains(scope.getStoreStatus()))
            unavailable("merchant ownership facts are damaged");
    }

    private static void requireMemberScope(MerchantMemberGrantScopeEntity scope, long memberId) {
        if (scope == null || scope.getMemberId() == null || scope.getMemberId() != memberId)
            notFound();
        if (scope.getUserId() == null || scope.getUserId() <= 0 || scope.getMemberStatus() == null
                || scope.getMemberVersion() == null || scope.getMemberVersion() < 0)
            unavailable("member facts are damaged");
    }

    private static void requireGrantUsable(MerchantMemberGrantScopeEntity scope) {
        if (scope.getGrantId() == null) return;
        if (scope.getGrantStatus() == null || !GRANT_STATUSES.contains(scope.getGrantStatus())
                || scope.getGrantVersion() == null || scope.getGrantVersion() < 0)
            unavailable("store grant facts are damaged");
        if ("REVOKED".equals(scope.getGrantStatus()))
            conflict("store grant was revoked; re-granting is pending ruling D3");
    }

    private static void validateInvitationFacts(MerchantMemberInvitationEntity invitation) {
        if (invitation.getId() == null || invitation.getId() <= 0
                || invitation.getMerchantId() == null || invitation.getMerchantId() <= 0
                || invitation.getStoreId() == null || invitation.getStoreId() <= 0
                || invitation.getInvitedBy() == null || invitation.getInvitedBy() <= 0
                || invitation.getVersion() == null || invitation.getVersion() < 0
                || invitation.getPhone() == null || !PHONE.matcher(invitation.getPhone()).matches()
                || invitation.getMemberName() == null || invitation.getMemberName().isEmpty()
                || invitation.getMemberName().codePointCount(0, invitation.getMemberName().length()) > 64
                || invitation.getStatus() == null || !INVITATION_STATUSES.contains(invitation.getStatus())
                || invitation.getPendingMarker() == null || invitation.getPendingMarker().isEmpty())
            unavailable("invitation facts are damaged");
        boolean confirmed = "CONFIRMED".equals(invitation.getStatus());
        if ((confirmed && (invitation.getMemberId() == null || invitation.getConfirmedBy() == null))
                || (!confirmed && (invitation.getMemberId() != null || invitation.getConfirmedBy() != null)))
            unavailable("invitation confirm facts are damaged");
    }

    private void insertActions(MerchantStaffMemberMapper mapper, long memberId, long storeId,
            List<String> actions) {
        for (String action : actions) {
            if (mapper.insertGrantAction(store.nextId(), memberId, storeId, action, now()) != 1)
                unavailable("store action insert failed");
        }
    }

    private static List<String> catalogActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) invalid();
        Set<String> distinct = new LinkedHashSet<>();
        for (String action : actions) {
            if (action == null || !ACTION_CATALOG.contains(action)) invalid();
            if (!distinct.add(action)) invalid();
        }
        return List.copyOf(distinct);
    }

    private static List<String> catalogCodes(List<String> values) {
        if (values == null) unavailable("granted actions are unavailable");
        List<String> codes = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || !ACTION_CATALOG.contains(value))
                unavailable("granted action is outside the approved catalog");
            codes.add(value);
        }
        return List.copyOf(codes);
    }

    private Intent ownerIntent(String action, String merchantId, String storeId, long memberId,
            long invitationId, String phone, String memberName, List<String> actions,
            Long expectedVersion, CommandContext context) {
        long merchant = target(merchantId, "merchantId");
        long targetStore = target(storeId, "storeId");
        Principal principal = principal(context);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("command", action);
        fields.put("merchantId", merchantId);
        fields.put("storeId", storeId);
        if (memberId != 0) fields.put("memberId", Long.toUnsignedString(memberId));
        if (invitationId != 0) fields.put("invitationId", Long.toUnsignedString(invitationId));
        // Sensitive values participate in equality via the pinned protected-value HMAC; the
        // canonical bytes never contain the name or phone in plaintext (35号 precedent).
        if (phone != null) fields.put("phone", protectedToken("merchant.staff-member.phone.v1", phone));
        if (memberName != null)
            fields.put("memberName", protectedToken("merchant.staff-member.name.v1", memberName));
        if (actions != null) fields.put("actions", String.join(",", actions));
        if (expectedVersion != null) fields.put("expectedVersion", Long.toString(expectedVersion));
        MerchantCanonicalParams.Canonical canonical = MerchantCanonicalParams.of(fields);
        byte[] key = MerchantRequestKey.encode(action, "USER", principal.operatorId(),
                "OWNER:" + principal.operatorId(), principal.requestId());
        return new Intent(action, merchant, targetStore, memberId, invitationId, phone, memberName,
                actions, expectedVersion, principal.actorId(), principal.operatorId(),
                principal.requestId(), principal.traceId(), key, canonical);
    }

    private String protectedToken(String purpose, String value) {
        byte[] token;
        try {
            token = protection.protect(purpose, value).equalityToken();
        } catch (RuntimeException failure) {
            unavailable("staff member canonical protection is unavailable");
            return null;
        }
        if (token == null || token.length != 32)
            unavailable("staff member canonical protection is invalid");
        return HexFormat.of().formatHex(token);
    }

    private String receiptJson(Object receipt) {
        try {
            return JSON.writeValueAsString(receipt);
        } catch (Exception failure) {
            unavailable("staff member receipt serialization failed");
            return null;
        }
    }

    private Principal principal(CommandContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null)
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context required");
        if (context.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "only miniapp users may run this command");
        long actor = actorId(context.operatorId());
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException bad) {
            invalid();
        }
        String trace = context.traceId();
        if (trace != null && (trace.isBlank() || trace.getBytes(StandardCharsets.UTF_8).length > 128
                || trace.codePoints().anyMatch(Character::isISOControl) || hasUnpairedSurrogate(trace)))
            invalid();
        return new Principal(actor, context.operatorId(), context.requestId(), trace);
    }

    private Principal readPrincipal(QueryContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null)
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context required");
        if (context.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "only merchant owner may read staff members");
        return new Principal(actorId(context.operatorId()), context.operatorId(), null, context.traceId());
    }

    private static QueryContext query(Intent intent) {
        return new QueryContext(intent.traceId() == null ? "staff-member-binding" : intent.traceId(),
                OperatorType.USER, Long.toUnsignedString(intent.actorId()));
    }

    private static long actorId(String value) {
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "invalid authenticated user");
        }
    }

    private byte[] requestIdBytes(Intent intent) {
        return intent.requestId().getBytes(StandardCharsets.UTF_8);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static String mask(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 64
                || hasUnpairedSurrogate(name)) invalid();
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return true;
            } else if (Character.isLowSurrogate(ch)) return true;
        }
        return false;
    }

    private static long target(String value, String field) {
        if (value == null) invalid();
        try {
            long parsed = IDS.fromApi(value);
            if (parsed <= 0) invalid();
            return parsed;
        } catch (IllegalArgumentException invalidValue) {
            invalid();
            return 0;
        }
    }

    private static long version(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,18})")) invalid();
        return Long.parseLong(value);
    }

    private static void invalid() {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid staff member request");
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant staff member not found");
    }

    private static void conflict(String message) {
        throw new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
