package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;

public record EnableMerchantStaffCommand(String merchantId, String storeId, String staffId,
                                         String expectedVersion, CommandContext context) {}
