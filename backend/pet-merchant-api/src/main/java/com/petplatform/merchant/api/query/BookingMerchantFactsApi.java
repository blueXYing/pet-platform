package com.petplatform.merchant.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.BookingMerchantFacts;
public interface BookingMerchantFactsApi {
    BookingMerchantFacts readCurrentStore(String storeId, QueryContext context);
}
