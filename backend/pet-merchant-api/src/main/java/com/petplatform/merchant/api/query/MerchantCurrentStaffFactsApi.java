package com.petplatform.merchant.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
/** All staff, including known inactive staff, in the caller's guarded current transaction. */
public interface MerchantCurrentStaffFactsApi {
    CurrentStoreStaffFacts readStore(String storeId, QueryContext context);
}
