package com.petplatform.service.biz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.service.api.dto.ServiceCategoryTypes.CreateServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.DisableServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.EnableServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryResult;
import com.petplatform.service.api.dto.ServiceCategoryTypes.UpdateServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceAdminAuthorization;
import com.petplatform.service.api.error.ServiceCategoryApiCodes;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort.Check;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort.Decision;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort.Phase;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceCategoryEntity;
import com.petplatform.service.biz.infrastructure.persistence.mapper.ServiceWriteMapper;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;

/**
 * CCR-SVC-CAT-001 B-SVC-02 admin dictionary writes (contract 74 §4): create, partial update and
 * enable/disable. The single idempotency scope {@code admin.service.category} is fixed for every
 * action, so a requestId reused with different parameters conflicts before any write; the action
 * and path code are part of the canonical parameters. Every execution re-proves
 * {@code service.category.manage} against the admin kernel inside the write transaction. Each
 * persisted write bumps {@code version} by one through a CAS and appends one append-only
 * {@code service_category_audit} row in the same transaction.
 */
public final class ServiceCategoryCommandService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final String NAMESPACE = "admin.service.category";
    private static final String AUTHORITY = "SERVICE_CATEGORY";
    private static final int DESCRIPTION_LIMIT = 200;
    private static final int ICON_URL_LIMIT = 512;

    private final ServiceWriteStore store;
    private final Clock clock;
    private final boolean allowHttpIconUrl;
    private final ServiceCategoryAuthorizationPort authorization;

    public ServiceCategoryCommandService(
            ServiceWriteStore store,
            Clock clock,
            boolean allowHttpIconUrl,
            ServiceCategoryAuthorizationPort authorization) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.allowHttpIconUrl = allowHttpIconUrl;
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
    }

    // ------------------------------------------------------------------ commands

    public boolean createOutcome(CreateServiceCategoryCommand c, ServiceCategoryResult[] out) {
        if (c == null) throw invalid("command is required");
        CommandContext ctx = operator(c.context());
        String code = code(c.categoryCode());
        String name = name(c.categoryName(), true);
        String parentCode = optionalCode(c.parentCode(), "parentCode");
        String iconUrl = iconUrl(c.iconUrl());
        String description = description(c.description());
        int sortNo = sortNo(c.sortNo(), 0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "CREATE");
        params.put("categoryCode", code);
        params.put("categoryName", name);
        params.put("parentCode", parentCode);
        params.put("iconUrl", iconUrl);
        params.put("description", description);
        params.put("sortNo", sortNo);
        return command(
                ctx,
                params,
                m -> {
                    long id = store.nextId();
                    authorize(c.authorization(), ctx, id, 0);
                    if (m.selectCategoryByCode(code) != null) throw codeConflict();
                    if (m.selectCategoryByName(name) != null) {
                        throw invalid("categoryName already exists");
                    }
                    Long parentId = parentId(m, parentCode, null);
                    try {
                        one(m.insertCategory(
                                id, code, name, parentId, iconUrl, description, sortNo, now()));
                    } catch (DuplicateKeyException duplicate) {
                        // Race with a concurrent create: the code key is the reported conflict;
                        // a name collision has no dedicated public code and stays a 400.
                        if (m.selectCategoryByCode(code) != null) throw codeConflict();
                        throw invalid("categoryName already exists");
                    }
                    ServiceCategoryEntity row = requireByCode(m, code);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("categoryCode", row.getCategoryCode());
                    detail.put("categoryName", row.getCategoryName());
                    detail.put("parentCode", parentCode);
                    detail.put("iconUrl", row.getIconUrl());
                    detail.put("description", row.getDescription());
                    detail.put("sortNo", row.getSortNo());
                    audit(m, ctx, row, "CREATE", null, "ENABLED", detail);
                    return result(row);
                },
                out);
    }

    public boolean updateOutcome(UpdateServiceCategoryCommand c, ServiceCategoryResult[] out) {
        if (c == null) throw invalid("command is required");
        CommandContext ctx = operator(c.context());
        String code = code(c.categoryCode());
        String name = name(c.categoryName(), false);
        boolean parentCodePresent = c.parentCodePresent();
        String parentCode =
                parentCodePresent ? optionalCode(c.parentCode(), "parentCode") : null;
        boolean iconUrlPresent = c.iconUrlPresent();
        String iconUrl = iconUrlPresent ? iconUrl(c.iconUrl()) : null;
        boolean descriptionPresent = c.descriptionPresent();
        String description = descriptionPresent ? description(c.description()) : null;
        Integer sortNo = c.sortNo() == null ? null : sortNo(c.sortNo(), 0);
        long expectedVersion = version(c.expectedVersion(), "expectedVersion");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "UPDATE");
        params.put("categoryCode", code);
        params.put("categoryName", name);
        params.put("parentCodePresent", parentCodePresent);
        params.put("parentCode", parentCode);
        params.put("iconUrlPresent", iconUrlPresent);
        params.put("iconUrl", iconUrl);
        params.put("descriptionPresent", descriptionPresent);
        params.put("description", description);
        params.put("sortNo", sortNo);
        params.put("expectedVersion", Long.toString(expectedVersion));
        return command(
                ctx,
                params,
                m -> {
                    ServiceCategoryEntity current = requireExisting(m, code);
                    authorize(c.authorization(), ctx, current.getId(), current.getVersion());
                    if (current.getVersion() != expectedVersion) throw versionConflict();
                    String newName = name == null ? current.getCategoryName() : name;
                    if (name != null && !name.equals(current.getCategoryName())) {
                        ServiceCategoryEntity sameName = m.selectCategoryByName(name);
                        if (sameName != null && !sameName.getId().equals(current.getId())) {
                            throw invalid("categoryName already exists");
                        }
                    }
                    Long newParentId =
                            !parentCodePresent
                                    ? current.getParentId()
                                    : parentCode == null
                                            ? null
                                            : parentId(m, parentCode, current.getId());
                    String newIconUrl =
                            !iconUrlPresent ? current.getIconUrl() : iconUrl;
                    String newDescription =
                            !descriptionPresent ? current.getDescription() : description;
                    int newSortNo = sortNo == null ? current.getSortNo() : sortNo;
                    int updated;
                    try {
                        updated =
                                m.updateCategory(
                                        current.getId(),
                                        newName,
                                        newParentId,
                                        newIconUrl,
                                        newDescription,
                                        newSortNo,
                                        expectedVersion,
                                        now());
                    } catch (DuplicateKeyException duplicate) {
                        throw invalid("categoryName already exists");
                    }
                    if (updated != 1) throw versionConflict();
                    ServiceCategoryEntity row = requireByCode(m, code);
                    audit(
                            m,
                            ctx,
                            row,
                            "UPDATE",
                            null,
                            null,
                            updateDetail(current, row, parentCodePresent));
                    return result(row);
                },
                out);
    }

    public boolean enableOutcome(EnableServiceCategoryCommand c, ServiceCategoryResult[] out) {
        return transition(c == null ? null : c.context(), c == null ? null : c.authorization(),
                c == null ? null : c.categoryCode(),
                c == null ? -1 : c.expectedVersion(), "ENABLE", "ENABLED", out);
    }

    public boolean disableOutcome(DisableServiceCategoryCommand c, ServiceCategoryResult[] out) {
        return transition(c == null ? null : c.context(), c == null ? null : c.authorization(),
                c == null ? null : c.categoryCode(),
                c == null ? -1 : c.expectedVersion(), "DISABLE", "DISABLED", out);
    }

    private boolean transition(
            CommandContext rawContext,
            ServiceAdminAuthorization ref,
            String rawCode,
            long rawExpectedVersion,
            String action,
            String targetStatus,
            ServiceCategoryResult[] out) {
        CommandContext ctx = operator(rawContext);
        String code = code(rawCode);
        long expectedVersion = version(rawExpectedVersion, "expectedVersion");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", action);
        params.put("categoryCode", code);
        params.put("expectedVersion", Long.toString(expectedVersion));
        return command(
                ctx,
                params,
                m -> {
                    ServiceCategoryEntity current = requireExisting(m, code);
                    authorize(ref, ctx, current.getId(), current.getVersion());
                    if (current.getVersion() != expectedVersion) throw versionConflict();
                    int updated =
                            m.updateCategoryStatus(
                                    current.getId(), targetStatus, expectedVersion, now());
                    if (updated != 1) throw versionConflict();
                    ServiceCategoryEntity row = requireByCode(m, code);
                    audit(
                            m,
                            ctx,
                            row,
                            action,
                            current.getStatus(),
                            targetStatus,
                            Map.of());
                    return result(row);
                },
                out);
    }

    // ------------------------------------------------------------- command plumbing

    private boolean command(
            CommandContext ctx,
            Map<String, Object> params,
            ServiceWriteStore.Work<ServiceCategoryResult> action,
            ServiceCategoryResult[] out) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "category command admission must not run inside an existing transaction");
        }
        ServiceCanonicalParams.Canonical canonical = ServiceCanonicalParams.of(params);
        String key =
                ServiceWriteStore.requestKey(
                        NAMESPACE, ctx.operatorType().name(), ctx.operatorId(), AUTHORITY,
                        ctx.requestId());
        admitWithRecovery(key, canonical);
        return executeBound(key, canonical, action, out);
    }

    private boolean executeBound(
            String key,
            ServiceCanonicalParams.Canonical canonical,
            ServiceWriteStore.Work<ServiceCategoryResult> action,
            ServiceCategoryResult[] out) {
        try {
            return runBound(key, canonical, action, out);
        } catch (ServiceWriteStore.CommitUnknown first) {
            try {
                return runBound(key, canonical, action, out);
            } catch (ServiceWriteStore.CommitUnknown second) {
                throw unavailable("category commit result remains unknown");
            }
        }
    }

    private boolean runBound(
            String key,
            ServiceCanonicalParams.Canonical canonical,
            ServiceWriteStore.Work<ServiceCategoryResult> action,
            ServiceCategoryResult[] out) {
        return store.execute(
                m -> {
                    ServiceWriteStore.Binding b = ServiceWriteStore.require(m, key);
                    ServiceWriteStore.same(b, canonical);
                    if ("SUCCEEDED".equals(b.status())) {
                        out[0] = read(b.receiptJson());
                        return false;
                    }
                    ServiceCategoryResult result = action.apply(m);
                    one(m.markBindingSucceeded(key, write(result)));
                    out[0] = result;
                    return true;
                });
    }

    private void admitWithRecovery(String key, ServiceCanonicalParams.Canonical canonical) {
        try {
            store.admit(key, canonical);
        } catch (ServiceWriteStore.CommitUnknown firstUnknown) {
            try {
                store.admit(key, canonical);
            } catch (ServiceWriteStore.CommitUnknown secondUnknown) {
                throw unavailable("category idempotency admission result remains unknown");
            }
        }
    }

    // ------------------------------------------------------------- persistence helpers

    private static ServiceCategoryEntity requireExisting(ServiceWriteMapper m, String code) {
        ServiceCategoryEntity row = m.selectCategoryByCode(code);
        if (row == null) throw categoryNotFound();
        damaged(row);
        return row;
    }

    private static ServiceCategoryEntity requireByCode(ServiceWriteMapper m, String code) {
        ServiceCategoryEntity row = m.selectCategoryByCode(code);
        if (row == null || row.getId() == null || row.getId() <= 0) {
            throw unavailable("category row is missing after write");
        }
        damaged(row);
        return row;
    }

    private static void damaged(ServiceCategoryEntity row) {
        if (row.getId() == null || row.getId() <= 0
                || row.getCategoryCode() == null || row.getCategoryCode().isBlank()
                || row.getCategoryName() == null || row.getCategoryName().isBlank()
                || row.getVersion() == null || row.getVersion() < 0
                || row.getSortNo() == null
                || !STATUSES.contains(row.getStatus())) {
            throw unavailable("category facts are damaged");
        }
    }

    private static Long parentId(ServiceWriteMapper m, String parentCode, Long selfId) {
        if (parentCode == null) return null;
        ServiceCategoryEntity parent = m.selectCategoryByCode(parentCode);
        if (parent == null) throw invalid("parentCode must reference an existing category");
        if (selfId != null && selfId.equals(parent.getId())) {
            throw invalid("parentCode cannot reference the category itself");
        }
        return parent.getId();
    }

    private void audit(
            ServiceWriteMapper m,
            CommandContext ctx,
            ServiceCategoryEntity row,
            String actionType,
            String fromStatus,
            String toStatus,
            Map<String, Object> detail) {
        try {
            one(m.insertCategoryAudit(
                    store.nextId(),
                    row.getId(),
                    row.getCategoryCode(),
                    actionType,
                    fromStatus,
                    toStatus,
                    principal(ctx),
                    ctx.requestId(),
                    ctx.traceId(),
                    detailJson(detail),
                    now()));
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(
                    CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "requestId has already been used by another category write");
        }
    }

    private static Map<String, Object> updateDetail(
            ServiceCategoryEntity before, ServiceCategoryEntity after, boolean parentCodePresent) {
        Map<String, Object> changes = new LinkedHashMap<>();
        change(changes, "categoryName", before.getCategoryName(), after.getCategoryName());
        change(changes, "parentId", before.getParentId(), after.getParentId());
        change(changes, "iconUrl", before.getIconUrl(), after.getIconUrl());
        change(changes, "description", before.getDescription(), after.getDescription());
        change(changes, "sortNo", before.getSortNo(), after.getSortNo());
        if (!parentCodePresent) changes.remove("parentId");
        return changes;
    }

    private static void change(
            Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("from", before);
            values.put("to", after);
            changes.put(field, values);
        }
    }

    private static String detailJson(Map<String, Object> detail) {
        try {
            return JSON.writeValueAsString(detail);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("category audit detail is not serializable", failure);
        }
    }

    private static ServiceCategoryResult result(ServiceCategoryEntity row) {
        return new ServiceCategoryResult(
                IDS.toApi(row.getId()), row.getCategoryCode(), row.getStatus(),
                row.getVersion().longValue());
    }

    private static String write(ServiceCategoryResult result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("category receipt is not serializable", failure);
        }
    }

    private static ServiceCategoryResult read(String receipt) {
        try {
            return JSON.readValue(receipt, ServiceCategoryResult.class);
        } catch (RuntimeException | java.io.IOException failure) {
            throw unavailable("stored category receipt is unreadable");
        }
    }

    // ------------------------------------------------------------- authorization

    /**
     * Re-proves the live admin session and {@code service.category.manage} inside the caller's
     * write transaction. The category id/version are server-bound values, never request input.
     */
    private void authorize(
            ServiceAdminAuthorization ref, CommandContext ctx, long categoryId, int version) {
        if (ref == null
                || ref.sessionId() == null
                || ref.sessionId().isBlank()
                || ref.sessionGeneration() < 0) {
            throw new ApiException(
                    CommonApiCodes.UNAUTHORIZED, "admin authorization reference is required");
        }
        Decision decision =
                authorization.check(
                        new Check(
                                ref.sessionId(),
                                ref.sessionGeneration(),
                                ctx.operatorId(),
                                "service.category.manage",
                                IDS.toApi(categoryId),
                                version,
                                "SERVICE_CATEGORY",
                                Phase.EXECUTE));
        ServiceCategoryAuthorizationPort.enforce(decision);
    }

    // ------------------------------------------------------------- validation

    private static String code(String value) {
        String code = trimToNull(value);
        if (code == null || !CATEGORY_CODE.matcher(code).matches()) {
            throw invalid("categoryCode must match ^[A-Z][A-Z0-9_]{1,31}$");
        }
        return code;
    }

    private static String optionalCode(String value, String field) {
        String code = trimToNull(value);
        if (code == null) return null;
        if (!CATEGORY_CODE.matcher(code).matches()) {
            throw invalid(field + " must match ^[A-Z][A-Z0-9_]{1,31}$");
        }
        return code;
    }

    private static String name(String value, boolean required) {
        String name = trimToNull(value);
        if (name == null) {
            if (required) throw invalid("categoryName is required");
            return null;
        }
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 20) {
            throw invalid("categoryName must contain 1 to 20 code points");
        }
        return name;
    }

    private String iconUrl(String value) {
        String icon = trimToNull(value);
        if (icon == null) return null;
        if (icon.length() > ICON_URL_LIMIT) throw invalid("iconUrl exceeds 512 characters");
        URI uri;
        try {
            uri = URI.create(icon);
        } catch (IllegalArgumentException malformed) {
            throw invalid("iconUrl is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean https = "https".equals(scheme);
        boolean http = allowHttpIconUrl && "http".equals(scheme);
        if (!https && !http) {
            throw invalid("iconUrl must be an HTTPS URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw invalid("iconUrl is invalid");
        return icon;
    }

    private static String description(String value) {
        String description = trimToNull(value);
        if (description == null) return null;
        if (description.codePointCount(0, description.length()) > DESCRIPTION_LIMIT) {
            throw invalid("description exceeds 200 code points");
        }
        return description;
    }

    private static int sortNo(Integer value, int fallback) {
        int sortNo = value == null ? fallback : value;
        if (sortNo < 0 || sortNo > 9_999) throw invalid("sortNo must be between 0 and 9999");
        return sortNo;
    }

    private static long version(long value, String field) {
        if (value < 0) throw invalid(field + " is invalid");
        return value;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static CommandContext operator(CommandContext ctx) {
        if (ctx == null || ctx.operatorId() == null || ctx.operatorId().isBlank()
                || ctx.requestId() == null || ctx.requestId().isBlank()) {
            throw invalid("command context is required");
        }
        principal(ctx);
        if (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "admin session is required");
        }
        return ctx;
    }

    private static long principal(CommandContext ctx) {
        try {
            long operator = Long.parseLong(ctx.operatorId());
            if (operator <= 0) throw invalid("operator is invalid");
            return operator;
        } catch (NumberFormatException malformed) {
            throw invalid("operator is invalid");
        }
    }

    private static void one(int updated) {
        if (updated != 1) throw versionConflict();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException categoryNotFound() {
        return new ApiException(ServiceCategoryApiCodes.CATEGORY_NOT_FOUND, "服务分类不存在");
    }

    private static ApiException codeConflict() {
        return new ApiException(
                ServiceCategoryApiCodes.CATEGORY_CODE_CONFLICT, "分类编号已存在");
    }

    private static ApiException versionConflict() {
        return new ApiException(
                ServiceCategoryApiCodes.CATEGORY_VERSION_CONFLICT, "服务分类版本已变化");
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
