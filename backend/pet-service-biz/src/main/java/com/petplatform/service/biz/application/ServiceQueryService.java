package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.merchant.api.dto.MerchantDisplayEligibilityDTO;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityQuery;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.dto.ServiceSnapshotPageDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import com.petplatform.service.biz.infrastructure.persistence.ServiceReadStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceItemReadEntity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    private final ServiceReadStore store;
    private final MerchantDisplayEligibilityApi merchantFacts;
    private final ServiceWriteDependencies.ServiceCoverUrlPort coverUrls;

    public ServiceQueryService(
            ServiceReadStore store,
            MerchantDisplayEligibilityApi merchantFacts,
            ServiceWriteDependencies.ServiceCoverUrlPort coverUrls) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.merchantFacts = Objects.requireNonNull(merchantFacts, "merchantFacts is required");
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

    /** C visibility rule: NOT_FOUND unless the four-condition conjunction holds. */
    public ServiceSnapshotDTO visibleService(ServiceSnapshotQuery query) {
        if (query == null) invalid("query is required");
        // Facts must be read inside the snapshot transaction: the signing facts port joins the
        // caller's transaction, and eligibility/visibility share one repeatable-read view.
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
