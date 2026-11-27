package com.petplatform.coupon.biz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.command.MerchantCouponAdminApi;
import com.petplatform.coupon.api.error.MerchantCouponApiCodes;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Check;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CollectionCheck;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CouponPort;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Phase;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Resource;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignUsageRow;
import com.petplatform.merchant.api.dto.MerchantStoreDTO;
import com.petplatform.merchant.api.query.MerchantQueryApi;
import com.petplatform.merchant.api.query.StoreIdQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Operator review of merchant self-built coupons (contract 79 section 4). Operators approve,
 * reject, pause and end; they never edit coupon content. Approval activates the campaign's
 * dedicated template and moves the campaign to SCHEDULED/ACTIVE in one transaction. Reuses the
 * platform coupon campaign action codes (coupon.campaign.read / coupon.campaign.publish) through
 * the module-local authorization port; a missing decision never reads or writes.
 */
public final class MerchantCouponAdminService implements MerchantCouponAdminApi {

    private static final Set<String> STATUSES = Set.of("DRAFT", "PENDING_REVIEW", "REJECTED",
            "CANCELED", "SCHEDULED", "ACTIVE", "PAUSED", "ENDED");
    private static final String RESOURCE = "MERCHANT_COUPON_CAMPAIGN";
    private static final String READ = "COUPON_CAMPAIGN_READ";
    private static final String PUBLISH = "COUPON_CAMPAIGN_PUBLISH";

    private final CouponAdminStore store;
    private final CouponPort authorization;
    private final MerchantQueryApi merchants;
    private final Clock clock;

