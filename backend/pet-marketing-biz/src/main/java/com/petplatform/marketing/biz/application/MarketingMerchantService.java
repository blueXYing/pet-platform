package com.petplatform.marketing.biz.application;

import com.petplatform.common.CommandContext;
import com.petplatform.marketing.api.MarketingMerchantApi;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignStoreRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.EnrollmentRow;
import com.petplatform.marketing.biz.infrastructure.persistence.mapper.MarketingMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Merchant-facing enrollment / merchant-initiated campaign flows (contract 71 §5). */
public final class MarketingMerchantService extends MarketingSupport implements MarketingMerchantApi {

    public MarketingMerchantService(MarketingStore store, MarketingStoreFactsPort storeFacts, Clock clock) {
        super(store, null, Objects.requireNonNull(storeFacts), null, clock);
    }

    @Override
    public MarketingViews.Page<MarketingViews.MerchantCampaignView> listOpenCampaigns(
            MerchantCampaignListQuery query) {
        Objects.requireNonNull(query, "query is required");
        requireMerchantQuery(query.context());
        MerchantScope scope = requireScope(query.scope(), false);
        MarketingStoreFactsPort.StoreFacts facts = storeFacts.store(scope.storeId());
        if (facts == null || !facts.visible()) conflict("MARKETING_STORE_NOT_ELIGIBLE", "门店当前不可见，不能报名活动");
        int page = query.page() < 1 ? 1 : Math.min(query.page(), 10_000);
        int pageSize = query.pageSize() < 1 ? 20 : Math.min(query.pageSize(), 50);
        return store.read(mapper -> {
            List<CampaignRow> visible = mapper.selectOpenEnrollmentCampaigns(nowUtc()).stream()
                    .filter(row -> cityAllowed(row, facts.cityCode()))
                    .sorted(java.util.Comparator.comparing(
                            (CampaignRow row) -> row.sortWeight == null ? 0 : row.sortWeight)
                            .thenComparing(row -> row.startAt)
                            .thenComparing(row -> row.id))
                    .toList();
            long total = visible.size();
            long from = (long) (page - 1) * pageSize;
            List<MarketingViews.MerchantCampaignView> items = new ArrayList<>();
            for (long index = from; index < from + pageSize && index < total; index++) {
                CampaignRow row = visible.get((int) index);
                EnrollmentRow enrollment = mapper.selectEnrollmentByCampaignStore(row.id, scope.storeId());
                String status = enrollment == null ? null : enrollment.status;
                items.add(merchantCampaign(row, status));
            }
            return new MarketingViews.Page<>(items, page, pageSize, total);
        });
    }

