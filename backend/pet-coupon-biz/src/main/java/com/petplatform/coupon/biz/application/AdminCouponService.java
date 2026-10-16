package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.CreateTemplateCommand;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.TemplateMutation;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.TemplateStatusCommand;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.UpdateTemplateCommand;
import com.petplatform.coupon.api.query.AdminCouponQueryApi;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceDetail;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceListQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceSummary;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateDetail;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateListQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateSummary;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.LedgerRow;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.TrialCalculation;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.TrialCalculationQuery;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Check;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CollectionCheck;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Phase;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Port;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Resource;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore.Binding;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminInstanceEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminLedgerEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Platform coupon governance kernel (contract 65 §4, PRD 6.4, A-005). Reads: template page /
 * detail / trial calculation plus the instance ledger with its append-only coupon_ledger
 * timeline. Writes: template create (DRAFT), white-listed edit (DRAFT/ACTIVE with
 * issuedCount=0), enable (DRAFT→ACTIVE) and offline (ACTIVE→OFFLINE); coupon instances are
 * never written (SSOT §9 grants no operator instance-status action — CCR-ADM-A005-OPS-001).
 * rule_json keeps the frozen 61 §2 four-key shape; the trial calculation is a pure read
 * projection of the freeze eligibility semantics. Every entry re-proves the current
 * session/action/scope through the injected authorization port; writes bind requestId
 * idempotency on command_idempotency (14号) with same-parameter replay returning the first
 * receipt and an expectedVersion optimistic guard.
 */
