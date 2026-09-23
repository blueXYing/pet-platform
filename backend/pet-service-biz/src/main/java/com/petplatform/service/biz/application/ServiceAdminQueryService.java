package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryItem;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryPage;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementDetailQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementItem;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementListQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementPage;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDecisionView;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDetail;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDetailQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewListQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewPage;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewSummary;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceCategoryEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceReviewDecisionEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Admin review and merchant workbench reads for the service write slice. Read-only projections of
 * every status for authorized readers only; C-side visibility stays in ServiceQueryService and is
 * never widened here. Admin session/action enforcement for these reads happens at the HTTP layer
 * (live session resolve + action codes, V1 single-operator ruling); write commands still
 * revalidate transactionally in ServiceCommandService.
 */
public final class ServiceAdminQueryService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> ALL_STATUSES =
            Set.of("DRAFT", "REVIEWING", "ACTIVE", "OFFLINE", "REJECTED");
    private static final Duration SLA = Duration.ofHours(24);

    private final ServiceWriteStore store;
    private final ServiceAdmissionGate admissions;
    private final Clock clock;

    public ServiceAdminQueryService(
            ServiceWriteStore store, ServiceAdmissionGate admissions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.admissions = Objects.requireNonNull(admissions, "admissions is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    // ------------------------------------------------------------------ admin review

    public ServiceReviewPage listForReview(ServiceReviewListQuery q) {
        if (q == null || q.page() < 1 || q.page() > 10_000 || q.pageSize() < 1
                || q.pageSize() > 100) invalid("page or pageSize is invalid");
        if (q.status() != null && !ALL_STATUSES.contains(q.status())) invalid("status is invalid");
        requireAdmin(q.context());
        Long categoryId = q.categoryId() == null ? null : id(q.categoryId(), "categoryId");
        Long merchantId = q.merchantId() == null ? null : id(q.merchantId(), "merchantId");
        return store.read(
                m -> {
                    long total = m.countForReview(blank(q.status()), categoryId, merchantId);
                    List<ServiceReviewSummary> items = new ArrayList<>();
                    if (total > 0) {
                        for (Map<String, Object> row : m.listForReview(
                                blank(q.status()), categoryId, merchantId, q.pageSize(),
                                (q.page() - 1) * q.pageSize())) {
                            items.add(summary(row));
                        }
                    }
                    return new ServiceReviewPage(q.page(), q.pageSize(), total, List.copyOf(items));
                });
    }

    public ServiceReviewDetail getForReview(ServiceReviewDetailQuery q) {
        if (q == null) invalid("query is required");
        requireAdmin(q.context());
        long serviceId = id(q.serviceId(), "serviceId");
        return store.read(
                m -> {
                    Map<String, Object> row = m.selectReviewItem(serviceId);
                    if (row == null || row.isEmpty()) notFound();
                    List<ServiceReviewDecisionView> decisions = new ArrayList<>();
                    for (ServiceReviewDecisionEntity d : m.selectDecisionsByService(serviceId)) {
                        decisions.add(
                                new ServiceReviewDecisionView(
                                        IDS.toApi(d.getId()),
                                        d.getSubmissionNo(),
                                        d.getDecisionType(),
                                        d.getOpinion(),
                                        utc(d.getDecidedAt())));
                    }
                    OffsetDateTime submittedAt = utc((LocalDateTime) row.get("submittedAt"));
                    return new ServiceReviewDetail(
                            str(row.get("serviceId")),
                            str(row.get("merchantId")),
                            str(row.get("storeId")),
                            str(row.get("serviceName")),
                            str(row.get("categoryId")),
                            str(row.get("categoryName")),
                            str(row.get("status")),
                            (BigDecimal) row.get("price"),
                            (BigDecimal) row.get("listPrice"),
                            intOrNull(row.get("durationMinutes")),
                            str(row.get("fulfillmentType")),
                            str(row.get("coverAssetId")),
                            str(row.get("description")),
                            intOrNull(row.get("submissionNo")),
                            submittedAt,
                            slaRemaining(submittedAt),
                            ((Number) row.get("version")).longValue(),
                            utc((LocalDateTime) row.get("updatedAt")),
                            List.copyOf(decisions));
                });
    }

    // ------------------------------------------------------------------ merchant workbench

    public ServiceManagementPage listManaged(ServiceManagementListQuery q) {
        if (q == null || q.page() < 1 || q.page() > 10_000 || q.pageSize() < 1
                || q.pageSize() > 100) invalid("page or pageSize is invalid");
        if (q.status() != null && !ALL_STATUSES.contains(q.status())) invalid("status is invalid");
        requireUser(q.context());
        long merchantId = id(q.merchantId(), "merchantId");
        long storeId = id(q.storeId(), "storeId");
        admissions.requireOperable(q.context(), merchantId, storeId);
        return store.read(
                m -> {
                    long total = m.countManaged(merchantId, storeId, blank(q.status()));
                    List<ServiceManagementItem> items = new ArrayList<>();
                    if (total > 0) {
                        for (Map<String, Object> row : m.listManaged(
                                merchantId, storeId, blank(q.status()), q.pageSize(),
                                (q.page() - 1) * q.pageSize())) {
                            items.add(managed(row));
                        }
                    }
                    return new ServiceManagementPage(
                            q.page(), q.pageSize(), total, List.copyOf(items));
                });
    }

    public ServiceManagementItem getManaged(ServiceManagementDetailQuery q) {
        if (q == null) invalid("query is required");
        requireUser(q.context());
        long serviceId = id(q.serviceId(), "serviceId");
        long merchantId = id(q.merchantId(), "merchantId");
        long storeId = id(q.storeId(), "storeId");
        admissions.requireOperable(q.context(), merchantId, storeId);
        return store.read(
                m -> {
                    Map<String, Object> row = m.selectManagedItem(serviceId);
                    if (row == null || row.isEmpty()
                            || Long.parseLong(str(row.get("merchantId"))) != merchantId
                            || Long.parseLong(str(row.get("storeId"))) != storeId) {
                        notFound();
                    }
                    return managed(row);
                });
    }

    public ServiceCategoryPage listEnabledCategories(ServiceCategoryQuery q) {
        if (q == null) invalid("query is required");
        requireUser(q.context());
        return store.read(
                m -> {
                    List<ServiceCategoryItem> items = new ArrayList<>();
                    for (ServiceCategoryEntity c : m.selectEnabledCategories()) {
                        items.add(
                                new ServiceCategoryItem(
                                        IDS.toApi(c.getId()), c.getCategoryName(), c.getSortNo()));
                    }
                    return new ServiceCategoryPage(List.copyOf(items));
                });
    }

    // ------------------------------------------------------------------ helpers

    private static void requireAdmin(QueryContext ctx) {
        if (ctx == null || ctx.operatorId() == null || ctx.operatorId().isBlank()
                || ctx.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "admin session is required");
        }
    }

    private static void requireUser(QueryContext ctx) {
        if (ctx == null || ctx.operatorId() == null || ctx.operatorId().isBlank()
                || ctx.operatorType() != OperatorType.USER) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "merchant session is required");
        }
    }

    private ServiceReviewSummary summary(Map<String, Object> row) {
        OffsetDateTime submittedAt = utc((LocalDateTime) row.get("submittedAt"));
        return new ServiceReviewSummary(
                str(row.get("serviceId")),
                str(row.get("merchantId")),
                str(row.get("storeId")),
                str(row.get("serviceName")),
                str(row.get("categoryId")),
                str(row.get("categoryName")),
                str(row.get("status")),
                (BigDecimal) row.get("price"),
                (BigDecimal) row.get("listPrice"),
                ((Number) row.get("submissionNo")).intValue(),
                submittedAt,
                slaRemaining(submittedAt),
                ((Number) row.get("rejectCount")).intValue(),
                ((Number) row.get("version")).longValue());
    }

    private ServiceManagementItem managed(Map<String, Object> row) {
        ServiceReviewDecisionView latest = null;
        if (row.get("latestRejectDecisionId") != null) {
            latest =
                    new ServiceReviewDecisionView(
                            str(row.get("latestRejectDecisionId")),
                            ((Number) row.get("latestRejectSubmissionNo")).intValue(),
                            "REJECT",
                            str(row.get("latestRejectOpinion")),
                            utc((LocalDateTime) row.get("latestRejectDecidedAt")));
        }
        Boolean verification = boolOrNull(row.get("verificationRequired"));
        return new ServiceManagementItem(
                str(row.get("serviceId")),
                str(row.get("merchantId")),
                str(row.get("storeId")),
                str(row.get("serviceName")),
                str(row.get("categoryId")),
                str(row.get("categoryName")),
                str(row.get("status")),
                (BigDecimal) row.get("price"),
                (BigDecimal) row.get("listPrice"),
                intOrNull(row.get("durationMinutes")),
                str(row.get("fulfillmentType")),
                str(row.get("coverAssetId")),
                str(row.get("applicablePetTypes")),
                str(row.get("staffRequirement")),
                verification == null ? Boolean.TRUE : verification,
                str(row.get("description")),
                str(row.get("aftersaleNote")),
                str(row.get("remark")),
                ((Number) row.get("submissionNo")).intValue(),
                utc((LocalDateTime) row.get("submittedAt")),
                ((Number) row.get("version")).longValue(),
                utc((LocalDateTime) row.get("updatedAt")),
                latest);
    }

    private Long slaRemaining(OffsetDateTime submittedAt) {
        if (submittedAt == null) return null;
        return SLA.minus(Duration.between(submittedAt.toInstant(), clock.instant())).toMinutes();
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * JDBC drivers widen numeric map columns (INT UNSIGNED arrives as Long): never cast Map
     * projections to concrete Number subclasses.
     */
    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Boolean boolOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean flag) return flag;
        return ((Number) value).intValue() != 0;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
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

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "service resource not found");
    }
}
