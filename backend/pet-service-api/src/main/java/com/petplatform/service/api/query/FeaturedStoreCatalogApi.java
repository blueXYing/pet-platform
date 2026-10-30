package com.petplatform.service.api.query;

import com.petplatform.service.api.dto.FeaturedStoreServiceTypes.FeaturedStoreServiceItem;
import java.util.List;

/**
 * Contract 74 §3.4 C-end featured-card service read port (B-CARD-01). Read-only and anonymous by
 * design; it authorizes nothing and never widens the service catalog projection. Both methods are
 * batch-only so the composing card read never issues a query per store or per service (contract
 * 74 §11).
 *
 * <p>Error contract: a lexically invalid or unknown/disabled categoryCode is INVALID_ARGUMENT
 * (400); read failures, malformed coordinates and damaged stored facts are
 * DEPENDENCY_UNAVAILABLE (503). A legal category or store set without ACTIVE supply is a normal
 * empty answer, never an error.
 */
public interface FeaturedStoreCatalogApi {

    /**
     * Distinct store ids holding at least one ACTIVE service, optionally narrowed to the exact
     * enabled category, in ascending numeric order. The caller intersects these with the
     * merchant-domain C-visible store set; service rows are never exposed here.
     */
    List<String> listActiveServiceStoreIds(FeaturedActiveServiceStoresQuery query);

    /**
     * Every ACTIVE service of the requested stores in one partitioned read (contract 74 §6.5
     * enumeration). One query per call regardless of store count; the per-store top-2-by-sold
     * selection belongs to the composing read after the order-domain sold facts are merged.
     */
    List<FeaturedStoreServiceItem> listStoreFeaturedServices(FeaturedStoreServicesQuery query);
}
