package com.petplatform.merchant.api.dto;

import java.util.List;

/** Owner-filtered membership page; fixed merchantId/storeId Long ascending order. */
public record MerchantMembershipPageDTO(
        List<MerchantMembershipDTO> items, int page, int pageSize, long total) {}
