package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;

/**
 * C catalog keyword search input (HTTP10 §3.3.3, 2026-10-19 ruling): the adapter trims and
 * length-bounds {@code keyword}; the domain matches it as a case-insensitive substring against
 * serviceName and categoryName of ACTIVE services whose merchant/store pair passes the approved
 * four-condition conjunction. No ranking, no segmentation — a plain filtered catalog read.
 */
public record CatalogServiceSearchQuery(String keyword, int page, int pageSize, QueryContext context) {}
