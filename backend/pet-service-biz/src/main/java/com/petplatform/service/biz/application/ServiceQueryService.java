package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantDisplayEligibilityDTO;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityQuery;
import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsApi;
import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsQuery;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryItem;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryPage;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryStores;
import com.petplatform.service.api.dto.FeaturedStoreServiceTypes.FeaturedStoreServiceItem;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.dto.ServiceSnapshotPageDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.api.query.CatalogServiceCategoryQuery;
import com.petplatform.service.api.query.CatalogServiceCategoryStoresQuery;
import com.petplatform.service.api.query.CatalogServiceSearchQuery;
import com.petplatform.service.api.query.FeaturedActiveServiceStoresQuery;
import com.petplatform.service.api.query.FeaturedStoreServicesQuery;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import com.petplatform.service.biz.infrastructure.persistence.ServiceReadStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.CatalogCategoryReadEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.CatalogCategoryStoreCountEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.FeaturedStoreServiceRowEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceItemReadEntity;
import com.petplatform.service.biz.infrastructure.persistence.mapper.ServiceReadMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CCR-W2-API-001 service domain (SVC-001): read-only catalog slice. Visibility and bookability
 * share the approved four-condition conjunction evaluated on merchant facts consumed through the
 * display-scoped SVC-D5 query inside the service read transaction (one repeatable-read snapshot).
 *
 * <p>Error split per the human approval: positively confirmed missing or ineligible -> NOT_FOUND;
 * facts-source failure, read failure or unknown stored state -> DEPENDENCY_UNAVAILABLE. Never
 * conflated, never degraded into a visible or bookable answer.
 */
