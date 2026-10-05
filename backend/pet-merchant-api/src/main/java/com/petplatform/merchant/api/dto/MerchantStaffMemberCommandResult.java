package com.petplatform.merchant.api.dto;

/** Supplement-23 result: the fresh or replayed receipt plus how it was obtained. */
public record MerchantStaffMemberCommandResult(MerchantStaffMemberDTO member, boolean created,
                                               boolean replayed) {}
