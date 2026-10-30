package com.petplatform.marketing.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.marketing.api.MarketingAdminApi.AdminAuthorization;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignStoreRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.EnrollmentRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.HomeBlockRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.PlacementRow;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shared marketing kernel helpers: validation, time, auth, idempotency, projections. */
abstract class MarketingSupport {

    protected static final ObjectMapper JSON = new ObjectMapper();

    protected final MarketingStore store;
    protected final MarketingAdminAuthorizationPort authorization;
    protected final MarketingStoreFactsPort storeFacts;
    protected final MarketingCouponFactsPort couponFacts;
    protected final Clock clock;

    protected MarketingSupport(
            MarketingStore store,
            MarketingAdminAuthorizationPort authorization,
            MarketingStoreFactsPort storeFacts,
            MarketingCouponFactsPort couponFacts,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = authorization;
        this.storeFacts = storeFacts;
        this.couponFacts = couponFacts;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------ time

    protected LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    protected OffsetDateTime nowOffset() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    protected static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    // -------------------------------------------------------------- validation

    protected static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid("id must be a decimal Snowflake String");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            invalid("id is too large");
            return 0;
        }
    }

    protected static String idString(Long value) {
        return value == null ? null : Long.toUnsignedString(value);
    }

    protected static String requireText(String value, int min, int max, String field) {
        if (value == null) invalid(field + " is required");
        String text = value.strip();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) invalid(field + " length is invalid");
        return text;
    }

    protected static String optionalText(String value, int max, String field) {
        if (value == null) return null;
        String text = value.strip();
        if (text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) > max) invalid(field + " length is invalid");
        return text;
    }

    protected static String optionalUrl(String value, String field) {
        return optionalUrl(value, field, false);
    }

    /** Local E2E may opt into http image URLs; production remains HTTPS-only. */
    protected static String optionalUrl(String value, String field, boolean allowHttp) {
        if (value == null || value.isBlank()) return null;
        String url = value.strip();
        boolean secure = url.startsWith("https://");
        boolean localHttp = allowHttp && url.startsWith("http://");
        if ((!secure && !localHttp) || url.length() > 512) invalid(field + " is invalid");
        return url;
    }

    protected static int boundedInt(Integer value, int fallback, int min, int max, String field) {
        if (value == null) return fallback;
        if (value < min || value > max) invalid(field + " is invalid");
        return value;
    }

    protected static void requireWindow(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) invalid("time window is invalid");
    }

    protected static List<String> stringList(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) invalid("list item is invalid");
            String text = value.strip();
            if (text.length() > 64) invalid("list item is too long");
            if (!result.contains(text)) result.add(text);
        }
        return List.copyOf(result);
    }

    protected static String jsonList(List<String> values) {
        try {
            return JSON.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception failure) {
            throw new IllegalStateException("json list serialization failed", failure);
        }
    }

    protected static List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual()) values.add(item.asText());
            }
            return List.copyOf(values);
        } catch (Exception failure) {
            return List.of();
        }
    }

    protected static String safeJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(json);
            return node == null ? null : node.toString();
        } catch (Exception failure) {
            invalid("materials must be valid JSON");
            return null;
        }
    }

    // -------------------------------------------------------------- authorization

    protected void authorize(AdminAuthorization auth, CommandContext context, String action,
            String resourceType, String resourceId, String cityCode, String merchantId) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
        if (context == null || context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "platform operator context is required");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException failure) {
            invalid("requestId is invalid");
        }
        if (authorization == null) unavailable("marketing authorization port is not assembled");
        MarketingAdminAuthorizationPort.Decision decision = authorization.check(
                new MarketingAdminAuthorizationPort.Check(
                        auth.sessionId(), auth.sessionGeneration(), context.operatorId(),
                        action, resourceType, resourceId, cityCode, merchantId));
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            unavailable("authorization decision is unavailable");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    protected void authorizeRead(AdminAuthorization auth, String action, String resourceType,
            String resourceId, String cityCode, String merchantId) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
        if (authorization == null) unavailable("marketing authorization port is not assembled");
        MarketingAdminAuthorizationPort.Decision decision = authorization.check(
                new MarketingAdminAuthorizationPort.Check(
                        auth.sessionId(), auth.sessionGeneration(), auth.operatorId(),
                        action, resourceType, resourceId, cityCode, merchantId));
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            unavailable("authorization decision is unavailable");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    protected static void requireMerchantQuery(com.petplatform.common.QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "merchant session is required");
        }
    }

    /** Row-level data-scope filter for admin list/detail reads (contract 71 §6). */
    protected static boolean scopeAllows(AdminAuthorization auth, CampaignRow row) {
        if (auth == null || "ALL".equals(auth.scopeMode())) return true;
        if ("CITY".equals(auth.scopeMode())) {
            if (auth.cityCodes().isEmpty()) return false;
            List<String> campaignCities = parseJsonList(row.cityCodes);
            if (campaignCities.isEmpty()) return true; // national campaign
            return campaignCities.stream().anyMatch(auth.cityCodes()::contains);
        }
        if ("MERCHANT".equals(auth.scopeMode())) {
            return row.ownerMerchantId != null
                    && auth.merchantIds().contains(Long.toUnsignedString(row.ownerMerchantId));
        }
        return false;
    }

    protected static boolean scopeAllows(AdminAuthorization auth, PlacementRow row) {
        if (auth == null || "ALL".equals(auth.scopeMode())) return true;
        if ("CITY".equals(auth.scopeMode())) {
            if (auth.cityCodes().isEmpty()) return false;
            return row.cityCode == null || auth.cityCodes().contains(row.cityCode);
        }
        return false; // placements have no merchant dimension
    }

    protected static boolean scopeAllowsEnrollment(AdminAuthorization auth, EnrollmentRow row,
            String storeCity) {
        if (auth == null || "ALL".equals(auth.scopeMode())) return true;
        if ("CITY".equals(auth.scopeMode())) {
            return storeCity != null && auth.cityCodes().contains(storeCity);
        }
        if ("MERCHANT".equals(auth.scopeMode())) {
            return row.merchantId != null
                    && auth.merchantIds().contains(Long.toUnsignedString(row.merchantId));
        }
        return false;
    }

    /** City-scoped writers may not create/update a campaign spanning cities outside their scope. */
    protected static void requireCampaignWriteScope(AdminAuthorization auth, List<String> cityCodes) {
        if (auth == null || "ALL".equals(auth.scopeMode())) return;
        if (!"CITY".equals(auth.scopeMode())) {
            forbidden("当前数据范围不能维护平台活动");
        }
        if (cityCodes == null || cityCodes.isEmpty() || !auth.cityCodes().containsAll(cityCodes)) {
            forbidden("城市范围不足");
        }
    }

    protected static boolean scopeAllowsStore(AdminAuthorization auth, String merchantId,
            String cityCode) {
        if (auth == null || "ALL".equals(auth.scopeMode())) return true;
        if ("CITY".equals(auth.scopeMode())) return cityCode != null && auth.cityCodes().contains(cityCode);
        if ("MERCHANT".equals(auth.scopeMode())) {
            return merchantId != null && auth.merchantIds().contains(merchantId);
        }
        return false;
    }

    protected static void requireMerchantCommand(CommandContext context) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "merchant session is required");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException failure) {
            invalid("requestId is invalid");
        }
    }

    // -------------------------------------------------------------- idempotency

    protected <T> T idempotent(
            String requestKey,
            String commandType,
            Map<String, Object> params,
            Function<String, T> parseReceipt,
            Supplier<T> work,
            Function<T, String> receiptJson) {
        MarketingCanonical canonical = MarketingCanonical.of(params);
        MarketingStore.Binding binding = admitWithRecovery(requestKey, commandType, canonical);
        MarketingStore.requireSame(binding, canonical);
        if ("SUCCEEDED".equals(binding.status())) return parseReceipt.apply(binding.receiptJson());
        if (!"IN_PROGRESS".equals(binding.status())) unavailable("idempotency binding is damaged");
        if (!binding.fresh()) conflict("marketing command is already in progress");
        try {
            T result = work.get();
            store.markSucceeded(requestKey, receiptJson.apply(result));
            return result;
        } catch (MarketingStore.CommitUnknown unknown) {
            MarketingStore.Binding replay = admitWithRecovery(requestKey, commandType, canonical);
            MarketingStore.requireSame(replay, canonical);
            if ("SUCCEEDED".equals(replay.status())) return parseReceipt.apply(replay.receiptJson());
            unavailable("marketing command commit result is unknown");
            return null;
        } catch (RuntimeException failure) {
            try {
                store.execute(mapper -> mapper.deleteBinding(requestKey));
            } catch (RuntimeException ignored) {
                // The original failure is the actionable one; a stale IN_PROGRESS row is
                // recovered by the operator retrying with a new requestId.
            }
            throw failure;
        }
    }

    private MarketingStore.Binding admitWithRecovery(
            String requestKey, String commandType, MarketingCanonical canonical) {
        try {
            return store.admit(requestKey, commandType, canonical);
        } catch (MarketingStore.CommitUnknown first) {
            try {
                return store.admit(requestKey, commandType, canonical);
            } catch (MarketingStore.CommitUnknown second) {
                unavailable("marketing idempotency admission result is unknown");
                return null;
            }
        }
    }

    protected static String requestKey(String commandType, CommandContext context, String suffix) {
        String raw = context.operatorType() + ":" + context.operatorId() + ":"
                + context.requestId()
                + (suffix == null || suffix.isBlank() ? "" : ":" + suffix);
        return "MKT:" + commandType + ":" + MarketingCanonical.hash(raw);
    }

    protected static String receiptJson(String id, String status, long version, int pageVersion) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status);
        out.put("version", version);
        out.put("pageVersion", pageVersion);
        out.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception failure) {
            throw new IllegalStateException("receipt serialization failed", failure);
        }
    }

    protected static MarketingViews.Receipt parseReceipt(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            return new MarketingViews.Receipt(
                    root.path("id").asText(null),
                    root.path("status").asText(null),
                    root.path("version").asLong(),
                    root.path("pageVersion").asInt(),
                    OffsetDateTime.parse(root.path("occurredAt").asText()));
        } catch (Exception failure) {
            unavailable("stored marketing receipt is not readable");
            return null;
        }
    }

    // -------------------------------------------------------------- projections

    protected static MarketingViews.CampaignCard campaignCard(CampaignRow row, int storeCount) {
        return new MarketingViews.CampaignCard(
                idString(row.id), row.campaignCode, row.name, row.ownerType, row.campaignType,
                row.participationMode, row.status, row.title, row.subtitle, row.tagCode, row.tagName,
                row.coverUrl, parseJsonList(row.cityCodes), parseJsonList(row.serviceCategoryCodes),
                offset(row.startAt), offset(row.endAt), offset(row.enrollStartAt),
                offset(row.enrollEndAt), row.enrollmentQuota, idString(row.couponCampaignId),
                row.pageVersion == null ? 0 : row.pageVersion, storeCount,
                row.sortWeight == null ? 0 : row.sortWeight,
                row.version == null ? 0 : row.version.intValue(),
                offset(row.createdAt), offset(row.updatedAt));
    }

    protected static MarketingViews.CampaignStoreLink storeLink(CampaignStoreRow row) {
        return new MarketingViews.CampaignStoreLink(
                idString(row.storeId), idString(row.merchantId), row.source, row.status,
                row.offerTag, row.offerTitle, row.offerCopy,
                row.sortWeight == null ? 0 : row.sortWeight);
    }

    protected static MarketingViews.PlacementCard placementCard(
            PlacementRow row, CampaignRow campaign) {
        String title = row.titleOverride != null ? row.titleOverride
                : campaign == null ? row.campaignTitle : campaign.title;
        String subtitle = row.subtitleOverride != null ? row.subtitleOverride
                : campaign == null ? null : campaign.subtitle;
        String cover = row.imageUrlOverride != null ? row.imageUrlOverride
                : campaign == null ? null : campaign.coverUrl;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("campaignId", idString(row.campaignId));
        String jumpParams;
        try {
            jumpParams = JSON.writeValueAsString(params);
        } catch (Exception failure) {
            throw new IllegalStateException("jump params serialization failed", failure);
        }
        return new MarketingViews.PlacementCard(
                idString(row.id), row.positionCode, idString(row.campaignId), title, subtitle,
                campaign == null ? null : campaign.tagCode, campaign == null ? null : campaign.tagName,
                cover, row.templateCode, "CAMPAIGN_PAGE", jumpParams,
                offset(row.startAt), offset(row.endAt),
                row.sortWeight == null ? 0 : row.sortWeight);
    }

    protected static MarketingViews.PlacementRow placementRow(PlacementRow row) {
        return new MarketingViews.PlacementRow(
                idString(row.id), row.positionCode, idString(row.campaignId), row.campaignTitle,
                row.cityCode, row.titleOverride, row.subtitleOverride, row.imageUrlOverride,
                row.templateCode, row.sortWeight == null ? 0 : row.sortWeight,
                offset(row.startAt), offset(row.endAt),
                row.status, row.version == null ? 0 : row.version.intValue());
    }

    protected static MarketingViews.HomeBlockCard homeBlockCard(HomeBlockRow row) {
        return new MarketingViews.HomeBlockCard(
                idString(row.id), row.positionCode, row.templateCode, row.title, row.subtitle,
                row.tagName, row.imageUrl, row.targetType, row.targetValue, row.cityCode,
                idString(row.sponsorMerchantId), idString(row.sponsorStoreId),
                row.sortWeight == null ? 0 : row.sortWeight);
    }

    protected static MarketingViews.HomeBlockRow homeBlockRow(HomeBlockRow row) {
        return new MarketingViews.HomeBlockRow(
                idString(row.id), row.blockCode, row.positionCode, row.templateCode, row.title,
                row.subtitle, row.tagName, row.imageUrl, row.targetType, row.targetValue,
                row.cityCode, idString(row.sponsorMerchantId), idString(row.sponsorStoreId),
                row.sortWeight == null ? 0 : row.sortWeight, offset(row.startAt), offset(row.endAt),
                row.status, row.version == null ? 0 : row.version.intValue());
    }

    protected static MarketingViews.EnrollmentView enrollmentView(EnrollmentRow row) {
        return new MarketingViews.EnrollmentView(
                idString(row.id), idString(row.campaignId), row.campaignTitle,
                idString(row.merchantId), idString(row.storeId), row.storeName, row.status,
                row.offerTag, row.offerTitle, row.offerCopy, row.materials, row.rejectReason,
                offset(row.submittedAt), offset(row.decidedAt), idString(row.decidedBy),
                row.version == null ? 0 : row.version.intValue());
    }

    protected static MarketingViews.MerchantCampaignView merchantCampaign(
            CampaignRow row, String enrollmentStatus) {
        return new MarketingViews.MerchantCampaignView(
                campaignCard(row, 0), enrollmentStatus, enrollmentStatus != null
                        && !"WITHDRAWN".equals(enrollmentStatus) && !"REJECTED".equals(enrollmentStatus));
    }

    // -------------------------------------------------------------- errors

    protected static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    protected static void notFound(String message) {
        throw new ApiException(CommonApiCodes.NOT_FOUND, message);
    }

    protected static void conflict(String message) {
        throw new ApiException(CommonApiCodes.CONFLICT, message);
    }

    protected static void conflict(String code, String message) {
        throw new ApiException(code, message);
    }

    protected static void notFound(String code, String message) {
        throw new ApiException(code, message);
    }

    protected static void forbidden(String message) {
        throw new ApiException(CommonApiCodes.FORBIDDEN, message);
    }

    protected static ApiException unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
