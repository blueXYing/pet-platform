package com.petplatform.merchant.api.dto;

/** Supplement-23 result for invitation-phase commands (invite / cancel). */
public record MerchantStaffInvitationCommandResult(MerchantStaffInvitationDTO invitation,
                                                   boolean created, boolean replayed) {}
