package com.petplatform.merchant.api.dto;

import java.util.List;

/** STAFF membership page; fixed merchantId/storeId Long ascending order, total filtered first. */
public record MerchantStaffMembershipPageDTO(
        List<MerchantStaffMembershipDTO> items, int page, int pageSize, long total) {}
