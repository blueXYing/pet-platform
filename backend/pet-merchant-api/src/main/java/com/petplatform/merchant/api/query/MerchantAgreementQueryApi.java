package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantAgreementDTO;

/** Owner-only electronic-agreement read capability approved by merchant supplement 27 section 6.2. */
public interface MerchantAgreementQueryApi {
    MerchantAgreementDTO getAgreement(MerchantAgreementQuery query);
}
