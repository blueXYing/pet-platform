package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;
import java.util.List;

/**
 * Full-replace store profile update (contract 27 §6.4 supplement, command
 * {@code merchant.store.profile.update}; 2026-10-19 CCR expansion). Every editable field
 * participates in the replace: an omitted JSON key clears the field, an explicit JSON null is
 * 400. The 2026-10-19 expansion adds the merchant-maintained store facts (storeName/address/
 * phone — the CCR minimal ruling: these changes do not trigger operations re-review; city/
 * location/qualification stay read-only), the STORE_PHOTO avatar asset, and the business
 * status switch (OPEN/RESTING). The write gate is the staff-write family (real OWNER +
 * merchant/store ACTIVE + APPROVED application + signed agreement) and the command rides the
 * supplement-23 idempotency binding with the expectedVersion optimistic lock.
 */
public record UpdateStoreProfileCommand(
        String merchantId,
        String storeId,
        String storeName,
        String address,
        String phone,
        String intro,
        String announcement,
        List<String> photoAssetIds,
        List<MerchantStoreProfileDTO.BusinessHour> businessHours,
        Integer promoLevel,
        String promoTitle,
        String promoText,
        String avatarAssetId,
        String businessStatus,
        String expectedVersion,
        CommandContext context) {}
