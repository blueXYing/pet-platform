package com.petplatform.service.api.query;

import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryPage;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryStores;

/**
 * Contract 74 §3.1/§3.2 C-end service-category read port (B-SVC-03/04). Read-only and anonymous
 * by design; it authorizes nothing and never widens the service catalog projection.
 *
 * <p>Error contract: a lexically invalid or unknown/disabled categoryCode is INVALID_ARGUMENT
 * (400); read failures and unknown stored facts are DEPENDENCY_UNAVAILABLE (503). A legal category
 * without visible supply is a normal empty answer, never an error.
 */
public interface ServiceCatalogCategoryApi {

    /** Enabled categories with supply facts grouped per store; no per-category queries. */
    CatalogServiceCategoryPage listCatalogCategories(CatalogServiceCategoryQuery query);

    /** Enabled category resolution for the /c/stores and /c/services category filters. */
    CatalogServiceCategoryStores listCatalogCategoryStores(
            CatalogServiceCategoryStoresQuery query);
}
