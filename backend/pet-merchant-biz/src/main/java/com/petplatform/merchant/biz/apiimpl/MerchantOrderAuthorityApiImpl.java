package com.petplatform.merchant.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantMybatis;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantStaffMapper;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.*;
import javax.sql.DataSource;
public final class MerchantOrderAuthorityApiImpl implements MerchantOrderAuthorityApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final MerchantStaffMapper mapper;
    public MerchantOrderAuthorityApiImpl(DataSource source,ScheduleCapacityGuardApi guard) {
        this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);
        mapper=MerchantMybatis.joiningTemplate(source).getMapper(MerchantStaffMapper.class);
    }
    @Override public void requireOwner(String merchant,String store,QueryContext context) {
        guard.requireHeld(store,source);
        if(context==null||context.operatorType()!=OperatorType.USER||context.operatorId()==null) throw denied();
        var row=mapper.lockOwnedScope(IDS.fromApi(merchant),IDS.fromApi(store),IDS.fromApi(context.operatorId()));
        if(row==null) throw denied();
        if(!Set.of("ACTIVE","OFFLINE").contains(row.getMerchantStatus())
                ||!Set.of("ACTIVE","OFFLINE").contains(row.getStoreStatus())) throw denied();
    }
    @Override public void requireExistingOrderAvailability(String merchant,String store,QueryContext context) {
        guard.requireHeld(store,source);
        if(context==null||context.operatorType()!=OperatorType.SYSTEM)throw denied();
        var row=mapper.lockExistingOrderScope(IDS.fromApi(merchant),IDS.fromApi(store));
        if(row==null||!Set.of("ACTIVE","OFFLINE").contains(row.getMerchantStatus())||!Set.of("ACTIVE","OFFLINE").contains(row.getStoreStatus()))throw denied();
    }
    private static ApiException denied(){return new ApiException(CommonApiCodes.FORBIDDEN,"Existing order authority unavailable");}
}
