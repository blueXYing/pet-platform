package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;

/**
 * Contract 74 §3.2 category filter resolution input (B-SVC-04): resolves a categoryCode against
 * the enabled dictionary and returns the visible-service candidate stores. Unknown or disabled
 * codes are INVALID_ARGUMENT (400); the context is linkage-only and never an authority.
 */
public record CatalogServiceCategoryStoresQuery(String categoryCode, QueryContext context) {}
