package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;

/**
 * C catalog search input (HTTP10 §3.3.3 + contract 74 §3.3): the adapter trims and
 * length-bounds {@code keyword}; {@code keyword} and {@code categoryCode} are each optional but at
 * least one is required, and both together combine with AND. The keyword matches a
 * case-insensitive substring against serviceName and categoryName of ACTIVE services whose
 * merchant/store pair passes the approved four-condition conjunction; the categoryCode resolves to
 * an enabled dictionary row and matches service_item.category_id exactly. No ranking, no
 * segmentation — a plain filtered catalog read.
 */
public record CatalogServiceSearchQuery(
        String keyword, String categoryCode, int page, int pageSize, QueryContext context) {

    /** Backward-compatible keyword-only form (existing HTTP10 §3.3.3 callers). */
    public CatalogServiceSearchQuery(String keyword, int page, int pageSize, QueryContext context) {
        this(keyword, null, page, pageSize, context);
    }
}
