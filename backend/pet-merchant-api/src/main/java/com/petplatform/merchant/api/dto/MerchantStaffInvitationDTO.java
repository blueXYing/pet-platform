package com.petplatform.merchant.api.dto;

/** OWNER-side invitation projection (contract 54 §2 state machine). Phone is always masked. */
public record MerchantStaffInvitationDTO(
        String merchantId,
        String storeId,
        String invitationId,
        String memberName,
        String phoneMasked,
        String status,
        String version) {}
