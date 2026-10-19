package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;

/**
 * Store profile read face (contract 27 §6.4 supplement). The subject is the session user;
 * the coordinates must name a store owned by that user's merchant (anti-enumeration: absent
 * and foreign stores are the same COMMON_NOT_FOUND). Existing-material read family: an
 * OFFLINE/FROZEN store keeps its profile readable; writes keep the ACTIVE gate.
 */
public interface MerchantStoreProfileQueryApi {

    MerchantStoreProfileDTO getStoreProfile(StoreProfileQuery query);

    /** merchantId/storeId are canonical decimal strings; the subject is the session owner. */
    record StoreProfileQuery(String merchantId, String storeId, QueryContext context) {}
}
