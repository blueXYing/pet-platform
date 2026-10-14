package com.petplatform.verification.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminAttemptView;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminAuthorization;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminRiskAttemptView;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationDetail;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationListQuery;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationQuery;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationSummary;
import com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Check;
import com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.CollectionCheck;
import com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision;
import com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Phase;
import com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Resource;
import com.petplatform.verification.biz.infrastructure.persistence.AdminVerificationQueryStore;
import com.petplatform.verification.biz.infrastructure.persistence.mapper.AdminVerificationQueryMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Platform verification-record read model for the single operator (contract 63, 运营 PRD
 * 6.3.5 dispute tracing). Read-only: the authorization port re-proves session/action/scope
 * inside this kernel before any row is read, the fixed list sort is verified_at DESC/id DESC,
 * and the detail exposes the attempt chain plus risk attempts (result codes only — the
 * credential cipher material stays sealed). Absent ids read as one COMMON_NOT_FOUND;
 * dataScope ALL is the only supported mode (verification rows carry no merchant/city
 * attribution — CCR-PERM-001 §3).
 */
public final class AdminVerificationQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_RANGE_DAYS = 366;
    static final Set<String> METHODS = Set.of("SCAN", "MANUAL");
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final AdminVerificationQueryStore store;
    private final AdminTradeAuthorizationPort.VerificationAuthorizationPort authorization;

    public AdminVerificationQueryService(
            AdminVerificationQueryStore store,
            AdminTradeAuthorizationPort.VerificationAuthorizationPort authorization) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
    }

    public PageResult<AdminVerificationSummary> listVerifications(AdminVerificationListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        authorizeCollection(auth, context);
        Filters filters = filters(query);
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countVerifications(
                filters.orderId(), filters.storeId(), filters.method(),
                filters.verifiedFrom(), filters.verifiedTo()));
        List<AdminVerificationSummary> items = total == 0
                ? List.of()
                : store.read(mapper -> mapper.selectVerificationPage(
                        filters.orderId(), filters.storeId(), filters.method(),
                        filters.verifiedFrom(), filters.verifiedTo(),
                        pageSize, (long) (page - 1) * pageSize)).stream()
                        .map(AdminVerificationQueryService::summary)
                        .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public AdminVerificationDetail getVerification(AdminVerificationQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        long verificationId = parse(query.verificationId());
        AdminVerificationQueryMapper.VerificationRow row =
                store.read(mapper -> mapper.selectVerificationById(verificationId));
        if (row == null) notFound();
        authorizeResource(auth, context, row);
        List<AdminAttemptView> attempts = store
                .read(mapper -> mapper.selectAttempts(row.getOrderId())).stream()
                .map(AdminVerificationQueryService::attempt)
                .toList();
        List<AdminRiskAttemptView> riskAttempts = store
                .read(mapper -> mapper.selectRiskAttempts(row.getOrderId())).stream()
                .map(AdminVerificationQueryService::riskAttempt)
                .toList();
        return new AdminVerificationDetail(
                IDS.toApi(row.getId()), IDS.toApi(row.getVerificationNo()),
                IDS.toApi(row.getOrderId()), IDS.toApi(row.getStoreId()), row.getVerifyMethod(),
                instant(row.getVerifiedAt()), IDS.toApi(row.getOperatorStaffId()),
                attempts, riskAttempts);
    }

    private void authorizeCollection(AdminAuthorization auth, QueryContext context) {
        Decision decision = authorization.checkCollection(new CollectionCheck(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(),
                "order.verification-log.read", "VERIFICATION_LOG_READ", Phase.READ_RESULT));
        requireDecision(decision, false);
    }

    private void authorizeResource(
            AdminAuthorization auth, QueryContext context, AdminVerificationQueryMapper.VerificationRow row) {
        Decision decision = authorization.check(new Check(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(),
                "order.verification-log.read",
                new Resource("VERIFICATION_RECORD", IDS.toApi(row.getId()), null, null,
                        Long.toString(row.getId())),
                "VERIFICATION_LOG_READ", Phase.READ_RESULT));
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
            Long orderId, Long storeId, String method,
            java.time.LocalDateTime verifiedFrom, java.time.LocalDateTime verifiedTo) {}

    private Filters filters(AdminVerificationListQuery query) {
        Long orderId = optionalId(query.orderId(), "orderId");
        Long storeId = optionalId(query.storeId(), "storeId");
        String method = query.method();
        if (method != null && !METHODS.contains(method)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "method is invalid");
        }
        java.time.LocalDateTime from = utc(query.verifiedFrom(), "verifiedFrom");
        java.time.LocalDateTime to = utc(query.verifiedTo(), "verifiedTo");
        if ((from == null) != (to == null)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "verified range must be paired");
        }
        if (from != null && (to.isBefore(from) || Duration.between(from, to).toDays() > MAX_RANGE_DAYS)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "verified range is invalid");
        }
        return new Filters(orderId, storeId, method, from, to);
    }

    private static AdminVerificationSummary summary(AdminVerificationQueryMapper.VerificationRow row) {
        return new AdminVerificationSummary(
                IDS.toApi(row.getId()), IDS.toApi(row.getVerificationNo()),
                IDS.toApi(row.getOrderId()), IDS.toApi(row.getStoreId()), row.getVerifyMethod(),
                instant(row.getVerifiedAt()));
    }

    private static AdminAttemptView attempt(AdminVerificationQueryMapper.AttemptRow row) {
        return new AdminAttemptView(
                IDS.toApi(row.getId()), row.getResult(), row.getFailReason(),
                instant(row.getAttemptedAt()));
    }

    private static AdminRiskAttemptView riskAttempt(AdminVerificationQueryMapper.RiskAttemptRow row) {
        return new AdminRiskAttemptView(
                IDS.toApi(row.getId()), row.getResultCode(), row.isCountsFailure(),
                instant(row.getAttemptedAt()));
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

    private static long parse(String verificationId) {
        try {
            return IDS.fromApi(verificationId);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "verificationId is invalid");
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

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "核销记录不存在");
    }
}
