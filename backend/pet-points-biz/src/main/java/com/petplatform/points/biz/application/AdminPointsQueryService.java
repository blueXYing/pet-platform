package com.petplatform.points.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.points.api.query.AdminPointsQueryApi;
import com.petplatform.points.api.query.AdminPointsQueryApi.AdminAuthorization;
import com.petplatform.points.api.query.AdminPointsQueryApi.AdminLedgerListQuery;
import com.petplatform.points.api.query.AdminPointsQueryApi.AdminLedgerRow;
import com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Check;
import com.petplatform.points.biz.application.AdminPointsAuthorizationPort.CollectionCheck;
import com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Decision;
import com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Phase;
import com.petplatform.points.biz.infrastructure.persistence.entity.AdminPointsLedgerEntity;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Platform points ledger read model for the single operator (contract 65 §5, PRD 6.5 积分流水,
 * HTTP10 §5.8 drafted route). Read-only: the authorization port re-proves session, the
 * points.ledger.read action code and scope inside this kernel before any row is read; the fixed
 * sort is created_at DESC/id DESC and bizType reuses the points_ledger vocabulary (SSOT §10:
 * accrue/clawback only, no spend). No write path exists here.
 */
public final class AdminPointsQueryService implements AdminPointsQueryApi {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> BIZ_TYPES =
            Set.of("SIGN_IN", "INVITE", "TASK", "ORDER_REWARD", "REFUND_CLAWBACK");

    private final com.petplatform.points.biz.infrastructure.persistence.AdminPointsQueryStore store;
    private final AdminPointsAuthorizationPort.Port authorization;

    public AdminPointsQueryService(
            com.petplatform.points.biz.infrastructure.persistence.AdminPointsQueryStore store,
            AdminPointsAuthorizationPort.Port authorization) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
    }

    @Override
    public PageResult<AdminLedgerRow> queryLedger(AdminLedgerListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        require(query.authorization());
        Decision decision = authorization.checkCollection(new CollectionCheck(
                query.authorization().sessionId(), query.authorization().sessionGeneration(),
                context.operatorId(), "points.ledger.read", "POINTS_LEDGER_READ",
                Phase.READ_RESULT));
        requireDecision(decision);
        Long userId = query.userId() == null ? null : userId(query.userId());
        String bizType = bizType(query.bizType());
        LocalDateTime from = utc(query.createdFrom());
        LocalDateTime to = utc(query.createdTo());
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        return store.read(m -> {
            long total = m.countLedger(userId, bizType, from, to);
            List<AdminLedgerRow> items = total == 0 ? List.of()
                    : m.selectLedger(userId, bizType, from, to, pageSize, (page - 1) * pageSize)
                            .stream().map(AdminPointsQueryService::row).toList();
            return new PageResult<>(items, total, page, pageSize);
        });
    }

    private static AdminLedgerRow row(AdminPointsLedgerEntity entity) {
        return new AdminLedgerRow(
                Long.toUnsignedString(entity.getId()), Long.toUnsignedString(entity.getUserId()),
                entity.getBizType(), Long.toString(entity.getDelta()),
                Long.toString(entity.getBalanceAfter()),
                entity.getOrderId() == null ? null : Long.toUnsignedString(entity.getOrderId()),
                entity.getCreatedAt() == null ? null
                        : entity.getCreatedAt().toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC));
    }

    private void requireDecision(Decision decision) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    private static void require(AdminAuthorization auth) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
    }

    private QueryContext operator(QueryContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null
                || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context is required");
        }
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "points ledger belongs to operators");
        }
        return context;
    }

    private static Long userId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "userId must be a decimal Snowflake String");
        }
        return Long.parseLong(value);
    }

    private static String bizType(String bizType) {
        if (bizType == null || bizType.isBlank()) return null;
        if (!BIZ_TYPES.contains(bizType)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "unknown bizType");
        }
        return bizType;
    }

    private static LocalDateTime utc(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }

    private static int page(Integer value) {
        if (value == null) return 1;
        if (value < 1 || value > 10_000) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "page is outside 1..10000");
        }
        return value;
    }

    private static int pageSize(Integer value) {
        if (value == null) return 20;
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,
                    "pageSize is outside 1.." + MAX_PAGE_SIZE);
        }
        return value;
    }
}
