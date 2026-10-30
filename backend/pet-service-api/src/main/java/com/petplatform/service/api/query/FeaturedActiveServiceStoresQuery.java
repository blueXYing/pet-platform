package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;

/**
 * Contract 74 §3.4 featured-card candidate input (B-CARD-01): the distinct store ids that hold at
 * least one ACTIVE service, optionally narrowed to the exact enabled {@code categoryCode}. A null
 * code means no category filter; a lexically invalid or unknown/disabled code is INVALID_ARGUMENT
 * (400). The context is linkage-only and never an authority.
 */
public record FeaturedActiveServiceStoresQuery(String categoryCode, QueryContext context) {}
