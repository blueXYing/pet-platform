package com.petplatform.customerservice.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.customerservice.biz.infrastructure.persistence.FeedbackTicketStore;
import com.petplatform.customerservice.biz.infrastructure.persistence.mapper.FeedbackTicketMapper;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetStatus;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.ResolveOwnedPrivateAssetsQuery;
import com.petplatform.user.api.query.UserActiveStatusApi;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;

/**
 * Contract 67 feedback-ticket kernel (PRD 5.1.28 complaint class; CCR-CS-FEEDBACK-001 minimum
 * plus the 2026-10-19 "继续扩展CCR" extension). C-end creation stays owner-scoped with the
 * frozen four-value type word, optional contact and 1..500-codepoint content; v0.2 adds the
 * optional attachment list (0..6 assets that the kernel re-proves READY and owned under the
 * 31号 FEEDBACK_ATTACHMENT purpose through the thirdparty *-api port — never a private
 * Mapper). The append-only event table is the audit (SUBMITTED by the submitter,
 * PROCESSING/RESOLVED by the operator); the single ops reply rides the ticket row on the one
 * RESOLVED transition. The ops face (list/detail/process/resolve) re-proves the current
 * session/action/scope through the injected module-local authorization port before any row is
 * touched; create/process/resolve are supplement-23 idempotent commands over this module's own
 * SQL14-mirror binding table. A cheap volatile per-user rate limiter blunts abuse spikes
 * (disclosed: not a security boundary). Content is stored verbatim; the owner reads own
 * tickets, the operator face reads verbatim per the ops-disclosure ruling (log sanitization
 * unchanged).
 */
public final class FeedbackTicketService {

    public static final String CREATE_NAMESPACE = "feedback.create";
    public static final String PROCESS_NAMESPACE = "feedback.process";
    public static final String RESOLVE_NAMESPACE = "feedback.resolve";
    /** PRD 5.1.28 four-value type vocabulary; never extended in V1. */
    public static final Set<String> TYPES = Set.of(
            "FEATURE_SUGGESTION", "BUG_REPORT", "SERVICE_COMPLAINT", "OTHER");
    /** Frozen PRD 5.1.28 unified ticket state word list. */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** v0.2 ops transitions cover exactly these two hops; the other two words stay unreachable. */
    public static final Set<String> OPS_STATUS_FILTERS = Set.of(
            STATUS_PENDING, STATUS_PROCESSING, STATUS_RESOLVED);
    /** Audit action vocabulary over the append-only event table. */
    public static final String ACTION_SUBMITTED = "SUBMITTED";
    public static final String ACTION_PROCESSING = "PROCESSING";
    public static final String ACTION_RESOLVED = "RESOLVED";
    /** Contract 31 purpose that scopes feedback attachments on the shared pipeline. */
    public static final String ATTACHMENT_PURPOSE = "FEEDBACK_ATTACHMENT";
    static final int CONTENT_MAX = 500;
    static final int CONTACT_MAX = 64;
    static final int REPLY_MAX = 1000;
    static final int PREVIEW_MAX = 50;
    static final int PAGE_MAX = 10_000;
    static final int PAGE_SIZE_MAX = 50;
    static final int ATTACHMENT_MAX = 6;
    public static final int RATE_LIMIT_PER_MINUTE = 5;

    public record TicketSummary(String ticketId, String type, String status,
                                String contentPreview, String createdAt) {}

    public record TicketEvent(String action, String createdAt) {}

    public record TicketReply(String reply, String repliedAt) {}

    public record TicketDetail(String ticketId, String type, String status, String content,
                               String contact, String contentPreview, String createdAt,
                               List<String> attachmentAssetIds, List<TicketEvent> events,
                               List<TicketReply> replies, Object resolution) {}

    /** Ops detail: the owner view verbatim plus the owner id (ops-side handling context). */
    public record OpsTicketDetail(String ticketId, String ownerUserId, String type, String status,
                                  String content, String contact, String contentPreview,
                                  String createdAt, List<String> attachmentAssetIds,
                                  List<TicketEvent> events, List<TicketReply> replies) {}

