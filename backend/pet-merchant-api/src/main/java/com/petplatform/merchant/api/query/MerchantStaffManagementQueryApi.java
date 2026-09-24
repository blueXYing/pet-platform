package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantStaffDTO;
import com.petplatform.merchant.api.dto.MerchantStaffPageDTO;

public interface MerchantStaffManagementQueryApi {
    MerchantStaffPageDTO listStaff(MerchantStaffListQuery query);
    MerchantStaffDTO getStaff(MerchantStaffQuery query);
}
