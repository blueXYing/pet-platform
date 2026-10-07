package com.petplatform.merchant.api.dto;

import java.util.List;

/** Employee-side invitation page (contract 54 §7), newest first (id DESC). */
public record MerchantStaffInvitationListPageDTO(
        List<MerchantStaffInvitationSummaryDTO> items, int page, int pageSize, long total) {}
