package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;

/** CCR-W2-API-001 service-domain: C catalog store page input (HTTP10 3.3.1). */
public record StoreServiceSnapshotQuery(
        String storeId, int page, int pageSize, QueryContext context) {}
