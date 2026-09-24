package com.petplatform.service.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.service.api.dto.ServiceWriteTypes.CreateServiceItemCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.DecideServiceReviewCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ForceOfflineServiceCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceAdminAuthorization;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceItemFields;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceItemResult;
import com.petplatform.service.api.dto.ServiceWriteTypes.SubmitServiceItemCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.TakeServiceOfflineCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.UpdateServiceItemCommand;
import com.petplatform.service.api.error.ServiceWriteApiCodes;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverAssetPort.CoverAssetFact;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceReviewAuthorizationPort;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceReviewAuthorizationPort.Decision;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceReviewAuthorizationPort.Phase;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceCategoryEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceItemEntity;
import com.petplatform.service.biz.infrastructure.persistence.mapper.ServiceWriteMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CCR-W2-API-001 service write slice (proposal v0.2, approved 2026-09-22). Five-value state
 * machine with append-only review decisions, supplement-23 idempotent commands, an owner
 * admission gate (fail closed) and a same-transaction ServiceReviewedEvent outbox append on
 * APPROVE/REJECT. Merchants never set ACTIVE; only an approved review decision does.
 */
public final class ServiceCommandService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> EDITABLE = Set.of("DRAFT", "REJECTED", "OFFLINE");
    private static final Set<String> ALL_STATUSES =
            Set.of("DRAFT", "REVIEWING", "ACTIVE", "OFFLINE", "REJECTED");
    private static final Set<String> PET_TYPES = Set.of("DOG", "CAT", "EXOTIC", "ALL");
    private static final Set<String> COVER_MEDIA = Set.of("image/jpeg", "image/png");
    private static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");

    private final ServiceWriteStore store;
    private final ServiceAdmissionGate admissions;
    private final ServiceWriteDependencies deps;
    private final Clock clock;

    public ServiceCommandService(
            ServiceWriteStore store,
            ServiceAdmissionGate admissions,
            ServiceWriteDependencies deps,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.admissions = Objects.requireNonNull(admissions, "admissions is required");
        this.deps = Objects.requireNonNull(deps, "deps is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    // ------------------------------------------------------------------ commands

    /** @return true when the command executed now, false when an identical replay returned its
     *     first receipt. */
    public boolean createOutcome(CreateServiceItemCommand c, ServiceItemResult[] out) {
        if (c == null) invalid("command is required");
        CommandContext ctx = user(c.context());
        long merchantId = id(c.merchantId(), "merchantId");
        long storeId = id(c.storeId(), "storeId");
        PreparedFields f = prepare(c.fields());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("fields", f.canonical());
        return command(
                "merchant.service.create",
                ctx,
                "STORE:" + merchantId + ":" + storeId,
                p,
                m -> {
                    requireOperable(ctx, merchantId, storeId);
                    long itemId = store.nextId();
                    one(m.insertItem(
                            itemId, merchantId, storeId, principal(ctx), f.categoryId(),
                            f.serviceName(), f.description(), f.price(), f.listPrice(),
                            f.durationMinutes(), f.fulfillmentType(), f.coverAssetId(),
                            f.applicablePetTypes(), f.staffRequirement(), f.verificationRequired(),
                            f.aftersaleNote(), f.remark(), now()));
                    return result(requireRow(m, itemId), null, null);
                },
                out);
    }

    public boolean updateOutcome(UpdateServiceItemCommand c, ServiceItemResult[] out) {
        if (c == null) invalid("command is required");
        CommandContext ctx = user(c.context());
        long itemId = id(c.serviceId(), "serviceId");
        PreparedFields f = prepare(c.fields());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("serviceId", c.serviceId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        p.put("fields", f.canonical());
        return command(
                "merchant.service.update",
                ctx,
                "SERVICE:" + itemId,
                p,
                m -> {
                    // 27号 §7 order: session -> ownership (404 anti-enumeration for anyone
                    // without the owned-store fact) -> state machine -> version CAS.
                    ServiceItemEntity item = locked(m, itemId);
                    requireOperable(ctx, item.getMerchantId(), item.getStoreId());
                    requireEditable(item, c.expectedVersion());
                    one(m.updateItem(
                            itemId, f.categoryId(), f.serviceName(), f.description(), f.price(),
                            f.listPrice(), f.durationMinutes(), f.fulfillmentType(),
                            f.coverAssetId(), f.applicablePetTypes(), f.staffRequirement(),
                            f.verificationRequired(), f.aftersaleNote(), f.remark(),
                            c.expectedVersion(), now()));
                    return result(requireRow(m, itemId), null, null);
                },
                out);
    }

    public boolean submitOutcome(SubmitServiceItemCommand c, ServiceItemResult[] out) {
        if (c == null) invalid("command is required");
        CommandContext ctx = user(c.context());
        long itemId = id(c.serviceId(), "serviceId");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("serviceId", c.serviceId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        return command(
                "merchant.service.submit",
                ctx,
                "SERVICE:" + itemId,
                p,
                m -> {
                    // Ownership before state: an unrelated caller learns nothing about the row.
                    ServiceItemEntity item = locked(m, itemId);
                    requireOperable(ctx, item.getMerchantId(), item.getStoreId());
                    requireEditable(item, c.expectedVersion());
                    validateSubmission(m, item, ctx);
                    one(m.submitItem(itemId, c.expectedVersion(), now()));
                    return result(requireRow(m, itemId), null, null);
                },
                out);
    }

    public boolean offlineOutcome(TakeServiceOfflineCommand c, ServiceItemResult[] out) {
        if (c == null) invalid("command is required");
        CommandContext ctx = user(c.context());
        long itemId = id(c.serviceId(), "serviceId");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("serviceId", c.serviceId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        return command(
                "merchant.service.offline",
                ctx,
                "SERVICE:" + itemId,
                p,
                m -> {
                    ServiceItemEntity item = locked(m, itemId);
                    requireOperable(ctx, item.getMerchantId(), item.getStoreId());
                    if (!"ACTIVE".equals(item.getStatus())) stateNotAllowed();
                    if (item.getVersion() != c.expectedVersion()) conflict("version changed");
                    one(m.takeOfflineItem(itemId, c.expectedVersion(), now()));
                    return result(requireRow(m, itemId), null, null);
                },
                out);
    }

    public boolean decideOutcome(DecideServiceReviewCommand c, ServiceItemResult[] out) {
        if (c == null) invalid("command is required");
        CommandContext ctx = operator(c.context());
        long itemId = id(c.serviceId(), "serviceId");
        String type = c.decisionType() == null ? "" : c.decisionType().trim();
        if (!Set.of("APPROVE", "REJECT").contains(type)) invalid("decisionType is invalid");
        String opinion = trimToNull(c.opinion());
        if (opinion != null && opinion.length() > 500) invalid("opinion exceeds 500 characters");
        if ("REJECT".equals(type) && (opinion == null || opinion.length() < 10)) {
            throw new ApiException(
                    ServiceWriteApiCodes.SERVICE_REVIEW_REASON_REQUIRED,
                    "驳回意见必填且长度为10到500字");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("serviceId", c.serviceId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        p.put("decisionType", type);
        p.put("opinion", opinion);
        return command(
                "admin.service.review.decide",
                ctx,
                "SERVICE:" + itemId,
                p,
                m -> {
                    ServiceItemEntity item = m.selectItemByIdForUpdate(itemId);
                    if (item == null) notFound();
                    strict(item);
                    Decision auth = authorize(c.authorization(), ctx, item, "service.review.decide");
                    if (!"REVIEWING".equals(item.getStatus())) stateNotAllowed();
                    if (item.getVersion() != c.expectedVersion()) conflict("version changed");
                    LocalDateTime at = now();
                    long decisionId = store.nextId();
                    one(m.insertDecision(
                            decisionId, itemId, item.getSubmissionNo(), type, opinion,
                            principal(ctx), at, auth.authzVersion(),
                            Long.toString(item.getVersion()),
                            ctx.requestId().getBytes(StandardCharsets.UTF_8), ctx.traceId(), at));
                    if ("APPROVE".equals(type)) one(m.approveItem(itemId, c.expectedVersion(), at));
                    else one(m.rejectItem(itemId, c.expectedVersion(), at));
                    publishReviewedEvent(ctx, item, type, opinion, at);
                    return result(requireRow(m, itemId), IDS.toApi(decisionId), null);
                },
                out);
    }

    public boolean forceOfflineOutcome(ForceOfflineServiceCommand c, ServiceItemResult[] out) {
        if (c == null) invalid("command is required");
        CommandContext ctx = operator(c.context());
        long itemId = id(c.serviceId(), "serviceId");
        String reason = trimToNull(c.reason());
        if (reason == null || reason.length() < 10 || reason.length() > 500)
            invalid("reason must contain 10 to 500 characters");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("serviceId", c.serviceId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        p.put("reason", reason);
        return command(
                "admin.service.forceOffline",
                ctx,
                "SERVICE:" + itemId,
                p,
                m -> {
                    ServiceItemEntity item = m.selectItemByIdForUpdate(itemId);
                    if (item == null) notFound();
                    strict(item);
                    // AUTH-domain action-code lexicon is lowercase dotted segments
                    // (AdminActionCheckQuery): the approved "forceOffline" intent is spelled
                    // service.force.offline there; PR discloses the spelling correction.
                    Decision auth = authorize(c.authorization(), ctx, item, "service.force.offline");
                    if (!"ACTIVE".equals(item.getStatus())) stateNotAllowed();
                    if (item.getVersion() != c.expectedVersion()) conflict("version changed");
                    LocalDateTime at = now();
                    long actionId = store.nextId();
                    one(m.insertGovernance(
                            actionId, itemId, reason, principal(ctx), at, auth.authzVersion(),
                            Long.toString(item.getVersion()),
                            ctx.requestId().getBytes(StandardCharsets.UTF_8), ctx.traceId(), at));
                    one(m.forceOfflineItem(itemId, c.expectedVersion(), at));
                    // No event by design: whether FORCE_OFFLINE notifies the merchant is the
                    // registered open question (proposal SVCW-D10 v0.2).
                    return result(requireRow(m, itemId), null, IDS.toApi(actionId));
                },
                out);
    }

    // ------------------------------------------------------------- command plumbing

    private boolean command(
            String namespace,
            CommandContext ctx,
            String authority,
            Map<String, Object> params,
            ServiceWriteStore.Work<ServiceItemResult> action,
            ServiceItemResult[] out) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "service command admission must not run inside an existing transaction");
        }
        ServiceCanonicalParams.Canonical canonical = ServiceCanonicalParams.of(params);
        String key =
                ServiceWriteStore.requestKey(
                        namespace, ctx.operatorType().name(), ctx.operatorId(), authority,
                        ctx.requestId());
        admitWithRecovery(key, canonical);
        return executeBound(key, canonical, action, out);
    }

    private boolean executeBound(
            String key,
            ServiceCanonicalParams.Canonical canonical,
            ServiceWriteStore.Work<ServiceItemResult> action,
            ServiceItemResult[] out) {
        try {
            return runBound(key, canonical, action, out);
        } catch (ServiceWriteStore.CommitUnknown first) {
            try {
                return runBound(key, canonical, action, out);
            } catch (ServiceWriteStore.CommitUnknown second) {
                unavailable("service commit result remains unknown");
                return false;
            }
        }
    }

    private boolean runBound(
            String key,
            ServiceCanonicalParams.Canonical canonical,
            ServiceWriteStore.Work<ServiceItemResult> action,
            ServiceItemResult[] out) {
        return store.execute(
                m -> {
                    ServiceWriteStore.Binding b = ServiceWriteStore.require(m, key);
                    ServiceWriteStore.same(b, canonical);
                    if ("SUCCEEDED".equals(b.status())) {
                        out[0] = read(b.receiptJson());
                        return false;
                    }
                    ServiceItemResult result = action.apply(m);
                    one(m.markBindingSucceeded(key, write(result)));
                    out[0] = result;
                    return true;
                });
    }

    private void admitWithRecovery(String key, ServiceCanonicalParams.Canonical canonical) {
        try {
            store.admit(key, canonical);
        } catch (ServiceWriteStore.CommitUnknown firstUnknown) {
            try {
                store.admit(key, canonical);
            } catch (ServiceWriteStore.CommitUnknown secondUnknown) {
                unavailable("idempotency admission result remains unknown");
            }
        }
    }

    // ------------------------------------------------------------- gates

    /**
     * Owner admission gate (SVCW-D5, shared with workbench reads): NOT_FOUND without the
     * owned-store fact (anti-enumeration), 503 on unreadable facts, 409 SERVICE_STATE_NOT_ALLOWED
     * when the merchant/store facts are clear but not operable.
     */
    private void requireOperable(CommandContext ctx, long merchantId, long storeId) {
        admissions.requireOperable(
                new com.petplatform.common.QueryContext(
                        ctx.traceId(), OperatorType.USER, ctx.operatorId()),
                merchantId,
                storeId);
    }

    private Decision authorize(
            ServiceAdminAuthorization ref, CommandContext ctx, ServiceItemEntity item,
            String action) {
        if (ref == null || ref.sessionId() == null || ref.sessionId().isBlank()
                || ref.sessionGeneration() < 0) {
            throw new ApiException(
                    CommonApiCodes.UNAUTHORIZED, "admin authorization reference is required");
        }
        Decision d =
                deps.authorization()
                        .check(
                                new ServiceReviewAuthorizationPort.Check(
                                        ref.sessionId(),
                                        ref.sessionGeneration(),
                                        ctx.operatorId(),
                                        action,
                                        new ServiceReviewAuthorizationPort.Resource(
                                                "SERVICE",
                                                IDS.toApi(item.getId()),
                                                IDS.toApi(item.getMerchantId()),
                                                null,
                                                Long.toString(item.getVersion())),
                                        "SERVICE_REVIEW",
                                        Phase.EXECUTE));
        if (d == null || d.checkedAt() == null || d.authzVersion() == null
                || d.authzVersion().isBlank()) {
            unavailable("authorization decision is invalid");
        }
        if (!d.allowed()) authorizationDenied(d.reasonCode());
        return d;
    }

    private static void authorizationDenied(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank())
            unavailable("authorization denial reason is missing");
        if ("RESOURCE_SCOPE_DENIED".equals(reasonCode)) notFound();
        if ("ACTION_NOT_GRANTED".equals(reasonCode))
            throw new ApiException(CommonApiCodes.FORBIDDEN, "admin action is not authorized");
        if (Set.of("SESSION_NOT_FOUND", "SESSION_REVOKED", "SESSION_CLOSED", "SESSION_EXPIRED",
                "SESSION_GENERATION_CHANGED", "OPERATOR_MISMATCH").contains(reasonCode)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "admin session is no longer valid");
        }
        unavailable("authorization decision reason is unsupported");
    }

    private ServiceItemEntity locked(ServiceWriteMapper m, long itemId) {
        ServiceItemEntity item = m.selectItemByIdForUpdate(itemId);
        if (item == null) notFound();
        strict(item);
        return item;
    }

    private static void requireEditable(ServiceItemEntity item, long expectedVersion) {
        if (!EDITABLE.contains(item.getStatus())) stateNotAllowed();
        if (item.getVersion() != expectedVersion) conflict("version changed");
    }

    private static ServiceItemEntity requireRow(ServiceWriteMapper m, long itemId) {
        ServiceItemEntity item = m.selectItemById(itemId);
        if (item == null) unavailable("service item row is missing after write");
        return item;
    }

    /**
     * Structural invariants only: business columns are legitimately NULL on loose drafts (10号
     * §4.10.1, F3 fix), so categoryId must not be treated as damaged — the submit gate enforces
     * completeness. System columns (ids/version/submission_no/status) stay required.
     */
    private static void strict(ServiceItemEntity item) {
        if (item.getId() == null || item.getId() <= 0
                || item.getMerchantId() == null || item.getStoreId() == null
                || item.getVersion() == null
                || item.getVersion() < 0 || item.getSubmissionNo() == null
                || item.getSubmissionNo() < 0
                || !ALL_STATUSES.contains(String.valueOf(item.getStatus()))) {
            unavailable("service item facts are damaged");
        }
    }

    // ------------------------------------------------------------- field validation

    private record PreparedFields(
            Long categoryId, String serviceName, String description, BigDecimal price,
            BigDecimal listPrice, Integer durationMinutes, String fulfillmentType,
            Long coverAssetId, String applicablePetTypes, String staffRequirement,
            Boolean verificationRequired, String aftersaleNote, String remark,
            Map<String, Object> canonical) {}

    /** Lenient draft-shape validation; the strict required set is re-checked at submit. */
    private PreparedFields prepare(ServiceItemFields raw) {
        if (raw == null) invalid("fields are required");
        Long categoryId = raw.categoryId() == null ? null : id(raw.categoryId(), "categoryId");
        String name = trimToNull(raw.serviceName());
        if (name != null && (name.length() < 2 || name.length() > 50))
            invalid("serviceName must contain 2 to 50 characters");
        String description = trimToNull(raw.description());
        if (description != null && description.length() > 1000)
            invalid("description exceeds 1000 characters");
        String staff = trimToNull(raw.staffRequirement());
        if (staff != null && staff.length() > 200) invalid("staffRequirement exceeds 200 characters");
        String aftersale = trimToNull(raw.aftersaleNote());
        if (aftersale != null && aftersale.length() > 500)
            invalid("aftersaleNote exceeds 500 characters");
        String remark = trimToNull(raw.remark());
        if (remark != null && remark.length() > 500) invalid("remark exceeds 500 characters");
        BigDecimal price = raw.price();
        if (price != null && (price.scale() > 2 || price.compareTo(BigDecimal.ZERO) <= 0))
            invalid("price must be positive with at most 2 decimal places");
        BigDecimal listPrice = raw.listPrice();
        if (listPrice != null && listPrice.scale() > 2)
            invalid("listPrice must have at most 2 decimal places");
        if (listPrice != null && price != null && listPrice.compareTo(price) < 0)
            invalid("listPrice must not be lower than price");
        Integer duration = raw.durationMinutes();
        if (duration != null && (duration < 1 || duration > 10_080))
            invalid("durationMinutes must be between 1 and 10080");
        String fulfillment = trimToNull(raw.fulfillmentType());
        if (fulfillment != null && !Set.of("IN_STORE", "PICKUP_DELIVERY").contains(fulfillment))
            invalid("fulfillmentType is invalid");
        Long coverAssetId =
                raw.coverAssetId() == null ? null : id(raw.coverAssetId(), "coverAssetId");
        String petTypes = normalizePetTypes(raw.applicablePetTypes());
        boolean verification =
                raw.verificationRequired() == null || raw.verificationRequired();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("serviceName", name);
        canonical.put("categoryId", categoryId);
        canonical.put("fulfillmentType", fulfillment);
        canonical.put("price", price == null ? null : price.toPlainString());
        canonical.put("listPrice", listPrice == null ? null : listPrice.toPlainString());
        canonical.put("durationMinutes", duration);
        canonical.put("coverAssetId", coverAssetId);
        canonical.put("applicablePetTypes", petTypes);
        canonical.put("staffRequirement", staff);
        canonical.put("verificationRequired", verification);
        canonical.put("description", description);
        canonical.put("aftersaleNote", aftersale);
        canonical.put("remark", remark);
        return new PreparedFields(
                categoryId, name, description, price, listPrice, duration, fulfillment,
                coverAssetId, petTypes, staff, verification, aftersale, remark, canonical);
    }

    private static String normalizePetTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) return null;
        Set<String> seen = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank())
                invalid("applicablePetTypes contains an empty value");
            String type = value.trim().toUpperCase();
            if (!PET_TYPES.contains(type)) invalid("applicablePetTypes is invalid");
            if (!seen.add(type)) invalid("applicablePetTypes contains duplicates");
        }
        if (seen.contains("ALL") && seen.size() > 1)
            invalid("applicablePetTypes ALL cannot combine with other types");
        return String.join(",", seen);
    }

    /**
     * Submission gate (运营端 §5.2 second step): the stored draft must carry every required field
     * — name, ENABLED category, fulfillment, price, duration, pet types and a cover asset owned
     * by the submitting owner (SVCW-D4). The store binding comes from the item row itself.
     */
    private void validateSubmission(
            ServiceWriteMapper m, ServiceItemEntity item, CommandContext ctx) {
        if (item.getServiceName() == null || item.getServiceName().length() < 2
                || item.getServiceName().length() > 50) invalid("serviceName is required");
        if (item.getPrice() == null || item.getPrice().compareTo(BigDecimal.ZERO) <= 0)
            invalid("price is required");
        if (item.getListPrice() != null && item.getListPrice().compareTo(item.getPrice()) < 0)
            invalid("listPrice must not be lower than price");
        if (item.getDurationMinutes() == null || item.getDurationMinutes() < 1)
            invalid("durationMinutes is required");
        if (item.getFulfillmentType() == null
                || !Set.of("IN_STORE", "PICKUP_DELIVERY").contains(item.getFulfillmentType()))
            invalid("fulfillmentType is required");
        if (item.getApplicablePetTypes() == null || item.getApplicablePetTypes().isBlank())
            invalid("applicablePetTypes is required");
        if (item.getCoverAssetId() == null) invalid("coverAssetId is required");
        // Null-guard before the primitive mapper lookup: a loose draft without a category must
        // fail the field-level 400, never an unboxing NPE dressed up as 503 (defect F3).
        if (item.getCategoryId() == null) invalid("categoryId is required");
        ServiceCategoryEntity category = m.selectCategoryById(item.getCategoryId());
        if (category == null || !"ENABLED".equals(category.getStatus()))
            invalid("categoryId must reference an ENABLED category");
        resolveCover(ctx, IDS.toApi(item.getCoverAssetId()));
    }

    private CoverAssetFact resolveCover(CommandContext ctx, String coverAssetId) {
        List<CoverAssetFact> facts;
        try {
            facts = deps.coverAssets().resolveOwned(ctx.operatorId(), List.of(coverAssetId));
        } catch (RuntimeException unavailableProvider) {
            unavailable("cover asset facts are unavailable");
            return null;
        }
        if (facts == null) unavailable("cover asset facts are unavailable");
        CoverAssetFact cover = facts.isEmpty() ? null : facts.get(0);
        if (cover == null
                || !coverAssetId.equals(cover.assetId())
                || !String.valueOf(ctx.operatorId()).equals(cover.ownerUserId())
                || !"READY".equals(cover.status())
                || !COVER_MEDIA.contains(cover.mediaType())
                || cover.bytes() < 1
                || cover.bytes() > 10_485_760) {
            // Field-level submission gate failure: 400 per the finalized 10号 §4.10.1 error
            // applicability (SERVICE_STATE_NOT_ALLOWED stays reserved for state/admission).
            invalid("coverAssetId must reference an owned READY image asset (jpeg/png, <=10MiB)");
        }
        return cover;
    }

    // ------------------------------------------------------------- event + receipt

    private void publishReviewedEvent(
            CommandContext ctx, ServiceItemEntity item, String decisionType, String opinion,
            LocalDateTime at) {
        if (item.getOwnerUserId() == null || item.getOwnerUserId() <= 0) {
            // Legacy SQL-seeded rows cannot address the merchant owner; fail closed rather than
            // emitting an unaddressable event (rows created through this slice always carry it).
            unavailable("service owner fact is missing for the review event");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceId", IDS.toApi(item.getId()));
        payload.put("serviceName", item.getServiceName());
        payload.put("merchantId", IDS.toApi(item.getMerchantId()));
        payload.put("storeId", IDS.toApi(item.getStoreId()));
        payload.put("submissionNo", item.getSubmissionNo());
        payload.put("decisionType", decisionType);
        payload.put("opinion", opinion);
        payload.put("decidedAt", EVENT_TIME.format(at.atOffset(ZoneOffset.UTC)));
        // Recipient field agreed with the notification-side consumer (role E, 2026-09-22):
        // keeps ServiceReviewedEvent.v1 consumers self-contained, no merchant table read.
        payload.put("ownerUserId", IDS.toApi(item.getOwnerUserId()));
        long eventId = store.nextId();
        deps.events()
                .publish(
                        new IntegrationEvent<>(
                                Long.toUnsignedString(eventId),
                                "ServiceReviewedEvent.v1",
                                1,
                                at.atOffset(ZoneOffset.UTC),
                                "SERVICE",
                                IDS.toApi(item.getId()),
                                ctx.traceId(),
                                payload));
    }

    private static ServiceItemResult result(
            ServiceItemEntity item, String decisionId, String actionId) {
        return new ServiceItemResult(
                IDS.toApi(item.getId()),
                IDS.toApi(item.getMerchantId()),
                IDS.toApi(item.getStoreId()),
                item.getStatus(),
                item.getVersion(),
                decisionId,
                actionId);
    }

    private static String write(ServiceItemResult result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("service receipt is not serializable", failure);
        }
    }

    private static ServiceItemResult read(String receipt) {
        try {
            return JSON.readValue(receipt, ServiceItemResult.class);
        } catch (RuntimeException | java.io.IOException failure) {
            unavailable("stored service receipt is unreadable");
            return null;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static CommandContext user(CommandContext ctx) {
        if (ctx == null || ctx.operatorId() == null || ctx.operatorId().isBlank()
                || ctx.requestId() == null || ctx.requestId().isBlank())
            invalid("command context is required");
        principal(ctx);
        if (ctx.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "merchant session is required");
        return ctx;
    }

    private static CommandContext operator(CommandContext ctx) {
        if (ctx == null || ctx.operatorId() == null || ctx.operatorId().isBlank()
                || ctx.requestId() == null || ctx.requestId().isBlank())
            invalid("command context is required");
        principal(ctx);
        if (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "admin session is required");
        return ctx;
    }

    private static long principal(CommandContext ctx) {
        try {
            long operator = Long.parseLong(ctx.operatorId());
            if (operator <= 0) invalid("operator is invalid");
            return operator;
        } catch (NumberFormatException malformed) {
            invalid("operator is invalid");
            throw malformed;
        }
    }

    private static long id(String value, String field) {
        if (value == null || value.isBlank()) invalid(field + " is required");
        try {
            long parsed = IDS.fromApi(value);
            if (parsed <= 0) invalid(field + " is invalid");
            return parsed;
        } catch (RuntimeException malformed) {
            invalid(field + " is invalid");
            throw malformed;
        }
    }

    private static void one(int updated) {
        if (updated != 1) conflict("service state or version changed concurrently");
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "service resource not found");
    }

    private static void stateNotAllowed() {
        throw new ApiException(
                ServiceWriteApiCodes.SERVICE_STATE_NOT_ALLOWED, "当前服务状态不允许该操作");
    }

    private static void conflict(String message) {
        throw new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
