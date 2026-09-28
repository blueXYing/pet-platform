package com.petplatform.service.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.BookingServiceFacts;
public interface BookingServiceFactsApi {
    BookingServiceFacts readCurrentService(String storeId, String serviceId, QueryContext context);
}
