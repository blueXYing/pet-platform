package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;

public record UpdateMerchantStaffCommand(String merchantId, String storeId, String staffId,
                                         String staffName, String phone, String expectedVersion,
                                         CommandContext context) {}
