package com.petplatform.review.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.dto.OrderSnapshotDTO;
import com.petplatform.order.api.dto.ReviewEligibilityDTO;
import com.petplatform.order.api.query.OrderQueryApi.OrderIdQuery;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.review.api.command.ReviewCommandApi;
import com.petplatform.review.api.query.ReviewQueryApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.review.biz.infrastructure.persistence.ReviewStore;
import com.petplatform.review.biz.infrastructure.persistence.entity.AppealListRowEntity;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewAppealRowEntity;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewListRowEntity;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewRowEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REV-001 review kernel (SSOT §11 + HTTP contract 10 §3.14 + internal 07 §7.7/§14). Every
 * admission fact is borrowed, never recomputed here: the ORDER domain answers §7.7
 * eligibility (verification, verifiedAt+30d window, refund exclusions incl. the 2026-10-07
 * refund-success ruling) and owns the order coordinates; this kernel adds only its own
 * facts — one review per order (uk_review_order backstop) and the first-success receipt on
 * the five-tuple requestId binding (supplement 23; 14号 command_idempotency storage). The
 * composite is SSOT §11.1's exact 40/40/20 in one decimal (4·store+4·service+2·staff tenths,
 * no rounding exists); scoreIncluded carries SSOT §11.2's partial-refund exclusion forward
 * from the eligibility verdict. The REV-002 appeal flow (CCR-REVIEW-APPEAL-001 / contract 56,
 * 2026-10-10) rides the same kernel: one appeal per review (SSOT §11.3 + uk_review_appeal_once),
 * operator decisions are Schema06's terminal APPROVED/REJECTED only, APPROVED hides the review,
 * and no aftersale re-review exists anywhere in this surface.
 */
