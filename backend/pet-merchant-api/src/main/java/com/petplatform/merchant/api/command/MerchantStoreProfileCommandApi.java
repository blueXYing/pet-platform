package com.petplatform.merchant.api.command;

import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;

/** Store profile command face (contract 27 §6.4 supplement). */
public interface MerchantStoreProfileCommandApi {

    MerchantStoreProfileDTO updateStoreProfile(UpdateStoreProfileCommand command);
}
