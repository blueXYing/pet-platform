package com.petplatform.marketing.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.marketing.api.MarketingAdminApi;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.AssetRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignStoreRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.EnrollmentRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.HomeBlockRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.PageVersionRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.PlacementRow;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import com.petplatform.marketing.biz.infrastructure.persistence.mapper.MarketingMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;

/** Platform-ops marketing center (contract 71 §4). */
public final class MarketingAdminService extends MarketingSupport implements MarketingAdminApi {

    private static final Set<String> CAMPAIGN_TYPES = Set.of("DISPLAY", "COUPON", "MERCHANT_ENROLL");
    private static final Set<String> PARTICIPATION = Set.of("PLATFORM_SELECTED", "MERCHANT_ENROLL");
    private static final Set<String> CAMPAIGN_STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "SCHEDULED", "ONLINE", "PAUSED", "ENDED", "CANCELED", "REJECTED");
    private static final Set<String> POSITIONS = Set.of("HOME_BANNER", "HOME_ACTIVITY", "STORE_LIST_TOP");
    private static final Set<String> HOME_BLOCK_POSITIONS = Set.of(
            "HOME_SERVICE", "HOME_COMMUNITY", "HOME_EXOTIC_PLANT");
    private static final Set<String> HOME_BLOCK_TEMPLATES = Set.of(
            "HOT_SERVICE_FEATURE", "COMMUNITY_HOT", "EXOTIC_PLANT");
    private static final Set<String> HOME_BLOCK_TARGETS = Set.of(
            "SERVICE_DIRECTORY", "SERVICE_CATEGORY", "STORE", "SERVICE", "CAMPAIGN",
            "COMMUNITY", "MINIAPP_PATH", "NONE");

    private final MarketingCityCatalogPort cityCatalog;
    private final boolean allowHttpAssets;
    private final MarketingAssetObjectStore assetObjectStore;

    public MarketingAdminService(
            MarketingStore store,
            MarketingAdminAuthorizationPort authorization,
            MarketingStoreFactsPort storeFacts,
            MarketingCouponFactsPort couponFacts,
            MarketingCityCatalogPort cityCatalog,
            Clock clock,
            boolean allowHttpAssets,
            MarketingAssetObjectStore assetObjectStore) {
        super(store, Objects.requireNonNull(authorization), Objects.requireNonNull(storeFacts),
                Objects.requireNonNull(couponFacts), clock);
        this.cityCatalog = Objects.requireNonNull(cityCatalog);
        this.allowHttpAssets = allowHttpAssets;
        this.assetObjectStore = Objects.requireNonNull(assetObjectStore);
    }

    // ---------------------------------------------------------------- campaigns

    @Override
    public MarketingViews.Page<MarketingViews.CampaignCard> listCampaigns(CampaignListQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeRead(query.authorization(), "marketing.campaign.read",
                "MARKETING_CAMPAIGN_LIST", "0", null, null);
        if (query.page() < 1 || query.page() > 10_000 || query.pageSize() < 1 || query.pageSize() > 100) {
            invalid("page is invalid");
        }
        String keyword = optionalText(query.keyword(), 50, "keyword");
        MarketingAdminApi.AdminAuthorization scope = query.authorization();
        return store.read(mapper -> {
            boolean restricted = !"ALL".equals(scope.scopeMode());
            List<CampaignRow> rows = restricted
                    ? mapper.selectCampaigns(query.status(), query.ownerType(), keyword,
                            Integer.MAX_VALUE, 0).stream().filter(row -> scopeAllows(scope, row)).toList()
                    : mapper.selectCampaigns(query.status(), query.ownerType(), keyword,
                            query.pageSize(), (long) (query.page() - 1) * query.pageSize());
            long total = restricted ? rows.size()
                    : mapper.countCampaigns(query.status(), query.ownerType(), keyword);
            long from = restricted ? (long) (query.page() - 1) * query.pageSize() : 0;
            List<MarketingViews.CampaignCard> items = new ArrayList<>();
            for (long index = from; index < from + query.pageSize() && index < rows.size(); index++) {
                CampaignRow row = rows.get((int) index);
                items.add(campaignCard(row, (int) mapper.countCampaignStores(row.id)));
            }
            return new MarketingViews.Page<>(items, query.page(), query.pageSize(), total);
        });
    }

    @Override
    public MarketingViews.CampaignDetail getCampaign(CampaignQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeRead(query.authorization(), "marketing.campaign.read",
                "MARKETING_CAMPAIGN_LIST", "0", null, null);
        long campaignId = id(query.campaignId());
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCampaignById(campaignId);
            if (row == null) notFound("活动不存在");
            if (!scopeAllows(query.authorization(), row)) forbidden("无权访问该活动");
            return detail(mapper, campaignId);
        });
    }

    @Override
    public MarketingViews.PagePreview previewCampaign(CampaignQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeRead(query.authorization(), "marketing.campaign.read",
                "MARKETING_CAMPAIGN_LIST", "0", null, null);
        long campaignId = id(query.campaignId());
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCampaignById(campaignId);
            if (row == null) notFound("活动不存在");
            if (!scopeAllows(query.authorization(), row)) forbidden("无权访问该活动");
            List<MarketingViews.CampaignStoreLink> stores = mapper.selectCampaignStores(campaignId)
                    .stream().map(MarketingSupport::storeLink).toList();
            String schema = row.pageSchemaJson != null && !row.pageSchemaJson.isBlank()
                    ? row.pageSchemaJson : MarketingPageSchema.defaultSchema(row);
            return new MarketingViews.PagePreview(
                    Long.toUnsignedString(campaignId),
                    row.pageDraftVersion == null ? 0 : row.pageDraftVersion,
                    row.pageVersion == null ? 0 : row.pageVersion,
                    schema, stores);
        });
    }

    @Override
    public MarketingViews.Receipt createCampaign(CreateCampaignCommand command) {
        Objects.requireNonNull(command, "command is required");
        CampaignInput input = validateInput(command.input());
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.campaign.manage",
                "MARKETING_CAMPAIGN_LIST", "0",
                input.cityCodes().isEmpty() ? null : input.cityCodes().getFirst(), null);
        requireCampaignWriteScope(command.authorization(), input.cityCodes());
        long operatorId = operatorId(context);
        String key = requestKey("marketing.campaign.create", context, null);
        Map<String, Object> params = campaignParams(input);
        return idempotent(key, "marketing.campaign.create", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    long id = store.nextId();
                    CampaignRow row = campaignRow(id, input, operatorId);
                    row.pageSchemaJson = MarketingPageSchema.defaultSchema(row);
                    row.pageDraftVersion = 1;
                    if (mapper.insertCampaign(row) != 1) unavailable("campaign insert failed");
                    auditCampaign(mapper, id, "CREATE", null, row.status, context, "{}");
                    return new MarketingViews.Receipt(
                            Long.toUnsignedString(id), row.status, 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt updateCampaign(UpdateCampaignCommand command) {
        Objects.requireNonNull(command, "command is required");
        CampaignInput input = validateInput(command.input());
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.campaign.manage",
                "MARKETING_CAMPAIGN", command.campaignId(), input.cityCodes().isEmpty() ? null : input.cityCodes().getFirst(), null);
        requireCampaignWriteScope(command.authorization(), input.cityCodes());
        long campaignId = id(command.campaignId());
        String key = requestKey("marketing.campaign.update", context, command.campaignId());
        Map<String, Object> params = campaignParams(input);
        params.put("campaignId", campaignId);
        params.put("expectedVersion", command.expectedVersion());
        return idempotent(key, "marketing.campaign.update", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null) notFound("活动不存在");
                    requireEditable(current.status);
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    CampaignRow row = campaignRow(campaignId, input, operatorId(context));
                    row.campaignCode = current.campaignCode;
                    row.ownerType = current.ownerType;
                    row.ownerMerchantId = current.ownerMerchantId;
                    row.ownerStoreId = current.ownerStoreId;
                    row.status = current.status;
                    row.pageSchemaJson = current.pageSchemaJson;
                    row.pageDraftVersion = current.pageDraftVersion;
                    row.publishedPageSchemaJson = current.publishedPageSchemaJson;
                    row.pageVersion = current.pageVersion;
                    row.rejectReason = current.rejectReason;
                    row.version = command.expectedVersion();
                    row.createdByType = current.createdByType;
                    row.createdById = current.createdById;
                    row.createdAt = current.createdAt;
                    if (mapper.updateCampaign(row) != 1) conflict("活动版本已变化");
                    auditCampaign(mapper, campaignId, "UPDATE", current.status, current.status, context,
                            jsonOf(Map.of("title", row.title)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), current.status,
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt replaceStores(ReplaceStoresCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.campaign.manage",
                "MARKETING_CAMPAIGN", command.campaignId(), null, null);
        long campaignId = id(command.campaignId());
        List<CampaignStoreInput> input = command.stores() == null ? List.of() : command.stores();
        Set<Long> seen = new HashSet<>();
        List<CampaignStoreRow> rows = new ArrayList<>();
        for (CampaignStoreInput item : input) {
            if (item == null) invalid("store is required");
            long storeId = id(item.storeId());
            if (!seen.add(storeId)) invalid("duplicate storeId");
            MarketingStoreFactsPort.StoreFacts facts = requireStore(storeId);
            long merchantId = id(facts.merchantId());
            CampaignStoreRow row = new CampaignStoreRow();
            row.id = store.nextId();
            row.campaignId = campaignId;
            row.merchantId = merchantId;
            row.storeId = storeId;
            row.source = "PLATFORM_SELECTED";
            row.status = "ACTIVE";
            row.offerTag = optionalText(item.offerTag(), 32, "offerTag");
            row.offerTitle = optionalText(item.offerTitle(), 100, "offerTitle");
            row.offerCopy = optionalText(item.offerCopy(), 100, "offerCopy");
            row.sortWeight = boundedInt(item.sortWeight(), 0, 0, 9999, "sortWeight");
            row.version = 1L;
            rows.add(row);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("campaignId", campaignId);
        params.put("expectedVersion", command.expectedVersion());
        params.put("stores", input.stream().map(CampaignStoreInput::storeId).toList());
        String key = requestKey("marketing.campaign.stores", context, command.campaignId());
        return idempotent(key, "marketing.campaign.stores", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null) notFound("活动不存在");
                    requireEditable(current.status);
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    mapper.deleteCampaignStores(campaignId);
                    LocalDateTime now = nowUtc();
                    for (CampaignStoreRow row : rows) {
                        row.createdAt = now;
                        row.updatedAt = now;
                        if (mapper.insertCampaignStore(row) != 1) unavailable("campaign store insert failed");
                    }
                    // The store set is part of the campaign aggregate: advance the campaign
                    // version so a concurrent editor cannot silently overwrite this set.
                    if (mapper.touchCampaignVersion(campaignId, command.expectedVersion(), now) != 1) {
                        conflict("活动版本已变化");
                    }
                    auditCampaign(mapper, campaignId, "REPLACE_STORES", current.status, current.status,
                            context, jsonOf(Map.of("storeCount", rows.size())));
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), current.status,
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt savePage(SavePageCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.campaign.manage",
                "MARKETING_CAMPAIGN", command.campaignId(), null, null);
        long campaignId = id(command.campaignId());
        String schema = MarketingPageSchema.validate(command.schemaJson(), allowHttpAssets);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("campaignId", campaignId);
        params.put("expectedVersion", command.expectedVersion());
        params.put("schema", schema);
        String key = requestKey("marketing.campaign.page.save", context, command.campaignId());
        return idempotent(key, "marketing.campaign.page.save", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null) notFound("活动不存在");
                    requireEditable(current.status);
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    int draft = (current.pageDraftVersion == null ? 0 : current.pageDraftVersion) + 1;
                    if (mapper.updateCampaignPageDraft(campaignId, schema, draft,
                            command.expectedVersion(), nowUtc()) != 1) {
                        conflict("活动版本已变化");
                    }
                    auditCampaign(mapper, campaignId, "PAGE_DRAFT", current.status, current.status,
                            context, jsonOf(Map.of("draftVersion", draft)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), current.status,
                            command.expectedVersion() + 1, draft, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), receipt.pageVersion()));
    }

    @Override
    public MarketingViews.Receipt publishCampaign(CampaignStatusCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.campaign.publish",
                "MARKETING_CAMPAIGN", command.campaignId(), null, null);
        long campaignId = id(command.campaignId());
        String key = requestKey("marketing.campaign.publish", context, command.campaignId());
        Map<String, Object> params = Map.of("campaignId", campaignId, "expectedVersion", command.expectedVersion());
        return idempotent(key, "marketing.campaign.publish", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null) notFound("活动不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    if (!Set.of("DRAFT", "PENDING_REVIEW", "SCHEDULED", "PAUSED").contains(current.status)) {
                        conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能发布");
                    }
                    if (current.ownerType == null || (!"PLATFORM".equals(current.ownerType)
                            && !"MERCHANT".equals(current.ownerType))) {
                        conflict("CAMPAIGN_STATE_NOT_ALLOWED", "活动归属非法");
                    }
                    if ("MERCHANT".equals(current.ownerType) && !"PENDING_REVIEW".equals(current.status)) {
                        conflict("CAMPAIGN_STATE_NOT_ALLOWED", "商家活动只有待审核状态可发布");
                    }
                    String schema = current.pageSchemaJson;
                    if (schema == null || schema.isBlank()) {
                        schema = MarketingPageSchema.defaultSchema(current);
                        MarketingPageSchema.validate(schema, allowHttpAssets);
                        int draft = (current.pageDraftVersion == null ? 0 : current.pageDraftVersion) + 1;
                        if (mapper.updateCampaignPageDraft(campaignId, schema, draft,
                                current.version, nowUtc()) != 1) conflict("活动版本已变化");
                        current.pageSchemaJson = schema;
                        current.pageDraftVersion = draft;
                        current.version = current.version + 1;
                    } else {
                        MarketingPageSchema.validate(schema, allowHttpAssets);
                    }
                    long storeCount = mapper.countCampaignStores(campaignId);
                    // A merchant-recruitment campaign may publish with zero stores so merchants
                    // can enroll during the recruitment window; every other campaign needs at
                    // least one visible participating store.
                    if (!"MERCHANT_ENROLL".equals(current.participationMode) && storeCount == 0) {
                        conflict("CAMPAIGN_LANDING_INVALID", "活动至少需要一家可见参与门店");
                    }
                    if (current.couponCampaignId != null) {
                        MarketingCouponFactsPort.CouponFacts facts =
                                couponFacts.facts(current.couponCampaignId);
                        if (facts == null) unavailable("券活动事实不可用");
                        if (!facts.exists() || !facts.usable()) conflict("CAMPAIGN_LANDING_INVALID", "关联券活动不可用");
                    }
                    LocalDateTime now = nowUtc();
                    String toStatus = current.startAt != null && current.startAt.isAfter(now)
                            ? "SCHEDULED" : "ONLINE";
                    // A publish always snapshots a fresh version. Taking max(published, snapshot)
                    // avoids colliding with a historical version after a rollback (the draft
                    // counter can move backwards while the snapshot history does not).
                    int pageVersion = Math.max(
                            current.pageVersion == null ? 0 : current.pageVersion,
                            mapper.selectMaxPageVersion(campaignId)) + 1;
                    long expected = current.version;
                    if (mapper.publishCampaignPage(campaignId, current.status, toStatus,
                            pageVersion, expected, now) != 1) {
                        conflict("活动版本已变化");
                    }
                    PageVersionRow versionRow = new PageVersionRow();
                    versionRow.id = store.nextId();
                    versionRow.campaignId = campaignId;
                    versionRow.version = pageVersion;
                    versionRow.schemaJson = current.pageSchemaJson;
                    versionRow.publishedByType = "ADMIN";
                    versionRow.publishedById = operatorId(context);
                    versionRow.publishedAt = now;
                    if (mapper.insertPageVersion(versionRow) != 1) unavailable("page version insert failed");
                    auditCampaign(mapper, campaignId, "PUBLISH", current.status, toStatus, context,
                            jsonOf(Map.of("pageVersion", pageVersion)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), toStatus,
                            expected + 1, pageVersion, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), receipt.pageVersion()));
    }

    @Override
    public MarketingViews.Receipt pauseCampaign(CampaignStatusCommand command) {
        return statusCommand(command, "PAUSED", Set.of("ONLINE"), "PAUSE");
    }

    @Override
    public MarketingViews.Receipt endCampaign(CampaignStatusCommand command) {
        return statusCommand(command, "ENDED", Set.of("ONLINE", "PAUSED", "SCHEDULED"), "END");
    }

    @Override
    public MarketingViews.Receipt cancelCampaign(CampaignStatusCommand command) {
        return statusCommand(command, "CANCELED", Set.of("DRAFT", "PENDING_REVIEW", "SCHEDULED", "PAUSED"), "CANCEL");
    }

    @Override
    public MarketingViews.Receipt rejectCampaign(RejectCampaignCommand command) {
        Objects.requireNonNull(command, "command is required");
        String reason = requireText(command.reason(), 1, 500, "reason");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.campaign.publish",
                "MARKETING_CAMPAIGN", command.campaignId(), null, null);
        long campaignId = id(command.campaignId());
        String key = requestKey("marketing.campaign.reject", context, command.campaignId());
        Map<String, Object> params = Map.of("campaignId", campaignId,
                "expectedVersion", command.expectedVersion(), "reason", reason);
        return idempotent(key, "marketing.campaign.reject", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null) notFound("活动不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    if (!"PENDING_REVIEW".equals(current.status)) conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能驳回");
                    if (mapper.updateCampaignStatus(campaignId, "PENDING_REVIEW", "REJECTED",
                            command.expectedVersion(), reason, nowUtc()) != 1) {
                        conflict("活动版本已变化");
                    }
                    auditCampaign(mapper, campaignId, "REJECT", current.status, "REJECTED", context,
                            jsonOf(Map.of("reason", reason)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), "REJECTED",
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt rollbackPage(RollbackPageCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.campaign.publish",
                "MARKETING_CAMPAIGN", command.campaignId(), null, null);
        long campaignId = id(command.campaignId());
        String key = requestKey("marketing.campaign.page.rollback", context, command.campaignId());
        Map<String, Object> params = Map.of("campaignId", campaignId,
                "pageVersion", command.pageVersion(), "expectedVersion", command.expectedVersion());
        return idempotent(key, "marketing.campaign.page.rollback", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null) notFound("活动不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    PageVersionRow version = mapper.selectPageVersion(campaignId, command.pageVersion());
                    if (version == null) notFound("页面版本不存在");
                    if (mapper.rollbackCampaignPage(campaignId, version.schemaJson, command.pageVersion(),
                            command.expectedVersion(), nowUtc()) != 1) {
                        conflict("活动版本已变化");
                    }
                    auditCampaign(mapper, campaignId, "PAGE_ROLLBACK", current.status, current.status,
                            context, jsonOf(Map.of("pageVersion", command.pageVersion())));
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), current.status,
                            command.expectedVersion() + 1, command.pageVersion(), nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), receipt.pageVersion()));
    }

    private MarketingViews.Receipt statusCommand(
            CampaignStatusCommand command, String toStatus, Set<String> fromStatuses, String action) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.campaign.publish",
                "MARKETING_CAMPAIGN", command.campaignId(), null, null);
        long campaignId = id(command.campaignId());
        String key = requestKey("marketing.campaign." + action.toLowerCase(), context, command.campaignId());
        Map<String, Object> params = Map.of("campaignId", campaignId,
                "expectedVersion", command.expectedVersion(), "toStatus", toStatus);
        return idempotent(key, "marketing.campaign." + action.toLowerCase(), params,
                MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null) notFound("活动不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    if (!fromStatuses.contains(current.status)) conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能变更");
                    if (mapper.updateCampaignStatus(campaignId, current.status, toStatus,
                            command.expectedVersion(), null, nowUtc()) != 1) {
                        conflict("活动版本已变化");
                    }
                    auditCampaign(mapper, campaignId, action, current.status, toStatus, context, "{}");
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), toStatus,
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    // ---------------------------------------------------------------- placements

    @Override
    public MarketingViews.AssetView uploadAsset(AssetUploadCommand command) {
        Objects.requireNonNull(command, "command is required");
        authorize(command.authorization(), command.context(), "marketing.campaign.manage",
                "MARKETING_CAMPAIGN_LIST", "1", null, null);
        byte[] content = command.content();
        if (content == null || content.length == 0) {
            throw new ApiException("MARKETING_ASSET_INVALID", "素材文件为空");
        }
        if (content.length > 2 * 1024 * 1024) {
            throw new ApiException("MARKETING_ASSET_TOO_LARGE", "素材不能超过 2MiB");
        }
        String type = command.contentType() == null ? "" : command.contentType().strip().toLowerCase();
        if (!"image/png".equals(type) && !"image/jpeg".equals(type)) {
            throw new ApiException("MARKETING_ASSET_INVALID", "仅支持 PNG/JPEG 图片");
        }
        java.awt.image.BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException failure) {
            throw new ApiException("MARKETING_ASSET_INVALID", "图片内容不可读取");
        }
        if (image == null) {
            throw new ApiException("MARKETING_ASSET_INVALID", "图片内容不可读取");
        }
        if (image.getWidth() < 1 || image.getHeight() < 1
                || image.getWidth() > 2000 || image.getHeight() > 2000) {
            throw new ApiException("MARKETING_ASSET_INVALID", "图片尺寸必须在 1..2000 之间");
        }
        String sha = sha256(content);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sha256", sha);
        params.put("contentType", type);
        params.put("width", image.getWidth());
        params.put("height", image.getHeight());
        params.put("bytes", content.length);
        String key = requestKey("marketing.asset.upload", command.context(), sha);
        return idempotent(key, "marketing.asset.upload", params,
                MarketingAdminService::parseAsset,
                () -> store.execute(mapper -> {
                    AssetRow row = new AssetRow();
                    row.id = store.nextId();
                    row.contentType = type;
                    row.bytes = (long) content.length;
                    row.width = image.getWidth();
                    row.height = image.getHeight();
                    row.sha256 = sha;
                    if (assetObjectStore.enabled()) {
                        String extension = "image/jpeg".equals(type) ? "jpg" : "png";
                        row.objectKey = "marketing-assets/" + Long.toUnsignedString(row.id) + "." + extension;
                        try {
                            assetObjectStore.put(row.objectKey, content, type);
                        } catch (RuntimeException failure) {
                            unavailable("素材对象存储不可用");
                        }
                        row.content = null;
                    } else {
                        row.content = content;
                    }
                    row.status = "ACTIVE";
                    row.createdById = operatorId(command.context());
                    row.createdAt = nowUtc();
                    if (mapper.insertAsset(row) != 1) unavailable("素材写入失败");
                    if (mapper.insertAssetAudit(store.nextId(), row.id, "UPLOAD",
                            row.createdById, command.context().requestId(),
                            command.context().traceId(), nowUtc()) != 1) {
                        unavailable("素材审计写入失败");
                    }
                    return new MarketingViews.AssetView(Long.toUnsignedString(row.id),
                            row.contentType, row.bytes, row.width, row.height, row.sha256,
                            offset(row.createdAt));
                }), MarketingAdminService::assetJson);
    }

    private static MarketingViews.AssetView parseAsset(String json) {
        try {
            var root = JSON.readTree(json);
            return new MarketingViews.AssetView(root.path("assetId").asText(),
                    root.path("contentType").asText(), root.path("bytes").asLong(),
                    root.path("width").asInt(), root.path("height").asInt(),
                    root.path("sha256").asText(),
                    OffsetDateTime.parse(root.path("createdAt").asText()));
        } catch (Exception failure) {
            unavailable("素材回执不可读取");
            return null;
        }
    }

    private static String assetJson(MarketingViews.AssetView asset) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", asset.assetId());
        out.put("contentType", asset.contentType());
        out.put("bytes", asset.bytes());
        out.put("width", asset.width());
        out.put("height", asset.height());
        out.put("sha256", asset.sha256());
        out.put("createdAt", asset.createdAt().toString());
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception failure) {
            throw new IllegalStateException("asset receipt serialization failed", failure);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    @Override
    public MarketingViews.Page<MarketingViews.PlacementRow> listPlacements(PlacementListQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeRead(query.authorization(), "marketing.placement.read",
                "MARKETING_PLACEMENT_LIST", "0", null, null);
        if (query.page() < 1 || query.page() > 10_000 || query.pageSize() < 1 || query.pageSize() > 100) {
            invalid("page is invalid");
        }
        MarketingAdminApi.AdminAuthorization scope = query.authorization();
        return store.read(mapper -> {
            boolean restricted = !"ALL".equals(scope.scopeMode());
            List<PlacementRow> rows = restricted
                    ? mapper.selectPlacements(query.positionCode(), query.status(),
                            Integer.MAX_VALUE, 0).stream().filter(row -> scopeAllows(scope, row)).toList()
                    : mapper.selectPlacements(query.positionCode(), query.status(),
                            query.pageSize(), (long) (query.page() - 1) * query.pageSize());
            long total = restricted ? rows.size()
                    : mapper.countPlacements(query.positionCode(), query.status());
            long from = restricted ? (long) (query.page() - 1) * query.pageSize() : 0;
            List<MarketingViews.PlacementRow> items = new ArrayList<>();
            for (long index = from; index < from + query.pageSize() && index < rows.size(); index++) {
                items.add(placementRow(rows.get((int) index)));
            }
            return new MarketingViews.Page<>(items, query.page(), query.pageSize(), total);
        });
    }

    @Override
    public MarketingViews.Receipt createPlacement(CreatePlacementCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        PlacementInput input = validatePlacement(command.input());
        authorize(command.authorization(), context, "marketing.placement.manage",
                "MARKETING_PLACEMENT_LIST", "0", input.cityCode(), null);
        long operatorId = operatorId(context);
        String key = requestKey("marketing.placement.create", context, null);
        Map<String, Object> params = placementParams(input);
        return idempotent(key, "marketing.placement.create", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    validatePlacementCampaign(mapper, input);
                    long id = store.nextId();
                    PlacementRow row = placementRow(id, input, operatorId);
                    if (mapper.insertPlacement(row) != 1) unavailable("placement insert failed");
                    auditCampaign(mapper, id(input.campaignId()), "PLACEMENT_CREATE", null, row.status, context,
                            jsonOf(Map.of("positionCode", row.positionCode, "placementId", id)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(id), row.status, 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt updatePlacement(UpdatePlacementCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        PlacementInput input = validatePlacement(command.input());
        authorize(command.authorization(), context, "marketing.placement.manage",
                "MARKETING_PLACEMENT", command.placementId(), null, null);
        long placementId = id(command.placementId());
        String key = requestKey("marketing.placement.update", context, command.placementId());
        Map<String, Object> params = placementParams(input);
        params.put("placementId", placementId);
        params.put("expectedVersion", command.expectedVersion());
        return idempotent(key, "marketing.placement.update", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    validatePlacementCampaign(mapper, input);
                    PlacementRow current = mapper.selectPlacementForUpdate(placementId);
                    if (current == null) notFound("投放位不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("投放位版本已变化");
                    }
                    if ("ENABLED".equals(current.status)) conflict("CAMPAIGN_STATE_NOT_ALLOWED", "启用中的投放位不能编辑");
                    PlacementRow row = placementRow(placementId, input, operatorId(context));
                    row.status = current.status;
                    row.version = command.expectedVersion();
                    row.createdById = current.createdById;
                    row.createdAt = current.createdAt;
                    if (mapper.updatePlacement(row) != 1) conflict("投放位版本已变化");
                    auditCampaign(mapper, current.campaignId, "PLACEMENT_UPDATE", current.status, current.status,
                            context, jsonOf(Map.of("placementId", placementId)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(placementId), current.status,
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt enablePlacement(PlacementStatusCommand command) {
        return placementStatus(command, true);
    }

    @Override
    public MarketingViews.Receipt disablePlacement(PlacementStatusCommand command) {
        return placementStatus(command, false);
    }

    private MarketingViews.Receipt placementStatus(PlacementStatusCommand command, boolean enable) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.placement.manage",
                "MARKETING_PLACEMENT", command.placementId(), null, null);
        long placementId = id(command.placementId());
        String action = enable ? "enable" : "disable";
        String key = requestKey("marketing.placement." + action, context, command.placementId());
        Map<String, Object> params = Map.of("placementId", placementId,
                "expectedVersion", command.expectedVersion(), "action", action);
        return idempotent(key, "marketing.placement." + action, params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    PlacementRow current = mapper.selectPlacementForUpdate(placementId);
                    if (current == null) notFound("投放位不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("投放位版本已变化");
                    }
                    String from = current.status;
                    String to;
                    if (enable) {
                        if (!Set.of("DRAFT", "DISABLED").contains(from)) conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能启用");
                        CampaignRow campaign = mapper.selectCampaignById(current.campaignId);
                        if (campaign == null
                                || !Set.of("SCHEDULED", "ONLINE").contains(campaign.status)) {
                            conflict("CAMPAIGN_STATE_NOT_ALLOWED", "活动尚未发布");
                        }
                        if ("HOME_BANNER".equals(current.positionCode)
                                && mapper.countEnabledPlacements("HOME_BANNER", current.cityCode, nowUtc()) >= 3) {
                            conflict("PLACEMENT_LIMIT_EXCEEDED", "首页 Banner 最多同时启用 3 个");
                        }
                        to = "ENABLED";
                    } else {
                        if (!"ENABLED".equals(from)) conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能停用");
                        to = "DISABLED";
                    }
                    if (mapper.updatePlacementStatus(placementId, from, to,
                            command.expectedVersion(), nowUtc()) != 1) conflict("投放位版本已变化");
                    auditCampaign(mapper, current.campaignId,
                            enable ? "PLACEMENT_ENABLE" : "PLACEMENT_DISABLE",
                            from, to, context, jsonOf(Map.of("placementId", placementId)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(placementId), to,
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    // ---------------------------------------------------------------- home blocks

    @Override
    public MarketingViews.Page<MarketingViews.HomeBlockRow> listHomeBlocks(HomeBlockListQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeRead(query.authorization(), "marketing.placement.read",
                "MARKETING_HOME_BLOCK_LIST", "0", null, null);
        if (query.page() < 1 || query.page() > 10_000 || query.pageSize() < 1 || query.pageSize() > 100) {
            invalid("page is invalid");
        }
        MarketingAdminApi.AdminAuthorization scope = query.authorization();
        return store.read(mapper -> {
            boolean restricted = !"ALL".equals(scope.scopeMode());
            List<HomeBlockRow> rows = restricted
                    ? mapper.selectHomeBlocks(query.positionCode(), query.status(),
                            Integer.MAX_VALUE, 0).stream()
                            .filter(row -> scopeAllowsHomeBlock(scope, row)).toList()
                    : mapper.selectHomeBlocks(query.positionCode(), query.status(),
                            query.pageSize(), (long) (query.page() - 1) * query.pageSize());
            long total = restricted ? rows.size()
                    : mapper.countHomeBlocks(query.positionCode(), query.status());
            long from = restricted ? (long) (query.page() - 1) * query.pageSize() : 0;
            List<MarketingViews.HomeBlockRow> items = new ArrayList<>();
            for (long index = from; index < from + query.pageSize() && index < rows.size(); index++) {
                items.add(homeBlockRow(rows.get((int) index)));
            }
            return new MarketingViews.Page<>(items, query.page(), query.pageSize(), total);
        });
    }

    @Override
    public MarketingViews.Receipt createHomeBlock(CreateHomeBlockCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        HomeBlockInput input = validateHomeBlockInput(command.input());
        authorize(command.authorization(), context, "marketing.placement.manage",
                "MARKETING_HOME_BLOCK_LIST", "0", input.cityCode(), null);
        long operatorId = operatorId(context);
        String key = requestKey("marketing.home-block.create", context, null);
        Map<String, Object> params = homeBlockParams(input);
        return idempotent(key, "marketing.home-block.create", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    HomeBlockRow row = homeBlockRow(store.nextId(), input, operatorId);
                    if (mapper.insertHomeBlock(row) != 1) unavailable("home block insert failed");
                    auditHomeBlock(mapper, row.id, "CREATE", null, row.status, context,
                            jsonOf(Map.of("positionCode", row.positionCode,
                                    "templateCode", row.templateCode)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(row.id), row.status, 1, 0,
                            nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt updateHomeBlock(UpdateHomeBlockCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        HomeBlockInput input = validateHomeBlockInput(command.input());
        authorize(command.authorization(), context, "marketing.placement.manage",
                "MARKETING_HOME_BLOCK", command.blockId(), null, null);
        long blockId = id(command.blockId());
        String key = requestKey("marketing.home-block.update", context, command.blockId());
        Map<String, Object> params = homeBlockParams(input);
        params.put("blockId", blockId);
        params.put("expectedVersion", command.expectedVersion());
        return idempotent(key, "marketing.home-block.update", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    HomeBlockRow current = mapper.selectHomeBlockForUpdate(blockId);
                    if (current == null) notFound("首页内容位不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("首页内容位版本已变化");
                    }
                    HomeBlockRow row = homeBlockRow(blockId, input, operatorId(context));
                    row.status = current.status;
                    row.version = command.expectedVersion();
                    row.createdById = current.createdById;
                    row.createdAt = current.createdAt;
                    if (mapper.updateHomeBlock(row) != 1) conflict("首页内容位版本已变化");
                    auditHomeBlock(mapper, blockId, "UPDATE", current.status, current.status,
                            context, jsonOf(Map.of("blockCode", row.blockCode)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(blockId), current.status,
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt enableHomeBlock(HomeBlockStatusCommand command) {
        return homeBlockStatus(command, true);
    }

    @Override
    public MarketingViews.Receipt disableHomeBlock(HomeBlockStatusCommand command) {
        return homeBlockStatus(command, false);
    }

    private MarketingViews.Receipt homeBlockStatus(HomeBlockStatusCommand command, boolean enable) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.placement.manage",
                "MARKETING_HOME_BLOCK", command.blockId(), null, null);
        long blockId = id(command.blockId());
        String action = enable ? "enable" : "disable";
        String key = requestKey("marketing.home-block." + action, context, command.blockId());
        Map<String, Object> params = Map.of("blockId", blockId,
                "expectedVersion", command.expectedVersion(), "action", action);
        return idempotent(key, "marketing.home-block." + action, params,
                MarketingSupport::parseReceipt, () -> store.execute(mapper -> {
                    HomeBlockRow current = mapper.selectHomeBlockForUpdate(blockId);
                    if (current == null) notFound("首页内容位不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("首页内容位版本已变化");
                    }
                    String from = current.status;
                    String to;
                    if (enable) {
                        if (!Set.of("DRAFT", "DISABLED").contains(from)) {
                            conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能启用");
                        }
                        to = "ENABLED";
                    } else {
                        if (!"ENABLED".equals(from)) {
                            conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能停用");
                        }
                        to = "DISABLED";
                    }
                    if (mapper.updateHomeBlockStatus(blockId, from, to,
                            command.expectedVersion(), nowUtc()) != 1) {
                        conflict("首页内容位版本已变化");
                    }
                    auditHomeBlock(mapper, blockId,
                            enable ? "ENABLE" : "DISABLE", from, to, context,
                            jsonOf(Map.of("blockCode", current.blockCode)));
                    return new MarketingViews.Receipt(Long.toUnsignedString(blockId), to,
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    // ---------------------------------------------------------------- enrollments

    @Override
    public MarketingViews.Page<MarketingViews.EnrollmentView> listEnrollments(EnrollmentListQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeRead(query.authorization(), "marketing.enrollment.read",
                "MARKETING_ENROLLMENT_LIST", "0", null, null);
        if (query.page() < 1 || query.page() > 10_000 || query.pageSize() < 1 || query.pageSize() > 100) {
            invalid("page is invalid");
        }
        MarketingAdminApi.AdminAuthorization scope = query.authorization();
        return store.read(mapper -> {
            boolean restricted = !"ALL".equals(scope.scopeMode());
            List<EnrollmentRow> rows = restricted
                    ? mapper.selectEnrollments(query.campaignId(), query.status(),
                            Integer.MAX_VALUE, 0)
                    : mapper.selectEnrollments(query.campaignId(), query.status(),
                            query.pageSize(), (long) (query.page() - 1) * query.pageSize());
            List<EnrollmentRow> visible = new ArrayList<>(rows.size());
            Map<Long, MarketingStoreFactsPort.StoreFacts> factsByStore = new LinkedHashMap<>();
            for (EnrollmentRow row : rows) {
                MarketingStoreFactsPort.StoreFacts facts = factsByStore.computeIfAbsent(
                        row.storeId, this::safeStore);
                if (facts != null) row.storeName = facts.storeName();
                if (restricted && !scopeAllowsEnrollment(scope, row,
                        facts == null ? null : facts.cityCode())) {
                    continue;
                }
                visible.add(row);
            }
            long total = restricted ? visible.size()
                    : mapper.countEnrollments(query.campaignId(), query.status());
            long from = restricted ? (long) (query.page() - 1) * query.pageSize() : 0;
            List<MarketingViews.EnrollmentView> items = new ArrayList<>();
            for (long index = from; index < from + query.pageSize() && index < visible.size(); index++) {
                items.add(enrollmentView(visible.get((int) index)));
            }
            return new MarketingViews.Page<>(items, query.page(), query.pageSize(), total);
        });
    }

    @Override
    public MarketingViews.Receipt reviewEnrollment(ReviewEnrollmentCommand command) {
        Objects.requireNonNull(command, "command is required");
        String decision = command.decision() == null ? "" : command.decision().toUpperCase();
        if (!Set.of("APPROVE", "REJECT").contains(decision)) invalid("decision is invalid");
        String reason = "REJECT".equals(decision)
                ? requireText(command.reason(), 1, 500, "reason") : optionalText(command.reason(), 500, "reason");
        CommandContext context = command.context();
        authorize(command.authorization(), context, "marketing.enrollment.review",
                "MARKETING_ENROLLMENT", command.enrollmentId(), null, null);
        long enrollmentId = id(command.enrollmentId());
        String key = requestKey("marketing.enrollment.review", context, command.enrollmentId());
        Map<String, Object> params = Map.of("enrollmentId", enrollmentId, "decision", decision,
                "expectedVersion", command.expectedVersion(), "reason", reason == null ? "" : reason);
        return idempotent(key, "marketing.enrollment.review", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    EnrollmentRow current = mapper.selectEnrollmentForUpdate(enrollmentId);
                    if (current == null) notFound("报名不存在");
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("报名版本已变化");
                    }
                    if (!Set.of("SUBMITTED", "UNDER_REVIEW").contains(current.status)) {
                        conflict("ENROLLMENT_STATE_NOT_ALLOWED", "当前报名状态不能审核");
                    }
                    long operatorId = operatorId(context);
                    if ("REJECT".equals(decision)) {
                        if (mapper.updateEnrollmentStatus(enrollmentId, current.status, "REJECTED",
                                command.expectedVersion(), reason, operatorId, nowUtc()) != 1) {
                            conflict("报名版本已变化");
                        }
                        auditEnrollment(mapper, enrollmentId, "REJECT", current.status, "REJECTED",
                                context, reason);
                        return new MarketingViews.Receipt(Long.toUnsignedString(enrollmentId), "REJECTED",
                                command.expectedVersion() + 1, 0, nowOffset());
                    }
                    CampaignRow campaign = mapper.selectCampaignForUpdate(current.campaignId);
                    if (campaign == null) notFound("活动不存在");
                    if (!"MERCHANT_ENROLL".equals(campaign.participationMode)) {
                        conflict("CAMPAIGN_STATE_NOT_ALLOWED", "活动不是招商活动");
                    }
                    if (!Set.of("SCHEDULED", "ONLINE").contains(campaign.status)) {
                        conflict("CAMPAIGN_STATE_NOT_ALLOWED", "活动当前不可审核报名");
                    }
                    if (campaign.enrollmentQuota != null
                            && mapper.countApprovedEnrollments(current.campaignId) >= campaign.enrollmentQuota) {
                        conflict("ENROLLMENT_QUOTA_EXCEEDED", "活动报名名额已满");
                    }
                    MarketingStoreFactsPort.StoreFacts facts = requireStore(current.storeId);
                    if (facts.merchantId() == null
                            || !facts.merchantId().equals(Long.toUnsignedString(current.merchantId))) {
                        conflict("MARKETING_STORE_NOT_ELIGIBLE", "报名门店归属不一致");
                    }
                    CampaignStoreRow link = new CampaignStoreRow();
                    link.id = store.nextId();
                    link.campaignId = current.campaignId;
                    link.merchantId = current.merchantId;
                    link.storeId = current.storeId;
                    link.source = "MERCHANT_ENROLLED";
                    link.status = "ACTIVE";
                    link.offerTag = current.offerTag;
                    link.offerTitle = current.offerTitle;
                    link.offerCopy = current.offerCopy;
                    link.sortWeight = 0;
                    link.version = 1L;
                    link.createdAt = nowUtc();
                    link.updatedAt = link.createdAt;
                    if (mapper.upsertCampaignStore(link) < 1) unavailable("campaign store upsert failed");
                    if (mapper.updateEnrollmentStatus(enrollmentId, current.status, "APPROVED",
                            command.expectedVersion(), null, operatorId, nowUtc()) != 1) {
                        conflict("报名版本已变化");
                    }
                    auditEnrollment(mapper, enrollmentId, "APPROVE", current.status, "APPROVED",
                            context, null);
                    return new MarketingViews.Receipt(Long.toUnsignedString(enrollmentId), "APPROVED",
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    // ---------------------------------------------------------------- helpers

    private MarketingViews.CampaignDetail detail(MarketingMapper mapper, long campaignId) {
        CampaignRow row = mapper.selectCampaignById(campaignId);
        if (row == null) notFound("活动不存在");
        List<MarketingViews.CampaignStoreLink> stores = mapper.selectCampaignStores(campaignId)
                .stream().map(MarketingSupport::storeLink).toList();
        List<MarketingViews.PageVersion> versions = mapper.selectPageVersions(campaignId).stream()
                .map(v -> new MarketingViews.PageVersion(v.version, offset(v.publishedAt))).toList();
        return new MarketingViews.CampaignDetail(
                campaignCard(row, stores.size()), stores, row.pageSchemaJson,
                row.publishedPageSchemaJson, versions);
    }

    private CampaignInput validateInput(CampaignInput input) {
        if (input == null) invalid("campaign input is required");
        String name = requireText(input.name(), 2, 100, "name");
        String type = requireText(input.campaignType(), 1, 24, "campaignType");
        if (!CAMPAIGN_TYPES.contains(type)) invalid("campaignType is invalid");
        String participation = requireText(input.participationMode(), 1, 24, "participationMode");
        if (!PARTICIPATION.contains(participation)) invalid("participationMode is invalid");
        String title = requireText(input.title(), 1, 100, "title");
        String subtitle = optionalText(input.subtitle(), 200, "subtitle");
        String tagCode = optionalTag(input.tagCode());
        String tagName = optionalText(input.tagName(), 32, "tagName");
        String coverUrl = optionalUrl(input.coverUrl(), "coverUrl", allowHttpAssets);
        List<String> cities = stringList(input.cityCodes());
        for (String city : cities) {
            if (!city.matches("[a-z][a-z0-9_-]{0,31}")) invalid("cityCode is invalid");
            if (!cityCatalog.isOpen(city)) invalid("cityCode is not open");
        }
        List<String> categories = stringList(input.serviceCategoryCodes());
        for (String category : categories) {
            if (!category.matches("[A-Za-z0-9_-]{1,32}")) invalid("serviceCategoryCode is invalid");
        }
        requireWindow(input.startAt(), input.endAt());
        OffsetDateTime enrollStart = input.enrollStartAt();
        OffsetDateTime enrollEnd = input.enrollEndAt();
        if ("MERCHANT_ENROLL".equals(participation)) {
            requireWindow(enrollStart, enrollEnd);
            // Recruitment may open before the activity starts (the usual campaign pattern);
            // it must close no later than the activity itself.
            if (enrollEnd.isAfter(input.endAt())) {
                invalid("enrollment window must end by the campaign end");
            }
        } else {
            enrollStart = null;
            enrollEnd = null;
        }
        Integer quota = input.enrollmentQuota();
        if (quota != null && (quota < 1 || quota > 10_000)) invalid("enrollmentQuota is invalid");
        if (!"MERCHANT_ENROLL".equals(participation) && quota != null) {
            invalid("enrollmentQuota is only for merchant enrollment campaigns");
        }
        String couponCampaignId = input.couponCampaignId();
        if (couponCampaignId != null && !couponCampaignId.isBlank()) {
            id(couponCampaignId);
        } else {
            couponCampaignId = null;
        }
        if ("COUPON".equals(type) && couponCampaignId == null) {
            invalid("couponCampaignId is required for COUPON campaigns");
        }
        if (!"COUPON".equals(type) && couponCampaignId != null) {
            invalid("couponCampaignId is only for COUPON campaigns");
        }
        int sort = boundedInt(input.sortWeight(), 0, 0, 9999, "sortWeight");
        return new CampaignInput(name, type, participation, title, subtitle, tagCode, tagName,
                coverUrl, cities, categories, input.startAt(), input.endAt(), enrollStart, enrollEnd,
                quota, couponCampaignId, sort);
    }

    private static String optionalTag(String value) {
        if (value == null || value.isBlank()) return null;
        String tag = value.strip();
        if (!tag.matches("[A-Za-z0-9_-]{1,32}")) invalid("tagCode is invalid");
        return tag;
    }

    private CampaignRow campaignRow(long id, CampaignInput input, long operatorId) {
        LocalDateTime now = nowUtc();
        CampaignRow row = new CampaignRow();
        row.id = id;
        row.campaignCode = "MKT" + Long.toUnsignedString(id);
        row.name = input.name();
        row.ownerType = "PLATFORM";
        row.ownerMerchantId = null;
        row.ownerStoreId = null;
        row.campaignType = input.campaignType();
        row.participationMode = input.participationMode();
        row.status = "DRAFT";
        row.title = input.title();
        row.subtitle = input.subtitle();
        row.tagCode = input.tagCode();
        row.tagName = input.tagName();
        row.coverUrl = input.coverUrl();
        row.cityCodes = jsonList(input.cityCodes());
        row.serviceCategoryCodes = jsonList(input.serviceCategoryCodes());
        row.startAt = utc(input.startAt());
        row.endAt = utc(input.endAt());
        row.enrollStartAt = utc(input.enrollStartAt());
        row.enrollEndAt = utc(input.enrollEndAt());
        row.enrollmentQuota = input.enrollmentQuota();
        row.couponCampaignId = input.couponCampaignId() == null ? null : id(input.couponCampaignId());
        row.pageSchemaJson = null;
        row.pageDraftVersion = 0;
        row.publishedPageSchemaJson = null;
        row.pageVersion = 0;
        row.sortWeight = input.sortWeight() == null ? 0 : input.sortWeight();
        row.rejectReason = null;
        row.version = 1L;
        row.createdByType = "ADMIN";
        row.createdById = operatorId;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    private static Map<String, Object> campaignParams(CampaignInput input) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", input.name());
        params.put("campaignType", input.campaignType());
        params.put("participationMode", input.participationMode());
        params.put("title", input.title());
        params.put("subtitle", input.subtitle());
        params.put("tagCode", input.tagCode());
        params.put("tagName", input.tagName());
        params.put("coverUrl", input.coverUrl());
        params.put("cityCodes", input.cityCodes());
        params.put("serviceCategoryCodes", input.serviceCategoryCodes());
        params.put("startAt", input.startAt());
        params.put("endAt", input.endAt());
        params.put("enrollStartAt", input.enrollStartAt());
        params.put("enrollEndAt", input.enrollEndAt());
        params.put("enrollmentQuota", input.enrollmentQuota());
        params.put("couponCampaignId", input.couponCampaignId());
        params.put("sortWeight", input.sortWeight());
        return params;
    }

    private static void requireEditable(String status) {
        if (!Set.of("DRAFT", "PENDING_REVIEW", "SCHEDULED", "PAUSED").contains(status)) {
            conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能编辑");
        }
    }

    private MarketingStoreFactsPort.StoreFacts requireStore(long storeId) {
        if (storeFacts == null) unavailable("store facts port is not assembled");
        MarketingStoreFactsPort.StoreFacts facts = storeFacts.store(storeId);
        if (facts == null) unavailable("store facts are unavailable");
        if (!facts.visible()) conflict("MARKETING_STORE_NOT_ELIGIBLE", "门店当前不可见");
        return facts;
    }

    private MarketingStoreFactsPort.StoreFacts safeStore(long storeId) {
        try {
            return storeFacts == null ? null : storeFacts.store(storeId);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private void validatePlacementCampaign(MarketingMapper mapper, PlacementInput input) {
        CampaignRow campaign = mapper.selectCampaignById(id(input.campaignId()));
        if (campaign == null) notFound("活动不存在");
        if (!Set.of("SCHEDULED", "ONLINE").contains(campaign.status)) {
            conflict("CAMPAIGN_STATE_NOT_ALLOWED", "活动尚未发布");
        }
        if (input.startAt().isBefore(offset(campaign.startAt))
                || input.endAt().isAfter(offset(campaign.endAt))) {
            conflict("CAMPAIGN_LANDING_INVALID", "投放窗口必须在活动窗口内");
        }
    }

    private PlacementInput validatePlacement(PlacementInput input) {
        if (input == null) invalid("placement input is required");
        String position = requireText(input.positionCode(), 1, 24, "positionCode");
        if (!POSITIONS.contains(position)) invalid("positionCode is invalid");
        long campaignId = id(input.campaignId());
        String city = optionalText(input.cityCode(), 32, "cityCode");
        if (city != null) {
            if (!city.matches("[a-z][a-z0-9_-]{0,31}")) invalid("cityCode is invalid");
            if (!cityCatalog.isOpen(city)) invalid("cityCode is not open");
        }
        String title = optionalText(input.titleOverride(), 100, "titleOverride");
        String subtitle = optionalText(input.subtitleOverride(), 200, "subtitleOverride");
        String image = optionalUrl(input.imageUrlOverride(), "imageUrlOverride", allowHttpAssets);
        String template = optionalText(input.templateCode(), 32, "templateCode");
        if (template != null && !template.matches("[A-Za-z0-9_-]{1,32}")) invalid("templateCode is invalid");
        int sort = boundedInt(input.sortWeight(), 0, 0, 9999, "sortWeight");
        requireWindow(input.startAt(), input.endAt());
        return new PlacementInput(position, Long.toUnsignedString(campaignId), city, title, subtitle,
                image, template, sort, input.startAt(), input.endAt());
    }

    private PlacementRow placementRow(long id, PlacementInput input, long operatorId) {
        LocalDateTime now = nowUtc();
        PlacementRow row = new PlacementRow();
        row.id = id;
        row.positionCode = input.positionCode();
        row.campaignId = id(input.campaignId());
        row.cityCode = input.cityCode();
        row.titleOverride = input.titleOverride();
        row.subtitleOverride = input.subtitleOverride();
        row.imageUrlOverride = input.imageUrlOverride();
        row.templateCode = input.templateCode();
        row.sortWeight = input.sortWeight() == null ? 0 : input.sortWeight();
        row.startAt = utc(input.startAt());
        row.endAt = utc(input.endAt());
        row.status = "DRAFT";
        row.version = 1L;
        row.createdById = operatorId;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    private static Map<String, Object> placementParams(PlacementInput input) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("positionCode", input.positionCode());
        params.put("campaignId", input.campaignId());
        params.put("cityCode", input.cityCode());
        params.put("titleOverride", input.titleOverride());
        params.put("subtitleOverride", input.subtitleOverride());
        params.put("imageUrlOverride", input.imageUrlOverride());
        params.put("templateCode", input.templateCode());
        params.put("sortWeight", input.sortWeight());
        params.put("startAt", input.startAt());
        params.put("endAt", input.endAt());
        return params;
    }

    private HomeBlockInput validateHomeBlockInput(HomeBlockInput input) {
        if (input == null) invalid("home block input is required");
        String code = requireText(input.blockCode(), 2, 64, "blockCode");
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,63}")) invalid("blockCode is invalid");
        String position = requireText(input.positionCode(), 1, 32, "positionCode");
        if (!HOME_BLOCK_POSITIONS.contains(position)) invalid("positionCode is invalid");
        String template = requireText(input.templateCode(), 1, 48, "templateCode");
        if (!HOME_BLOCK_TEMPLATES.contains(template)) invalid("templateCode is invalid");
        String title = requireText(input.title(), 1, 100, "title");
        String subtitle = optionalText(input.subtitle(), 200, "subtitle");
        String tag = optionalText(input.tagName(), 32, "tagName");
        String image = optionalUrl(input.imageUrl(), "imageUrl", allowHttpAssets);
        String targetType = requireText(input.targetType(), 1, 32, "targetType");
        if (!HOME_BLOCK_TARGETS.contains(targetType)) invalid("targetType is invalid");
        String targetValue = optionalText(input.targetValue(), 255, "targetValue");
        if ("NONE".equals(targetType)) {
            targetValue = null;
        } else if (targetValue == null && "SERVICE_DIRECTORY".equals(targetType)) {
            targetValue = "ALL";
        } else if (targetValue == null && "COMMUNITY".equals(targetType)) {
            targetValue = "HOT";
        } else if (targetValue == null) {
            invalid("targetValue is required");
        }
        if ("MINIAPP_PATH".equals(targetType)
                && !targetValue.startsWith("/consumer/pages/")) {
            invalid("targetValue must be a consumer page path");
        }
        String city = optionalText(input.cityCode(), 32, "cityCode");
        if (city != null) {
            if (!city.matches("[a-z][a-z0-9_-]{0,31}")) invalid("cityCode is invalid");
            if (!cityCatalog.isOpen(city)) invalid("cityCode is not open");
        }
        String sponsorMerchant = optionalId(input.sponsorMerchantId(), "sponsorMerchantId");
        String sponsorStore = optionalId(input.sponsorStoreId(), "sponsorStoreId");
        if ((sponsorMerchant == null) != (sponsorStore == null)) {
            invalid("sponsorMerchantId and sponsorStoreId must be supplied together");
        }
        int sort = boundedInt(input.sortWeight(), 0, 0, 9999, "sortWeight");
        requireWindow(input.startAt(), input.endAt());
        return new HomeBlockInput(code, position, template, title, subtitle, tag, image,
                targetType, targetValue, city, sponsorMerchant, sponsorStore, sort,
                input.startAt(), input.endAt());
    }

    private static String optionalId(String value, String field) {
        if (value == null || value.isBlank()) return null;
        return Long.toUnsignedString(id(value.strip()));
    }

    private HomeBlockRow homeBlockRow(long id, HomeBlockInput input, long operatorId) {
        LocalDateTime now = nowUtc();
        HomeBlockRow row = new HomeBlockRow();
        row.id = id;
        row.blockCode = input.blockCode();
        row.positionCode = input.positionCode();
        row.templateCode = input.templateCode();
        row.title = input.title();
        row.subtitle = input.subtitle();
        row.tagName = input.tagName();
        row.imageUrl = input.imageUrl();
        row.targetType = input.targetType();
        row.targetValue = input.targetValue();
        row.cityCode = input.cityCode();
        row.sponsorMerchantId = input.sponsorMerchantId() == null ? null : id(input.sponsorMerchantId());
        row.sponsorStoreId = input.sponsorStoreId() == null ? null : id(input.sponsorStoreId());
        row.sortWeight = input.sortWeight() == null ? 0 : input.sortWeight();
        row.startAt = utc(input.startAt());
        row.endAt = utc(input.endAt());
        row.status = "DRAFT";
        row.version = 1L;
        row.createdById = operatorId;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    private static Map<String, Object> homeBlockParams(HomeBlockInput input) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("blockCode", input.blockCode());
        params.put("positionCode", input.positionCode());
        params.put("templateCode", input.templateCode());
        params.put("title", input.title());
        params.put("subtitle", input.subtitle());
        params.put("tagName", input.tagName());
        params.put("imageUrl", input.imageUrl());
        params.put("targetType", input.targetType());
        params.put("targetValue", input.targetValue());
        params.put("cityCode", input.cityCode());
        params.put("sponsorMerchantId", input.sponsorMerchantId());
        params.put("sponsorStoreId", input.sponsorStoreId());
        params.put("sortWeight", input.sortWeight());
        params.put("startAt", input.startAt());
        params.put("endAt", input.endAt());
        return params;
    }

    private static boolean scopeAllowsHomeBlock(MarketingAdminApi.AdminAuthorization auth,
            HomeBlockRow row) {
        if (auth == null || "ALL".equals(auth.scopeMode())) return true;
        if ("CITY".equals(auth.scopeMode())) {
            return row.cityCode == null || auth.cityCodes().contains(row.cityCode);
        }
        return false;
    }

    private void auditHomeBlock(MarketingMapper mapper, long blockId, String action,
            String from, String to, CommandContext context, String detailJson) {
        if (mapper.insertHomeBlockAudit(store.nextId(), blockId, action, from, to,
                operatorId(context), context.requestId(), context.traceId(), detailJson, nowUtc()) != 1) {
            unavailable("home block audit insert failed");
        }
    }

    private void auditCampaign(MarketingMapper mapper, long campaignId, String action,
            String from, String to, CommandContext context, String detailJson) {
        if (mapper.insertCampaignAudit(store.nextId(), campaignId, action, from, to,
                context.operatorType().name(), operatorId(context), context.requestId(),
                context.traceId(), detailJson, nowUtc()) != 1) {
            unavailable("campaign audit insert failed");
        }
    }

    private void auditEnrollment(MarketingMapper mapper, long enrollmentId, String action,
            String from, String to, CommandContext context, String reason) {
        if (mapper.insertEnrollmentAudit(store.nextId(), enrollmentId, action, from, to,
                context.operatorType().name(), operatorId(context), reason, context.requestId(),
                context.traceId(), nowUtc()) != 1) {
            unavailable("enrollment audit insert failed");
        }
    }

    private static long operatorId(CommandContext context) {
        try {
            return Long.parseLong(context.operatorId());
        } catch (RuntimeException failure) {
            invalid("operatorId is invalid");
            return 0;
        }
    }

    private static String jsonOf(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception failure) {
            return "{}";
        }
    }
}