    @Override
    public MarketingViews.Receipt enroll(EnrollCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireMerchantCommand(command.context());
        MerchantScope scope = requireScope(command.scope(), true);
        long campaignId = id(command.campaignId());
        String offerTag = optionalText(command.offerTag(), 32, "offerTag");
        String offerTitle = optionalText(command.offerTitle(), 100, "offerTitle");
        String offerCopy = optionalText(command.offerCopy(), 100, "offerCopy");
        String materials = safeJson(command.materialsJson());
        String key = requestKey("marketing.enrollment.submit", command.context(),
                scope.merchantId() + ":" + scope.storeId() + ":" + campaignId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("campaignId", campaignId);
        params.put("merchantId", scope.merchantId());
        params.put("storeId", scope.storeId());
        params.put("offerTag", offerTag);
        params.put("offerTitle", offerTitle);
        params.put("offerCopy", offerCopy);
        params.put("materials", materials);
        return idempotent(key, "marketing.enrollment.submit", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow campaign = mapper.selectCampaignForUpdate(campaignId);
                    if (campaign == null) notFound("活动不存在");
                    if (!"MERCHANT_ENROLL".equals(campaign.participationMode)) {
                        conflict("ENROLLMENT_NOT_OPEN", "活动不接受商家报名");
                    }
                    if (!SetHolder.ENROLLABLE.contains(campaign.status)
                            || campaign.enrollStartAt == null || campaign.enrollEndAt == null
                            || campaign.enrollStartAt.isAfter(nowUtc())
                            || !campaign.enrollEndAt.isAfter(nowUtc())) {
                        conflict("ENROLLMENT_NOT_OPEN", "不在报名窗口");
                    }
                    MarketingStoreFactsPort.StoreFacts facts = requireVisibleStore(scope.storeId());
                    if (facts.merchantId() == null
                            || !facts.merchantId().equals(Long.toUnsignedString(scope.merchantId()))) {
                        conflict("MARKETING_STORE_NOT_ELIGIBLE", "门店归属不一致");
                    }
                    List<String> campaignCities = parseJsonList(campaign.cityCodes);
                    if (!campaignCities.isEmpty()
                            && (facts.cityCode() == null || !campaignCities.contains(facts.cityCode()))) {
                        conflict("ENROLLMENT_NOT_OPEN", "不在报名窗口");
                    }
                    EnrollmentRow existing = mapper.selectEnrollmentByCampaignStore(campaignId, scope.storeId());
                    LocalDateTime now = nowUtc();
                    if (existing != null && SetHolder.ACTIVE_ENROLLMENT.contains(existing.status)) {
                        conflict("ENROLLMENT_ALREADY_EXISTS", "同门店同活动已有有效报名");
                    }
                    if (existing != null) {
                        String fromStatus = existing.status;
                        existing.status = "SUBMITTED";
                        existing.offerTag = offerTag;
                        existing.offerTitle = offerTitle;
                        existing.offerCopy = offerCopy;
                        existing.serviceIds = null;
                        existing.materials = materials;
                        existing.rejectReason = null;
                        existing.submittedAt = now;
                        existing.decidedAt = null;
                        existing.decidedBy = null;
                        existing.updatedAt = now;
                        if (mapper.updateEnrollment(existing) != 1) conflict("报名版本已变化");
                        auditEnrollment(mapper, existing.id, "RESUBMIT", fromStatus, "SUBMITTED",
                                command.context(), null);
                        return new MarketingViews.Receipt(Long.toUnsignedString(existing.id), "SUBMITTED",
                                existing.version + 1, 0, nowOffset());
                    }
                    EnrollmentRow row = new EnrollmentRow();
                    row.id = store.nextId();
                    row.campaignId = campaignId;
                    row.merchantId = scope.merchantId();
                    row.storeId = scope.storeId();
                    row.status = "SUBMITTED";
                    row.offerTag = offerTag;
                    row.offerTitle = offerTitle;
                    row.offerCopy = offerCopy;
                    row.serviceIds = null;
                    row.materials = materials;
                    row.rejectReason = null;
                    row.submittedAt = now;
                    row.decidedAt = null;
                    row.decidedBy = null;
                    row.version = 1L;
                    row.createdAt = now;
                    row.updatedAt = now;
                    try {
                        if (mapper.insertEnrollment(row) != 1) unavailable("enrollment insert failed");
                    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                        conflict("ENROLLMENT_ALREADY_EXISTS", "同门店同活动已有有效报名");
                    }
                    auditEnrollment(mapper, row.id, "SUBMIT", null, "SUBMITTED", command.context(), null);
                    return new MarketingViews.Receipt(Long.toUnsignedString(row.id), "SUBMITTED", 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Page<MarketingViews.EnrollmentView> listMyEnrollments(
            MerchantEnrollmentListQuery query) {
        Objects.requireNonNull(query, "query is required");
        requireMerchantQuery(query.context());
        MerchantScope scope = requireScope(query.scope(), false);
        int page = query.page() < 1 ? 1 : Math.min(query.page(), 10_000);
        int pageSize = query.pageSize() < 1 ? 20 : Math.min(query.pageSize(), 50);
        return store.read(mapper -> {
            List<EnrollmentRow> rows = mapper.selectMerchantEnrollments(scope.merchantId(), scope.storeId(),
                    query.status(), pageSize, (long) (page - 1) * pageSize);
            long total = mapper.countMerchantEnrollments(scope.merchantId(), scope.storeId(), query.status());
            MarketingStoreFactsPort.StoreFacts facts = storeFacts.store(scope.storeId());
            List<MarketingViews.EnrollmentView> items = new ArrayList<>(rows.size());
            for (EnrollmentRow row : rows) {
                if (facts != null) row.storeName = facts.storeName();
                items.add(enrollmentView(row));
            }
            return new MarketingViews.Page<>(items, page, pageSize, total);
        });
    }

    @Override
    public MarketingViews.Receipt withdraw(WithdrawCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireMerchantCommand(command.context());
        MerchantScope scope = requireScope(command.scope(), false);
        long enrollmentId = id(command.enrollmentId());
        String key = requestKey("marketing.enrollment.withdraw", command.context(),
                scope.merchantId() + ":" + scope.storeId() + ":" + enrollmentId);
        Map<String, Object> params = Map.of("enrollmentId", enrollmentId,
                "expectedVersion", command.expectedVersion(), "merchantId", scope.merchantId(),
                "storeId", scope.storeId());
        return idempotent(key, "marketing.enrollment.withdraw", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    EnrollmentRow current = mapper.selectEnrollmentForUpdate(enrollmentId);
                    if (current == null) notFound("报名不存在");
                    if (!Objects.equals(current.merchantId, scope.merchantId())
                            || !Objects.equals(current.storeId, scope.storeId())) {
                        notFound("报名不存在");
                    }
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("报名版本已变化");
                    }
                    if (!SetHolder.WITHDRAWABLE.contains(current.status)) {
                        conflict("ENROLLMENT_STATE_NOT_ALLOWED", "当前状态不能撤回");
                    }
                    if (mapper.updateEnrollmentStatus(enrollmentId, current.status, "WITHDRAWN",
                            command.expectedVersion(), null, null, nowUtc()) != 1) {
                        conflict("报名版本已变化");
                    }
                    auditEnrollment(mapper, enrollmentId, "WITHDRAW", current.status, "WITHDRAWN",
                            command.context(), null);
                    return new MarketingViews.Receipt(Long.toUnsignedString(enrollmentId), "WITHDRAWN",
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Page<MarketingViews.CampaignCard> listMyCampaigns(
            MerchantMyCampaignListQuery query) {
        Objects.requireNonNull(query, "query is required");
        requireMerchantQuery(query.context());
        MerchantScope scope = requireScope(query.scope(), false);
        int page = query.page() < 1 ? 1 : Math.min(query.page(), 10_000);
        int pageSize = query.pageSize() < 1 ? 20 : Math.min(query.pageSize(), 50);
        return store.read(mapper -> {
            List<CampaignRow> rows = mapper.selectMyCampaigns(scope.merchantId(), scope.storeId(),
                    query.status(), pageSize, (long) (page - 1) * pageSize);
            long total = mapper.countMyCampaigns(scope.merchantId(), scope.storeId(), query.status());
            List<MarketingViews.CampaignCard> items = new ArrayList<>(rows.size());
            for (CampaignRow row : rows) {
                items.add(campaignCard(row, (int) mapper.countCampaignStores(row.id)));
            }
            return new MarketingViews.Page<>(items, page, pageSize, total);
        });
    }

    @Override
    public MarketingViews.Receipt createMyCampaign(MerchantCreateCampaignCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireMerchantCommand(command.context());
        MerchantScope scope = requireScope(command.scope(), true);
        String name = requireText(command.name(), 2, 100, "name");
        String title = requireText(command.title(), 1, 100, "title");
        String subtitle = optionalText(command.subtitle(), 200, "subtitle");
        String tagCode = optionalTagCode(command.tagCode());
        String tagName = optionalText(command.tagName(), 32, "tagName");
        String coverUrl = optionalUrl(command.coverUrl(), "coverUrl");
        requireWindow(command.startAt(), command.endAt());
        String key = requestKey("marketing.campaign.merchant.create", command.context(),
                scope.merchantId() + ":" + scope.storeId());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("merchantId", scope.merchantId());
        params.put("storeId", scope.storeId());
        params.put("name", name);
        params.put("title", title);
        params.put("subtitle", subtitle);
        params.put("tagCode", tagCode);
        params.put("tagName", tagName);
        params.put("coverUrl", coverUrl);
        params.put("startAt", command.startAt());
        params.put("endAt", command.endAt());
        return idempotent(key, "marketing.campaign.merchant.create", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    MarketingStoreFactsPort.StoreFacts facts = requireVisibleStore(scope.storeId());
                    long id = store.nextId();
                    CampaignRow row = merchantCampaignRow(id, scope, name, title, subtitle, tagCode,
                            tagName, coverUrl, command.startAt(), command.endAt(), facts);
                    row.pageSchemaJson = MarketingPageSchema.defaultSchema(row);
                    row.pageDraftVersion = 1;
                    if (mapper.insertCampaign(row) != 1) unavailable("campaign insert failed");
                    CampaignStoreRow link = ownStoreLink(id, scope, facts, title, subtitle);
                    if (mapper.insertCampaignStore(link) != 1) unavailable("campaign store insert failed");
                    auditCampaign(mapper, id, "MERCHANT_CREATE", null, "DRAFT", command.context());
                    return new MarketingViews.Receipt(Long.toUnsignedString(id), "DRAFT", 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt updateMyCampaign(MerchantUpdateCampaignCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireMerchantCommand(command.context());
        MerchantScope scope = requireScope(command.scope(), true);
        long campaignId = id(command.campaignId());
        String name = requireText(command.name(), 2, 100, "name");
        String title = requireText(command.title(), 1, 100, "title");
        String subtitle = optionalText(command.subtitle(), 200, "subtitle");
        String tagCode = optionalTagCode(command.tagCode());
        String tagName = optionalText(command.tagName(), 32, "tagName");
        String coverUrl = optionalUrl(command.coverUrl(), "coverUrl");
        requireWindow(command.startAt(), command.endAt());
        String key = requestKey("marketing.campaign.merchant.update", command.context(),
                scope.merchantId() + ":" + scope.storeId() + ":" + campaignId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("campaignId", campaignId);
        params.put("expectedVersion", command.expectedVersion());
        params.put("name", name);
        params.put("title", title);
        params.put("subtitle", subtitle);
        params.put("tagCode", tagCode);
        params.put("tagName", tagName);
        params.put("coverUrl", coverUrl);
        params.put("startAt", command.startAt());
        params.put("endAt", command.endAt());
        return idempotent(key, "marketing.campaign.merchant.update", params, MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null || !Objects.equals(current.ownerMerchantId, scope.merchantId())
                            || !Objects.equals(current.ownerStoreId, scope.storeId())) {
                        notFound("活动不存在");
                    }
                    if (!Set.of("DRAFT", "REJECTED").contains(current.status)) {
                        conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能编辑");
                    }
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    current.name = name;
                    current.title = title;
                    current.subtitle = subtitle;
                    current.tagCode = tagCode;
                    current.tagName = tagName;
                    current.coverUrl = coverUrl;
                    current.startAt = utc(command.startAt());
                    current.endAt = utc(command.endAt());
                    current.version = command.expectedVersion();
                    current.updatedAt = nowUtc();
                    if (mapper.updateCampaign(current) != 1) conflict("活动版本已变化");
                    // Keep the merchant campaign's default page in sync with the edited title/
                    // subtitle/cover. The merchant never edits the raw Schema; the admin can
                    // still replace it before publishing.
                    String schema = MarketingPageSchema.defaultSchema(current);
                    int draft = (current.pageDraftVersion == null ? 0 : current.pageDraftVersion) + 1;
                    if (mapper.updateCampaignPageDraft(campaignId, schema, draft,
                            command.expectedVersion() + 1, nowUtc()) != 1) {
                        conflict("活动版本已变化");
                    }
                    current.pageSchemaJson = schema;
                    current.pageDraftVersion = draft;
                    current.version = command.expectedVersion() + 2;
                    CampaignStoreRow link = ownStoreLink(campaignId, scope, null, title, subtitle);
                    if (mapper.upsertCampaignStore(link) < 1) unavailable("campaign store upsert failed");
                    auditCampaign(mapper, campaignId, "MERCHANT_UPDATE", current.status, current.status,
                            command.context());
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), current.status,
                            command.expectedVersion() + 2, draft, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    @Override
    public MarketingViews.Receipt submitMyCampaign(MerchantCampaignStatusCommand command) {
        return merchantCampaignStatus(command, "PENDING_REVIEW", Set.of("DRAFT", "REJECTED"), "SUBMIT");
    }

    @Override
    public MarketingViews.Receipt cancelMyCampaign(MerchantCampaignStatusCommand command) {
        return merchantCampaignStatus(command, "CANCELED",
                Set.of("DRAFT", "REJECTED", "PENDING_REVIEW"), "CANCEL");
    }

    private MarketingViews.Receipt merchantCampaignStatus(
            MerchantCampaignStatusCommand command, String toStatus,
            java.util.Set<String> fromStatuses, String action) {
        Objects.requireNonNull(command, "command is required");
        requireMerchantCommand(command.context());
        MerchantScope scope = requireScope(command.scope(), true);
        long campaignId = id(command.campaignId());
        String key = requestKey("marketing.campaign.merchant." + action.toLowerCase(),
                command.context(), scope.merchantId() + ":" + scope.storeId() + ":" + campaignId);
        Map<String, Object> params = Map.of("campaignId", campaignId,
                "expectedVersion", command.expectedVersion(), "toStatus", toStatus);
        return idempotent(key, "marketing.campaign.merchant." + action.toLowerCase(), params,
                MarketingSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    CampaignRow current = mapper.selectCampaignForUpdate(campaignId);
                    if (current == null || !Objects.equals(current.ownerMerchantId, scope.merchantId())
                            || !Objects.equals(current.ownerStoreId, scope.storeId())) {
                        notFound("活动不存在");
                    }
                    if (current.version == null || current.version != command.expectedVersion()) {
                        conflict("活动版本已变化");
                    }
                    if (!fromStatuses.contains(current.status)) conflict("CAMPAIGN_STATE_NOT_ALLOWED", "当前状态不能变更");
                    if ("PENDING_REVIEW".equals(toStatus)
                            && mapper.countCampaignStores(campaignId) == 0) {
                        conflict("CAMPAIGN_LANDING_INVALID", "活动至少需要一家参与门店");
                    }
                    if (mapper.updateCampaignStatus(campaignId, current.status, toStatus,
                            command.expectedVersion(), null, nowUtc()) != 1) {
                        conflict("活动版本已变化");
                    }
                    auditCampaign(mapper, campaignId, "MERCHANT_" + action, current.status, toStatus,
                            command.context());
                    return new MarketingViews.Receipt(Long.toUnsignedString(campaignId), toStatus,
                            command.expectedVersion() + 1, 0, nowOffset());
                }), receipt -> receiptJson(receipt.id(), receipt.status(), receipt.version(), 0));
    }

    // ---------------------------------------------------------------- helpers

    private MerchantScope requireScope(MerchantScope scope, boolean write) {
        if (scope == null || scope.userId() <= 0 || scope.merchantId() <= 0 || scope.storeId() <= 0) {
            invalid("merchant scope is required");
        }
        MarketingStoreFactsPort.OwnershipFacts facts =
                storeFacts.ownership(scope.userId(), scope.merchantId(), scope.storeId());
        if (facts == null) unavailable("merchant ownership facts are unavailable");
        if (!facts.owned()) forbidden("非本人门店");
        if (write && (!facts.merchantActive() || !facts.storeActive() || !facts.applicationApproved())) {
            conflict("MARKETING_STORE_NOT_ELIGIBLE", "门店当前不可运营");
        }
        return scope;
    }

    private MarketingStoreFactsPort.StoreFacts requireVisibleStore(long storeId) {
        MarketingStoreFactsPort.StoreFacts facts = storeFacts.store(storeId);
        if (facts == null) unavailable("store facts are unavailable");
        if (!facts.visible()) conflict("MARKETING_STORE_NOT_ELIGIBLE", "门店当前不可见");
        return facts;
    }

    private static boolean cityAllowed(CampaignRow row, String city) {
        List<String> cities = parseJsonList(row.cityCodes);
        return cities.isEmpty() || (city != null && cities.contains(city));
    }

    private static String optionalTagCode(String value) {
        if (value == null || value.isBlank()) return null;
        String tag = value.strip();
        if (!tag.matches("[A-Za-z0-9_-]{1,32}")) invalid("tagCode is invalid");
        return tag;
    }

    private CampaignRow merchantCampaignRow(long id, MerchantScope scope, String name, String title,
            String subtitle, String tagCode, String tagName, String coverUrl,
            java.time.OffsetDateTime startAt, java.time.OffsetDateTime endAt,
            MarketingStoreFactsPort.StoreFacts facts) {
        LocalDateTime now = nowUtc();
        CampaignRow row = new CampaignRow();
        row.id = id;
        row.campaignCode = "MCT" + Long.toUnsignedString(id);
        row.name = name;
        row.ownerType = "MERCHANT";
        row.ownerMerchantId = scope.merchantId();
        row.ownerStoreId = scope.storeId();
        row.campaignType = "DISPLAY";
        row.participationMode = "PLATFORM_SELECTED";
        row.status = "DRAFT";
        row.title = title;
        row.subtitle = subtitle;
        row.tagCode = tagCode;
        row.tagName = tagName;
        row.coverUrl = coverUrl;
        row.cityCodes = jsonList(facts.cityCode() == null ? List.of() : List.of(facts.cityCode()));
        row.serviceCategoryCodes = jsonList(List.of());
        row.startAt = utc(startAt);
        row.endAt = utc(endAt);
        row.enrollStartAt = null;
        row.enrollEndAt = null;
        row.enrollmentQuota = null;
        row.couponCampaignId = null;
        row.pageSchemaJson = null;
        row.pageDraftVersion = 0;
        row.publishedPageSchemaJson = null;
        row.pageVersion = 0;
        row.sortWeight = 0;
        row.rejectReason = null;
        row.version = 1L;
        row.createdByType = "MERCHANT";
        row.createdById = scope.userId();
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    private CampaignStoreRow ownStoreLink(long campaignId, MerchantScope scope,
            MarketingStoreFactsPort.StoreFacts facts, String title, String subtitle) {
        LocalDateTime now = nowUtc();
        CampaignStoreRow row = new CampaignStoreRow();
        row.id = store.nextId();
        row.campaignId = campaignId;
        row.merchantId = scope.merchantId();
        row.storeId = scope.storeId();
        row.source = "MERCHANT_OWN";
        row.status = "ACTIVE";
        row.offerTag = null;
        row.offerTitle = title;
        row.offerCopy = clip(subtitle, 100);
        row.sortWeight = 0;
        row.version = 1L;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    private static String clip(String value, int maxCodePoints) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private void auditCampaign(MarketingMapper mapper, long campaignId, String action,
            String from, String to, CommandContext context) {
        if (mapper.insertCampaignAudit(store.nextId(), campaignId, action, from, to,
                context.operatorType().name(), operatorId(context), context.requestId(),
                context.traceId(), "{}", nowUtc()) != 1) {
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

    private static final class SetHolder {
        private static final java.util.Set<String> ENROLLABLE =
                java.util.Set.of("SCHEDULED", "ONLINE");
        private static final java.util.Set<String> ACTIVE_ENROLLMENT =
                java.util.Set.of("SUBMITTED", "UNDER_REVIEW", "APPROVED");
        /** Contract 71 §5.2: only an undecided enrollment may be withdrawn. */
        private static final java.util.Set<String> WITHDRAWABLE =
                java.util.Set.of("SUBMITTED", "UNDER_REVIEW");
    }
}
