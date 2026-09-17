package com.petplatform.merchant.api.dto;

import java.time.OffsetDateTime;

/** Stable minimal receipt; a future HTTP adapter renders acceptedAt as UTC with three fractional digits. */
public record MerchantAgreementConsentDTO(
        String merchantId,
        String agreementVersion,
        OffsetDateTime acceptedAt,
        String signingStatus
) {}
