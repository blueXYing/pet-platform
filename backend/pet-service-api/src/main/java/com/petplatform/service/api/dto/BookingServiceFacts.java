package com.petplatform.service.api.dto;
import java.math.BigDecimal;
import java.util.Set;
public record BookingServiceFacts(String serviceId, String merchantId, String storeId,
        String serviceName, String categoryId, String categoryName, BigDecimal price,
        int durationMinutes, String fulfillmentType, String description,
        Set<String> applicablePetTypes, boolean verificationRequired, String version) {
    public BookingServiceFacts { applicablePetTypes=Set.copyOf(applicablePetTypes); }
}
