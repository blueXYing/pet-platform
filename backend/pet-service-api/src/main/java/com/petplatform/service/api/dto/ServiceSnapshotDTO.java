package com.petplatform.service.api.dto;

import com.petplatform.service.api.enums.FulfillmentType;
import java.math.BigDecimal;

/**
 * API07 5.1 frozen eleven-field projection plus the approved cover display amendment (proposal
 * v0.2 / 2026-09-22 ruling): coverAssetId mirrors the stored binding when present, while
 * coverUrl/coverUrlExpiresAt are populated only for consumer-visible reads (ACTIVE + the
 * four-condition conjunction) through the presigned-url port. A value copy taken at query time.
 */
public record ServiceSnapshotDTO(
        String serviceId,
        String merchantId,
        String storeId,
        String serviceName,
        String categoryId,
        String categoryName,
        BigDecimal salePrice,
        Integer durationMinutes,
        FulfillmentType fulfillmentType,
        String description,
        String coverAssetId,
        String coverUrl,
        String coverUrlExpiresAt
) {}
