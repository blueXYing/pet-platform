package com.petplatform.merchant.api.command;

import com.petplatform.merchant.api.dto.MerchantAgreementConsentDTO;

/** Owner-only first-consent command approved by merchant supplement 27 section 6.2. */
public interface MerchantAgreementCommandApi {
    MerchantAgreementConsentDTO consent(MerchantAgreementConsentCommand command);
}
