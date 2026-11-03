package com.petplatform.coupon.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.coupon.api.command.AdminCouponCampaignApi;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Check;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CollectionCheck;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Phase;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Resource;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.ClaimRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Coupon campaign/batch admin face (contract 72 §4). */
public final class AdminCouponCampaignService implements AdminCouponCampaignApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> AUDIENCES = Set.of("ALL", "NEW_USER");

    private final CouponAdminStore store;
    private final AdminCouponAuthorizationPort.CouponPort authorization;
    private final Clock clock;

    public AdminCouponCampaignService(CouponAdminStore store,
            AdminCouponAuthorizationPort.CouponPort authorization, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.authorization = Objects.requireNonNull(authorization);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Page<CampaignView> listCampaigns(ListQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeCollection(query.authorization(), "coupon.campaign.read", "COUPON_CAMPAIGN_READ");
        int page = query.page() < 1 ? 1 : Math.min(query.page(), 10_000);
        int pageSize = query.pageSize() < 1 ? 20 : Math.min(query.pageSize(), 100);
        return store.read(mapper -> {
            List<CampaignRow> rows = mapper.selectCouponCampaigns(query.status(), pageSize,
                    (page - 1) * pageSize);
            long total = mapper.countCouponCampaigns(query.status());
            return new Page<>(rows.stream().map(AdminCouponCampaignService::view).toList(),
                    page, pageSize, total);
        });
    }

    @Override
    public CampaignDetail getCampaign(DetailQuery query) {
        Objects.requireNonNull(query, "query is required");
        long campaignId = id(query.campaignId());
        authorize(query.authorization(), "coupon.campaign.read", campaignId, "COUPON_CAMPAIGN_READ");
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCouponCampaignById(campaignId);
            if (row == null) throw notFound();
            return new CampaignDetail(view(row), row.ruleJson,
                    (int) mapper.countCouponClaims(campaignId));
        });
    }

    @Override
    public Receipt createCampaign(CreateCommand command) {
        Objects.requireNonNull(command, "command is required");
        Input input = validate(command.input());
        CommandContext context = command.context();
        authorizeCommand(command.authorization(), context, "coupon.campaign.manage",
                "COUPON_CAMPAIGN_LIST", "0", "COUPON_CAMPAIGN_MANAGE");
        long operatorId = operatorId(context);
        String key = requestKey("coupon.campaign.create", context, null);
        Map<String, Object> params = input.params();
        return idempotent(key, params, () -> store.execute(mapper -> {
            AdminTemplateEntity template = mapper.selectTemplateForUpdate(input.templateId());
            requireTemplate(template, input);
            long id = store.nextId();
            LocalDateTime now = nowUtc();
            try {
                if (mapper.insertCouponCampaign(id, input.name(), input.templateId(),
                        input.audienceType(), input.perUserLimit(), input.totalLimit(),
                        input.budgetLimit(), utc(input.startAt()), utc(input.endAt()),
                        "ACTIVE_CLAIM", "DRAFT", operatorId, now) != 1) {
                    unavailable("coupon campaign insert failed");
                }
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw conflict("券活动名称已存在");
            }
            audit(mapper, id, "CREATE", null, "DRAFT", operatorId, context, "{}");
            return new Receipt(Long.toUnsignedString(id), "DRAFT", 1, now());
        }), receipt -> receiptJson(receipt.campaignId(), receipt.status(), receipt.version(), receipt.occurredAt()));
    }

    @Override
    public Receipt updateCampaign(UpdateCommand command) {
        Objects.requireNonNull(command, "command is required");
        long campaignId = id(command.campaignId());
        Input input = validate(command.input());
        CommandContext context = command.context();
        authorizeCommand(command.authorization(), context, "coupon.campaign.manage",
                "COUPON_CAMPAIGN", command.campaignId(), "COUPON_CAMPAIGN_MANAGE");
        String key = requestKey("coupon.campaign.update", context, command.campaignId());
        Map<String, Object> params = input.params();
        params.put("campaignId", campaignId);
        params.put("expectedVersion", command.expectedVersion());
        return idempotent(key, params, () -> store.execute(mapper -> {
            CampaignRow current = mapper.selectCouponCampaignForUpdate(campaignId);
            if (current == null) throw notFound();
            if (current.version == null || current.version != command.expectedVersion()) {
                throw conflict("券活动版本已变化");
            }
            if (!"DRAFT".equals(current.status)) throw conflict("仅草稿券活动可编辑");
            AdminTemplateEntity template = mapper.selectTemplateForUpdate(input.templateId());
            requireTemplate(template, input);
            try {
                if (mapper.updateCouponCampaign(campaignId, input.name(), input.templateId(),
                        input.audienceType(), input.perUserLimit(), input.totalLimit(),
                        input.budgetLimit(), utc(input.startAt()), utc(input.endAt()),
                        command.expectedVersion(), nowUtc()) != 1) {
                    throw conflict("券活动版本已变化");
                }
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw conflict("券活动名称已存在");
            }
            audit(mapper, campaignId, "UPDATE", current.status, current.status,
                    operatorId(context), context, "{}");
            return new Receipt(Long.toUnsignedString(campaignId), current.status,
                    command.expectedVersion() + 1, now());
        }), receipt -> receiptJson(receipt.campaignId(), receipt.status(), receipt.version(), receipt.occurredAt()));
    }

    @Override
    public Receipt publishCampaign(StatusCommand command) {
        return status(command, "coupon.campaign.publish", Set.of("DRAFT"), "PUBLISH", true);
    }

    @Override
    public Receipt pauseCampaign(StatusCommand command) {
        return status(command, "coupon.campaign.publish", Set.of("ACTIVE"), "PAUSE", false);
    }

    @Override
    public Receipt endCampaign(StatusCommand command) {
        return status(command, "coupon.campaign.publish", Set.of("ACTIVE", "PAUSED", "SCHEDULED"), "END", false);
    }

    @Override
    public Page<ClaimView> listClaims(ClaimsQuery query) {
        Objects.requireNonNull(query, "query is required");
        long campaignId = id(query.campaignId());
        authorize(query.authorization(), "coupon.campaign.read", campaignId, "COUPON_CAMPAIGN_READ");
        int page = query.page() < 1 ? 1 : Math.min(query.page(), 10_000);
        int pageSize = query.pageSize() < 1 ? 20 : Math.min(query.pageSize(), 100);
        return store.read(mapper -> {
            List<ClaimRow> rows = mapper.selectCouponClaims(campaignId, pageSize, (page - 1) * pageSize);
            long total = mapper.countCouponClaims(campaignId);
            List<ClaimView> items = rows.stream()
                    .map(row -> new ClaimView(Long.toUnsignedString(row.id),
                            Long.toUnsignedString(row.userId), Long.toUnsignedString(row.couponInstanceId),
                            offset(row.claimedAt)))
                    .toList();
            return new Page<>(items, page, pageSize, total);
        });
    }

    private Receipt status(StatusCommand command, String action, Set<String> fromStatuses,
            String actionName, boolean publish) {
        Objects.requireNonNull(command, "command is required");
        long campaignId = id(command.campaignId());
        CommandContext context = command.context();
        authorizeCommand(command.authorization(), context, action,
                "COUPON_CAMPAIGN", command.campaignId(), "COUPON_CAMPAIGN_PUBLISH");
        String key = requestKey("coupon.campaign." + actionName.toLowerCase(), context, command.campaignId());
        Map<String, Object> params = Map.of("campaignId", campaignId,
                "expectedVersion", command.expectedVersion(), "toStatus", actionName);
        return idempotent(key, params, () -> store.execute(mapper -> {
            CampaignRow current = mapper.selectCouponCampaignForUpdate(campaignId);
            if (current == null) throw notFound();
            if (current.version == null || current.version != command.expectedVersion()) {
                throw conflict("券活动版本已变化");
            }
            if (!fromStatuses.contains(current.status)) throw conflict("当前状态不能变更");
            String to = "PAUSE".equals(actionName) ? "PAUSED"
                    : "END".equals(actionName) ? "ENDED"
                    : current.startAt != null && current.startAt.isAfter(nowUtc()) ? "SCHEDULED" : "ACTIVE";
            if (publish) {
                AdminTemplateEntity template = mapper.selectTemplateForUpdate(current.templateId);
                requireTemplate(template, new Input(current.name, current.templateId, current.audienceType,
                        current.perUserLimit, current.totalLimit, current.budgetLimit,
                        offset(current.startAt), offset(current.endAt), Map.of()));
            }
            if (mapper.updateCouponCampaignStatus(campaignId, current.status, to,
                    command.expectedVersion(), nowUtc()) != 1) {
                throw conflict("券活动版本已变化");
            }
            audit(mapper, campaignId, actionName, current.status, to, operatorId(context), context, "{}");
            return new Receipt(Long.toUnsignedString(campaignId), to,
                    command.expectedVersion() + 1, now());
        }), receipt -> receiptJson(receipt.campaignId(), receipt.status(), receipt.version(), receipt.occurredAt()));
    }

    // ---------------------------------------------------------------- validation

    private Input validate(CampaignInput input) {
        if (input == null) invalid("coupon campaign input is required");
        String name = text(input.name(), 2, 50, "name");
        long templateId = id(input.templateId());
        String audience = text(input.audienceType(), 1, 16, "audienceType");
        if (!AUDIENCES.contains(audience)) invalid("audienceType is invalid");
        if (input.perUserLimit() != 1) invalid("V1 perUserLimit must be 1");
        if (input.totalLimit() < 1 || input.totalLimit() > 1_000_000) invalid("totalLimit is invalid");
        if (input.budgetLimit() == null || input.budgetLimit().signum() <= 0
                || input.budgetLimit().scale() > 2) invalid("budgetLimit is invalid");
        if (input.startAt() == null || input.endAt() == null
                || !input.startAt().isBefore(input.endAt())) invalid("campaign window is invalid");
        return new Input(name, templateId, audience, input.perUserLimit(), input.totalLimit(),
                input.budgetLimit(), input.startAt(), input.endAt(), Map.of());
    }

    private void requireTemplate(AdminTemplateEntity template, Input input) {
        if (template == null) throw notFound();
        if (!"ACTIVE".equals(template.getStatus())) throw conflict("券模板未启用");
        LocalDateTime now = nowUtc();
        if (now.isBefore(template.getValidStartAt()) || now.isAfter(template.getValidEndAt())) {
            throw conflict("券模板不在有效期内");
        }
        if (utc(input.startAt()).isBefore(template.getValidStartAt())
                || utc(input.endAt()).isAfter(template.getValidEndAt())) {
            throw conflict("券活动窗口必须在券模板有效期内");
        }
        CouponRule rule = CouponRule.parse(template.getRuleJson());
        if (rule == null) throw conflict("券模板规则不可用");
        if (input.budgetLimit().compareTo(rule.amountOff()) < 0) {
            throw conflict("预算不足以发放一张券");
        }
    }

    // ---------------------------------------------------------------- helpers

    private Receipt idempotent(String key, Map<String, Object> params,
            java.util.function.Supplier<Receipt> work,
            java.util.function.Function<Receipt, String> receiptJson) {
        AdminCouponCanonicalParams.Canonical canonical = AdminCouponCanonicalParams.of(params);
        CouponAdminStore.Binding binding = store.admit(key, canonical);
        if (binding.paramsSha256() == null || !binding.paramsSha256().equals(canonical.sha256())
                || !java.util.Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "coupon campaign idempotency conflict");
        }
        if ("SUCCEEDED".equals(binding.status())) {
            return parseReceipt(binding.receiptJson());
        }
        Receipt receipt = work.get();
        if (store.execute(mapper -> mapper.markBindingSucceeded(key, receiptJson.apply(receipt))) != 1) {
            unavailable("coupon campaign idempotency marking failed");
        }
        return receipt;
    }

    private void authorize(AdminAuthorization auth, String action, long resourceId, String purpose) {
        requireAuth(auth);
        var decision = authorization.check(new Check(auth.sessionId(), auth.sessionGeneration(),
                auth.operatorId(), action,
                new Resource("COUPON_CAMPAIGN", Long.toUnsignedString(resourceId), null, null, "0"),
                purpose, Phase.READ_RESULT));
        requireAllowed(decision);
    }

    private void authorizeCollection(AdminAuthorization auth, String action, String purpose) {
        requireAuth(auth);
        var decision = authorization.checkCollection(new CollectionCheck(
                auth.sessionId(), auth.sessionGeneration(), auth.operatorId(), action,
                purpose, Phase.READ_RESULT));
        requireAllowed(decision);
    }

    private void authorizeCommand(AdminAuthorization auth, CommandContext context, String action,
            String resourceType, String resourceId, String purpose) {
        requireAuth(auth);
        if (context == null || context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "platform operator context is required");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException failure) {
            invalid("requestId is invalid");
        }
        var decision = authorization.check(new Check(auth.sessionId(), auth.sessionGeneration(),
                context.operatorId(), action,
                new Resource(resourceType, resourceId, null, null, "0"),
                purpose, Phase.EXECUTE));
        requireAllowed(decision);
    }

    private static void requireAuth(AdminAuthorization auth) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
    }

    private static void requireAllowed(AdminCouponAuthorizationPort.Decision decision) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            unavailable("authorization decision is unavailable");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    private static CampaignView view(CampaignRow row) {
        CouponRule rule = CouponRule.parse(row.ruleJson);
        String typeLabel = CouponRule.typeLabel(row.ruleJson);
        if (typeLabel == null) {
            typeLabel = rule == null || rule.thresholdAmount() == null ? "无门槛券" : "满减券";
        }
        return new CampaignView(
                Long.toUnsignedString(row.id), row.name, Long.toUnsignedString(row.templateId),
                row.templateName,
                rule == null ? null : rule.amountOff().toPlainString(),
                rule == null || rule.thresholdAmount() == null ? null : rule.thresholdAmount().toPlainString(),
                typeLabel, row.audienceType, row.perUserLimit, row.totalLimit,
                row.budgetLimit == null ? null : row.budgetLimit.toPlainString(),
                row.issuedCount == null ? 0 : row.issuedCount,
                row.issuedAmount == null ? null : row.issuedAmount.toPlainString(),
                offset(row.startAt), offset(row.endAt), row.status,
                row.version == null ? 0 : row.version.intValue());
    }

    private static String text(String value, int min, int max, String field) {
        if (value == null) invalid(field + " is required");
        String text = value.strip();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) invalid(field + " length is invalid");
        return text;
    }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid("id is invalid");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            invalid("id is too large");
            return 0;
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private static String requestKey(String type, CommandContext context, String suffix) {
        String raw = context.operatorType() + ":" + context.operatorId() + ":"
                + context.requestId() + (suffix == null ? "" : ":" + suffix);
        return "MKT:" + type + ":" + AdminCouponCanonicalParams.sha256Hex(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static long operatorId(CommandContext context) {
        try {
            return Long.parseLong(context.operatorId());
        } catch (RuntimeException failure) {
            invalid("operatorId is invalid");
            return 0;
        }
    }

    private void audit(com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponAdminMapper mapper,
            long campaignId, String action, String from, String to, long operatorId,
            CommandContext context, String detail) {
        if (mapper.insertCouponCampaignAudit(store.nextId(), campaignId, action, from, to,
                operatorId, context.requestId(), context.traceId(), detail, nowUtc()) != 1) {
            unavailable("coupon campaign audit insert failed");
        }
    }

    private static String receiptJson(String id, String status, long version,
            OffsetDateTime occurredAt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", id);
        out.put("status", status);
        out.put("version", version);
        out.put("occurredAt", occurredAt.toString());
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception failure) {
            throw new IllegalStateException("coupon campaign receipt serialization failed", failure);
        }
    }

    private static Receipt parseReceipt(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            String occurredAt = root.path("occurredAt").asText(null);
            return new Receipt(root.path("campaignId").asText(), root.path("status").asText(),
                    root.path("version").asLong(), occurredAt == null || occurredAt.isBlank()
                    ? OffsetDateTime.now(ZoneOffset.UTC) : OffsetDateTime.parse(occurredAt));
        } catch (Exception failure) {
            unavailable("stored coupon campaign receipt is not readable");
            return null;
        }
    }

    private record Input(String name, long templateId, String audienceType, int perUserLimit,
            int totalLimit, BigDecimal budgetLimit, OffsetDateTime startAt, OffsetDateTime endAt,
            Map<String, Object> extra) {
        Map<String, Object> params() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", name);
            params.put("templateId", templateId);
            params.put("audienceType", audienceType);
            params.put("perUserLimit", perUserLimit);
            params.put("totalLimit", totalLimit);
            params.put("budgetLimit", budgetLimit);
            params.put("startAt", startAt);
            params.put("endAt", endAt);
            return params;
        }
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException notFound() {
        return new ApiException(CommonApiCodes.NOT_FOUND, "资源不存在");
    }

    private static ApiException conflict(String message) {
        return new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
