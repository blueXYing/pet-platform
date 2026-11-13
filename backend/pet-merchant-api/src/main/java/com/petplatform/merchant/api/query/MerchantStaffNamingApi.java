package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;
import java.util.Collection;
import java.util.Map;

/**
 * Store-scoped staff display-name batch read (CCR-M-VERIFY-RECORD-001, approved 2026-11-12):
 * resolves staffId → staff_name for the merchant verification-record list's STAFF rows. The
 * caller (the VER read kernel) has already passed its OWNER store guard, so this face does not
 * repeat the guard; it still filters strictly by {@code storeId}, reading only this store's own
 * merchant_staff rows (schema 52) — cross-store or absent ids simply do not appear in the
 * result. Read-only; no profile, phone or login fact is exposed beyond the display name.
 */
public interface MerchantStaffNamingApi {

    /** staffId → staff_name for this store's merchant_staff rows; absent ids stay absent. */
    Map<String, String> staffNames(String storeId, Collection<String> staffIds, QueryContext context);
}
