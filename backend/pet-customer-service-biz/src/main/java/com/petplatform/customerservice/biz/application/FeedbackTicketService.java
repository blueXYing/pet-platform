package com.petplatform.customerservice.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.customerservice.biz.infrastructure.persistence.FeedbackTicketStore;
import com.petplatform.customerservice.biz.infrastructure.persistence.mapper.FeedbackTicketMapper;
import com.petplatform.user.api.query.UserActiveStatusApi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;

/**
 * Contract 67 feedback-ticket kernel (PRD 5.1.28 complaint class; CCR-CS-FEEDBACK-001
 * minimum): owner-scoped C-end creation with the frozen four-value type word, optional
 * contact, 1..500-codepoint content; the ticket lands in the frozen PENDING state (no ops
 * face in V1, no transitions); the append-only event row is the audit; creation is a
 * supplement-23 idempotent command over this module's own SQL14-mirror binding table. A
 * cheap volatile per-user rate limiter blunts abuse spikes (disclosed: not a security
 * boundary). Content is stored verbatim and read only by its owner (SSOT has no feedback
 * sanitization clause — CCR §2).
 */
public final class FeedbackTicketService {

    public static final String NAMESPACE = "feedback.create";
    /** PRD 5.1.28 four-value type vocabulary; never extended in V1. */
    public static final Set<String> TYPES = Set.of(
            "FEATURE_SUGGESTION", "BUG_REPORT", "SERVICE_COMPLAINT", "OTHER");
    /** Frozen PRD 5.1.28 unified ticket state word list; V1 only ever writes PENDING. */
    public static final String STATUS_PENDING = "PENDING";
    static final int CONTENT_MAX = 500;
    static final int CONTACT_MAX = 64;
    static final int PREVIEW_MAX = 50;
    static final int PAGE_MAX = 10_000;
    static final int PAGE_SIZE_MAX = 50;
    public static final int RATE_LIMIT_PER_MINUTE = 5;

    public record TicketSummary(String ticketId, String type, String status,
                                String contentPreview, String createdAt) {}

    public record TicketEvent(String action, String createdAt) {}

    public record TicketDetail(String ticketId, String type, String status, String content,
                               String contact, String contentPreview, String createdAt,
                               List<TicketEvent> events, List<Object> replies, Object resolution) {}

    public record TicketPage(int page, int pageSize, long total, List<TicketSummary> items) {}

    public record CreateFeedbackCommand(CommandContext context, String type, String contact,
                                        String content) {}

    public record CreateOutcome(TicketSummary receipt, boolean replayed) {}

    private final FeedbackTicketStore store;
    private final UserActiveStatusApi activeUsers;
    private final SnowflakeIdGenerator ids;
    private final java.time.Clock clock;
    private final java.util.concurrent.ConcurrentMap<String, Window> rateWindow =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final ObjectMapper CODEC = new ObjectMapper();

    private record Window(long minute, int count) {}

    public FeedbackTicketService(FeedbackTicketStore store, UserActiveStatusApi activeUsers,
                                 SnowflakeIdGenerator ids) {
        this(store, activeUsers, ids, java.time.Clock.systemUTC());
    }

    FeedbackTicketService(FeedbackTicketStore store, UserActiveStatusApi activeUsers,
                          SnowflakeIdGenerator ids, java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.activeUsers = Objects.requireNonNull(activeUsers, "user active-status port is required");
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
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
        long userId = actorUserId(context);
        enforceRateLimit(userId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", type);
        params.put("contact", contact);
        params.put("content", content);
        String requestKey = NAMESPACE + "|USER|" + context.operatorId() + "|USER_SELF|"
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
            store.mapper().insertEvent(freshId(), ticketId, "SUBMITTED", "USER", userId, null);
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
        int currentPage = page == null ? 1 : page;
        int currentPageSize = pageSize == null ? 20 : pageSize;
        if (currentPage < 1 || currentPage > PAGE_MAX || currentPageSize < 1
                || currentPageSize > PAGE_SIZE_MAX) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
        return store.read(mapper -> {
            long total = mapper.countMine(userId);
            var rows = mapper.selectMinePage(userId, currentPageSize,
                    (int) ((currentPage - 1L) * currentPageSize));
            List<TicketSummary> items = rows.stream().map(FeedbackTicketService::toSummary).toList();
            return new TicketPage(currentPage, currentPageSize, total, items);
        });
    }

    /** Owner detail; not-mine and missing are one indistinguishable 404 (anti-enumeration). */
    public TicketDetail getMine(String ownerUserId, String ticketIdText) {
        long userId = actorId(ownerUserId);
        long ticketId = PetNumericIds.parse(ticketIdText);
        if (ticketId <= 0) {
            throw new ApiException("FEEDBACK_TICKET_NOT_FOUND", "反馈工单不存在");
        }
        return store.read(mapper -> {
            FeedbackTicketMapper.TicketRow row = mapper.findTicket(ticketId, userId);
            if (row == null) throw new ApiException("FEEDBACK_TICKET_NOT_FOUND", "反馈工单不存在");
            List<TicketEvent> events = mapper.selectEvents(ticketId).stream()
                    .map(event -> new TicketEvent(event.action, iso(event.createdAt))).toList();
            return new TicketDetail(Long.toUnsignedString(row.id), row.type, row.status, row.content,
                    row.contact, preview(row.content), iso(row.createdAt), List.copyOf(events),
                    List.of(), null);
        });
    }

    // ------------------------------------------------------------------ frame

    public static final class ParamsConflict extends RuntimeException {
        public ParamsConflict() {
            super("same requestId with different parameters");
        }
    }

    private FeedbackTicketMapper.Binding requireBinding(FeedbackTicketMapper mapper, String requestKey) {
        FeedbackTicketMapper.Binding binding = mapper.selectBindingForUpdate(requestKey);
        if (binding == null) {
            throw new IllegalStateException("Idempotency binding disappeared; transaction rolled back");
        }
        return binding;
    }

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

    private static CommandContext trustedUserContext(CommandContext context) {
        CommandContext checked = PublicContractChecks.requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.USER) {
            throw new IllegalArgumentException("Feedback commands require a USER operator");
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

    private static String requireText(String value, int min, int max, String field) {
        if (value == null || value.codePointCount(0, value.length()) < min
                || value.codePointCount(0, value.length()) > max) {
            throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
        return value;
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

    private static String iso(java.time.LocalDateTime value) {
        return java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(value.toInstant(java.time.ZoneOffset.UTC));
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
