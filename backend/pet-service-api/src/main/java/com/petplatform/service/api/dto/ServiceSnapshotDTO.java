package com.petplatform.service.api.dto;

import com.petplatform.service.api.enums.FulfillmentType;
import java.math.BigDecimal;

/** API07 5.1 frozen eleven-field projection; a value copy taken at query time. */
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
        String description
) {}