public final class ReviewService implements ReviewQueryApi, ReviewCommandApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Contract 10 §3.14 / OpenAPI11 CreateReviewRequest: 1..5 per dimension, 2000 code points. */
    private static final int MAX_CONTENT_CODE_POINTS = 2000;

    private final ReviewStore store;
    private final OrderQueryApi orders;
    private final Clock clock;
    private final ScheduleCapacityGuardApi guard;
    private final ReviewAppealPorts appealAuthority;

    public ReviewService(ReviewStore store, OrderQueryApi orders) {
        this(store, orders, Clock.systemUTC());
    }

    public ReviewService(ReviewStore store, OrderQueryApi orders, Clock clock) {
        this(store, orders, clock, null, null);
    }

    /** REV-002 assembly: the appeal faces additionally need the store guard and the port-side
     *  OWNER/admin authority (boot assembles them under pet.review.appeal.enabled). */
    public ReviewService(ReviewStore store, OrderQueryApi orders, Clock clock,
            ScheduleCapacityGuardApi guard, ReviewAppealPorts appealAuthority) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.orders = Objects.requireNonNull(orders, "orders is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.guard = guard;
        this.appealAuthority = appealAuthority;
    }

    // ------------------------------------------------------------------ §14.1 read

    @Override
    public ReviewEligibilityDTO checkEligibility(ReviewEligibilityQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = userContext(query.context());
        ReviewEligibilityDTO order = orders.checkReviewEligibility(
                new OrderIdQuery(query.orderId(), context));
        ReviewRowEntity existing = store.execute(m -> m.selectReviewByOrderIdForUpdate(id(query.orderId())));
        if (existing != null) {
            // Registry 12 §11: one review per order — the review domain's own overlay; the
            // order-fact window stays reported so the page can still show the deadline fact.
            return new ReviewEligibilityDTO(false, false, order.reviewDeadline(), "REVIEW_ALREADY_EXISTS");
        }
        return order;
    }

    // ------------------------------------------------------------------ §14.2 write

    @Override
    public ReviewCreateResult create(ReviewCreateCommand command) {
        return createWithOutcome(command).result();
    }

    @Override
    public CreationOutcome createWithOutcome(ReviewCreateCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = userContext(command.context());
        long orderId = id(command.orderId());
        int storeScore = score(command.storeScore(), "storeScore");
        int serviceScore = score(command.serviceScore(), "serviceScore");
        int staffScore = score(command.staffScore(), "staffScore");
        String content = content(command.content());
        if (command.mediaFileIds() != null && !command.mediaFileIds().isEmpty()) {
            // The contract keeps mediaFileIds in the shape; the media capability is not open
            // in this slice, so a non-empty list is a closed 400, never a silent drop.
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "评价媒体附件未开放");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", command.orderId());
        fields.put("storeScore", storeScore);
        fields.put("serviceScore", serviceScore);
        fields.put("staffScore", staffScore);
        fields.put("content", content);
        fields.put("mediaFileIds", List.of());
        ReviewCanonicalParams.Canonical canonical = ReviewCanonicalParams.of(fields);
        String requestKey = ReviewStore.requestKey("review.create", context.operatorType().name(),
                context.operatorId(), "ORDER:" + command.orderId(), context.requestId());

        QueryContext read = new QueryContext(context.traceId(), context.operatorType(), context.operatorId());
        ReviewStore.Binding binding = store.admit(requestKey, canonical);
        ReviewStore.same(binding, canonical);
        if ("SUCCEEDED".equals(binding.status())) {
            return new CreationOutcome(replay(binding, orderId, read), false);
        }
        return store.execute(m -> {
            ReviewStore.Binding locked = ReviewStore.require(m, requestKey);
            ReviewStore.same(locked, canonical);
            if ("SUCCEEDED".equals(locked.status())) {
                return new CreationOutcome(replay(locked, orderId, read), false);
            }

            // §5.3: eligibility and ownership re-proven under the execution lock, every time.
            ReviewEligibilityDTO eligibility =
                    orders.checkReviewEligibility(new OrderIdQuery(command.orderId(), read));
            if (!eligibility.eligible()) {
                throw new ApiException(eligibility.rejectCode(), "订单当前不可评价");
            }
            OrderSnapshotDTO snapshot = orders.getOrder(new OrderIdQuery(command.orderId(), read));
            if (m.selectReviewByOrderIdForUpdate(orderId) != null) {
                throw new ApiException("REVIEW_ALREADY_EXISTS", "该订单已评价");
            }

            long reviewId = store.nextId();
            BigDecimal composite = BigDecimal.valueOf(
                    4L * storeScore + 4L * serviceScore + 2L * staffScore, 1);
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            try {
                if (m.insertReview(reviewId, orderId, IDS.fromApi(context.operatorId()),
                        IDS.fromApi(snapshot.merchantId()), IDS.fromApi(snapshot.storeId()),
                        IDS.fromApi(snapshot.serviceId()),
                        BigDecimal.valueOf(storeScore), BigDecimal.valueOf(serviceScore),
                        BigDecimal.valueOf(staffScore), composite, eligibility.scoreIncluded(),
                        content, now) != 1) {
                    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评价写入失败");
                }
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                // uk_review_order race backstop: the sibling command won the row first.
                throw new ApiException("REVIEW_ALREADY_EXISTS", "该订单已评价");
            }
            ReviewCreateResult receipt = new ReviewCreateResult(IDS.toApi(reviewId), eligibility.scoreIncluded());
            String receiptJson;
            try {
                receiptJson = JSON.writeValueAsString(
                        Map.of("reviewId", receipt.reviewId(), "scoreIncluded", receipt.scoreIncluded()));
            } catch (Exception failure) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评价回执序列化失败");
            }
            if (m.markBindingSucceeded(requestKey, receiptJson) != 1) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评价幂等回执提交失败");
            }
            return new CreationOutcome(receipt, true);
        });
    }

    /**
     * §5.4 protected replay: current permission re-proven (the order must still be visible to
     * this session via the order module's anti-enumeration), then the first receipt returns.
     */
    private ReviewCreateResult replay(ReviewStore.Binding binding, long orderId, QueryContext context) {
        orders.checkReviewEligibility(new OrderIdQuery(IDS.toApi(orderId), context));
        try {
            Map<?, ?> saved = JSON.readValue(binding.receiptJson(), Map.class);
            return new ReviewCreateResult(IDS.toApi(IDS.fromApi(String.valueOf(saved.get("reviewId")))),
                    Boolean.TRUE.equals(saved.get("scoreIncluded")));
        } catch (RuntimeException damaged) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评价幂等回执不可读");
        } catch (Exception damaged) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评价幂等回执不可读");
        }
    }

    // ------------------------------------------------------------------ §14.2 appeal (REV-002)

    @Override
    public ReviewAppealResult appeal(ReviewAppealCommand command) {
        return appealWithOutcome(command).result();
    }

    @Override
    public AppealOutcome appealWithOutcome(ReviewAppealCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = userAppealContext(command.context());
        requireAppealAssembled();
        if (context.requestId() == null) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "requestId is required");
        }
        String reviewIdApi = idString(command.reviewId());
        String reason = appealReason(command.reason());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reviewId", reviewIdApi);
        fields.put("reason", reason);
        ReviewCanonicalParams.Canonical canonical = ReviewCanonicalParams.of(fields);
        String requestKey = ReviewStore.requestKey("review.appeal", context.operatorType().name(),
                context.operatorId(), "REVIEW:" + reviewIdApi, context.requestId());

        ReviewStore.Binding binding = store.admit(requestKey, canonical);
        ReviewStore.same(binding, canonical);
        if ("SUCCEEDED".equals(binding.status())) {
            return new AppealOutcome(replayAppeal(binding, context, reviewIdApi), false);
        }
        return store.execute(m -> {
            ReviewStore.Binding locked = ReviewStore.require(m, requestKey);
            ReviewStore.same(locked, canonical);
            if ("SUCCEEDED".equals(locked.status())) {
                return new AppealOutcome(replayAppeal(locked, context, reviewIdApi), false);
            }

            // Existence and OWNER authority are re-proven under the execution lock; a foreign
            // review shares the missing review's single REVIEW_NOT_FOUND (56号 anti-enumeration).
            ReviewRowEntity review = m.selectReviewByIdForUpdate(IDS.fromApi(reviewIdApi));
            if (review == null) throw new ApiException("REVIEW_NOT_FOUND", "评价不存在");
            requireOwner(context, review);
            if (m.selectAppealByReviewIdForUpdate(review.getId()) != null) {
                throw new ApiException("REVIEW_APPEAL_ALREADY_USED", "每条评价最多申诉一次");
            }

            long appealId = store.nextId();
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            try {
                if (m.insertAppeal(appealId, review.getId(), review.getMerchantId(), reason, now) != 1) {
                    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评价申诉写入失败");
                }
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                // uk_review_appeal_once race backstop: the sibling command won the appeal first.
                throw new ApiException("REVIEW_APPEAL_ALREADY_USED", "每条评价最多申诉一次");
            }
            ReviewAppealResult receipt = new ReviewAppealResult(
                    IDS.toApi(appealId), reviewIdApi, "SUBMITTED", instant(now));
            markSucceeded(m, requestKey, appealReceipt(receipt));
            return new AppealOutcome(receipt, true);
        });
    }

    /**
     * §5.4 protected replay: current OWNER authority re-proven under the store guard (the
     * MER check requires a held guard, so the proof runs inside this transaction), then the
     * first receipt returns.
     */
    private ReviewAppealResult replayAppeal(
            ReviewStore.Binding binding, CommandContext context, String reviewIdApi) {
        return store.execute(m -> {
            ReviewRowEntity review = m.selectReviewById(IDS.fromApi(reviewIdApi));
            if (review == null) throw new ApiException("REVIEW_NOT_FOUND", "评价不存在");
            requireOwner(context, review);
            try {
                Map<?, ?> saved = JSON.readValue(binding.receiptJson(), Map.class);
                return new ReviewAppealResult(String.valueOf(saved.get("appealId")),
                        String.valueOf(saved.get("reviewId")), String.valueOf(saved.get("status")),
                        String.valueOf(saved.get("createdAt")));
            } catch (Exception damaged) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评价申诉幂等回执不可读");
            }
        });
    }

    // ------------------------------------------------------------------ appeal decision (REV-002)

    @Override
    public ReviewAppealDecisionResult decide(ReviewAppealDecisionCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        Objects.requireNonNull(context, "context is required");
        requireAppealAssembled();
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.requestId() == null) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "仅运营可裁决评价申诉");
        }
        String appealIdApi = idString(command.appealId());
        String decisionType = command.decisionType();
        if (!"APPROVED".equals(decisionType) && !"REJECTED".equals(decisionType)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "裁决类型不合法");
        }
        String reason = appealReason(command.reason());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("appealId", appealIdApi);
        fields.put("decisionType", decisionType);
        fields.put("reason", reason);
        ReviewCanonicalParams.Canonical canonical = ReviewCanonicalParams.of(fields);
        String requestKey = ReviewStore.requestKey("review.appeal.decide",
                context.operatorType().name(), context.operatorId(),
                "APPEAL:" + appealIdApi, context.requestId());

        ReviewStore.Binding binding = store.admit(requestKey, canonical);
        ReviewStore.same(binding, canonical);
        if ("SUCCEEDED".equals(binding.status())) {
            return replayDecision(binding, context, appealIdApi);
        }
        return store.execute(m -> {
            ReviewStore.Binding locked = ReviewStore.require(m, requestKey);
            ReviewStore.same(locked, canonical);
            if ("SUCCEEDED".equals(locked.status())) {
                return replayDecision(locked, context, appealIdApi);
            }

            ReviewAppealRowEntity appeal = m.selectAppealByIdForUpdate(IDS.fromApi(appealIdApi));
            if (appeal == null) throw new ApiException(CommonApiCodes.NOT_FOUND, "评价申诉不存在");
            ReviewRowEntity review = m.selectReviewByIdForUpdate(appeal.getReviewId());
            if (review == null) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "申诉引用的评价不可读");
            }
            requireAppealAdmin(context, appeal, review, "review.appeal.decide");
            if (!"SUBMITTED".equals(appeal.getStatus())) {
                // A decided appeal is final: no re-decision, no second appeal (56号 §4).
                throw new ApiException(CommonApiCodes.CONFLICT, "该申诉已裁决，不可改判");
            }

            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (m.decideAppeal(appeal.getId(), decisionType, reason,
                    IDS.fromApi(context.operatorId()), now) != 1) {
                throw new ApiException(CommonApiCodes.CONFLICT, "该申诉已裁决，不可改判");
            }
            String visibility = review.getVisibilityStatus();
            if ("APPROVED".equals(decisionType)) {
                // The violating review stops public display; an already-hidden row is a no-op
                // with the same terminal fact (56号 §4).
                m.hideReview(review.getId(), now);
                visibility = "HIDDEN";
            }
            ReviewAppealDecisionResult receipt = new ReviewAppealDecisionResult(appealIdApi,
                    IDS.toApi(appeal.getReviewId()), decisionType, decisionType, reason,
                    instant(now), visibility);
            markSucceeded(m, requestKey, decisionReceipt(receipt));
            return receipt;
        });
    }

    /** Protected replay re-proves the current admin action under the guard, then the receipt. */
    private ReviewAppealDecisionResult replayDecision(
            ReviewStore.Binding binding, CommandContext context, String appealIdApi) {
        return store.execute(m -> {
            ReviewAppealRowEntity appeal = m.selectAppealById(IDS.fromApi(appealIdApi));
            if (appeal == null) throw new ApiException(CommonApiCodes.NOT_FOUND, "评价申诉不存在");
            ReviewRowEntity review = m.selectReviewById(appeal.getReviewId());
            if (review == null) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "申诉引用的评价不可读");
            }
            requireAppealAdmin(context, appeal, review, "review.appeal.decide");
            try {
                Map<?, ?> saved = JSON.readValue(binding.receiptJson(), Map.class);
                return new ReviewAppealDecisionResult(String.valueOf(saved.get("appealId")),
                        String.valueOf(saved.get("reviewId")), String.valueOf(saved.get("status")),
                        String.valueOf(saved.get("decisionType")),
                        String.valueOf(saved.get("decisionReason")),
                        String.valueOf(saved.get("decidedAt")),
                        String.valueOf(saved.get("reviewVisibility")));
            } catch (Exception damaged) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "申诉裁决幂等回执不可读");
            }
        });
    }

    // ------------------------------------------------------------------ appeal reads (REV-002)

    @Override
    public ReviewPage listStoreReviews(StoreReviewListQuery query) {
        Objects.requireNonNull(query, "query is required");
        CommandContext context = userAppealContext(query.context());
        requireAppealAssembled();
        long merchantId = IDS.fromApi(idString(query.merchantId()));
        long storeId = IDS.fromApi(idString(query.storeId()));
        int page = page(query.page(), 1, 10_000);
        int pageSize = page(query.pageSize(), 1, 50);
        return store.execute(m -> {
            // The claimed coordinates are proven under the store guard before any row is read.
            acquireGuard(query.storeId(), context);
            appealAuthority.requireReviewOwnerRead(context, query.merchantId(), query.storeId());
            long total = m.countStoreReviews(merchantId, storeId);
            List<ReviewSummary> items = m.pageStoreReviews(merchantId, storeId,
                    (long) (page - 1) * pageSize, pageSize).stream().map(ReviewService::summary)
                    .toList();
            return new ReviewPage(page, pageSize, total, items);
        });
    }

    @Override
    public ReviewDetail getStoreReview(StoreReviewGetQuery query) {
        Objects.requireNonNull(query, "query is required");
        CommandContext context = userAppealContext(query.context());
        requireAppealAssembled();
        String reviewIdApi = idString(query.reviewId());
        return store.execute(m -> {
            ReviewRowEntity review = m.selectReviewById(IDS.fromApi(reviewIdApi));
            // Foreign and missing reviews share one REVIEW_NOT_FOUND (56号 anti-enumeration);
            // the read itself rides the OWNER read family (ACTIVE/OFFLINE/FROZEN all readable).
            if (review == null) throw new ApiException("REVIEW_NOT_FOUND", "评价不存在");
            acquireGuard(IDS.toApi(review.getStoreId()), context);
            try {
                appealAuthority.requireReviewOwnerRead(context,
                        IDS.toApi(review.getMerchantId()), IDS.toApi(review.getStoreId()));
            } catch (ApiException denied) {
                if (!CommonApiCodes.FORBIDDEN.equals(denied.code())) throw denied;
                throw new ApiException("REVIEW_NOT_FOUND", "评价不存在");
            }
            ReviewAppealRowEntity appeal = m.selectAppealByReviewId(review.getId());
            return detail(summaryOf(review), appeal);
        });
    }

    @Override
    public AppealPage listAppeals(AppealListQuery query) {
        Objects.requireNonNull(query, "query is required");
        CommandContext context = adminReadContext(query.context());
        requireAppealAssembled();
        String status = query.status();
        if (status != null && !APPEAL_STATUSES.contains(status)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "申诉状态不合法");
        }
        int page = page(query.page(), 1, 10_000);
        int pageSize = page(query.pageSize(), 1, 50);
        return store.execute(m -> {
            appealAuthority.requireAppealAdminList(context);
            long total = m.countAppeals(status);
            List<AppealSummary> items = m.pageAppeals(status,
                    (long) (page - 1) * pageSize, pageSize).stream()
                    .map(ReviewService::appealSummary).toList();
            return new AppealPage(page, pageSize, total, items);
        });
    }

    @Override
    public AppealDetail getAppeal(AppealGetQuery query) {
        Objects.requireNonNull(query, "query is required");
        CommandContext context = adminReadContext(query.context());
        requireAppealAssembled();
        String appealIdApi = idString(query.appealId());
        return store.execute(m -> {
            ReviewAppealRowEntity appeal = m.selectAppealById(IDS.fromApi(appealIdApi));
            if (appeal == null) throw new ApiException(CommonApiCodes.NOT_FOUND, "评价申诉不存在");
            ReviewRowEntity review = m.selectReviewById(appeal.getReviewId());
            if (review == null) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "申诉引用的评价不可读");
            }
            requireAppealAdmin(context, appeal, review, "review.appeal.read");
            return new AppealDetail(appealSummary(of(appeal, review)), summaryOf(review),
                    appeal.getDecisionReason(),
                    appeal.getDecidedBy() == null ? null : IDS.toApi(appeal.getDecidedBy()));
        });
    }

    // ------------------------------------------------------------------ appeal helpers

    private static final Set<String> APPEAL_STATUSES =
            Set.of("SUBMITTED", "PROCESSING", "APPROVED", "REJECTED");

    /** Appeal writes/reads ride the MINIAPP USER session the OWNER face uses (56号 §1). */
    private static CommandContext userAppealContext(CommandContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorType() != OperatorType.USER || context.operatorId() == null) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "仅商家 OWNER 可操作评价申诉");
        }
        return context;
    }

    private static CommandContext adminReadContext(CommandContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "仅运营可读取评价申诉");
        }
        return context;
    }

    /** Appeal faces are assembled under pet.review.appeal.enabled; anything else fails closed. */
    private void requireAppealAssembled() {
        if (guard == null || appealAuthority == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评价申诉能力未装配");
        }
    }

    /** Guard + OWNER write authority inside the caller's execution transaction. */
    private void requireOwner(CommandContext context, ReviewRowEntity review) {
        requireAppealAssembled();
        acquireGuard(IDS.toApi(review.getStoreId()), context);
        try {
            appealAuthority.requireReviewOwner(context,
                    IDS.toApi(review.getMerchantId()), IDS.toApi(review.getStoreId()));
        } catch (ApiException denied) {
            if (!CommonApiCodes.FORBIDDEN.equals(denied.code())) throw denied;
            // Anti-enumeration: a foreign review answers exactly like a missing one.
            throw new ApiException("REVIEW_NOT_FOUND", "评价不存在");
        }
    }

    /** Guard + admin action check inside the caller's transaction (resource REVIEW_APPEAL). */
    private void requireAppealAdmin(
            CommandContext context, ReviewAppealRowEntity appeal, ReviewRowEntity review,
            String action) {
        requireAppealAssembled();
        acquireGuard(IDS.toApi(review.getStoreId()), context);
        appealAuthority.requireAppealAdmin(context, IDS.toApi(appeal.getId()),
                IDS.toApi(appeal.getMerchantId()), IDS.toApi(review.getStoreId()), action);
    }

    private void acquireGuard(String storeId, CommandContext context) {
        guard.acquire(List.of(storeId), new QueryContext(
                context.traceId(), OperatorType.SYSTEM, null));
    }

    /** Schema06 reason: NOT NULL VARCHAR(1000) — trimmed non-blank, bounded code points. */
    private static String appealReason(String value) {
        if (value == null) throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "申诉理由必填");
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 1000
                || trimmed.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "申诉理由须为1~1000个字符");
        }
        return trimmed;
    }

    private static int page(Integer value, int min, int max) {
        if (value == null) return min;
        if (value < min || value > max) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "分页参数不合法");
        }
        return value;
    }

    private static void markSucceeded(
            com.petplatform.review.biz.infrastructure.persistence.mapper.ReviewCommandMapper m,
            String requestKey, String receiptJson) {
        if (m.markBindingSucceeded(requestKey, receiptJson) != 1) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "幂等回执提交失败");
        }
    }

    private static String appealReceipt(ReviewAppealResult receipt) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "appealId", receipt.appealId(), "reviewId", receipt.reviewId(),
                    "status", receipt.status(), "createdAt", receipt.createdAt()));
        } catch (Exception failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "申诉回执序列化失败");
        }
    }

    private static String decisionReceipt(ReviewAppealDecisionResult receipt) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "appealId", receipt.appealId(), "reviewId", receipt.reviewId(),
                    "status", receipt.status(), "decisionType", receipt.decisionType(),
                    "decisionReason", receipt.decisionReason(),
                    "decidedAt", receipt.decidedAt(),
                    "reviewVisibility", receipt.reviewVisibility()));
        } catch (Exception failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "裁决回执序列化失败");
        }
    }

    private static String instant(LocalDateTime value) {
        return value == null ? null
                : new DateTimeFormatterBuilder().appendInstant(3)
                        .toFormatter().format(value.toInstant(ZoneOffset.UTC));
    }

    private static String score(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static ReviewSummary summaryOf(ReviewRowEntity review) {
        return new ReviewSummary(IDS.toApi(review.getId()), IDS.toApi(review.getOrderId()),
                score(review.getStoreScore()), score(review.getServiceScore()),
                score(review.getStaffScore()), score(review.getCompositeScore()),
                Boolean.TRUE.equals(review.getScoreIncluded()), review.getVisibilityStatus(),
                review.getContent(), instant(review.getCreatedAt()), null, null);
    }

    private static ReviewSummary summary(ReviewListRowEntity row) {
        return new ReviewSummary(IDS.toApi(row.getId()), IDS.toApi(row.getOrderId()),
                score(row.getStoreScore()), score(row.getServiceScore()),
                score(row.getStaffScore()), score(row.getCompositeScore()),
                Boolean.TRUE.equals(row.getScoreIncluded()), row.getVisibilityStatus(),
                row.getContent(), instant(row.getCreatedAt()), row.getAppealStatus(),
                row.getAppealId() == null ? null : IDS.toApi(row.getAppealId()));
    }

    private static ReviewDetail detail(ReviewSummary review, ReviewAppealRowEntity appeal) {
        if (appeal == null) return new ReviewDetail(review, null, null, null, null);
        return new ReviewDetail(
                new ReviewSummary(review.reviewId(), review.orderId(), review.storeScore(),
                        review.serviceScore(), review.staffScore(), review.compositeScore(),
                        review.scoreIncluded(), review.visibilityStatus(), review.content(),
                        review.createdAt(), appeal.getStatus(), IDS.toApi(appeal.getId())),
                appeal.getReason(), instant(appeal.getCreatedAt()),
                appeal.getDecisionReason(), instant(appeal.getDecidedAt()));
    }

    private static AppealListRowEntity of(ReviewAppealRowEntity appeal, ReviewRowEntity review) {
        AppealListRowEntity row = new AppealListRowEntity();
        row.setId(appeal.getId());
        row.setReviewId(appeal.getReviewId());
        row.setMerchantId(appeal.getMerchantId());
        row.setStoreId(review.getStoreId());
        row.setOrderId(review.getOrderId());
        row.setStatus(appeal.getStatus());
        row.setReason(appeal.getReason());
        row.setCreatedAt(appeal.getCreatedAt());
        row.setDecidedAt(appeal.getDecidedAt());
        return row;
    }

    private static AppealSummary appealSummary(AppealListRowEntity row) {
        return new AppealSummary(IDS.toApi(row.getId()), IDS.toApi(row.getReviewId()),
                IDS.toApi(row.getMerchantId()), IDS.toApi(row.getStoreId()),
                IDS.toApi(row.getOrderId()), row.getStatus(), row.getReason(),
                instant(row.getCreatedAt()), instant(row.getDecidedAt()));
    }

    // ------------------------------------------------------------------ validation

    private static QueryContext userContext(QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorType() != OperatorType.USER || context.operatorId() == null) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "仅订单本人可评价");
        }
        return context;
    }

    private static CommandContext userContext(CommandContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorType() != OperatorType.USER || context.operatorId() == null) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "仅订单本人可评价");
        }
        return context;
    }

    /** Validates and returns the canonical decimal string (appeal faces keep String IDs). */
    private static String idString(String value) {
        try {
            return IDS.toApi(IDS.fromApi(value));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "编号不合法");
        }
    }

    private static long id(String value) {
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "订单编号不合法");
        }
    }

    private static int score(int value, String name) {
        if (value < 1 || value > 5) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, name + "评分须为1~5");
        }
        return value;
    }

    private static String content(String value) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) > MAX_CONTENT_CODE_POINTS) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "评价内容过长");
        }
        return value;
    }
}
