package com.petplatform.coupon.biz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.command.MerchantCouponApi;
import com.petplatform.coupon.api.error.MerchantCouponApiCodes;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignUsageRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.MerchantCouponStatsRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;

/**
 * Merchant self-built coupon face (contract 79 section 3). Each campaign owns exactly one
 * merchant template (created DRAFT, activated by the operator on approval) and one budget
 * account. Every write is terminal-idempotent (command_idempotency), version-checked and
 * audited into coupon_campaign_audit; owner admission answers 404 for anything the caller
 * does not own. Merchants never publish here.
 */
public final class MerchantCouponService implements MerchantCouponApi {

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> STATUSES = Set.of("DRAFT", "PENDING_REVIEW", "REJECTED",
            "CANCELED", "SCHEDULED", "ACTIVE", "PAUSED", "ENDED");
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final String SCOPE_SUMMARY = "本店服务通用";
    private static final String CREATOR = "MERCHANT_OWNER";

    private final CouponAdminStore store;
    private final MerchantCouponAdmissionGate admissions;
    private final Clock clock;

    public MerchantCouponService(CouponAdminStore store, MerchantCouponAdmissionGate admissions,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.admissions = Objects.requireNonNull(admissions);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Page list(ListQuery query) {
        Objects.requireNonNull(query, "query is required");
        long merchantId = id(query.merchantId());
        long storeId = id(query.storeId());
        if (query.status() != null && !STATUSES.contains(query.status())) invalid("status is invalid");
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        admissions.requireOperable(query.context(), merchantId, storeId);
        return store.read(mapper -> {
            List<CampaignRow> rows = mapper.selectMerchantCouponCampaigns(merchantId, query.status(),
                    pageSize, (page - 1) * pageSize);
            long total = mapper.countMerchantCouponCampaigns(merchantId, query.status());
            Map<Long, CampaignUsageRow> usage = usage(mapper.selectMerchantCouponUsage(merchantId));
            return new Page(rows.stream().map(row -> item(row, usage.get(row.id))).toList(),
                    page, pageSize, total);
        });
    }

    @Override
    public Item get(GetQuery query) {
        Objects.requireNonNull(query, "query is required");
        long campaignId = id(query.campaignId());
        long merchantId = id(query.merchantId());
        admissions.requireOperable(query.context(), merchantId, id(query.storeId()));
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCouponCampaignById(campaignId);
            requireOwned(row, merchantId);
            Map<Long, CampaignUsageRow> usage = usage(mapper.selectMerchantCouponUsage(merchantId));
            return item(row, usage.get(row.id));
        });
    }

    @Override
    public Overview overview(ScopeQuery query) {
        Objects.requireNonNull(query, "query is required");
        long merchantId = id(query.merchantId());
        admissions.requireOperable(query.context(), merchantId, id(query.storeId()));
        LocalDate month = LocalDate.now(clock.withZone(BEIJING)).withDayOfMonth(1);
        LocalDateTime monthStart = utc(month.atStartOfDay(BEIJING).toOffsetDateTime());
        LocalDateTime monthEnd = utc(month.plusMonths(1).atStartOfDay(BEIJING).toOffsetDateTime());
        return store.read(mapper -> {
            MerchantCouponStatsRow stats = mapper.selectMerchantCouponStats(merchantId, monthStart, monthEnd);
            int issued = stats.issuedCount == null ? 0 : stats.issuedCount.intValue();
            int used = stats.usedCount == null ? 0 : stats.usedCount.intValue();
            BigDecimal rate = issued == 0 ? new BigDecimal("0.0000")
                    : BigDecimal.valueOf(used).divide(BigDecimal.valueOf(issued), 4, RoundingMode.HALF_UP);
            return new Overview(
                    stats.totalCampaignCount == null ? 0 : stats.totalCampaignCount.intValue(),
                    stats.activeCampaignCount == null ? 0 : stats.activeCampaignCount.intValue(),
                    stats.pendingReviewCount == null ? 0 : stats.pendingReviewCount.intValue(),
                    stats.monthIssuedCount == null ? 0 : stats.monthIssuedCount.intValue(),
                    rate,
                    stats.drivenOrderCount == null ? 0 : stats.drivenOrderCount.intValue());
        });
    }

    // ------------------------------------------------------------------ writes

    @Override
    public Receipt create(CreateCommand command) {
        Objects.requireNonNull(command, "command is required");
        Input input = validate(command.input());
        CommandContext context = command(command.context());
        long merchantId = id(command.merchantId());
        long storeId = id(command.storeId());
        admissions.requireOperable(queryOf(context), merchantId, storeId);
        long userId = userId(context);
        return idempotent(requestKey("create", context, null), params("create", null, input), () ->
                store.execute(mapper -> {
                    LocalDateTime now = nowUtc();
                    long templateId = store.nextId();
                    if (mapper.insertMerchantTemplate(templateId, input.name(), merchantId,
                            utc(input.startAt()), utc(input.endAt()),
                            ruleJson(input.amountOff(), input.thresholdAmount()), now) != 1) {
                        unavailable("merchant coupon template insert failed");
                    }
                    long campaignId = store.nextId();
                    try {
                        if (mapper.insertMerchantCouponCampaign(campaignId, merchantId, storeId,
                                input.name(), templateId, input.perUserLimit(), input.totalLimit(),
                                input.budgetLimit(), utc(input.startAt()), utc(input.endAt()),
                                input.description(), userId, now) != 1) {
                            unavailable("merchant coupon campaign insert failed");
                        }
                    } catch (DuplicateKeyException duplicate) {
                        throw new ApiException(MerchantCouponApiCodes.NAME_DUPLICATE, "已有同名的活动");
                    }
                    if (mapper.insertMerchantCouponBudget(store.nextId(), campaignId, merchantId,
                            input.budgetLimit(), now) != 1) {
                        unavailable("merchant coupon budget insert failed");
                    }
                    audit(mapper, campaignId, "CREATE", null, "DRAFT", userId, context, "{}", now);
                    return new Receipt(Long.toUnsignedString(campaignId), "DRAFT", 1);
                }));
    }

    @Override
    public Receipt update(UpdateCommand command) {
        Objects.requireNonNull(command, "command is required");
        Input input = validate(command.input());
        CommandContext context = command(command.context());
        long campaignId = id(command.campaignId());
        long merchantId = id(command.merchantId());
        admissions.requireOperable(queryOf(context), merchantId, id(command.storeId()));
        long userId = userId(context);
        return idempotent(requestKey("update", context, command.campaignId()),
                params("update", command.expectedVersion(), input), () ->
                store.execute(mapper -> {
                    CampaignRow current = lockOwned(mapper, campaignId, merchantId);
                    requireVersion(current, command.expectedVersion());
                    if (!"DRAFT".equals(current.status) && !"REJECTED".equals(current.status)) {
                        throw stateNotAllowed("仅草稿或已驳回的活动可编辑");
                    }
                    AdminTemplateEntity template = mapper.selectTemplateForUpdate(current.templateId);
                    if (template == null || !"DRAFT".equals(template.getStatus()) || template.getVersion() == null) {
                        throw stateNotAllowed("券模板当前不可编辑");
                    }
                    LocalDateTime now = nowUtc();
                    if (mapper.updateMerchantTemplate(template.getId(), input.name(), utc(input.startAt()),
                            utc(input.endAt()), ruleJson(input.amountOff(), input.thresholdAmount()),
                            template.getVersion(), now) != 1) {
                        throw conflict("活动版本已变化，请刷新后重试");
                    }
                    try {
                        if (mapper.updateMerchantCouponCampaign(campaignId, input.name(),
                                input.perUserLimit(), input.totalLimit(), input.budgetLimit(),
                                utc(input.startAt()), utc(input.endAt()), input.description(),
                                command.expectedVersion(), now) != 1) {
                            throw conflict("活动版本已变化，请刷新后重试");
                        }
                    } catch (DuplicateKeyException duplicate) {
                        throw new ApiException(MerchantCouponApiCodes.NAME_DUPLICATE, "已有同名的活动");
                    }
                    if (mapper.updateMerchantCouponBudgetLimit(campaignId, input.budgetLimit(), now) != 1) {
                        unavailable("merchant coupon budget update failed");
                    }
                    audit(mapper, campaignId, "UPDATE", current.status, current.status, userId, context,
                            "{}", now);
                    return new Receipt(Long.toUnsignedString(campaignId), current.status,
                            command.expectedVersion() + 1);
                }));
    }

    @Override
    public Receipt submit(StatusCommand command) {
        Objects.requireNonNull(command, "command is required");
        return status(command, "submit", Set.of("DRAFT", "REJECTED"), "PENDING_REVIEW", null);
    }

    @Override
    public Receipt cancel(StatusCommand command) {
        Objects.requireNonNull(command, "command is required");
        return status(command, "cancel", Set.of("DRAFT", "REJECTED", "PENDING_REVIEW"), "CANCELED", null);
    }

    private Receipt status(StatusCommand command, String action, Set<String> fromStatuses,
            String toStatus, String reviewedBy) {
        CommandContext context = command(command.context());
        long campaignId = id(command.campaignId());
        long merchantId = id(command.merchantId());
        admissions.requireOperable(queryOf(context), merchantId, id(command.storeId()));
        long userId = userId(context);
        return idempotent(requestKey(action, context, command.campaignId()),
                params(action, command.expectedVersion(), null), () ->
                store.execute(mapper -> {
                    CampaignRow current = lockOwned(mapper, campaignId, merchantId);
                    requireVersion(current, command.expectedVersion());
                    if (!fromStatuses.contains(current.status)) {
                        throw stateNotAllowed("当前状态不能" + ("submit".equals(action) ? "提交" : "取消"));
                    }
                    if (!current.endAt.isAfter(nowUtc())) {
                        throw stateNotAllowed("活动已过有效期");
                    }
                    LocalDateTime now = nowUtc();
                    int changed = "submit".equals(action)
                            ? mapper.submitMerchantCouponCampaign(campaignId, command.expectedVersion(), now)
                            : mapper.cancelMerchantCouponCampaign(campaignId, command.expectedVersion(), now);
                    if (changed != 1) throw conflict("活动版本已变化，请刷新后重试");
                    String actionName = "submit".equals(action) ? "SUBMIT" : "CANCEL";
                    audit(mapper, campaignId, actionName, current.status, toStatus, userId, context, "{}", now);
                    return new Receipt(Long.toUnsignedString(campaignId), toStatus,
                            command.expectedVersion() + 1);
                }));
    }

    // ------------------------------------------------------------------ validation

    private static Input validate(Input input) {
        if (input == null) invalid("coupon campaign input is required");
        String name = text(input.name(), 2, 50, "name");
        BigDecimal amountOff = money(input.amountOff(), "amountOff");
        if (amountOff == null || amountOff.signum() <= 0) invalid("amountOff is invalid");
        BigDecimal threshold = input.thresholdAmount() == null ? null : money(input.thresholdAmount(), "thresholdAmount");
        if (threshold != null && threshold.signum() <= 0) invalid("thresholdAmount is invalid");
        if (input.totalLimit() < 1 || input.totalLimit() > 1_000_000) invalid("totalLimit is invalid");
        if (input.perUserLimit() < 1 || input.perUserLimit() > 10
                || input.perUserLimit() > input.totalLimit()) {
            invalid("perUserLimit is invalid");
        }
        BigDecimal budget = money(input.budgetLimit(), "budgetLimit");
        if (budget == null || budget.signum() <= 0 || budget.compareTo(amountOff) < 0) {
            invalid("budgetLimit is invalid");
        }
        if (input.startAt() == null || input.endAt() == null
                || !input.startAt().isBefore(input.endAt())) invalid("campaign window is invalid");
        try {
            PublicContractChecks.requireMillisecondPrecision(input.startAt());
            PublicContractChecks.requireMillisecondPrecision(input.endAt());
        } catch (IllegalArgumentException malformed) {
            invalid("campaign window precision is invalid");
        }
        String description = input.description() == null || input.description().isBlank()
                ? null : input.description().strip();
        if (description != null && description.codePointCount(0, description.length()) > 300) {
            invalid("description is too long");
        }
        return new Input(name, amountOff.setScale(2), threshold == null ? null : threshold.setScale(2),
                input.totalLimit(), input.perUserLimit(), budget.setScale(2), input.startAt(),
                input.endAt(), description);
    }

    private static String ruleJson(BigDecimal amountOff, BigDecimal thresholdAmount) {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("amountOff", amountOff.toPlainString());
        if (thresholdAmount != null) rule.put("thresholdAmount", thresholdAmount.toPlainString());
        rule.put("scopeSummary", SCOPE_SUMMARY);
        rule.put("typeLabel", thresholdAmount == null ? "无门槛券" : "满减券");
        try {
            return JSON.writeValueAsString(rule);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("coupon rule serialization failed", failure);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Row lock + ownership: anything the merchant does not own reads as not found. */
    private static CampaignRow lockOwned(
            com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponAdminMapper mapper,
            long campaignId, long merchantId) {
        CampaignRow current = mapper.selectCouponCampaignForUpdate(campaignId);
        requireOwned(current, merchantId);
        return current;
    }

    private static void requireOwned(CampaignRow row, long merchantId) {
        if (row == null || !row.merchantOwned() || !Objects.equals(row.ownerMerchantId, merchantId)) {
            throw new ApiException(MerchantCouponApiCodes.NOT_FOUND, "商家券活动不存在");
        }
    }

    private static void requireVersion(CampaignRow current, long expectedVersion) {
        if (current.version == null || current.version != expectedVersion) {
            throw conflict("活动版本已变化，请刷新后重试");
        }
    }

    private static Map<Long, CampaignUsageRow> usage(List<CampaignUsageRow> rows) {
        Map<Long, CampaignUsageRow> out = new HashMap<>();
        for (CampaignUsageRow row : rows) out.put(row.campaignId, row);
        return out;
    }

    /** Contract 79 section 2.3 wire shape; money keeps two decimals. */
    private static Item item(CampaignRow row, CampaignUsageRow usage) {
        CouponRule rule = CouponRule.parse(row.ruleJson);
        BigDecimal amountOff = rule == null ? new BigDecimal("0.00") : rule.amountOff().setScale(2);
        int usedCount = usage == null || usage.usedCount == null ? 0 : usage.usedCount.intValue();
        int driven = usage == null || usage.drivenOrderCount == null ? 0 : usage.drivenOrderCount.intValue();
        String typeLabel = CouponRule.typeLabel(row.ruleJson);
        return new Item(
                Long.toUnsignedString(row.id),
                row.ownerMerchantId == null ? null : Long.toUnsignedString(row.ownerMerchantId),
                row.ownerStoreId == null ? null : Long.toUnsignedString(row.ownerStoreId),
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
                offset(row.submittedAt), offset(row.reviewedAt), offset(row.createdAt), offset(row.updatedAt));
    }

    private Receipt idempotent(String key, Map<String, Object> params, Supplier<Receipt> work) {
        AdminCouponCanonicalParams.Canonical canonical = AdminCouponCanonicalParams.of(params);
        CouponAdminStore.Binding binding = store.admit(key, canonical);
        if (binding.paramsSha256() == null || !binding.paramsSha256().equals(canonical.sha256())
                || !java.util.Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "merchant coupon idempotency conflict");
        }
        if ("SUCCEEDED".equals(binding.status())) return parseReceipt(binding.receiptJson());
        Receipt receipt = work.get();
        if (store.execute(mapper -> mapper.markBindingSucceeded(key, receiptJson(receipt))) != 1) {
            unavailable("merchant coupon idempotency marking failed");
        }
        return receipt;
    }

    private static Map<String, Object> params(String action, Long expectedVersion, Input input) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", action);
        params.put("expectedVersion", expectedVersion);
        if (input != null) {
            params.put("name", input.name());
            params.put("amountOff", input.amountOff().toPlainString());
            params.put("thresholdAmount", input.thresholdAmount() == null ? null : input.thresholdAmount().toPlainString());
            params.put("totalLimit", input.totalLimit());
            params.put("perUserLimit", input.perUserLimit());
            params.put("budgetLimit", input.budgetLimit().toPlainString());
            params.put("startAt", input.startAt().toString());
            params.put("endAt", input.endAt().toString());
            params.put("description", input.description());
        }
        return params;
    }

    private static String requestKey(String action, CommandContext context, String campaignId) {
        String raw = context.operatorType() + ":" + context.operatorId() + ":" + context.requestId()
                + ":" + action + ":" + (campaignId == null ? "" : campaignId);
        return "MKT:merchant.coupon." + action + ":"
                + AdminCouponCanonicalParams.sha256Hex(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static CommandContext command(CommandContext context) {
        if (context == null || context.operatorType() != OperatorType.USER) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "merchant session is required");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException malformed) {
            invalid("requestId is invalid");
        }
        return context;
    }

    private static QueryContext queryOf(CommandContext context) {
        return new QueryContext(context.traceId(), OperatorType.USER, context.operatorId());
    }

    private void audit(com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponAdminMapper mapper,
            long campaignId, String action, String from, String to, long operatorId,
            CommandContext context, String detail, LocalDateTime now) {
        if (mapper.insertCouponCampaignAudit(store.nextId(), campaignId, action, from, to,
                operatorId, context.requestId(), context.traceId(), detail, now) != 1) {
            unavailable("merchant coupon audit insert failed");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String receiptJson(Receipt receipt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", receipt.campaignId());
        out.put("status", receipt.status());
        out.put("version", receipt.version());
        try {
            return JSON.writeValueAsString(out);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("merchant coupon receipt serialization failed", failure);
        }
    }

    private static Receipt parseReceipt(String json) {
        try {
            var root = JSON.readTree(json);
            return new Receipt(root.path("campaignId").asText(), root.path("status").asText(),
                    root.path("version").asLong());
        } catch (Exception failure) {
            unavailable("stored merchant coupon receipt is not readable");
            return null;
        }
    }

    private static long userId(CommandContext context) {
        try {
            return Long.parseLong(context.operatorId());
        } catch (RuntimeException malformed) {
            invalid("operatorId is invalid");
            return 0;
        }
    }

    private static int page(int page) {
        return page < 1 ? 1 : Math.min(page, 10_000);
    }

    private static int pageSize(int pageSize) {
        return pageSize < 1 ? 20 : Math.min(pageSize, 50);
    }

    private static String text(String value, int min, int max, String field) {
        if (value == null) invalid(field + " is required");
        String text = value.strip();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) invalid(field + " length is invalid");
        return text;
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null) return null;
        if (value.scale() > 2 || value.compareTo(new BigDecimal("99999999.99")) > 0) invalid(field + " is invalid");
        return value;
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

    private static LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private static void invalid(String message) {
        throw new ApiException(MerchantCouponApiCodes.INVALID, message);
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
