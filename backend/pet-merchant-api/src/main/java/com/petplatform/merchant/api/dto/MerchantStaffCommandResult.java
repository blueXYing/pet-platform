package com.petplatform.merchant.api.dto;

public record MerchantStaffCommandResult(MerchantStaffDTO staff, boolean created, boolean replayed) {}
