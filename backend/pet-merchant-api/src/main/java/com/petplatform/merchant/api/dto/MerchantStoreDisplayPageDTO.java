package com.petplatform.merchant.api.dto;

import java.util.List;

/** STR-D6 display page: the standard envelope; {@code total} counts visible stores only. */
public record MerchantStoreDisplayPageDTO(
        List<MerchantStoreDisplayDTO> items, int page, int pageSize, long total) {

    public MerchantStoreDisplayPageDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
