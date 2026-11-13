package com.petplatform.verification.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;

/**
 * Read-only merchant verification-record list face (CCR-M-VERIFY-RECORD-001, approved
 * 2026-11-12): the store's own completed verifications for the merchant workbench 「核销记录」
 * tab (Figma 12:7426). OWNER-only per the CCR §9 ruling — the kernel re-proves the store
 * coordinate and the OWNER membership through {@code MerchantOrderAuthorityApi#requireOwnerRead}
 * on every call, so a foreign, absent or non-owned store answers the same COMMON_FORBIDDEN
 * (anti-enumeration) and cross-store rows never appear. The operator renders as the CCR §9
 * display pair only: STAFF rows carry the member's real staff name (batch-resolved from this
 * store's merchant_staff rows), OWNER rows carry the fixed 「店长」 label; the raw operatorId
 * never crosses this surface. Credential secrets (code cipher, lookup hash) and customer phone
 * stay sealed (contract 48/63 red lines, CCR-M-ORDER-CARD-001 §9.3). No write path exists.
 */
public interface MerchantVerificationReadApi {

    /** One page of the store's verification records, newest first (verified_at DESC, id DESC). */
    PageResult<VerificationRow> listStoreVerifications(StoreVerificationListQuery query);

    /**
     * Wire-neutral verification row. {@code operatorName} follows the CCR §9 ruling:
     * STAFF = the member's real staff_name from this store's merchant_staff rows (null when
     * the profile row is gone — never fabricated), OWNER = the fixed 「店长」 label.
     * {@code operatorStaffId} is nullable and OWNER rows read null.
     */
    record VerificationRow(
            String verificationId, String orderId, String storeId,
            String operatorType, String membershipKind, String operatorStaffId,
            String operatorName, String verifyMethod, OffsetDateTime verifiedAt) {}

    /**
     * merchantId/storeId are canonical decimal strings; orderId is an optional exact filter;
     * verifiedFrom/verifiedTo are optional paired UTC bounds (span ≤366 days, same query
     * discipline as the contract-63 face); the subject is the session user.
     */
    record StoreVerificationListQuery(
            String merchantId, String storeId, String orderId,
            OffsetDateTime verifiedFrom, OffsetDateTime verifiedTo,
            int page, int pageSize, QueryContext context) {}
}
