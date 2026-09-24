package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.StoreStaffFactsDTO;

/** Internal store staff facts for schedule capacity; never exposed as a client route. */
public interface MerchantStoreStaffFactsApi {
    StoreStaffFactsDTO listActiveStoreStaffFacts(StoreStaffFactsQuery query);
}
