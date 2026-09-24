package com.petplatform.merchant.api.dto;

import java.util.List;

/** ID-only staff facts. No profile, contact or operator authority is conveyed. */
public record StoreStaffFactsDTO(String storeId, List<String> activeStaffIds) {
    public StoreStaffFactsDTO {
        activeStaffIds = List.copyOf(activeStaffIds);
    }
}
