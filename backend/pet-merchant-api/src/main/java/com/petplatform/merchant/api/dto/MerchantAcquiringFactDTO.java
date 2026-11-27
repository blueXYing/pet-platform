package com.petplatform.merchant.api.dto;

/**
 * Facts behind the "开通收款" admission check (Ruling 44 §2: archive normal, application approved,
 * platform agreement signed). Identifiers are decimal strings; nothing sensitive is exposed.
 */
public record MerchantAcquiringFactDTO(
    String merchantId,
    String ownerUserId,
    String merchantName,
    String merchantStatus,
    String merchantTypeCode,
    String cityCode,
    String applicationStatus,
    String signingStatus) {}
