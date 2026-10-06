package com.petplatform.merchant.api.dto;

import java.util.List;

/** OWNER invitation page for one store, newest first. */
public record MerchantStaffInvitationPageDTO(
        List<MerchantStaffInvitationDTO> items, int page, int pageSize, long total) {}
