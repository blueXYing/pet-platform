package com.petplatform.merchant.api.dto;

/**
 * Order-contact projection of one store (HTTP10 §3.7 supplement, 2026-10-19 ruling). Only the
 * business contact line of the store: {@code phoneMasked} for display (same masking rule as the
 * catalog) and {@code phone} — the raw number, delivered solely on the authenticated,
 * owner-scoped C order detail read for system dialing (wx.makePhoneCall). Either field is null
 * when the store row carries no phone.
 */
public record MerchantStoreContactDTO(String storeId, String phoneMasked, String phone) {}
