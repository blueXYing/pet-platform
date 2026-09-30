package com.petplatform.merchant.api.query;
import com.petplatform.common.QueryContext;
/** Current owner authority for existing orders, under the caller's store guard. */
public interface MerchantOrderAuthorityApi {
    default void requireExistingOrderAvailability(String merchantId,String storeId,QueryContext context) {
        throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Existing-order availability provider required");
    }
    void requireOwner(String merchantId, String storeId, QueryContext context);
    /** Existing material remains readable by its current OWNER while frozen; grants no write. */
    default void requireOwnerRead(String merchantId, String storeId, QueryContext context) {
        throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Owner read authority provider required");
    }
    /** Current resource identity under the store guard; this fact does not grant an action. */
    default ResourceScope requireResourceScope(String merchantId, String storeId, QueryContext context) {
        throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Merchant resource scope provider required");
    }
    record ResourceScope(String merchantId,String storeId,String cityCode,String scopeVersion) {}
}
