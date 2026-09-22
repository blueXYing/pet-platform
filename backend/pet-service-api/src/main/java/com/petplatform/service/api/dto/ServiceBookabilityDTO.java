package com.petplatform.service.api.dto;

import java.util.List;

/**
 * API07 5.1.1: basic eligibility only - never slot availability nor order success. Internal
 * consumers (ORD/SCH) read reasonCodes; C-side HTTP responses carry no bookability.
 */
public record ServiceBookabilityDTO(
        String serviceId,
        String merchantId,
        String storeId,
        boolean bookable,
        List<String> reasonCodes
) {}
