package com.petplatform.service.api.dto;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Contract 38 authoritative service facts at booking time. Contract 77 §6.5 amendment: the tail
 * carries the flash-price resolution — price is the effective price (flash price when a flash
 * window is live), listPrice is the original service price only under FLASH (null under LIST),
 * flashDiscount is service price − flash price under FLASH and ZERO under LIST.
 */
public record BookingServiceFacts(String serviceId, String merchantId, String storeId,
        String serviceName, String categoryId, String categoryName, BigDecimal price,
        int durationMinutes, String fulfillmentType, String description,
        Set<String> applicablePetTypes, boolean verificationRequired, String version,
        BigDecimal listPrice, String priceSource, String flashPriceId, BigDecimal flashDiscount) {
    public BookingServiceFacts { applicablePetTypes=Set.copyOf(applicablePetTypes); }
}
