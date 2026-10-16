package com.petplatform.refund.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminApplicationView;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminAuthorization;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminExecutionView;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundDetail;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundListQuery;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundQuery;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundSummary;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Check;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.CollectionCheck;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Phase;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Resource;
import com.petplatform.refund.biz.infrastructure.persistence.AdminRefundQueryStore;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.AdminRefundQueryMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Platform refund read model for the single operator (contract 63, HTTP10 §5.2 query side).
 * Read-only: the authorization port re-proves session/action/scope inside this kernel before
 * any row is read, the fixed sort is created_at DESC/id DESC, and refund_order + execution +
 * application rows are projected without their sealed cipher material. Absent ids read as one
 * COMMON_NOT_FOUND (anti-enumeration); dataScope ALL is the only supported mode (refund rows
 * carry no uniform merchant/city attribution — CCR-PERM-001 §3).
 */
public final class AdminRefundQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_RANGE_DAYS = 366;
    static final Set<String> STATUSES = Set.of("CREATED", "PROCESSING", "SUCCESS", "FAILED", "UNKNOWN");
    static final Set<String> SOURCE_TYPES = Set.of(
            "PRESTART_AUTO", "MERCHANT_REJECT_ORDER", "MERCHANT_APPROVED", "MERCHANT_TIMEOUT_AUTO",
            "AFTERSALE_DECISION", "LATE_PAYMENT_TIMEOUT", "ADMIN_ABNORMAL_CLOSE");
    static final Set<String> REFUND_TYPES = Set.of("FULL", "PARTIAL");
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final AdminRefundQueryStore store;
    private final AdminTradeAuthorizationPort.RefundAuthorizationPort authorization;

    public AdminRefundQueryService(
            AdminRefundQueryStore store,
            AdminTradeAuthorizationPort.RefundAuthorizationPort authorization) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
    }

    public PageResult<AdminRefundSummary> listRefunds(AdminRefundListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        authorizeCollection(auth, context);
        Filters filters = filters(query);
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countRefunds(
                filters.status(), filters.sourceType(), filters.refundType(), filters.orderId(),
                filters.createdFrom(), filters.createdTo()));
        List<AdminRefundSummary> items = total == 0
                ? List.of()
                : store.read(mapper -> mapper.selectRefundPage(
                        filters.status(), filters.sourceType(), filters.refundType(),
                        filters.orderId(), filters.createdFrom(), filters.createdTo(),
                        pageSize, (long) (page - 1) * pageSize)).stream()
                        .map(AdminRefundQueryService::summary)
                        .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public AdminRefundDetail getRefund(AdminRefundQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        long refundOrderId = parse(query.refundOrderId());
        AdminRefundQueryMapper.RefundRow row = store.read(mapper -> mapper.selectRefundById(refundOrderId));
        if (row == null) notFound();
        authorizeResource(auth, context, row);
        AdminApplicationView application = row.getApplicationId() == null ? null
                : new AdminApplicationView(
                        IDS.toApi(row.getApplicationId()), row.getApplicationStatus(),
                        money(row.getRequestedAmount()), instant(row.getMerchantDeadline()),
                        instant(row.getDecidedAt()));
        AdminExecutionView execution = row.getChannelTradeNo() == null ? null
                : new AdminExecutionView(
                        row.getChannelTradeNo(), money(row.getExecutionRefundAmount()),
                        instant(row.getChannelPaidAt()), row.getCurrency());
        return new AdminRefundDetail(
                IDS.toApi(row.getId()), IDS.toApi(row.getRefundNo()), IDS.toApi(row.getOrderId()),
                row.getRefundType(), row.getSourceType(), row.getStatus(),
                money(row.getRefundAmount()), row.getInitiatorType(), row.getChannelRefundNo(),
                instant(row.getSucceededAt()), instant(row.getCreatedAt()),
                application, execution);
    }

    private void authorizeCollection(AdminAuthorization auth, QueryContext context) {
        Decision decision = authorization.checkCollection(new CollectionCheck(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(), "refund.read",
                "REFUND_READ", Phase.READ_RESULT));
        requireDecision(decision, false);
    }

    private void authorizeResource(AdminAuthorization auth, QueryContext context, AdminRefundQueryMapper.RefundRow row) {
        Decision decision = authorization.check(new Check(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(), "refund.read",
                new Resource("REFUND_ORDER", IDS.toApi(row.getId()), null, null,
                        Long.toString(row.getVersion() == null ? 0L : row.getVersion())),
                "REFUND_READ", Phase.READ_RESULT));
        requireDecision(decision, true);
    }

    private static void requireDecision(Decision decision, boolean resourceRead) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
        if (!decision.allowed()) {
            if (resourceRead && "RESOURCE_SCOPE_DENIED".equals(decision.reasonCode())) notFound();
            throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
        }
    }

    record Filters(
            String status, String sourceType, String refundType, Long orderId,
            java.time.LocalDateTime createdFrom, java.time.LocalDateTime createdTo) {}

    private Filters filters(AdminRefundListQuery query) {
        String status = query.status();
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "status is invalid");
        }
        String sourceType = query.sourceType();
        if (sourceType != null && !SOURCE_TYPES.contains(sourceType)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "sourceType is invalid");
        }
        String refundType = query.refundType();
        if (refundType != null && !REFUND_TYPES.contains(refundType)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "refundType is invalid");
        }
        Long orderId = null;
        if (query.orderId() != null && !query.orderId().isBlank()) {
            try {
                orderId = IDS.fromApi(query.orderId());
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "orderId is invalid");
            }
        }
        java.time.LocalDateTime from = utc(query.createdFrom(), "createdFrom");
        java.time.LocalDateTime to = utc(query.createdTo(), "createdTo");
        if ((from == null) != (to == null)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "created range must be paired");
        }
        if (from != null && (to.isBefore(from) || Duration.between(from, to).toDays() > MAX_RANGE_DAYS)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "created range is invalid");
        }
        return new Filters(status, sourceType, refundType, orderId, from, to);
    }

    private static AdminRefundSummary summary(AdminRefundQueryMapper.RefundRow row) {
        return new AdminRefundSummary(
                IDS.toApi(row.getId()), IDS.toApi(row.getRefundNo()), IDS.toApi(row.getOrderId()),
                row.getRefundType(), row.getSourceType(), row.getStatus(),
                money(row.getRefundAmount()), row.getInitiatorType(), row.getChannelRefundNo(),
                instant(row.getSucceededAt()), instant(row.getCreatedAt()));
    }

    private static QueryContext operator(QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "平台运营身份必需");
        }
        return context;
    }

    private static AdminAuthorization reference(AdminAuthorization authorization) {
        if (authorization == null || authorization.sessionId() == null
                || authorization.sessionId().isBlank() || authorization.sessionGeneration() < 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "admin authorization reference is required");
        }
        return authorization;
    }

    private static long parse(String refundOrderId) {
        try {
            return IDS.fromApi(refundOrderId);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "refundOrderId is invalid");
        }
    }

    private static int page(int page) {
        return page < 1 ? 1 : page;
    }

    private static int pageSize(int pageSize) {
        return pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static java.time.LocalDateTime utc(OffsetDateTime value, String field) {
        if (value == null) return null;
        try {
            return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }

    private static java.time.OffsetDateTime instant(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static java.math.BigDecimal money(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "退款单不存在");
    }
}
