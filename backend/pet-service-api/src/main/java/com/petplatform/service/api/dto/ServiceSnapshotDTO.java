package com.petplatform.service.api.dto;

import com.petplatform.service.api.enums.FulfillmentType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * API07 5.1 frozen eleven-field projection plus the approved cover display amendment (proposal
 * v0.2 / 2026-09-22 ruling): coverAssetId mirrors the stored binding when present, while
 * coverUrl/coverUrlExpiresAt are populated only for consumer-visible reads (ACTIVE + the
 * four-condition conjunction) through the presigned-url port. A value copy taken at query time.
 *
 * <p>Contract 77 §5 amendment: the tail carries the consumer flash-price view — priceSource LIST
 * or FLASH, the crossed-out listPrice and the effective flashPrice, the window and the remaining
 * quota. With no flash price or the switch off every tail field is null (priceSource stays LIST)
 * and behavior is byte-identical to the pre-flash projection.
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
        String coverUrlExpiresAt,
        String priceSource,
        BigDecimal listPrice,
        BigDecimal flashPrice,
        OffsetDateTime flashStartAt,
        OffsetDateTime flashEndAt,
        Integer flashRemaining
) {}
