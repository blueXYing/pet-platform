package com.petplatform.merchant.api.query;
import com.petplatform.common.QueryContext;
/** Current owner authority for existing orders, under the caller's store guard. */
public interface MerchantOrderAuthorityApi {
    void requireOwner(String merchantId, String storeId, QueryContext context);
}
