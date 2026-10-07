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
import com.petplatform.review.biz.infrastructure.persistence.ReviewStore;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewRowEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REV-001 review kernel (SSOT §11 + HTTP contract 10 §3.14 + internal 07 §7.7/§14). Every
 * admission fact is borrowed, never recomputed here: the ORDER domain answers §7.7
 * eligibility (verification, verifiedAt+30d window, refund exclusions incl. the 2026-10-07
 * refund-success ruling) and owns the order coordinates; this kernel adds only its own
 * facts — one review per order (uk_review_order backstop) and the first-success receipt on
 * the five-tuple requestId binding (supplement 23; 14号 command_idempotency storage). The
 * composite is SSOT §11.1's exact 40/40/20 in one decimal (4·store+4·service+2·staff tenths,
 * no rounding exists); scoreIncluded carries SSOT §11.2's partial-refund exclusion forward
 * from the eligibility verdict. The REV-002 appeal flow is not part of this kernel.
 */
public final class ReviewService implements ReviewQueryApi, ReviewCommandApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Contract 10 §3.14 / OpenAPI11 CreateReviewRequest: 1..5 per dimension, 2000 code points. */
    private static final int MAX_CONTENT_CODE_POINTS = 2000;

    private final ReviewStore store;
    private final OrderQueryApi orders;
    private final Clock clock;

    public ReviewService(ReviewStore store, OrderQueryApi orders) {
        this(store, orders, Clock.systemUTC());
    }

    public ReviewService(ReviewStore store, OrderQueryApi orders, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.orders = Objects.requireNonNull(orders, "orders is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
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
