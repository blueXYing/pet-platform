package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;

/** Owner-scoped admission surface approved by HTTP10 workbench selection and CCR-W2-ADMISSION-001. */
public interface MerchantAdmissionQueryApi {

    MerchantMembershipPageDTO listMemberships(MerchantMembershipQuery query);

    MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query);
}
