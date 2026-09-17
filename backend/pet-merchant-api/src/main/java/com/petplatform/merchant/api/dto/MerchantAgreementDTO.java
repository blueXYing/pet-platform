package com.petplatform.merchant.api.dto;

import java.time.OffsetDateTime;

/**
 * Unsigned reads expose current text; signed reads expose the immutable accepted version.
 * A future HTTP adapter formats acceptedAt in UTC with exactly three fractional digits and omits
 * acceptedVersion/acceptedAt from the NOT_SIGNED JSON representation.
 */
public record MerchantAgreementDTO(
        String merchantId,
        String agreementVersion,
        String content,
        String contentSha256,
        String signingStatus,
        String acceptedVersion,
        OffsetDateTime acceptedAt
) {}
