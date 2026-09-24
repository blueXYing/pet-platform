package com.petplatform.merchant.api.dto;

import java.util.List;

public record MerchantStaffPageDTO(List<MerchantStaffDTO> items, int page, int pageSize, long total) {
    public MerchantStaffPageDTO {
        items = List.copyOf(items);
    }
}
