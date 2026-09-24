package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;

public record CreateMerchantStaffCommand(String merchantId, String storeId, String staffName,
                                         String phone, String employmentStatus, Boolean serviceEnabled,
                                         CommandContext context) {}
