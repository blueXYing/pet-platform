package com.petplatform.merchant.api.query;
import com.petplatform.common.QueryContext;
/** Current owner authority for existing orders, under the caller's store guard. */
public interface MerchantOrderAuthorityApi {
    default void requireExistingOrderAvailability(String merchantId,String storeId,QueryContext context) {
        throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Existing-order availability provider required");
    }
    void requireOwner(String merchantId, String storeId, QueryContext context);
}
