package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;

/** API07 5.1.1: internal bookability aggregation input. */
public record ServiceBookabilityQuery(String serviceId, String storeId, QueryContext context) {}
