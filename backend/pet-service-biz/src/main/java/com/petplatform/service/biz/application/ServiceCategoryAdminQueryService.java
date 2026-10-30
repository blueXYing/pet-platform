package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminItem;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminListQuery;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminPage;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceAdminAuthorization;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort.CollectionCheck;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort.Decision;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort.Phase;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Admin dictionary read for the service category management page. The HTTP layer resolves the
 * ADMIN_WEB Bearer; this service additionally re-proves {@code service.category.read} against the
 * authoritative admin kernel before scanning any category row (V1 single-operator ruling).
 */
public final class ServiceCategoryAdminQueryService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");

    private final ServiceWriteStore store;
    private final ServiceCategoryAuthorizationPort authorization;

    public ServiceCategoryAdminQueryService(
            ServiceWriteStore store, ServiceCategoryAuthorizationPort authorization) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
    }

    public ServiceCategoryAdminPage list(ServiceCategoryAdminListQuery q) {
        if (q == null || q.page() < 1 || q.page() > 10_000 || q.pageSize() < 1
                || q.pageSize() > 50) {
            throw invalid("page or pageSize is invalid");
        }
        if (q.status() != null && !STATUSES.contains(q.status())) throw invalid("status is invalid");
        String keyword = trimToNull(q.keyword());
        if (keyword != null && keyword.length() > 50) throw invalid("keyword exceeds 50 characters");
        requireAdmin(q.context());
        authorize(q.authorization(), q.context());
        String pattern = escapeLike(keyword);
        return store.read(
                m -> {
                    long total = m.countCategories(q.status(), pattern);
                    List<ServiceCategoryAdminItem> items = new ArrayList<>();
                    if (total > 0) {
                        for (Map<String, Object> row : m.listCategories(
                                q.status(), pattern, q.pageSize(),
                                (q.page() - 1) * q.pageSize())) {
                            items.add(item(row));
                        }
                    }
                    return new ServiceCategoryAdminPage(
                            q.page(), q.pageSize(), total, List.copyOf(items));
                });
    }

    private ServiceCategoryAdminItem item(Map<String, Object> row) {
        Object id = row.get("categoryId");
        Object version = row.get("version");
        Object sortNo = row.get("sortNo");
        if (!(id instanceof Number) || !(version instanceof Number)
                || !(sortNo instanceof Number)) {
            throw unavailable("category listing facts are damaged");
        }
        return new ServiceCategoryAdminItem(
                IDS.toApi(((Number) id).longValue()),
                str(row.get("categoryCode")),
                str(row.get("categoryName")),
                str(row.get("parentCode")),
                str(row.get("iconUrl")),
                str(row.get("description")),
                ((Number) sortNo).intValue(),
                str(row.get("status")),
                ((Number) version).longValue(),
                utc((LocalDateTime) row.get("createdAt")),
                utc((LocalDateTime) row.get("updatedAt")));
    }

    private static void requireAdmin(QueryContext ctx) {
        if (ctx == null || ctx.operatorId() == null || ctx.operatorId().isBlank()
                || ctx.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "admin session is required");
        }
    }

    private void authorize(ServiceAdminAuthorization ref, QueryContext ctx) {
        if (ref == null
                || ref.sessionId() == null
                || ref.sessionId().isBlank()
                || ref.sessionGeneration() < 0) {
            throw new ApiException(
                    CommonApiCodes.UNAUTHORIZED, "admin authorization reference is required");
        }
        Decision decision =
                authorization.checkCollection(
                        new CollectionCheck(
                                ref.sessionId(),
                                ref.sessionGeneration(),
                                ctx.operatorId(),
                                "service.category.read",
                                "SERVICE_CATEGORY",
                                Phase.READ_RESULT));
        ServiceCategoryAuthorizationPort.enforce(decision);
    }

    /**
     * Escapes the three LIKE metacharacters using {@code !} as the SQL ESCAPE character, so the
     * admin keyword is always a literal substring search (never a wildcard expression).
     */
    static String escapeLike(String keyword) {
        if (keyword == null) return null;
        return keyword.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
                .replace("\\", "!\\\\");
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