    public MerchantCouponAdminService(CouponAdminStore store, CouponPort authorization,
            MerchantQueryApi merchants, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.authorization = Objects.requireNonNull(authorization);
        this.merchants = merchants;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Page list(ListQuery query) {
        Objects.requireNonNull(query, "query is required");
        if (query.status() != null && !STATUSES.contains(query.status())) invalid("status is invalid");
        Long merchantId = query.merchantId() == null || query.merchantId().isBlank()
                ? null : id(query.merchantId());
        int page = query.page() < 1 ? 1 : Math.min(query.page(), 10_000);
        int pageSize = query.pageSize() < 1 ? 20 : Math.min(query.pageSize(), 50);
        authorizeCollection(query.authorization(), "coupon.campaign.read", READ);
        return store.read(mapper -> {
            List<CampaignRow> rows = mapper.selectMerchantCouponCampaignsForReview(query.status(),
                    merchantId, pageSize, (page - 1) * pageSize);
            long total = mapper.countMerchantCouponCampaignsForReview(query.status(), merchantId);
            Map<Long, CampaignUsageRow> usage = usage(mapper, rows.stream().map(row -> row.id).toList());
            return new Page(rows.stream().map(row -> item(row, usage.get(row.id), query.context()))
                    .toList(), page, pageSize, total);
        });
    }

    private Map<Long, CampaignUsageRow> usage(
            com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponAdminMapper mapper,
            List<Long> campaignIds) {
        Map<Long, CampaignUsageRow> usage = new HashMap<>();
        if (campaignIds.isEmpty()) return usage;
        for (CampaignUsageRow row : mapper.selectMerchantCouponUsageByIds(campaignIds)) {
            usage.put(row.campaignId, row);
        }
        return usage;
    }

    @Override
    public Item get(GetQuery query) {
        Objects.requireNonNull(query, "query is required");
        long campaignId = id(query.campaignId());
        authorize(query.authorization(), "coupon.campaign.read", campaignId, READ);
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCouponCampaignById(campaignId);
            if (row == null || !row.merchantOwned()) throw notFound();
            return item(row, usage(mapper, List.of(row.id)).get(row.id), query.context());
        });
    }

    // ------------------------------------------------------------------ review

    @Override
    public Receipt approve(ReviewCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = operatorContext(command.context());
        long campaignId = id(command.campaignId());
        authorizeCommand(command, "APPROVE");
        return idempotent(requestKey("approve", context, campaignId),
                reviewParams("approve", campaignId, command.expectedVersion(), null), () ->
                store.execute(mapper -> {
                    CampaignRow current = lock(campaignId, mapper);
                    requireVersion(current, command.expectedVersion());
                    if (!"PENDING_REVIEW".equals(current.status)) {
                        throw stateNotAllowed("仅待审核的活动可通过");
                    }
                    LocalDateTime now = nowUtc();
                    if (!current.endAt.isAfter(now)) {
                        throw stateNotAllowed("活动已过有效期，请驳回");
                    }
                    AdminTemplateEntity template = mapper.selectTemplateForUpdate(current.templateId);
                    if (template == null || !"DRAFT".equals(template.getStatus())
                            || template.getVersion() == null
                            || !Objects.equals(current.templateOwnerMerchantId, current.ownerMerchantId)) {
                        throw stateNotAllowed("券模板状态不允许通过");
                    }
                    String to = current.startAt.isAfter(now) ? "SCHEDULED" : "ACTIVE";
                    if (mapper.activateMerchantTemplate(template.getId(), template.getVersion(), now) != 1
                            || mapper.reviewMerchantCouponCampaign(campaignId, command.expectedVersion(), to,
                                    null, "PLATFORM_OPERATOR", operatorId(context), now) != 1) {
                        throw conflict("活动版本已变化，请刷新后重试");
                    }
                    audit(mapper, campaignId, "APPROVE", current.status, to, context, "{}", now);
                    return new Receipt(Long.toUnsignedString(campaignId), to, command.expectedVersion() + 1);
                }));
    }

    @Override
    public Receipt reject(ReviewCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = operatorContext(command.context());
        String reason = command.reason() == null ? "" : command.reason().strip();
        int length = reason.codePointCount(0, reason.length());
        if (length < 1 || length > 500) invalid("驳回原因为 1-500 字");
        long campaignId = id(command.campaignId());
        authorizeCommand(command, "REJECT");
        return idempotent(requestKey("reject", context, campaignId),
                reviewParams("reject", campaignId, command.expectedVersion(), reason), () ->
                store.execute(mapper -> {
                    CampaignRow current = lock(campaignId, mapper);
                    requireVersion(current, command.expectedVersion());
                    if (!"PENDING_REVIEW".equals(current.status)) {
                        throw stateNotAllowed("仅待审核的活动可驳回");
                    }
                    LocalDateTime now = nowUtc();
                    if (mapper.reviewMerchantCouponCampaign(campaignId, command.expectedVersion(), "REJECTED",
                            reason, "PLATFORM_OPERATOR", operatorId(context), now) != 1) {
                        throw conflict("活动版本已变化，请刷新后重试");
                    }
                    audit(mapper, campaignId, "REJECT", current.status, "REJECTED", context,
                            "{\"reason\":" + jsonString(reason) + "}", now);
                    return new Receipt(Long.toUnsignedString(campaignId), "REJECTED",
                            command.expectedVersion() + 1);
                }));
    }

    @Override
    public Receipt pause(ReviewCommand command) {
        return settle(command, "pause", "PAUSE", Set.of("SCHEDULED", "ACTIVE"), "PAUSED");
    }

    @Override
    public Receipt end(ReviewCommand command) {
        return settle(command, "end", "END", Set.of("SCHEDULED", "ACTIVE", "PAUSED"), "ENDED");
    }

    private Receipt settle(ReviewCommand command, String action, String actionName,
            Set<String> fromStatuses, String toStatus) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = operatorContext(command.context());
        long campaignId = id(command.campaignId());
        authorizeCommand(command, actionName);
        return idempotent(requestKey(action, context, campaignId),
                reviewParams(action, campaignId, command.expectedVersion(), null), () ->
                store.execute(mapper -> {
                    CampaignRow current = lock(campaignId, mapper);
                    requireVersion(current, command.expectedVersion());
                    if (!fromStatuses.contains(current.status)) {
                        throw stateNotAllowed("当前状态不能" + ("PAUSE".equals(actionName) ? "暂停" : "结束"));
                    }
                    LocalDateTime now = nowUtc();
                    int changed = "PAUSE".equals(actionName)
                            ? mapper.pauseMerchantCouponCampaign(campaignId, command.expectedVersion(), now)
                            : mapper.endMerchantCouponCampaign(campaignId, command.expectedVersion(), now);
                    if (changed != 1) throw conflict("活动版本已变化，请刷新后重试");
                    audit(mapper, campaignId, actionName, current.status, toStatus, context, "{}", now);
                    return new Receipt(Long.toUnsignedString(campaignId), toStatus,
                            command.expectedVersion() + 1);
                }));
    }

    // ------------------------------------------------------------------ authorization

    private void authorize(AdminAuthorization auth, String action, long campaignId, String purpose) {
        requireAuth(auth);
        requireAllowed(authorization.check(new Check(auth.sessionId(), auth.sessionGeneration(),
                auth.operatorId(), action,
                new Resource(RESOURCE, Long.toUnsignedString(campaignId), null, null, "0"),
                purpose, Phase.READ_RESULT)));
    }

    private void authorizeCollection(AdminAuthorization auth, String action, String purpose) {
        requireAuth(auth);
        requireAllowed(authorization.checkCollection(new CollectionCheck(auth.sessionId(),
                auth.sessionGeneration(), auth.operatorId(), action, purpose, Phase.READ_RESULT)));
    }

    private void authorizeCommand(ReviewCommand command, String actionName) {
        AdminAuthorization auth = command.authorization();
        requireAuth(auth);
        CommandContext context = command.context();
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "platform operator context is required");
        }
        requireAllowed(authorization.check(new Check(auth.sessionId(), auth.sessionGeneration(),
                context.operatorId(), "coupon.campaign.publish",
                new Resource(RESOURCE, command.campaignId(), null, null, "0"),
                actionName, Phase.EXECUTE)));
    }

    private static void requireAuth(AdminAuthorization auth) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
    }

    private static void requireAllowed(Decision decision) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            unavailable("authorization decision is unavailable");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    // ------------------------------------------------------------------ helpers

    private static CampaignRow lock(long campaignId,
            com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponAdminMapper mapper) {
        CampaignRow current = mapper.selectCouponCampaignForUpdate(campaignId);
        if (current == null || !current.merchantOwned()) throw notFound();
        return current;
    }

    private static void requireVersion(CampaignRow current, long expectedVersion) {
        if (current.version == null || current.version != expectedVersion) {
            throw conflict("活动版本已变化，请刷新后重试");
        }
    }

    private static CommandContext operatorContext(CommandContext context) {
        if (context == null) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "operator context is required");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException malformed) {
            invalid("requestId is invalid");
        }
        return context;
    }

    /** Contract 79 section 4 wire shape with merchant/store display names. */
    private Item item(CampaignRow row, CampaignUsageRow usage, QueryContext context) {
        CouponRule rule = CouponRule.parse(row.ruleJson);
        BigDecimal amountOff = rule == null ? new BigDecimal("0.00") : rule.amountOff().setScale(2);
        int usedCount = usage == null || usage.usedCount == null ? 0 : usage.usedCount.intValue();
        int driven = usage == null || usage.drivenOrderCount == null ? 0 : usage.drivenOrderCount.intValue();
        String typeLabel = CouponRule.typeLabel(row.ruleJson);
        String merchantName = null;
        String storeName = null;
        if (merchants != null && row.ownerStoreId != null) {
            try {
                MerchantStoreDTO facts = merchants.getStore(new StoreIdQuery(
                        Long.toUnsignedString(row.ownerStoreId), context));
                merchantName = facts == null ? null : facts.merchantName();
                storeName = facts == null ? null : facts.storeName();
            } catch (RuntimeException unreadable) {
                merchantName = null; // display labels only: never fail the review list
                storeName = null;
            }
        }
        return new Item(
                Long.toUnsignedString(row.id),
                row.ownerMerchantId == null ? null : Long.toUnsignedString(row.ownerMerchantId),
                merchantName,
                row.ownerStoreId == null ? null : Long.toUnsignedString(row.ownerStoreId),
                storeName,
                Long.toUnsignedString(row.templateId),
                row.name,
                rule != null && rule.thresholdAmount() != null ? "THRESHOLD" : "NO_THRESHOLD",
                amountOff,
                rule == null || rule.thresholdAmount() == null ? null : rule.thresholdAmount().setScale(2),
                typeLabel == null ? (rule != null && rule.thresholdAmount() != null ? "满减券" : "无门槛券") : typeLabel,
                row.totalLimit == null ? 0 : row.totalLimit,
                row.perUserLimit == null ? 1 : row.perUserLimit,
                row.budgetLimit == null ? null : row.budgetLimit.setScale(2),
                row.issuedCount == null ? 0 : row.issuedCount,
                row.issuedAmount == null ? new BigDecimal("0.00") : row.issuedAmount.setScale(2),
                offset(row.startAt), offset(row.endAt),
                row.description, row.status, row.rejectReason,
                row.version == null ? 0 : row.version,
                row.issuedCount == null ? 0 : row.issuedCount,
                usedCount, driven,
                amountOff.multiply(BigDecimal.valueOf(usedCount)).setScale(2),
                offset(row.submittedAt), offset(row.reviewedAt), row.reviewedByType,
                offset(row.createdAt), offset(row.updatedAt));
    }

    private Receipt idempotent(String key, Map<String, Object> params, Supplier<Receipt> work) {
        AdminCouponCanonicalParams.Canonical canonical = AdminCouponCanonicalParams.of(params);
        CouponAdminStore.Binding binding = store.admit(key, canonical);
        if (binding.paramsSha256() == null || !binding.paramsSha256().equals(canonical.sha256())
                || !java.util.Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "merchant coupon review idempotency conflict");
        }
        if ("SUCCEEDED".equals(binding.status())) return parseReceipt(binding.receiptJson());
        Receipt receipt = work.get();
        if (store.execute(mapper -> mapper.markBindingSucceeded(key, receiptJson(receipt))) != 1) {
            unavailable("merchant coupon review idempotency marking failed");
        }
        return receipt;
    }

    private static Map<String, Object> reviewParams(String action, long campaignId,
            long expectedVersion, String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", action);
        params.put("campaignId", campaignId);
        params.put("expectedVersion", expectedVersion);
        params.put("reason", reason);
        return params;
    }

    private static String requestKey(String action, CommandContext context, long campaignId) {
        String raw = context.operatorType() + ":" + context.operatorId() + ":" + context.requestId()
                + ":review." + action + ":" + campaignId;
        return "MKT:merchant.coupon.review." + action + ":"
                + AdminCouponCanonicalParams.sha256Hex(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void audit(
            com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponAdminMapper mapper,
            long campaignId, String action, String from, String to, CommandContext context,
            String detail, LocalDateTime now) {
        if (mapper.insertCouponCampaignAudit(store.nextId(), campaignId, action, from, to,
                operatorId(context), context.requestId(), context.traceId(), detail, now) != 1) {
            unavailable("merchant coupon review audit insert failed");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String jsonString(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("audit reason serialization failed", failure);
        }
    }

    private static String receiptJson(Receipt receipt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", receipt.campaignId());
        out.put("status", receipt.status());
        out.put("version", receipt.version());
        try {
            return JSON.writeValueAsString(out);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("merchant coupon review receipt serialization failed", failure);
        }
    }

    private static Receipt parseReceipt(String json) {
        try {
            var root = JSON.readTree(json);
            return new Receipt(root.path("campaignId").asText(), root.path("status").asText(),
                    root.path("version").asLong());
        } catch (Exception failure) {
            unavailable("stored merchant coupon review receipt is not readable");
            return null;
        }
    }

    private static long operatorId(CommandContext context) {
        try {
            return Long.parseLong(context.operatorId());
        } catch (RuntimeException malformed) {
            invalid("operatorId is invalid");
            return 0;
        }
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

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static void invalid(String message) {
        throw new ApiException(MerchantCouponApiCodes.INVALID, message);
    }

    private static ApiException notFound() {
        return new ApiException(MerchantCouponApiCodes.NOT_FOUND, "商家券活动不存在");
    }

    private static ApiException stateNotAllowed(String message) {
        return new ApiException(MerchantCouponApiCodes.STATE_NOT_ALLOWED, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