    public record TicketPage(int page, int pageSize, long total, List<TicketSummary> items) {}

    public record CreateFeedbackCommand(CommandContext context, String type, String contact,
                                        String content, List<String> attachmentAssetIds) {}

    public record CreateOutcome(TicketSummary receipt, boolean replayed) {}

    public record OpsListQuery(FeedbackOpsAuthorizationPort.Authorization authorization,
                               QueryContext context,
                               String status, String type, Integer page, Integer pageSize) {}

    public record OpsTicketQuery(FeedbackOpsAuthorizationPort.Authorization authorization,
                                 QueryContext context, String ticketId) {}

    public record ProcessCommand(CommandContext context,
                                 FeedbackOpsAuthorizationPort.Authorization authorization,
                                 String ticketId) {}

    public record ResolveCommand(CommandContext context,
                                 FeedbackOpsAuthorizationPort.Authorization authorization,
                                 String ticketId, String reply) {}

    public record TransitionReceipt(String ticketId, String status, long version,
                                    OffsetDateTime occurredAt) {}

    public record ProcessOutcome(TransitionReceipt receipt, boolean replayed) {}

    private final FeedbackTicketStore store;
    private final UserActiveStatusApi activeUsers;
    private final SnowflakeIdGenerator ids;
    private final java.time.Clock clock;
    /** Boot-level adapters; both fail closed when their owner slice is not assembled. */
    private final FeedbackAssetProofPort.Port assets;
    private final FeedbackOpsAuthorizationPort.Port opsAuthorization;
    private final java.util.concurrent.ConcurrentMap<String, Window> rateWindow =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final ObjectMapper CODEC = new ObjectMapper();

    private record Window(long minute, int count) {}

    public FeedbackTicketService(FeedbackTicketStore store, UserActiveStatusApi activeUsers,
                                 SnowflakeIdGenerator ids, FeedbackAssetProofPort.Port assets,
                                 FeedbackOpsAuthorizationPort.Port opsAuthorization) {
        this(store, activeUsers, ids, assets, opsAuthorization, java.time.Clock.systemUTC());
    }

    /** Assembler-facing convenience: the store stays module-internal (ARCH-002). */
    public FeedbackTicketService(javax.sql.DataSource dataSource, UserActiveStatusApi activeUsers,
                                 SnowflakeIdGenerator ids, FeedbackAssetProofPort.Port assets,
                                 FeedbackOpsAuthorizationPort.Port opsAuthorization) {
        this(new FeedbackTicketStore(dataSource), activeUsers, ids, assets, opsAuthorization,
                java.time.Clock.systemUTC());
    }

    public FeedbackTicketService(FeedbackTicketStore store, UserActiveStatusApi activeUsers,
                                 SnowflakeIdGenerator ids, FeedbackAssetProofPort.Port assets,
                                 FeedbackOpsAuthorizationPort.Port opsAuthorization,
                                 java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.activeUsers = Objects.requireNonNull(activeUsers, "user active-status port is required");
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
        this.assets = Objects.requireNonNull(assets, "private-asset port is required");
        this.opsAuthorization = Objects.requireNonNull(opsAuthorization,
                "ops authorization port is required");
        this.clock = Objects.requireNonNull(clock);
    }