public final class AdminCouponService implements AdminCouponQueryApi,
        com.petplatform.coupon.api.command.AdminCouponCommandApi {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> TEMPLATE_STATUSES = Set.of("DRAFT", "ACTIVE", "OFFLINE");
    private static final Set<String> INSTANCE_STATUSES =
            Set.of("AVAILABLE", "FROZEN", "USED", "EXPIRED", "RISK_FROZEN");

    private final CouponAdminStore store;
    private final Port authorization;
    private final java.time.Clock clock;

    public AdminCouponService(CouponAdminStore store, Port authorization, java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    // ------------------------------------------------------------------- reads

    @Override
    public PageResult<AdminTemplateSummary> listTemplates(AdminTemplateListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        authorizeCollection(auth(query.authorization()), context, "coupon.template.read",
                "COUPON_TEMPLATE_READ");
        String status = templateStatus(query.status());
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        return store.read(m -> {
            long total = m.countTemplates(status);
            List<AdminTemplateSummary> items = total == 0 ? List.of()
                    : m.selectTemplates(status, pageSize, (page - 1) * pageSize).stream()
                            .map(AdminCouponService::templateSummary).toList();
            return new PageResult<>(items, total, page, pageSize);
        });
    }

    @Override
    public AdminTemplateDetail getTemplate(AdminTemplateQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        long templateId = id(query.templateId());
        authorizeRow(auth(query.authorization()), context, "coupon.template.read",
                "COUPON_TEMPLATE", String.valueOf(templateId));
        AdminTemplateEntity row = store.read(m -> m.selectTemplateById(templateId));
        if (row == null) throw notFound();
        return templateDetail(row);
    }

    @Override
    public TrialCalculation trialCalculation(TrialCalculationQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        long templateId = id(query.templateId());
        authorizeRow(auth(query.authorization()), context, "coupon.template.read",
                "COUPON_TEMPLATE", String.valueOf(templateId));
        BigDecimal orderAmount = amount(query.orderAmount(), "orderAmount");
        return store.read(m -> {
            AdminTemplateEntity row = m.selectTemplateById(templateId);
            if (row == null) throw notFound();
            return trial(row, orderAmount);
        });
    }

    @Override
    public PageResult<AdminInstanceSummary> listInstances(AdminInstanceListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        authorizeCollection(auth(query.authorization()), context, "coupon.instance.read",
                "COUPON_INSTANCE_READ");
        Long templateId = query.templateId() == null ? null : id(query.templateId());
        String status = instanceStatus(query.status());
        Long orderId = query.orderId() == null ? null : id(query.orderId());
        LocalDateTime from = utcLocal(query.createdFrom());
        LocalDateTime to = utcLocal(query.createdTo());
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        return store.read(m -> {
            long total = m.countInstances(templateId, status, orderId, from, to);
            List<AdminInstanceSummary> items = total == 0 ? List.of()
                    : m.selectInstances(templateId, status, orderId, from, to, pageSize,
                            (page - 1) * pageSize).stream()
                            .map(AdminCouponService::instanceSummary).toList();
            return new PageResult<>(items, total, page, pageSize);
        });
    }

    @Override
    public AdminInstanceDetail getInstance(AdminInstanceQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        long instanceId = id(query.instanceId());
        authorizeRow(auth(query.authorization()), context, "coupon.instance.read",
                "COUPON_INSTANCE", String.valueOf(instanceId));
        return store.read(m -> {
            AdminInstanceEntity row = m.selectInstanceById(instanceId);
            if (row == null) throw notFound();
            List<LedgerRow> ledger = m.selectLedgerByInstance(instanceId).stream()
                    .map(AdminCouponService::ledgerRow).toList();
            return new AdminInstanceDetail(
                    Long.toUnsignedString(row.getId()), Long.toUnsignedString(row.getTemplateId()),
                    row.getTemplateName(), Long.toUnsignedString(row.getUserId()), row.getStatus(),
                    amounts(row)[0], amounts(row)[1], utc(row.getOriginalExpireAt()),
                    utc(row.getExpireAt()), row.getOrderId() == null ? null
                            : Long.toUnsignedString(row.getOrderId()),
                    utc(row.getFrozenAt()), utc(row.getFreezeExpireAt()), utc(row.getUsedAt()),
                    utc(row.getCreatedAt()), utc(row.getUpdatedAt()), ledger);
        });
    }

    // ------------------------------------------------------------------ writes

    @Override
    public TemplateMutation createTemplate(CreateTemplateCommand command) {
        Objects.requireNonNull(command, "command is required");
        String name = name(command.name());
        BigDecimal amountOff = amount(command.amountOff(), "amountOff");
        BigDecimal thresholdAmount = optionalAmount(command.thresholdAmount(), "thresholdAmount");
        if (thresholdAmount != null && thresholdAmount.compareTo(amountOff) <= 0) {
            invalid("满减券使用门槛必须大于面额");
        }
        OffsetDateTime start = requireInstant(command.validStartAt(), "validStartAt");
        OffsetDateTime end = requireInstant(command.validEndAt(), "validEndAt");
        if (!start.isBefore(end)) invalid("validStartAt must be before validEndAt");
        String scopeSummary = optionalText(command.scopeSummary(), 64, "scopeSummary");
        String typeLabel = command.typeLabel() == null || command.typeLabel().isBlank()
                ? (thresholdAmount == null ? "无门槛券" : "满减券")
                : text(command.typeLabel(), 16, "typeLabel");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "create");
        params.put("name", name);
        params.put("amountOff", amountOff.toPlainString());
        params.put("thresholdAmount", thresholdAmount == null ? null : thresholdAmount.toPlainString());
        params.put("validStartAt", start.toString());
        params.put("validEndAt", end.toString());
        params.put("scopeSummary", scopeSummary);
        params.put("typeLabel", typeLabel);
        return runBound(params, command.context(), command.authorization(),
                "coupon.template.create", "coupon.template.manage", "COUPON_TEMPLATE", "create",
                key -> {
                    long id = store.nextId();
                    LocalDateTime now = nowUtc();
                    if (store.execute(m -> m.insertTemplate(id, name, "DRAFT",
                            utcLocal(start), utcLocal(end), ruleJson(amountOff, thresholdAmount,
                                    scopeSummary, typeLabel), now)) != 1) {
                        throw internal("coupon template create failed");
                    }
                    AdminTemplateEntity row = store.read(m -> m.selectTemplateById(id));
                    return receipt(key, row);
                });
    }

    @Override
    public TemplateMutation updateTemplate(UpdateTemplateCommand command) {
        Objects.requireNonNull(command, "command is required");
        long templateId = id(command.templateId());
        String name = name(command.name());
        BigDecimal amountOff = amount(command.amountOff(), "amountOff");
        BigDecimal thresholdAmount = optionalAmount(command.thresholdAmount(), "thresholdAmount");
        if (thresholdAmount != null && thresholdAmount.compareTo(amountOff) <= 0) {
            invalid("满减券使用门槛必须大于面额");
        }
        OffsetDateTime start = requireInstant(command.validStartAt(), "validStartAt");
        OffsetDateTime end = requireInstant(command.validEndAt(), "validEndAt");
        if (!start.isBefore(end)) invalid("validStartAt must be before validEndAt");
        String scopeSummary = optionalText(command.scopeSummary(), 64, "scopeSummary");
        String typeLabel = command.typeLabel() == null || command.typeLabel().isBlank()
                ? (thresholdAmount == null ? "无门槛券" : "满减券")
                : text(command.typeLabel(), 16, "typeLabel");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "update");
        params.put("templateId", templateId);
        params.put("name", name);
        params.put("amountOff", amountOff.toPlainString());
        params.put("thresholdAmount", thresholdAmount == null ? null : thresholdAmount.toPlainString());
        params.put("validStartAt", start.toString());
        params.put("validEndAt", end.toString());
        params.put("scopeSummary", scopeSummary);
        params.put("typeLabel", typeLabel);
        params.put("expectedVersion", command.expectedVersion());
        return runBound(params, command.context(), command.authorization(),
                "coupon.template.update", "coupon.template.manage", "COUPON_TEMPLATE",
                String.valueOf(templateId),
                key -> {
                    LocalDateTime now = nowUtc();
                    if (store.execute(m -> m.updateTemplateFields(templateId, name, utcLocal(start),
                            utcLocal(end), ruleJson(amountOff, thresholdAmount, scopeSummary, typeLabel),
                            command.expectedVersion(), now)) != 1) {
                        throw conflict();
                    }
                    AdminTemplateEntity row = store.read(m -> m.selectTemplateById(templateId));
                    if (row == null) throw notFound();
                    return receipt(key, row);
                });
    }

    @Override
    public TemplateMutation enableTemplate(TemplateStatusCommand command) {
        return statusTransition(command, "ACTIVE", "DRAFT", "coupon.template.enable");
    }

    @Override
    public TemplateMutation offlineTemplate(TemplateStatusCommand command) {
        return statusTransition(command, "OFFLINE", "ACTIVE", "coupon.template.offline");
    }

    private TemplateMutation statusTransition(
            TemplateStatusCommand command, String toStatus, String fromStatus, String namespace) {
        Objects.requireNonNull(command, "command is required");
        long templateId = id(command.templateId());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", toStatus.toLowerCase(Locale.ROOT));
        params.put("templateId", templateId);
        params.put("toStatus", toStatus);
        params.put("expectedVersion", command.expectedVersion());
        return runBound(params, command.context(), command.authorization(), namespace,
                "coupon.template.manage", "COUPON_TEMPLATE", String.valueOf(templateId),
                key -> {
                    LocalDateTime now = nowUtc();
                    if (store.execute(m -> m.updateTemplateStatus(templateId, fromStatus, toStatus,
                            command.expectedVersion(), now)) != 1) {
                        throw conflict();
                    }
                    AdminTemplateEntity row = store.read(m -> m.selectTemplateById(templateId));
                    if (row == null) throw notFound();
                    return receipt(key, row);
                });
    }

    // ----------------------------------------------------------------- helpers

    private TrialCalculation trial(AdminTemplateEntity row, BigDecimal orderAmount) {
        CouponRule rule = CouponRule.parse(row.getRuleJson());
        if (rule == null) {
            return new TrialCalculation(Long.toUnsignedString(row.getId()), orderAmount, null,
                    null, orderAmount, false, "RULE_UNREADABLE");
        }
        if (!"ACTIVE".equals(row.getStatus())) {
            return new TrialCalculation(Long.toUnsignedString(row.getId()), orderAmount,
                    rule.thresholdAmount(), rule.amountOff(), orderAmount, false,
                    "NOT_ACTIVE");
        }
        LocalDateTime now = nowUtc();
        if (now.isBefore(row.getValidStartAt())) {
            return new TrialCalculation(Long.toUnsignedString(row.getId()), orderAmount,
                    rule.thresholdAmount(), rule.amountOff(), orderAmount, false,
                    "NOT_YET_VALID");
        }
        if (now.isAfter(row.getValidEndAt())) {
            return new TrialCalculation(Long.toUnsignedString(row.getId()), orderAmount,
                    rule.thresholdAmount(), rule.amountOff(), orderAmount, false,
                    "EXPIRED");
        }
        BigDecimal threshold = rule.thresholdAmount();
        if (threshold != null && orderAmount.compareTo(threshold) < 0) {
            return new TrialCalculation(Long.toUnsignedString(row.getId()), orderAmount, threshold,
                    rule.amountOff(), orderAmount, false, "BELOW_THRESHOLD");
        }
        if (rule.amountOff().compareTo(orderAmount) >= 0) {
            return new TrialCalculation(Long.toUnsignedString(row.getId()), orderAmount, threshold,
                    rule.amountOff(), orderAmount, false, "DISCOUNT_EXCEEDS_AMOUNT");
        }
        return new TrialCalculation(Long.toUnsignedString(row.getId()), orderAmount, threshold,
                rule.amountOff(), orderAmount.subtract(rule.amountOff()), true, null);
    }

    /** Shared write pipeline: authorize → admit binding (replay on SUCCEEDED) → work. */
    private TemplateMutation runBound(
            Map<String, Object> params, CommandContext rawContext,
            com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization auth,
            String namespace, String actionCode, String resourceType, String resourceId,
            java.util.function.Function<String, AdminTemplateEntity> work) {
        CommandContext context = commandContext(rawContext);
        authorizeCommandRow(auth, operatorContext(context), actionCode, resourceType, resourceId);
        var canonical = AdminCouponCanonicalParams.of(params);
        String key = storeKey(namespace, context, resourceId);
        Binding binding = admitWithRecovery(key, canonical);
        // 14号 equality fact: a stored binding answers only when the protected parameters are
        // byte-identical; any other payload under the same requestId is a 409 key conflict.
        if (binding.paramsSha256() == null
                || !binding.paramsSha256().equals(canonical.sha256())
                || !java.util.Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "coupon admin idempotency conflict");
        }
        if ("SUCCEEDED".equals(binding.status())) {
            return parseReceipt(binding.receiptJson());
        }
        AdminTemplateEntity row = runWithRecovery(key, work);
        return new TemplateMutation(Long.toUnsignedString(row.getId()), row.getName(),
                row.getStatus(), row.getVersion() == null ? 0 : row.getVersion(),
                utc(row.getUpdatedAt()));
    }

    private AdminTemplateEntity runWithRecovery(
            String key, java.util.function.Function<String, AdminTemplateEntity> work) {
        try {
            return work.apply(key);
        } catch (CouponAdminStore.CommitUnknown first) {
            try {
                return work.apply(key);
            } catch (CouponAdminStore.CommitUnknown second) {
                throw unavailable("coupon admin commit result remains unknown");
            }
        }
    }

    /** Persists the template receipt on the binding; called inside the execution transaction. */
    private AdminTemplateEntity receipt(String key, AdminTemplateEntity row) {
        String json = "{\"templateId\":\"" + Long.toUnsignedString(row.getId())
                + "\",\"name\":" + jsonString(row.getName()) + ",\"status\":\"" + row.getStatus()
                + "\",\"version\":" + (row.getVersion() == null ? 0 : row.getVersion())
                + ",\"occurredAt\":\"" + now() + "\"}";
        if (store.execute(m -> m.markBindingSucceeded(key, json)) != 1) {
            throw unavailable("coupon admin idempotency marking failed");
        }
        return row;
    }

    private static TemplateMutation parseReceipt(String json) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return new TemplateMutation(root.path("templateId").asText(), root.path("name").asText(),
                    root.path("status").asText(), root.path("version").asLong(0),
                    OffsetDateTime.parse(root.path("occurredAt").asText()));
        } catch (Exception failure) {
            throw unavailable("stored coupon admin receipt is not readable");
        }
    }

    private Binding admitWithRecovery(String key, AdminCouponCanonicalParams.Canonical canonical) {
        try {
            return store.admit(key, canonical);
        } catch (CouponAdminStore.CommitUnknown first) {
            try {
                return store.admit(key, canonical);
            } catch (CouponAdminStore.CommitUnknown second) {
                throw unavailable("coupon admin admission result remains unknown");
            }
        }
    }

    private static AdminTemplateSummary templateSummary(AdminTemplateEntity row) {
        CouponRule rule = CouponRule.parse(row.getRuleJson());
        return new AdminTemplateSummary(
                Long.toUnsignedString(row.getId()), row.getName(), row.getStatus(),
                rule == null ? null : rule.amountOff(),
                rule == null ? null : rule.thresholdAmount(),
                utc(row.getValidStartAt()), utc(row.getValidEndAt()), row.getTotalStock(),
                row.getIssuedCount() == null ? 0 : row.getIssuedCount(),
                row.getVersion() == null ? 0 : row.getVersion(),
                utc(row.getCreatedAt()), utc(row.getUpdatedAt()));
    }

    private static AdminTemplateDetail templateDetail(AdminTemplateEntity row) {
        CouponRule rule = CouponRule.parse(row.getRuleJson());
        String ruleJson = row.getRuleJson();
        String scopeSummary = stringMember(ruleJson, "scopeSummary");
        String typeLabel = stringMember(ruleJson, "typeLabel");
        return new AdminTemplateDetail(
                Long.toUnsignedString(row.getId()), row.getName(), row.getStatus(),
                rule == null ? null : rule.amountOff(),
                rule == null ? null : rule.thresholdAmount(),
                scopeSummary, typeLabel,
                utc(row.getValidStartAt()), utc(row.getValidEndAt()), row.getTotalStock(),
                row.getIssuedCount() == null ? 0 : row.getIssuedCount(),
                row.getVersion() == null ? 0 : row.getVersion(),
                utc(row.getCreatedAt()), utc(row.getUpdatedAt()));
    }

    private static AdminInstanceSummary instanceSummary(AdminInstanceEntity row) {
        BigDecimal[] amounts = amounts(row);
        return new AdminInstanceSummary(
                Long.toUnsignedString(row.getId()), Long.toUnsignedString(row.getTemplateId()),
                row.getTemplateName(), Long.toUnsignedString(row.getUserId()), row.getStatus(),
                amounts[0], amounts[1], utc(row.getOriginalExpireAt()), utc(row.getExpireAt()),
                row.getOrderId() == null ? null : Long.toUnsignedString(row.getOrderId()),
                utc(row.getFrozenAt()), utc(row.getUsedAt()), utc(row.getCreatedAt()));
    }

    private static LedgerRow ledgerRow(AdminLedgerEntity row) {
        return new LedgerRow(
                Long.toUnsignedString(row.getId()), row.getAction(), row.getFromStatus(),
                row.getToStatus(),
                row.getOrderId() == null ? null : Long.toUnsignedString(row.getOrderId()),
                row.getRequestId(), row.getRemark(), utc(row.getCreatedAt()));
    }

    /** 65 §8.4: instance rule amounts read the template's current rule document. */
    private static BigDecimal[] amounts(AdminInstanceEntity row) {
        CouponRule rule = CouponRule.parse(row.getRuleJson());
        return new BigDecimal[] {
                rule == null ? null : rule.amountOff(),
                rule == null ? null : rule.thresholdAmount()};
    }

    private static String stringMember(String ruleJson, String member) {
        if (ruleJson == null || ruleJson.isBlank()) return null;
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ruleJson);
            var value = root.path(member);
            return value.isTextual() ? value.asText() : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Frozen 61 §2 four-key document; null members are omitted (absent = no threshold). */
    private static String ruleJson(BigDecimal amountOff, BigDecimal thresholdAmount,
            String scopeSummary, String typeLabel) {
        StringBuilder out = new StringBuilder("{");
        out.append("\"amountOff\":").append(jsonString(amountOff.toPlainString()));
        if (thresholdAmount != null) {
            out.append(",\"thresholdAmount\":").append(jsonString(thresholdAmount.toPlainString()));
        }
        if (scopeSummary != null) {
            out.append(",\"scopeSummary\":").append(jsonString(scopeSummary));
        }
        if (typeLabel != null) {
            out.append(",\"typeLabel\":").append(jsonString(typeLabel));
        }
        return out.append('}').toString();
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.append('"').toString();
    }

    private void authorizeCollection(com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization auth,
            QueryContext context, String actionCode, String purpose) {
        requireQuery(auth);
        Decision decision = authorization.checkCollection(new CollectionCheck(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(),
                actionCode, purpose, Phase.READ_RESULT));
        requireDecision(decision);
    }

    private void authorizeRow(com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization auth,
            QueryContext context, String actionCode, String resourceType, String resourceId) {
        requireQuery(auth);
        check(auth.sessionId(), auth.sessionGeneration(), context, actionCode, resourceType, resourceId);
    }

    private void authorizeCommandRow(
            com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization auth,
            QueryContext context, String actionCode, String resourceType, String resourceId) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
        check(auth.sessionId(), auth.sessionGeneration(), context, actionCode, resourceType, resourceId);
    }

    private void check(String sessionId, long sessionGeneration, QueryContext context,
            String actionCode, String resourceType, String resourceId) {
        Decision decision = authorization.check(new Check(
                sessionId, sessionGeneration, context.operatorId(),
                actionCode, new Resource(resourceType, resourceId, null, null, null),
                actionCode.replace('.', '_').toUpperCase(Locale.ROOT), Phase.EXECUTE));
        requireDecision(decision);
    }

    private static com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization auth(
            com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization auth) {
        return auth;
    }

    private void requireDecision(Decision decision) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    private static void requireQuery(
            com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization auth) {
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
            throw new ApiException(CommonApiCodes.FORBIDDEN, "coupon admin belongs to operators");
        }
        return context;
    }

    private CommandContext commandContext(CommandContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null
                || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
        }
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "coupon admin belongs to operators");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException bad) {
            invalid("requestId is invalid");
        }
        return context;
    }

    private QueryContext operatorContext(CommandContext context) {
        return new QueryContext(context.traceId(), context.operatorType(), context.operatorId());
    }

    private String storeKey(String namespace, CommandContext context, String scope) {
        return "rk-v1:" + namespace + ":" + context.operatorType().name() + ":"
                + context.operatorId() + ":" + scope + ":" + context.requestId();
    }

    private static String templateStatus(String status) {
        if (status == null || status.isBlank()) return null;
        if (!TEMPLATE_STATUSES.contains(status)) invalid("unknown template status");
        return status;
    }

    private static String instanceStatus(String status) {
        if (status == null || status.isBlank()) return null;
        if (!INSTANCE_STATUSES.contains(status)) invalid("unknown instance status");
        return status;
    }

    private static String name(String raw) {
        String value = text(raw, 50, "name");
        if (value.codePointCount(0, value.length()) < 2) invalid("name is below 2 codepoints");
        return value;
    }

    private static String text(String raw, int max, String field) {
        if (raw == null) invalid(field + " is required");
        String value = raw.trim();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > max) {
            invalid(field + " is empty or exceeds " + max + " codepoints");
        }
        return value;
    }

    private static String optionalText(String raw, int max, String field) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > max) {
            invalid(field + " exceeds " + max + " codepoints");
        }
        return value;
    }

    private static BigDecimal amount(BigDecimal raw, String field) {
        if (raw == null || raw.signum() <= 0) invalid(field + " must be positive");
        return scale(raw, field);
    }

    private static BigDecimal optionalAmount(BigDecimal raw, String field) {
        if (raw == null) return null;
        if (raw.signum() < 0) invalid(field + " must not be negative");
        return scale(raw, field);
    }

    private static BigDecimal scale(BigDecimal value, String field) {
        BigDecimal scaled = value.setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (scaled.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
            invalid(field + " exceeds the amount limit");
        }
        return scaled;
    }

    private static OffsetDateTime requireInstant(OffsetDateTime value, String field) {
        if (value == null) invalid(field + " is required");
        return value;
    }

    private static long id(String value) {
        Objects.requireNonNull(value, "id is required");
        if (!value.matches("[1-9][0-9]{0,18}")) invalid("id must be a decimal Snowflake String");
        return Long.parseLong(value);
    }

    private static int page(Integer value) {
        if (value == null) return 1;
        if (value < 1 || value > 10_000) invalid("page is outside 1..10000");
        return value;
    }

    private static int pageSize(Integer value) {
        if (value == null) return 20;
        if (value < 1 || value > MAX_PAGE_SIZE) invalid("pageSize is outside 1.." + MAX_PAGE_SIZE);
        return value;
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC);
    }

    private static LocalDateTime utcLocal(OffsetDateTime value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException notFound() {
        return new ApiException(CommonApiCodes.NOT_FOUND, "资源不存在");
    }

    private static ApiException conflict() {
        return new ApiException(CommonApiCodes.CONFLICT, "当前状态或版本冲突，请刷新后核对");
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    private static ApiException internal(String message) {
        return new ApiException(CommonApiCodes.INTERNAL_ERROR, message);
    }
}
