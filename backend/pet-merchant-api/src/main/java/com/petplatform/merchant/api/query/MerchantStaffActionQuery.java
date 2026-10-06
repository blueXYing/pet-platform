package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/**
 * Fail-closed STAFF action gate input. actionCode is an approved catalog code (lowercase ASCII
 * letters/digits/dots/hyphens, 1..100); the first slice pins no catalog, callers pass codes
 * that a later approved action contract owns.
 */
public record MerchantStaffActionQuery(
        String merchantId, String storeId, String actionCode, QueryContext context) {}
