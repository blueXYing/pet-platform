package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantCurrentStaffFactsStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/** No profile data or login privileges are exposed through protection facts. */
public final class MerchantCurrentStaffFactsApiImpl implements MerchantCurrentStaffFactsApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final MerchantCurrentStaffFactsStore store;

    public MerchantCurrentStaffFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        store=new MerchantCurrentStaffFactsStore(source,guard);
    }

    @Override public CurrentStoreStaffFacts readStore(String storeId, QueryContext context) {
        if(context==null) throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"query context required");
        long id;
        try { id=IDS.fromApi(storeId); }
        catch (IllegalArgumentException bad) { throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid storeId"); }
        return store.read(storeId,mapper -> {
            var shop=mapper.lockStore(id);
            if(shop==null) throw new ApiException(CommonApiCodes.NOT_FOUND,"merchant store not found");
            if(!Objects.equals(shop.getStoreId(),id) || shop.getMerchantId()==null || shop.getMerchantId()<=0)
                throw unavailable();
            var rows=mapper.lockStaff(id);
            if(rows==null) throw unavailable();
            var facts=new ArrayList<CurrentStaffFact>();
            Set<Long> seen=new HashSet<>();
            for(var row:rows) {
                if(row==null || row.getStaffId()==null || row.getStaffId()<=0 || !seen.add(row.getStaffId())
                        || !Objects.equals(row.getStoreId(),id) || !Objects.equals(row.getMerchantId(),shop.getMerchantId())
                        || !("ACTIVE".equals(row.getEmploymentStatus()) || "INACTIVE".equals(row.getEmploymentStatus()))
                        || row.getServiceEnabled()==null || (row.getServiceEnabled()!=0 && row.getServiceEnabled()!=1)
                        || ("INACTIVE".equals(row.getEmploymentStatus()) && row.getServiceEnabled()==1)
                        || row.getStaffVersion()==null || row.getStaffVersion()<0) throw unavailable();
                facts.add(new CurrentStaffFact(IDS.toApi(row.getStaffId()),IDS.toApi(row.getMerchantId()),storeId,
                        row.getEmploymentStatus(),row.getServiceEnabled()==1,Long.toString(row.getStaffVersion())));
            }
            return new CurrentStoreStaffFacts(IDS.toApi(shop.getMerchantId()),storeId,true,facts);
        });
    }

    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"current merchant staff facts are inconsistent");
    }
}