    /** C-end creation: strict validation, ACTIVE guard, rate limit, idempotent write + audit. */
    public CreateOutcome create(CreateFeedbackCommand command) {
        CommandContext context = trustedUserContext(command.context());
        String type = command.type();
        if (type == null || !TYPES.contains(type)) {
            throw new ApiException("FEEDBACK_TYPE_INVALID", "反馈类型不合法");
        }
        String content = requireText(command.content(), 1, CONTENT_MAX, "content");
        String contact = command.contact() == null ? null
                : requireText(command.contact(), 1, CONTACT_MAX, "contact");
        List<Long> attachments = validateAttachments(context, command.attachmentAssetIds());
        long userId = actorUserId(context);
        enforceRateLimit(userId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", type);
        params.put("contact", contact);
        params.put("content", content);
        params.put("attachmentAssetIds", attachments.stream().map(FeedbackTicketService::text).toList());
        String requestKey = CREATE_NAMESPACE + "|USER|" + context.operatorId() + "|USER_SELF|"
                + context.requestId();
        FeedbackCanonicalParams.Canonical canonical = FeedbackCanonicalParams.of(params);
        Optional<String> replay;
        try {
            replay = store.read(mapper -> {
                try {
                    mapper.insertBinding(freshId(), requestKey, canonical.version,
                            canonical.sha256, canonical.bytes);
                    return Optional.<String>empty();
                } catch (DuplicateKeyException bound) {
                    FeedbackTicketMapper.Binding existing = requireBinding(mapper, requestKey);
                    if (!existing.paramsSha256.equals(canonical.sha256)
                            || !java.util.Arrays.equals(existing.paramsCanonical, canonical.bytes)) {
                        throw new ParamsConflict();
                    }
                    return "SUCCEEDED".equals(existing.status)
                            ? Optional.ofNullable(existing.receiptJson) : Optional.<String>empty();
                }
            });
        } catch (ParamsConflict conflict) {
            throw new ApiException("IDEMPOTENCY_KEY_CONFLICT", "相同 requestId 对应不同参数");
        }
        if (replay.isPresent()) {
            return new CreateOutcome(deserialize(replay.orElseThrow()), true);
        }
        TicketSummary receipt = store.execution().execute(status -> {
            requireBinding(store.mapper(), requestKey);
            activeUsers.requireActiveUser(context.operatorId());
            long ticketId = freshId();
            store.mapper().insertTicket(ticketId, userId, type, contact, content, STATUS_PENDING);
            for (int index = 0; index < attachments.size(); index++) {
                store.mapper().insertAttachment(freshId(), ticketId, attachments.get(index), index);
            }
            store.mapper().insertEvent(freshId(), ticketId, ACTION_SUBMITTED, "USER", userId, null);
            TicketSummary accepted = new TicketSummary(Long.toUnsignedString(ticketId), type,
                    STATUS_PENDING, preview(content), isoNow());
            if (store.mapper().markSucceeded(requestKey, serialize(accepted)) != 1) {
                throw new IllegalStateException("Binding completion pairing violated; rolled back");
            }
            return accepted;
        });
        return new CreateOutcome(receipt, false);
    }

    /** Owner-scoped page, fixed created_at DESC, id DESC sort. */
    public TicketPage listMine(String ownerUserId, Integer page, Integer pageSize) {
        long userId = actorId(ownerUserId);
        int[] bounds = pageBounds(page, pageSize);
        return store.read(mapper -> {
            long total = mapper.countMine(userId);
            var rows = mapper.selectMinePage(userId, bounds[1],
                    (int) ((bounds[0] - 1L) * bounds[1]));
            List<TicketSummary> items = rows.stream().map(FeedbackTicketService::toSummary).toList();
            return new TicketPage(bounds[0], bounds[1], total, items);
        });
    }

    /** Owner detail; not-mine and missing are one indistinguishable 404 (anti-enumeration). */
    public TicketDetail getMine(String ownerUserId, String ticketIdText) {
        long userId = actorId(ownerUserId);
        long ticketId = parseTicketId(ticketIdText);
        return store.read(mapper -> {
            FeedbackTicketMapper.TicketRow row = mapper.findTicket(ticketId, userId);
            if (row == null) throw notFound();
            List<TicketEvent> events = mapper.selectEvents(ticketId).stream()
                    .map(event -> new TicketEvent(event.action, iso(event.createdAt))).toList();
            return new TicketDetail(Long.toUnsignedString(row.id), row.type, row.status, row.content,
                    row.contact, preview(row.content), iso(row.createdAt),
                    attachmentIds(mapper, ticketId), List.copyOf(events),
                    repliesOf(row), null);
        });
    }

    // ------------------------------------------------------------------ ops face

    /** Ops list over all tickets; status/type filters inside the frozen vocabularies. */
    public TicketPage listForOps(OpsListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operatorContext(query.context());
        authorizeCollection(query.authorization(), context, "complaint.read");
        String status = statusFilter(query.status());
        String type = typeFilter(query.type());
        int[] bounds = pageBounds(query.page(), query.pageSize());
        return store.read(mapper -> {
            long total = mapper.countForOps(status, type);
            var rows = mapper.selectOpsPage(status, type, bounds[1],
                    (int) ((bounds[0] - 1L) * bounds[1]));
            List<TicketSummary> items = rows.stream().map(FeedbackTicketService::toSummary).toList();
            return new TicketPage(bounds[0], bounds[1], total, items);
        });
    }

    /** Ops detail: the ticket verbatim (content/contact) plus owner and attachment facts. */
    public OpsTicketDetail getForOps(OpsTicketQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operatorContext(query.context());
        long ticketId = parseTicketId(query.ticketId());
        authorizeRow(query.authorization(), context, "complaint.read", ticketId,
                FeedbackOpsAuthorizationPort.Phase.READ_RESULT);
        return store.read(mapper -> {
            FeedbackTicketMapper.TicketRow row = mapper.findTicketForOps(ticketId);
            if (row == null) throw notFound();
            List<TicketEvent> events = mapper.selectEvents(ticketId).stream()
                    .map(event -> new TicketEvent(event.action, iso(event.createdAt))).toList();
            return new OpsTicketDetail(Long.toUnsignedString(row.id),
                    Long.toUnsignedString(row.userId), row.type, row.status, row.content,
                    row.contact, preview(row.content), iso(row.createdAt),
                    attachmentIds(mapper, ticketId), List.copyOf(events), repliesOf(row));
        });
    }

    /** PENDING → PROCESSING; idempotent, optimistic-guarded, audited. */
    public ProcessOutcome processTicket(ProcessCommand command) {
        CommandContext context = trustedOperatorContext(command.context());
        long ticketId = parseTicketId(command.ticketId());
        authorizeRow(command.authorization(), operatorContextOf(context), "complaint.handle", ticketId,
                FeedbackOpsAuthorizationPort.Phase.EXECUTE);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ticketId", command.ticketId());
        params.put("toStatus", STATUS_PROCESSING);
        TransitionReceipt receipt = runBound(params, context, PROCESS_NAMESPACE, ticketId,
                key -> {
                    FeedbackTicketMapper.TicketRow row = requireTicket(ticketId);
                    if (!STATUS_PENDING.equals(row.status)) throw conflict();
                    if (store.mapper().markProcessing(ticketId, STATUS_PENDING, row.version,
                            actorId(context.operatorId())) != 1) {
                        throw conflict();
                    }
                    store.mapper().insertEvent(freshId(), ticketId, ACTION_PROCESSING, "OPERATOR",
                            actorId(context.operatorId()), null);
                    return receipt(key, ticketId, STATUS_PROCESSING, row.version + 1);
                });
        return new ProcessOutcome(receipt, false);
    }

    /** PROCESSING → RESOLVED carrying the single ops reply (1..1000 codepoints). */
    public ProcessOutcome resolveTicket(ResolveCommand command) {
        CommandContext context = trustedOperatorContext(command.context());
        long ticketId = parseTicketId(command.ticketId());
        String reply = requireText(command.reply(), 1, REPLY_MAX, "reply");
        authorizeRow(command.authorization(), operatorContextOf(context), "complaint.decide", ticketId,
                FeedbackOpsAuthorizationPort.Phase.EXECUTE);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ticketId", command.ticketId());
        params.put("toStatus", STATUS_RESOLVED);
        params.put("reply", reply);
        TransitionReceipt receipt = runBound(params, context, RESOLVE_NAMESPACE, ticketId,
                key -> {
                    FeedbackTicketMapper.TicketRow row = requireTicket(ticketId);
                    if (!STATUS_PROCESSING.equals(row.status)) throw conflict();
                    if (store.mapper().markResolved(ticketId, row.version,
                            actorId(context.operatorId()), reply) != 1) {
                        throw conflict();
                    }
                    store.mapper().insertEvent(freshId(), ticketId, ACTION_RESOLVED, "OPERATOR",
                            actorId(context.operatorId()), null);
                    return receipt(key, ticketId, STATUS_RESOLVED, row.version + 1);
                });
        return new ProcessOutcome(receipt, false);
    }

    /**
     * Shared ops write pipeline: admit the idempotency binding (replay on SUCCEEDED) → run the
     * kernel work and mark the binding succeeded in one commit each. Same-key different-bytes
     * replay is a 409 key conflict.
     */
    private TransitionReceipt runBound(Map<String, Object> params, CommandContext context,
            String namespace, long ticketId, java.util.function.Function<String, TransitionReceipt> work) {
        String requestKey = namespace + "|OPS|" + context.operatorId() + "|OPS_ALL|"
                + context.requestId();
        FeedbackCanonicalParams.Canonical canonical = FeedbackCanonicalParams.of(params);
        Optional<String> replay;
        try {
            replay = store.read(mapper -> {
                try {
                    mapper.insertBinding(freshId(), requestKey, canonical.version,
                            canonical.sha256, canonical.bytes);
                    return Optional.<String>empty();
                } catch (DuplicateKeyException bound) {
                    FeedbackTicketMapper.Binding existing = requireBinding(mapper, requestKey);
                    if (!existing.paramsSha256.equals(canonical.sha256)
                            || !java.util.Arrays.equals(existing.paramsCanonical, canonical.bytes)) {
                        throw new ParamsConflict();
                    }
                    return "SUCCEEDED".equals(existing.status)
                            ? Optional.ofNullable(existing.receiptJson) : Optional.<String>empty();
                }
            });
        } catch (ParamsConflict conflict) {
            throw new ApiException("IDEMPOTENCY_KEY_CONFLICT", "相同 requestId 对应不同参数");
        }
        if (replay.isPresent()) {
            return deserializeReceipt(replay.orElseThrow());
        }
        TransitionReceipt receipt = store.execution().execute(status -> {
            requireBinding(store.mapper(), requestKey);
            requireTicket(ticketId);
            TransitionReceipt done = work.apply(requestKey);
            if (store.mapper().markSucceeded(requestKey, serializeReceipt(done)) != 1) {
                throw new IllegalStateException("Binding completion pairing violated; rolled back");
            }
            return done;
        });
        return receipt;
    }

    private FeedbackTicketMapper.Binding requireBinding(FeedbackTicketMapper mapper, String requestKey) {
        FeedbackTicketMapper.Binding binding = mapper.selectBindingForUpdate(requestKey);
        if (binding == null) {
            throw new IllegalStateException("Idempotency binding disappeared; transaction rolled back");
        }
        return binding;
    }

    private FeedbackTicketMapper.TicketRow requireTicket(long ticketId) {
        FeedbackTicketMapper.TicketRow row = store.mapper().findTicketForOps(ticketId);
        if (row == null) throw notFound();
        return row;
    }

    /** Builds the transition receipt; runBound persists it once on the binding. */
    private TransitionReceipt receipt(String requestKey, long ticketId, String status, long version) {
        return new TransitionReceipt(Long.toUnsignedString(ticketId), status,
                version, clock.instant().atOffset(java.time.ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ frame

    public static final class ParamsConflict extends RuntimeException {
        public ParamsConflict() {
            super("same requestId with different parameters");
        }
    }

    private static QueryContext operatorContextOf(CommandContext context) {
        return new QueryContext(context.traceId(), context.operatorType(), context.operatorId());
    }

    // ------------------------------------------------------------------ helpers

    private void enforceRateLimit(long userId) {
        long minute = clock.instant().getEpochSecond() / 60;
        String key = Long.toUnsignedString(userId);
        Window updated = rateWindow.compute(key, (ignored, current) -> current == null
                || current.minute() != minute ? new Window(minute, 1)
                : new Window(current.minute(), current.count() + 1));
        if (updated.count() > RATE_LIMIT_PER_MINUTE) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.RATE_LIMITED,
                    "提交过于频繁，请稍后重试");
        }
    }

    /** Re-proves every attachment READY and owned by the submitter under the 31号 purpose. */
    private List<Long> validateAttachments(CommandContext context, List<String> assetIdTexts) {
        if (assetIdTexts == null || assetIdTexts.isEmpty()) return List.of();
        if (assetIdTexts.size() > ATTACHMENT_MAX) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT,
                    "附件最多 " + ATTACHMENT_MAX + " 张");
        }
        LinkedHashSet<Long> assetIds = new LinkedHashSet<>();
        for (String assetIdText : assetIdTexts) {
            long assetId = PetNumericIds.parse(assetIdText);
            if (assetId <= 0) {
                throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT,
                        "附件编号不合法");
            }
            if (!assetIds.add(assetId)) {
                throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT,
                        "附件编号重复");
            }
        }
        List<com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetFact> facts =
                assets.resolveOwned(new ResolveOwnedPrivateAssetsQuery(context.operatorId(),
                        List.copyOf(assetIdTexts), ATTACHMENT_PURPOSE));
        for (var fact : facts) {
            if (fact.status() != PrivateAssetStatus.READY) {
                throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT,
                        "附件尚未就绪，请重新上传后再提交");
            }
        }
        return List.copyOf(assetIds);
    }

    private List<TicketReply> repliesOf(FeedbackTicketMapper.TicketRow row) {
        if (row.reply == null) return List.of();
        return List.of(new TicketReply(row.reply, iso(row.repliedAt)));
    }

    private static List<String> attachmentIds(FeedbackTicketMapper mapper, long ticketId) {
        return mapper.selectAttachments(ticketId).stream()
                .map(row -> Long.toUnsignedString(row.assetId)).toList();
    }

    private void authorizeCollection(FeedbackOpsAuthorizationPort.Authorization authorization,
            QueryContext context, String actionCode) {
        requireAuthorizationReference(authorization);
        var decision = opsAuthorization.checkCollection(
                new FeedbackOpsAuthorizationPort.CollectionCheck(
                        authorization.sessionId(), authorization.sessionGeneration(),
                        context.operatorId(), actionCode, "FEEDBACK_OPS",
                        FeedbackOpsAuthorizationPort.Phase.READ_RESULT));
        requireDecision(decision);
    }

    private void authorizeRow(FeedbackOpsAuthorizationPort.Authorization authorization,
            QueryContext context, String actionCode, long ticketId,
            FeedbackOpsAuthorizationPort.Phase phase) {
        requireAuthorizationReference(authorization);
        var decision = opsAuthorization.check(
                new FeedbackOpsAuthorizationPort.Check(
                        authorization.sessionId(), authorization.sessionGeneration(),
                        context.operatorId(), actionCode,
                        new FeedbackOpsAuthorizationPort.Resource(
                                "FEEDBACK_TICKET", Long.toUnsignedString(ticketId), null, null, null),
                        "FEEDBACK_OPS", phase));
        requireDecision(decision);
    }

    private void requireAuthorizationReference(FeedbackOpsAuthorizationPort.Authorization authorization) {
        if (authorization == null || authorization.sessionId() == null
                || authorization.sessionId().isBlank() || authorization.sessionGeneration() <= 0) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.UNAUTHORIZED,
                    "authorization reference is required");
        }
    }

    private void requireDecision(FeedbackOpsAuthorizationPort.Decision decision) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "授权决断不可用");
        }
        if (!decision.allowed()) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.FORBIDDEN, "无权执行该操作");
        }
    }

    private static QueryContext operatorContext(QueryContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null
                || context.operatorId().isBlank()) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.UNAUTHORIZED,
                    "authenticated query context is required");
        }
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.FORBIDDEN,
                    "feedback ops belongs to operators");
        }
        return context;
    }

    private static CommandContext trustedUserContext(CommandContext context) {
        CommandContext checked = PublicContractChecks.requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.USER) {
            throw new IllegalArgumentException("Feedback commands require a USER operator");
        }
        return checked;
    }

    private static CommandContext trustedOperatorContext(CommandContext context) {
        CommandContext checked = PublicContractChecks.requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new IllegalArgumentException("Feedback ops commands require an operator");
        }
        return checked;
    }

    private static long actorUserId(CommandContext context) {
        return actorId(context.operatorId());
    }

    private static long actorId(String ownerUserId) {
        long userId = PetNumericIds.parse(ownerUserId);
        if (userId <= 0) throw new ApiException(com.petplatform.common.CommonApiCodes.UNAUTHORIZED,
                "user unavailable");
        return userId;
    }

    private long freshId() {
        long id = ids.nextId();
        if (id <= 0) throw new IllegalStateException("Invalid ID from provider");
        return id;
    }

    private static long parseTicketId(String ticketIdText) {
        long ticketId = PetNumericIds.parse(ticketIdText);
        if (ticketId <= 0) throw notFound();
        return ticketId;
    }

    private static String requireText(String value, int min, int max, String field) {
        if (value == null || value.codePointCount(0, value.length()) < min
                || value.codePointCount(0, value.length()) > max) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
        return value;
    }

    private static String statusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        if (!OPS_STATUS_FILTERS.contains(status)) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT,
                    "status 仅支持 PENDING/PROCESSING/RESOLVED");
        }
        return status;
    }

    private static String typeFilter(String type) {
        if (type == null || type.isBlank()) return null;
        if (!TYPES.contains(type)) {
            throw new ApiException("FEEDBACK_TYPE_INVALID", "反馈类型不合法");
        }
        return type;
    }

    private static int[] pageBounds(Integer page, Integer pageSize) {
        int currentPage = page == null ? 1 : page;
        int currentPageSize = pageSize == null ? 20 : pageSize;
        if (currentPage < 1 || currentPage > PAGE_MAX || currentPageSize < 1
                || currentPageSize > PAGE_SIZE_MAX) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
        return new int[] {currentPage, currentPageSize};
    }

    private static ApiException notFound() {
        return new ApiException("FEEDBACK_TICKET_NOT_FOUND", "反馈工单不存在");
    }

    private static ApiException conflict() {
        return new ApiException(com.petplatform.common.CommonApiCodes.CONFLICT,
                "当前状态或版本冲突，请刷新后核对");
    }

    private static TicketSummary toSummary(FeedbackTicketMapper.TicketRow row) {
        return new TicketSummary(Long.toUnsignedString(row.id), row.type, row.status,
                preview(row.content), iso(row.createdAt));
    }

    static String preview(String content) {
        if (content == null) return "";
        int[] codePoints = content.codePoints().limit(PREVIEW_MAX + 1).toArray();
        return codePoints.length <= PREVIEW_MAX ? content
                : new String(codePoints, 0, PREVIEW_MAX);
    }

    private static String text(long value) {
        return Long.toUnsignedString(value);
    }

    private static String serialize(TicketSummary receipt) {
        try {
            return CODEC.writeValueAsString(receipt);
        } catch (Exception failure) {
            throw new IllegalStateException("Feedback receipt serialization failed", failure);
        }
    }

    private static TicketSummary deserialize(String raw) {
        try {
            return CODEC.readValue(raw, TicketSummary.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Feedback receipt deserialization failed", failure);
        }
    }

    private static String serializeReceipt(TransitionReceipt receipt) {
        try {
            return CODEC.writeValueAsString(receipt);
        } catch (Exception failure) {
            throw new IllegalStateException("Feedback receipt serialization failed", failure);
        }
    }

    private static TransitionReceipt deserializeReceipt(String raw) {
        try {
            return CODEC.readValue(raw, TransitionReceipt.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Feedback receipt deserialization failed", failure);
        }
    }

    private static String iso(java.time.LocalDateTime value) {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(value.toInstant(java.time.ZoneOffset.UTC));
    }

    private String isoNow() {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(clock.instant());
    }

    /** Pet-user style decimal Snowflake parsing; module-local to avoid cross-module helpers. */
    private static final class PetNumericIds {
        private PetNumericIds() {}

        static long parse(String value) {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) return -1;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException malformed) {
                return -1;
            }
        }
    }
}
