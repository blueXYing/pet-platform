package com.petplatform.merchant.api.dto;

import java.util.List;

/** OWNER member page for one store; members are those bound through contract-54 confirm. */
public record MerchantStaffMemberPageDTO(
        List<MerchantStaffMemberDTO> items, int page, int pageSize, long total) {}
