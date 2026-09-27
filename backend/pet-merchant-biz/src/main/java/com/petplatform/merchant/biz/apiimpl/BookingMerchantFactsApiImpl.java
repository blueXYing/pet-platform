package com.petplatform.merchant.biz.apiimpl;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.BookingMerchantFacts;
import com.petplatform.merchant.api.query.BookingMerchantFactsApi;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.BookingMerchantFactsService;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import javax.sql.DataSource;
public final class BookingMerchantFactsApiImpl implements BookingMerchantFactsApi {
    private final BookingMerchantFactsService service;
    public BookingMerchantFactsApiImpl(DataSource source,ScheduleCapacityGuardApi guard,ApplicationReviewFactsReader applications){service=new BookingMerchantFactsService(source,guard,applications);}
    @Override public BookingMerchantFacts readCurrentStore(String storeId,QueryContext context){return service.read(storeId,context);}
}
