package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;

/** API07 5.1.1: internal snapshot projection input. */
public record ServiceSnapshotQuery(String serviceId, QueryContext context) {}
