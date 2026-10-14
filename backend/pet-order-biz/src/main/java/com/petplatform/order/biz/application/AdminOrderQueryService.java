package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminAuthorization;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderDetail;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderDetailQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderListQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderSummary;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminStatusLogQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.RescheduleRecord;
import com.petplatform.order.api.query.AdminOrderQueryApi.ServiceSnapshot;
import com.petplatform.order.api.query.AdminOrderQueryApi.StatusLog;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Check;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort.CollectionCheck;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Decision;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Phase;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Resource;
import com.petplatform.order.biz.application.OrderDisplayStatus.Facts;
import com.petplatform.order.biz.infrastructure.persistence.AdminOrderQueryStore;
import com.petplatform.order.biz.infrastructure.persistence.entity.AdminOrderRowEntity;
import com.petplatform.order.biz.infrastructure.persistence.entity.AdminStatusLogEntity;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Platform order read model for the single operator (contract 63, HTTP10 §5.11). Read-only:
 * the authorization port re-proves session/action/scope inside this kernel before any row is
 * read (lists via the collection entry check, single resources via the locked row scope), the
 * fixed sorts are created_at DESC/id DESC (list) and created_at ASC/id ASC (timeline), and the
 * optional displayStatus filter reuses the same {@link OrderDisplayStatus} truth table as the
 * C/M lists. Absent and foreign ids read as one COMMON_NOT_FOUND (anti-enumeration). No write
 * path; dataScope ALL is the only supported mode (the kernel resource carries no city fact, so
 * CITY/MERCHANT scopes deny — CCR-PERM-001 §3).
 */
