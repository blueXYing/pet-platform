package com.petplatform.merchant.api.dto;

import java.util.List;

/**
 * OWNER-side member projection (contract 54 §4; position label per CCR-M-STAFF-POS-001).
 * Phone and name come from the member's CONFIRMED invitation row; contract-52 relations
 * never store them. staffId stays absent in this slice: the grant display reference backfill
 * is pending the 48-K1 revision ruling. position is the owner-editable free-text label
 * (0..64 code points, '' = not set) and never a permission fact.
 */
public record MerchantStaffMemberDTO(
        String merchantId,
        String storeId,
        String memberId,
        String memberName,
        String position,
        String phoneMasked,
        String memberStatus,
        String grantStatus,
        List<String> grantedActions,
        String memberVersion,
        String grantVersion) {}
