package com.petplatform.merchant.api.dto;

import java.util.List;

/**
 * Employee confirm-page projection (contract 54 §4). Returned only to the session whose
 * verified account phone equals the invitation phone; anything else is anti-enumeration 404.
 */
public record MerchantStaffInvitationDetailDTO(
        String invitationId,
        String merchantId,
        String merchantName,
        String storeId,
        String storeName,
        String memberName,
        List<String> grantedActions,
        String status) {}
