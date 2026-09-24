package com.petplatform.merchant.api.command;

import com.petplatform.merchant.api.dto.MerchantStaffCommandResult;

public interface MerchantStaffCommandApi {
    MerchantStaffCommandResult createStaff(CreateMerchantStaffCommand command);
    MerchantStaffCommandResult updateStaff(UpdateMerchantStaffCommand command);
    MerchantStaffCommandResult enableStaff(EnableMerchantStaffCommand command);
}