public final class ServiceQueryService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    /**
     * Known stored statuses (06号 comment as amended by 33号). REVIEWING/REJECTED are legal write-
     * side states since the service write slice: they stay invisible to consumers (still 404) but
     * are no longer treated as unknown facts (2026-09-22 ruling, proposal v0.2).
     */
    private static final Set<String> SERVICE_STATUSES =
            Set.of("DRAFT", "REVIEWING", "ACTIVE", "OFFLINE", "REJECTED");

    /** Registry 12 §12 service-domain not-found code (SVC-D1b: 404, never COMMON_NOT_FOUND). */
    private static final String SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND";

    /** Contract 74 §2.1 stable categoryCode lexical shape (the same rule the write slice uses). */
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    private final ServiceReadStore store;
    private final MerchantDisplayEligibilityApi merchantFacts;
    private final MerchantDisplayStoreIdsApi displayStoreIds;
    private final ServiceWriteDependencies.ServiceCoverUrlPort coverUrls;

    public ServiceQueryService(
            ServiceReadStore store,
            MerchantDisplayEligibilityApi merchantFacts,
            MerchantDisplayStoreIdsApi displayStoreIds,
            ServiceWriteDependencies.ServiceCoverUrlPort coverUrls) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.merchantFacts = Objects.requireNonNull(merchantFacts, "merchantFacts is required");
        this.displayStoreIds = Objects.requireNonNull(displayStoreIds, "displayStoreIds is required");
        this.coverUrls = coverUrls; // nullable on purpose: internal reads never sign a cover URL
    }

    /** Internal projection: any stored row by id; the returned copy never tracks later changes. */
    public ServiceSnapshotDTO snapshot(ServiceSnapshotQuery query) {
        if (query == null) invalid("query is required");
        ServiceItemReadEntity row = store.read(mapper -> mapper.selectServiceById(targetId(query.serviceId())));
        if (row == null) notFound();
        return toSnapshot(row);
    }

    /** Internal aggregation with reasonCodes; basic eligibility only (no slots, no order). */
    public ServiceBookabilityDTO bookability(ServiceBookabilityQuery query) {
        if (query == null) invalid("query is required");
        long serviceId = targetId(query.serviceId());
        long storeId = targetId(query.storeId());
        return store.read(mapper -> {
            ServiceItemReadEntity row = mapper.selectServiceById(serviceId);
            if (row == null || row.getStoreId() == null || row.getStoreId() != storeId) notFound();
            List<String> reasons = new ArrayList<>();
            if (!"ACTIVE".equals(row.getStatus())) reasons.add("SERVICE_OFFLINE");
            MerchantDisplayEligibilityDTO facts = displayFacts(row, query.context());
            if (!facts.merchantEnabled()) reasons.add("MERCHANT_DISABLED");
            if (!facts.storeEnabled()) reasons.add("STORE_DISABLED");
            if (!facts.acceptsNewOrders()) reasons.add("MERCHANT_NOT_ACCEPTING_ORDERS");
            return new ServiceBookabilityDTO(
                    IDS.toApi(row.getId()), IDS.toApi(row.getMerchantId()), IDS.toApi(row.getStoreId()),
                    reasons.isEmpty(), List.copyOf(reasons));
        });
    }

    /** C catalog page: ACTIVE services of a store whose merchant facts pass the conjunction. */
    public ServiceSnapshotPageDTO storePage(StoreServiceSnapshotQuery query) {
        if (query == null) invalid("query is required");
        long storeId = targetId(query.storeId());
        int page = query.page();
        int pageSize = query.pageSize();
        if (page < 1 || page > 10_000) invalid("page is invalid");
        if (pageSize < 1 || pageSize > 50) invalid("pageSize is invalid");
        return store.read(mapper -> {
            List<ServiceItemReadEntity> rows =
                    mapper.selectActiveStoreServices(storeId, pageSize, (page - 1) * pageSize);
            long total = mapper.countActiveStoreServices(storeId);
            if (rows.isEmpty()) return new ServiceSnapshotPageDTO(List.of(), page, pageSize, total);
            Set<Long> merchantIds = new LinkedHashSet<>();
            for (ServiceItemReadEntity row : rows) merchantIds.add(row.getMerchantId());
            if (merchantIds.size() != 1) unavailable("store service merchant binding is inconsistent");
            // One eligibility check covers the whole page: every row shares the store's pair.
            MerchantDisplayEligibilityDTO facts = displayFacts(rows.get(0), query.context());
            if (!facts.merchantEnabled() || !facts.storeEnabled() || !facts.acceptsNewOrders()) {
                return new ServiceSnapshotPageDTO(List.of(), page, pageSize, 0);
            }
            List<ServiceSnapshotDTO> items = new ArrayList<>(rows.size());
            for (ServiceItemReadEntity row : rows) items.add(withCoverUrl(toSnapshot(row)));
            return new ServiceSnapshotPageDTO(List.copyOf(items), page, pageSize, total);
        });
    }

    /**
     * Contract 74 §3.3 C catalog search: keyword and/or exact categoryCode, at least one present,
     * AND when both. Same visibility machinery as {@link #storePage}: ACTIVE rows, one eligibility
     * read per distinct merchant/store pair inside the single read-only snapshot, ineligible rows
     * silently hidden, facts failures fail closed as 503 — never an empty-page degradation.
     * Candidates are matched in SQL (pre-escaped LIKE for the keyword, exact id for the category);
     * the visible total is computed after eligibility so pagination stays honest. Keyword reads
     * keep the fixed merchant/store/service order; a category-only read orders by service id
     * (contract 74 §3.3 stable paging).
     */
    public ServiceSnapshotPageDTO catalogSearch(CatalogServiceSearchQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        String keyword = query.keyword() == null ? null : query.keyword().strip();
        if (keyword != null
                && (keyword.isEmpty() || keyword.codePointCount(0, keyword.length()) > 30)) {
            invalid("keyword is invalid");
        }
        String categoryCode = query.categoryCode();
        if (categoryCode != null && !CATEGORY_CODE.matcher(categoryCode).matches()) {
            invalid("categoryCode is invalid");
        }
        if (keyword == null && categoryCode == null) {
            invalid("keyword or categoryCode is required");
        }
        int page = query.page();
        int pageSize = query.pageSize();
        if (page < 1 || page > 10_000) invalid("page is invalid");
        if (pageSize < 1 || pageSize > 50) invalid("pageSize is invalid");
        return store.read(mapper -> {
            Long categoryId = categoryCode == null ? null : enabledCategoryId(mapper, categoryCode);
            List<ServiceItemReadEntity> matched = mapper.selectActiveServiceCandidates(
                    keyword == null ? null : likeEscape(keyword), categoryId);
            Map<String, MerchantDisplayEligibilityDTO> factsByPair = new LinkedHashMap<>();
            List<ServiceItemReadEntity> visible = new ArrayList<>(Math.min(matched.size(), 256));
            for (ServiceItemReadEntity row : matched) {
                if (row.getId() == null || row.getId() <= 0
                        || row.getMerchantId() == null || row.getMerchantId() <= 0
                        || row.getStoreId() == null || row.getStoreId() <= 0) {
                    unavailable("service identity facts are invalid");
                }
                MerchantDisplayEligibilityDTO facts = factsByPair.computeIfAbsent(
                        row.getMerchantId() + ":" + row.getStoreId(),
                        ignored -> displayFacts(row, query.context()));
                if (!facts.merchantEnabled() || !facts.storeEnabled() || !facts.acceptsNewOrders()) {
                    continue; // Confirmed ineligible: hidden, never an error.
                }
                visible.add(row);
            }
            long total = visible.size();
            List<ServiceSnapshotDTO> items = new ArrayList<>(Math.min(pageSize, visible.size()));
            long from = (page - 1L) * pageSize;
            for (long index = from; index < from + pageSize && index < total; index++) {
                items.add(withCoverUrl(toSnapshot(visible.get((int) index))));
            }
            return new ServiceSnapshotPageDTO(List.copyOf(items), page, pageSize, total);
        });
    }

    /**
     * Contract 74 §3.1 C category availability. Enabled dictionary rows are combined with one
     * grouped ACTIVE-service count per (category, store); the merchant domain supplies the
     * C-visible store set for the requested open cities (three-condition conjunction + compat-city
     * integrity) inside the same repeatable-read snapshot. A category survives only when at least
     * one visible store holds an ACTIVE service in it; counts are the distinct surviving stores
     * and the sum of their ACTIVE services. One category read + one grouped count + one merchant
     * display-store read — never a query per category (contract 74 §11).
     */
    public CatalogServiceCategoryPage catalogCategories(CatalogServiceCategoryQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        List<String> cityCodes = query.cityCodes();
        if (cityCodes.isEmpty()) return new CatalogServiceCategoryPage(List.of());
        return store.read(mapper -> {
            Set<Long> visibleStores = visibleStoreIds(cityCodes, query.context());
            List<CatalogCategoryReadEntity> categories = mapper.selectEnabledCategories();
            List<CatalogCategoryStoreCountEntity> buckets =
                    mapper.countActiveServicesByCategoryStore();
            Map<Long, long[]> aggregates = new HashMap<>();
            for (CatalogCategoryStoreCountEntity bucket : buckets) {
                if (bucket.getCategoryId() == null || bucket.getCategoryId() <= 0
                        || bucket.getStoreId() == null || bucket.getStoreId() <= 0
                        || bucket.getServiceCount() == null || bucket.getServiceCount() <= 0) {
                    unavailable("service category availability facts are invalid");
                }
                if (!visibleStores.contains(bucket.getStoreId())) continue;
                long[] aggregate = aggregates.computeIfAbsent(
                        bucket.getCategoryId(), ignored -> new long[2]);
                aggregate[0] += 1;
                aggregate[1] += bucket.getServiceCount();
            }
            List<CatalogServiceCategoryItem> items = new ArrayList<>();
            for (CatalogCategoryReadEntity category : categories) {
                long[] aggregate = aggregates.get(category.getId());
                if (aggregate == null) continue; // No visible supply: the category disappears.
                items.add(categoryItem(category, aggregate));
            }
            return new CatalogServiceCategoryPage(items);
        });
    }

    /**
     * Contract 74 §3.2/§3.3 categoryCode resolution: an enabled dictionary row yields the distinct
     * stores with ACTIVE services in that category, ascending. Unknown or disabled codes are
     * INVALID_ARGUMENT (400); a legal category without supply is a normal empty list. The caller
     * composes this with its own visibility read (store catalog) or the service visibility
     * machinery above — service rows are never exposed here.
     */
    public CatalogServiceCategoryStores catalogCategoryStores(
            CatalogServiceCategoryStoresQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        String categoryCode = query.categoryCode();
        if (categoryCode == null || !CATEGORY_CODE.matcher(categoryCode).matches()) {
            invalid("categoryCode is invalid");
        }
        return store.read(mapper -> {
            long categoryId = enabledCategoryId(mapper, categoryCode);
            List<Long> storeIds = mapper.selectActiveCategoryStoreIds(categoryId);
            List<String> ids = new ArrayList<>(storeIds.size());
            for (Long storeId : storeIds) {
                if (storeId == null || storeId <= 0) {
                    unavailable("service category store facts are invalid");
                }
                ids.add(IDS.toApi(storeId));
            }
            return new CatalogServiceCategoryStores(categoryCode, ids);
        });
    }

    /**
     * Contract 74 §3.4 featured-card candidates (B-CARD-01): distinct stores holding at least one
     * ACTIVE service, optionally narrowed to the exact enabled category, ascending. Store
     * C-visibility is composed by the caller over the merchant display-store facts; this read
     * stays one grouped query and never exposes service rows.
     */
    public List<String> featuredStoreIds(FeaturedActiveServiceStoresQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        String categoryCode = query.categoryCode();
        if (categoryCode != null && !CATEGORY_CODE.matcher(categoryCode).matches()) {
            invalid("categoryCode is invalid");
        }
        return store.read(mapper -> {
            Long categoryId = categoryCode == null ? null : enabledCategoryId(mapper, categoryCode);
            List<Long> storeIds = mapper.selectActiveServiceStoreIds(categoryId);
            List<String> ids = new ArrayList<>(storeIds.size());
            for (Long storeId : storeIds) {
                if (storeId == null || storeId <= 0) {
                    unavailable("featured store facts are invalid");
                }
                ids.add(IDS.toApi(storeId));
            }
            return List.copyOf(ids);
        });
    }

    /**
     * Contract 74 §6.5 featured-card service enumeration (B-CARD-01): every ACTIVE service of the
     * requested stores in one window-partitioned query. The per-service dictionary category is
     * mandatory on the wire, so a missing category row is damaged facts and fails closed (503);
     * the sold-count merge and the confirmed top-2 selection belong to the composing read after
     * the order-domain facts arrive.
     */
    public List<FeaturedStoreServiceItem> featuredStoreServices(FeaturedStoreServicesQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        if (query.storeIds().size() > FeaturedStoreServicesQuery.MAX_STORE_IDS) {
            invalid("storeIds is invalid");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String storeId : query.storeIds()) {
            long value = targetId(storeId);
            ids.add(value);
        }
        if (ids.isEmpty()) return List.of();
        return store.read(mapper -> {
            List<FeaturedStoreServiceRowEntity> rows =
                    mapper.selectFeaturedStoreServices(List.copyOf(ids));
            List<FeaturedStoreServiceItem> items = new ArrayList<>(rows.size());
            for (FeaturedStoreServiceRowEntity row : rows) items.add(featuredServiceItem(row));
            return List.copyOf(items);
        });
    }

    /** Contract 74 §6.5 wire projection with fail-closed stored-fact validation. */
    private static FeaturedStoreServiceItem featuredServiceItem(FeaturedStoreServiceRowEntity row) {
        if (row.getServiceId() == null || row.getServiceId() <= 0
                || row.getStoreId() == null || row.getStoreId() <= 0
                || row.getMerchantId() == null || row.getMerchantId() <= 0
                || row.getCategoryId() == null || row.getCategoryId() <= 0) {
            unavailable("featured service identity facts are invalid");
        }
        if (row.getServiceName() == null || row.getServiceName().isBlank()
                || row.getServiceName().codePointCount(0, row.getServiceName().length()) > 50) {
            unavailable("featured service name facts are invalid");
        }
        if (row.getCategoryCode() == null || !CATEGORY_CODE.matcher(row.getCategoryCode()).matches()
                || row.getCategoryName() == null || row.getCategoryName().isBlank()
                || row.getCategoryName().codePointCount(0, row.getCategoryName().length()) > 20
                || row.getCategorySortNo() == null
                || row.getCategorySortNo() < 0 || row.getCategorySortNo() > 9999) {
            unavailable("featured service category facts are invalid");
        }
        if (row.getSalePrice() == null || row.getSalePrice().compareTo(BigDecimal.ZERO) <= 0
                || (row.getListPrice() != null
                        && row.getListPrice().compareTo(row.getSalePrice()) < 0)
                || row.getDurationMinutes() == null || row.getDurationMinutes() <= 0) {
            unavailable("featured service price facts are invalid");
        }
        FulfillmentType fulfillment;
        try {
            fulfillment = FulfillmentType.valueOf(String.valueOf(row.getFulfillmentType()));
        } catch (RuntimeException unknown) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "featured service fulfillment type is unknown");
        }
        return new FeaturedStoreServiceItem(
                IDS.toApi(row.getServiceId()),
                IDS.toApi(row.getStoreId()),
                IDS.toApi(row.getMerchantId()),
                row.getServiceName(),
                IDS.toApi(row.getCategoryId()),
                row.getCategoryCode(),
                row.getCategoryName(),
                row.getCategorySortNo(),
                row.getSalePrice(),
                row.getListPrice(),
                row.getDurationMinutes(),
                fulfillment.name());
    }

    /** Resolves an exact, enabled category code to its id; unknown/disabled stay indistinguishable. */
    private static long enabledCategoryId(ServiceReadMapper mapper, String categoryCode) {
        CatalogCategoryReadEntity row = mapper.selectCategoryByCode(categoryCode);
        if (row == null) invalid("categoryCode is invalid");
        String status = row.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            unavailable("service category status is unknown");
        }
        if (row.getId() == null || row.getId() <= 0) {
            unavailable("service category identity is invalid");
        }
        if (!"ENABLED".equals(status)) invalid("categoryCode is invalid");
        return row.getId();
    }

    /** C-visible store set for the requested open cities, from the merchant display-domain read. */
    private Set<Long> visibleStoreIds(List<String> cityCodes, QueryContext context) {
        List<String> ids;
        try {
            ids = displayStoreIds.listDisplayStoreIds(
                    new MerchantDisplayStoreIdsQuery(cityCodes, context));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            unavailable("merchant display store facts unavailable");
            return Set.of();
        }
        if (ids == null) unavailable("merchant display store facts are invalid");
        Set<Long> stores = new HashSet<>();
        for (String id : ids) {
            try {
                stores.add(IDS.fromApi(id));
            } catch (RuntimeException malformed) {
                unavailable("merchant display store facts are invalid");
            }
        }
        return stores;
    }

    /** Contract 74 §3.1 wire projection with fail-closed stored-fact validation. */
    private static CatalogServiceCategoryItem categoryItem(
            CatalogCategoryReadEntity category, long[] aggregate) {
        if (category.getId() == null || category.getId() <= 0) {
            unavailable("service category identity is invalid");
        }
        String code = category.getCategoryCode();
        if (code == null || !CATEGORY_CODE.matcher(code).matches()) {
            unavailable("service category code is invalid");
        }
        String name = category.getCategoryName();
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 20) {
            unavailable("service category name is invalid");
        }
        Integer sortNo = category.getSortNo();
        if (sortNo == null || sortNo < 0 || sortNo > 9999) {
            unavailable("service category sort number is invalid");
        }
        String iconUrl = category.getIconUrl();
        if (iconUrl != null && iconUrl.codePointCount(0, iconUrl.length()) > 512) {
            unavailable("service category icon is invalid");
        }
        String description = category.getDescription();
        if (description != null && description.codePointCount(0, description.length()) > 200) {
            unavailable("service category description is invalid");
        }
        long storeCount = aggregate[0];
        long serviceCount = aggregate[1];
        if (storeCount <= 0 || serviceCount <= 0
                || storeCount > Integer.MAX_VALUE || serviceCount > Integer.MAX_VALUE) {
            unavailable("service category availability facts are invalid");
        }
        return new CatalogServiceCategoryItem(
                code,
                name,
                category.getParentCode(),
                iconUrl,
                description,
                sortNo,
                (int) storeCount,
                (int) serviceCount);
    }

    /** LIKE wildcard literalisation for the §3.3.3 keyword match (escape char is a backslash). */
    private static String likeEscape(String keyword) {
        String backslash = "\\";
        return keyword.replace(backslash, "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** C visibility rule: NOT_FOUND unless the four-condition conjunction holds. */
    public ServiceSnapshotDTO visibleService(ServiceSnapshotQuery query) {
        if (query == null) invalid("query is required");
        // Merchant agreement/eligibility and service visibility share one repeatable-read view.
        // After visibility passes, the cover signer suspends this read-only transaction and
        // validates current asset readiness under its own short lock before local presigning.
        return store.read(mapper -> {
            ServiceItemReadEntity row = mapper.selectServiceById(targetId(query.serviceId()));
            if (row == null || !"ACTIVE".equals(row.getStatus())) notFound();
            MerchantDisplayEligibilityDTO facts = displayFacts(row, query.context());
            if (!facts.merchantEnabled() || !facts.storeEnabled() || !facts.acceptsNewOrders()) notFound();
            return withCoverUrl(toSnapshot(row));
        });
    }

    /**
     * Consumer cover display (2026-09-22 ruling #3): a presigned URL is issued only for a row that
     * already passed the visibility conjunction and carries a cover binding. No cover -> null
     * fields; a bound cover whose signer is unavailable fails closed (503), never an unsigned or
     * stale URL.
     */
    private ServiceSnapshotDTO withCoverUrl(ServiceSnapshotDTO snapshot) {
        if (snapshot.coverAssetId() == null) return snapshot;
        if (coverUrls == null) unavailable("cover url signer is unavailable");
        ServiceWriteDependencies.ServiceCoverUrlPort.CoverUrl signed;
        try {
            signed = coverUrls.sign(snapshot.coverAssetId());
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            unavailable("cover url signer is unavailable");
            return null;
        }
        if (signed == null || signed.url() == null || signed.url().isBlank()
                || signed.expiresAtEpochSeconds() <= 0) {
            unavailable("cover url signature is invalid");
        }
        return new ServiceSnapshotDTO(
                snapshot.serviceId(),
                snapshot.merchantId(),
                snapshot.storeId(),
                snapshot.serviceName(),
                snapshot.categoryId(),
                snapshot.categoryName(),
                snapshot.salePrice(),
                snapshot.durationMinutes(),
                snapshot.fulfillmentType(),
                snapshot.description(),
                snapshot.coverAssetId(),
                signed.url(),
                java.time.Instant.ofEpochSecond(signed.expiresAtEpochSeconds())
                        .toString());
    }

    private MerchantDisplayEligibilityDTO displayFacts(
            ServiceItemReadEntity row, com.petplatform.common.QueryContext context) {
        try {
            return merchantFacts.checkDisplayEligibility(new MerchantDisplayEligibilityQuery(
                    IDS.toApi(row.getMerchantId()), IDS.toApi(row.getStoreId()), context));
        } catch (ApiException missing) {
            if (CommonApiCodes.NOT_FOUND.equals(missing.code())) notFound();
            throw missing;
        }
    }

    private static ServiceSnapshotDTO toSnapshot(ServiceItemReadEntity row) {
        if (row.getId() == null || row.getId() <= 0
                || row.getMerchantId() == null || row.getStoreId() == null
                || row.getCategoryId() == null) unavailable("service identity facts are invalid");
        if (!SERVICE_STATUSES.contains(String.valueOf(row.getStatus()))) unavailable("service status is unknown");
        if (row.getServiceName() == null || row.getServiceName().isBlank()
                || row.getPrice() == null || row.getPrice().compareTo(BigDecimal.ZERO) < 0
                || row.getDurationMinutes() == null || row.getDurationMinutes() <= 0) {
            unavailable("service projection facts are invalid");
        }
        FulfillmentType fulfillment;
        try {
            fulfillment = FulfillmentType.valueOf(String.valueOf(row.getFulfillmentType()));
        } catch (RuntimeException unknown) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "service fulfillment type is unknown");
        }
        return new ServiceSnapshotDTO(
                IDS.toApi(row.getId()),
                IDS.toApi(row.getMerchantId()),
                IDS.toApi(row.getStoreId()),
                row.getServiceName(),
                IDS.toApi(row.getCategoryId()),
                row.getCategoryName(),
                row.getPrice(),
                row.getDurationMinutes(),
                fulfillment,
                row.getDescription(),
                row.getCoverAssetId() == null ? null : IDS.toApi(row.getCoverAssetId()),
                null,
                null);
    }

    private static long targetId(String value) {
        if (value == null || value.isBlank()) invalid("id is required");
        try {
            long id = IDS.fromApi(value);
            if (id <= 0) invalid("id is invalid");
            return id;
        } catch (RuntimeException malformed) {
            invalid("id is invalid");
            throw malformed;
        }
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void notFound() {
        // SVC-D1b: confirmed missing OR any visibility condition failing answers the same
        // domain code, indistinguishable from the caller's perspective (anti-probing).
        throw new ApiException(SERVICE_NOT_FOUND, "service resource not found");
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
