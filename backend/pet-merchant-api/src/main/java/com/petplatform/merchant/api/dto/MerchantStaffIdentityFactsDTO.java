package com.petplatform.merchant.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Current STAFF identity facts for one merchant store (supplement 27 §5 getFacts). Facts are
 * re-read per call; authzVersion is an invalidation label only and never an execution licence.
 */
public record MerchantStaffIdentityFactsDTO(
        String merchantId,
        String storeId,
        String membershipKind,
        Boolean membershipEnabled,
        String applicationStatus,
        String signingStatus,
        String merchantStatus,
        String storeStatus,
        String authzVersion,
        OffsetDateTime checkedAt,
        String staffId,
        List<String> grantedActions) {}
