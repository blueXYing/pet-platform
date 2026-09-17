package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantOrderEligibilityDTO;
import com.petplatform.merchant.api.dto.MerchantStaffDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDTO;

/** Approved merchant-domain read contract (Internal API 07 section 4.1 and supplement 27 section 4). */
public interface MerchantQueryApi {

    MerchantStoreDTO getStore(StoreIdQuery query);

    MerchantOrderEligibilityDTO checkOrderEligibility(MerchantOrderEligibilityQuery query);

    MerchantStaffDTO getStaff(MerchantStaffQuery query);
}
