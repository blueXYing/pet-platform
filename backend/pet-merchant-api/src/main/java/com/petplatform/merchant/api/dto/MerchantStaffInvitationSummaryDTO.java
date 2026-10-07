package com.petplatform.merchant.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Employee-side invitation list row (contract 54 §7). Discloses the same read projection family
 * as the confirm-page detail (merchant/store names, invited name, granted actions, status) plus
 * the row timestamps; never any phone form — the row is only ever returned to the session whose
 * verified account phone matches the stored invitation phone.
 */
public record MerchantStaffInvitationSummaryDTO(
        String invitationId,
        String merchantId,
        String merchantName,
        String storeId,
        String storeName,
        String memberName,
        List<String> grantedActions,
        String status,
        OffsetDateTime invitedAt,
        OffsetDateTime updatedAt) {}
