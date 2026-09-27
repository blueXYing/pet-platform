package com.petplatform.merchant.biz.application;

import com.petplatform.common.*;
import com.petplatform.merchant.api.dto.BookingMerchantFacts;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Consumer booking eligibility, without incorrectly requiring the consumer to own the merchant. */
public final class BookingMerchantFactsService {
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final ApplicationReviewFactsReader applications;
    private final MerchantAgreementStore agreements;
    private final JdbcTemplate jdbc;
    public BookingMerchantFactsService(DataSource source,ScheduleCapacityGuardApi guard,ApplicationReviewFactsReader applications){
        this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);
        this.applications=Objects.requireNonNull(applications);agreements=new MerchantAgreementStore(source);jdbc=new JdbcTemplate(source);
    }
    public BookingMerchantFacts read(String storeId,QueryContext context){
        if(context==null)throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"context required");
        long id;
        try{id=new DecimalPublicIdCodec().fromApi(storeId);}catch(IllegalArgumentException bad){throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid store id");}
        guard.requireHeld(storeId,source);
        try {
            var rows=jdbc.query("SELECT m.id,m.merchant_name,m.status merchant_status,s.store_name,s.address,s.status store_status "
                    +"FROM merchant_store s JOIN merchant m ON m.id=s.merchant_id WHERE s.id=? FOR UPDATE",
                    (r,n)->new Row(r.getLong("id"),r.getString("merchant_name"),r.getString("merchant_status"),r.getString("store_name"),r.getString("address"),r.getString("store_status")),id);
            if(rows.isEmpty())throw new ApiException(CommonApiCodes.NOT_FOUND,"merchant store unavailable");
            Row row=rows.getFirst();
            if(row.merchantId<=0 || blank(row.merchantName)||blank(row.storeName)||blank(row.address)
                    || !Set.of("APPLYING","ACTIVE","OFFLINE","FROZEN","CANCELED").contains(row.merchantStatus)
                    || !Set.of("ACTIVE","OFFLINE","FROZEN").contains(row.storeStatus))throw unavailable();
            if(!"ACTIVE".equals(row.merchantStatus))throw new ApiException("MERCHANT_DISABLED","merchant unavailable");
            if(!"ACTIVE".equals(row.storeStatus))throw new ApiException("STORE_DISABLED","store unavailable");
            var anchor=jdbc.queryForList("SELECT id FROM merchant_application WHERE reserved_merchant_id=? FOR UPDATE",Long.class,row.merchantId);
            if(anchor.size()!=1)throw unavailable();
            var review=applications.read(row.merchantId);
            if(review==null || !Set.of("DRAFT","REVIEWING","APPROVED","REJECTED").contains(review.applicationStatus()))throw unavailable();
            if(!"APPROVED".equals(review.applicationStatus()))throw new ApiException("MERCHANT_DISABLED","merchant not approved");
            agreements.joinCurrentTransaction(mapper->{
                var accepted=MerchantAgreementService.acceptedAgreements(mapper,row.merchantId,true);
                if(accepted.isEmpty())throw new ApiException("MERCHANT_DISABLED","merchant not signed");
                MerchantAgreementService.validateDocument(accepted.getFirst(),true);return null;
            });
            return new BookingMerchantFacts(Long.toString(row.merchantId),storeId,row.merchantName,row.storeName,row.address);
        }catch(ApiException known){rollback();throw known;}catch(RuntimeException failed){rollback();throw unavailable();}
    }
    private record Row(long merchantId,String merchantName,String merchantStatus,String storeName,String address,String storeStatus){}
    private static boolean blank(String text){return text==null||text.isBlank();}
    private void rollback(){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"booking merchant facts unavailable");}
}
