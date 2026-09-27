package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.*;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.service.api.dto.BookingServiceFacts;
import com.petplatform.service.api.query.BookingServiceFactsApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The current service price/type is authoritative; caller-supplied quotes are never used. */
public final class BookingServiceFactsApiImpl implements BookingServiceFactsApi {
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final JdbcTemplate jdbc;
    public BookingServiceFactsApiImpl(DataSource source,ScheduleCapacityGuardApi guard){this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);jdbc=new JdbcTemplate(source);}
    @Override public BookingServiceFacts readCurrentService(String storeId,String serviceId,QueryContext context){
        if(context==null)throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"context required");
        long shop=id(storeId),service=id(serviceId);guard.requireHeld(storeId,source);
        try {
            var rows=jdbc.query("SELECT s.id,s.merchant_id,s.store_id,s.service_name,s.category_id,"
                    +"s.price,s.duration_minutes,s.fulfillment_type,s.description,s.applicable_pet_types,s.verification_required,s.version,s.status "
                    +"FROM service_item s WHERE s.id=? FOR UPDATE",(r,n)->{
                String state=r.getString("status");
                if(!Set.of("DRAFT","REVIEWING","ACTIVE","OFFLINE","REJECTED").contains(state))throw unavailable();
                if(r.getLong("store_id")!=shop || !"ACTIVE".equals(state))throw new ApiException("SERVICE_NOT_BOOKABLE","service unavailable");
                String types=r.getString("applicable_pet_types");
                if(types==null||types.isBlank())throw unavailable();
                Set<String> pets=new LinkedHashSet<>();
                for(String type:types.split(",",-1))if(!Set.of("DOG","CAT","EXOTIC","ALL").contains(type)||!pets.add(type))throw unavailable();
                if(pets.contains("ALL")&&pets.size()!=1)throw unavailable();
                BigDecimal price=r.getBigDecimal("price");
                int duration=r.getInt("duration_minutes"),verify=r.getInt("verification_required");
                long merchant=r.getLong("merchant_id"),category=r.getLong("category_id"),version=r.getLong("version");
                // Category is display metadata. A nonlocking current read avoids serializing every
                // booking in a shared category while the actual service row remains locked.
                String categoryName=category>0?jdbc.queryForObject("SELECT category_name FROM service_category WHERE id=?",String.class,category):null;
                if(price==null||price.signum()<=0||price.compareTo(new BigDecimal("9999999999999999.99"))>0
                        ||duration<1||duration>10080||merchant<=0||category<=0||version<0
                        ||(verify!=0&&verify!=1)||blank(r.getString("service_name"))||blank(categoryName))throw unavailable();
                price=price.setScale(2,RoundingMode.UNNECESSARY);
                return new BookingServiceFacts(serviceId,Long.toString(merchant),storeId,r.getString("service_name"),Long.toString(category),categoryName,price,duration,r.getString("fulfillment_type"),r.getString("description"),pets,verify==1,Long.toString(version));
            },service);
            if(rows.isEmpty())throw new ApiException("SERVICE_NOT_FOUND","service unavailable");
            var result=rows.getFirst();
            if(!Set.of("IN_STORE","PICKUP_DELIVERY").contains(result.fulfillmentType()))throw unavailable();
            return result;
        }catch(ApiException known){rollback();throw known;}catch(RuntimeException failed){rollback();throw unavailable();}
    }
    private static long id(String value){try{return new DecimalPublicIdCodec().fromApi(value);}catch(IllegalArgumentException bad){throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid service or store id");}}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private void rollback(){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"booking service facts unavailable");}
}