public final class AdminOrderQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_RANGE_DAYS = 366;
    private static final Set<String> PAYMENT_STATUSES = Set.of("INIT", "PAID");
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final AdminOrderQueryStore store;
    private final AdminTradeAuthorizationPort.OrderAuthorizationPort authorization;
    private final java.time.Clock clock;

    public AdminOrderQueryService(
            AdminOrderQueryStore store,
            AdminTradeAuthorizationPort.OrderAuthorizationPort authorization) {
        this(store, authorization, java.time.Clock.systemUTC());
    }

    public AdminOrderQueryService(
            AdminOrderQueryStore store,
            AdminTradeAuthorizationPort.OrderAuthorizationPort authorization,
            java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public PageResult<AdminOrderSummary> listOrders(AdminOrderListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        authorizeCollection(auth, context, "order.read", "ORDER_READ");
        Filters filters = filters(query);
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countOrders(
                filters.displayStatus(), filters.paymentStatus(), filters.merchantId(),
                filters.storeId(), filters.orderNo(), filters.merchantKeyword(),
                filters.createdFrom(), filters.createdTo(), filters.rescheduledOnly()));
        List<AdminOrderSummary> items;
        if (total == 0) {
            items = List.of();
        } else {
            items = store
                    .read(mapper -> mapper.selectOrderPage(
                            filters.displayStatus(), filters.paymentStatus(), filters.merchantId(),
                            filters.storeId(), filters.orderNo(), filters.merchantKeyword(),
                            filters.createdFrom(), filters.createdTo(), filters.rescheduledOnly(),
                            pageSize, (long) (page - 1) * pageSize))
                    .stream()
                    .map(AdminOrderQueryService::summary)
                    .toList();
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    public AdminOrderDetail getOrder(AdminOrderDetailQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        long orderId = parse(query.orderId());
        AdminOrderRowEntity row = store.read(mapper -> mapper.selectOrderById(orderId));
        if (row == null) notFound();
        authorizeResource(auth, context, "order.read", "ORDER_READ", row);
        List<RescheduleRecord> reschedules =
                store.read(mapper -> mapper.selectReschedules(orderId)).stream()
                        .map(AdminOrderQueryService::reschedule)
                        .toList();
        return detail(row, reschedules);
    }

    public PageResult<StatusLog> listStatusLogs(AdminStatusLogQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        long orderId = parse(query.orderId());
        AdminOrderRowEntity row = store.read(mapper -> mapper.selectOrderById(orderId));
        if (row == null) notFound();
        authorizeResource(auth, context, "order.status-log.read", "ORDER_STATUS_LOG_READ", row);
        List<StatusLog> items = store.read(mapper -> mapper.selectStatusLogs(orderId))
                .stream()
                .map(AdminOrderQueryService::statusLog)
                .toList();
        return new PageResult<>(items, items.size(), 1, Math.max(items.size(), 1));
    }

    private void authorizeCollection(
            AdminAuthorization auth, QueryContext context, String action, String purpose) {
        Decision decision = authorization.checkCollection(new CollectionCheck(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(), action,
                purpose, Phase.READ_RESULT));
        requireDecision(decision, false);
    }

    private void authorizeResource(
            AdminAuthorization auth, QueryContext context, String action, String purpose,
            AdminOrderRowEntity row) {
        Decision decision = authorization.check(new Check(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(), action,
                new Resource("ORDER", IDS.toApi(row.getId()), IDS.toApi(row.getMerchantId()),
                        null, Long.toString(row.getVersion() == null ? 0L : row.getVersion())),
                purpose, Phase.READ_RESULT));
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
            String displayStatus, String paymentStatus, Long merchantId, Long storeId,
            Long orderNo, String merchantKeyword, java.time.LocalDateTime createdFrom,
            java.time.LocalDateTime createdTo, boolean rescheduledOnly) {}

    private Filters filters(AdminOrderListQuery query) {
        String displayStatus = query.displayStatus();
        if (displayStatus != null && !OrderDisplayStatus.VALUES.contains(displayStatus)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "displayStatus is invalid");
        }
        String paymentStatus = query.paymentStatus();
        if (paymentStatus != null && !PAYMENT_STATUSES.contains(paymentStatus)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "paymentStatus is invalid");
        }
        Long merchantId = optionalId(query.merchantId(), "merchantId");
        Long storeId = optionalId(query.storeId(), "storeId");
        Long orderNo = optionalId(query.orderNo(), "orderNo");
        String keyword = query.keyword();
        String merchantKeyword = null;
        Long keywordOrderNo = null;
        if (keyword != null) {
            if (keyword.length() < 2 || keyword.length() > 64 || keyword.isBlank()
                    || keyword.indexOf('%') >= 0 || keyword.indexOf('_') >= 0
                    || keyword.indexOf('\\') >= 0) {
                throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "keyword is invalid");
            }
            // Pure Snowflake-shaped digits match the order number exactly; anything else
            // is a service-snapshot merchant-name search.
            if (keyword.matches("[1-9][0-9]{0,18}")) {
                keywordOrderNo = IDS.fromApi(keyword);
            } else {
                merchantKeyword = "%" + keyword + "%";
            }
        }
        java.time.LocalDateTime from = utc(query.createdFrom(), "createdFrom");
        java.time.LocalDateTime to = utc(query.createdTo(), "createdTo");
        if ((from == null) != (to == null)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "created range must be paired");
        }
        if (from != null) {
            if (to.isBefore(from)
                    || Duration.between(from, to).toDays() > MAX_RANGE_DAYS) {
                throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "created range is invalid");
            }
        }
        boolean rescheduledOnly = query.rescheduledOnly();
        if (keywordOrderNo != null && orderNo == null) {
            orderNo = keywordOrderNo;
        }
        return new Filters(displayStatus, paymentStatus, merchantId, storeId, orderNo,
                merchantKeyword, from, to, rescheduledOnly);
    }

    private static AdminOrderSummary summary(AdminOrderRowEntity row) {
        return new AdminOrderSummary(
                IDS.toApi(row.getId()), IDS.toApi(row.getOrderNo()), IDS.toApi(row.getUserId()),
                IDS.toApi(row.getMerchantId()), IDS.toApi(row.getStoreId()),
                IDS.toApi(row.getServiceId()), display(row), row.getOrderStage(),
                row.getPaymentStatus(), row.getMerchantName(), row.getServiceName(),
                money(row.getPayAmount()), money(row.getRefundedAmount()),
                instant(row.getAppointmentStartAt()), instant(row.getAppointmentEndAt()),
                instant(row.getCreatedAt()),
                row.getRescheduleCount() == null ? 0 : row.getRescheduleCount());
    }

    private static AdminOrderDetail detail(AdminOrderRowEntity row, List<RescheduleRecord> reschedules) {
        ServiceSnapshot snapshot = new ServiceSnapshot(
                row.getServiceName(), row.getCategoryName(), row.getMerchantName(),
                row.getStoreName(), row.getStoreAddress(), money(row.getServicePrice()),
                row.getServiceDurationMinutes() == null ? 0 : row.getServiceDurationMinutes());
        return new AdminOrderDetail(
                IDS.toApi(row.getId()), IDS.toApi(row.getOrderNo()), IDS.toApi(row.getUserId()),
                IDS.toApi(row.getMerchantId()), IDS.toApi(row.getStoreId()),
                IDS.toApi(row.getServiceId()), display(row), row.getOrderStage(),
                row.getPaymentStatus(), row.getRefundApplicationStatus(), refundStatus(row),
                row.getAftersaleStatus(), row.getVerificationStatus(),
                row.getRefundOrderId() == null ? null : IDS.toApi(row.getRefundOrderId()),
                money(row.getOriginalAmount()), money(row.getDiscountAmount()),
                money(row.getPayAmount()), money(row.getRefundedAmount()),
                instant(row.getAppointmentStartAt()), instant(row.getAppointmentEndAt()),
                instant(row.getPaidAt()), instant(row.getConfirmedAt()),
                instant(row.getVerifiedAt()), instant(row.getCanceledAt()),
                instant(row.getCreatedAt()),
                row.getRescheduleCount() == null ? 0 : row.getRescheduleCount(),
                snapshot, reschedules);
    }

    private static RescheduleRecord reschedule(com.petplatform.order.biz.infrastructure.persistence.entity.AdminRescheduleEntity row) {
        return new RescheduleRecord(
                IDS.toApi(row.getId()), instant(row.getOldStart()), instant(row.getOldEnd()),
                instant(row.getNewStart()), instant(row.getNewEnd()),
                instant(row.getRescheduledAt()));
    }

    private static StatusLog statusLog(AdminStatusLogEntity row) {
        return new StatusLog(
                IDS.toApi(row.getId()), IDS.toApi(row.getOrderId()), row.getDimension(),
                row.getFromStatus(), row.getToStatus(), row.getEventType(),
                row.getOperatorType(), row.getOperatorId() == null ? null : IDS.toApi(row.getOperatorId()),
                row.getRemark(), instant(row.getCreatedAt()));
    }

    private static String display(AdminOrderRowEntity row) {
        try {
            return OrderDisplayStatus.compute(new Facts(row.getOrderStage(), row.getRefundOrderId(),
                    row.getRefundedAmount(), row.getPayAmount(), row.getRefundApplicationStatus(),
                    row.getAftersaleStatus()));
        } catch (RuntimeException unreadable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "订单状态投影不可用");
        }
    }

    /** Same order-domain refund vocabulary as the C-end snapshot projection. */
    private static String refundStatus(AdminOrderRowEntity row) {
        if (row.getRefundOrderId() == null) return null;
        java.math.BigDecimal refunded = row.getRefundedAmount();
        return refunded != null && refunded.signum() > 0 ? "SUCCESS" : "CREATED";
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

    private static long parse(String orderId) {
        try {
            return IDS.fromApi(orderId);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "orderId is invalid");
        }
    }

    private static Long optionalId(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
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
        throw new ApiException(CommonApiCodes.NOT_FOUND, "订单不存在");
    }
}
