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
        requireOwnerStatus(merchant,store,context,Set.of("ACTIVE","OFFLINE"));
    }
    @Override public void requireOwnerRead(String merchant,String store,QueryContext context) {
        requireOwnerStatus(merchant,store,context,Set.of("ACTIVE","OFFLINE","FROZEN"));
    }
    private void requireOwnerStatus(String merchant,String store,QueryContext context,Set<String> statuses) {
        guard.requireHeld(store,source);
        if(context==null||context.operatorType()!=OperatorType.USER||context.operatorId()==null) throw denied();
        var row=mapper.lockOwnedScope(IDS.fromApi(merchant),IDS.fromApi(store),IDS.fromApi(context.operatorId()));
        if(row==null) throw denied();
        if(row.getMerchantStatus()==null||row.getStoreStatus()==null
                ||!statuses.contains(row.getMerchantStatus())||!statuses.contains(row.getStoreStatus())) throw denied();
    }
    @Override public void requireExistingOrderAvailability(String merchant,String store,QueryContext context) {
        guard.requireHeld(store,source);
        if(context==null||context.operatorType()!=OperatorType.SYSTEM)throw denied();
        var row=mapper.lockExistingOrderScope(IDS.fromApi(merchant),IDS.fromApi(store));
        if(row==null||!Set.of("ACTIVE","OFFLINE").contains(row.getMerchantStatus())||!Set.of("ACTIVE","OFFLINE").contains(row.getStoreStatus()))throw denied();
    }
    private static ApiException denied(){return new ApiException(CommonApiCodes.FORBIDDEN,"Existing order authority unavailable");}
    @Override public ResourceScope requireResourceScope(String merchant,String store,QueryContext context) {
        guard.requireHeld(store,source);
        if(context==null||context.operatorType()==null)throw denied();
        var row=mapper.lockOrderResourceScope(IDS.fromApi(merchant),IDS.fromApi(store));
        if(row==null||row.getMerchantStatus()==null||row.getStoreStatus()==null||row.getCityCode()==null
                ||!row.getCityCode().matches("[a-z][a-z0-9_-]{0,31}")
                ||row.getMerchantVersion()==null||row.getStoreVersion()==null||row.getProfileVersion()==null
                ||row.getMerchantVersion()<0||row.getStoreVersion()<0||row.getProfileVersion()<0
                ||!Set.of("ACTIVE","OFFLINE","FROZEN").contains(row.getMerchantStatus())
                ||!Set.of("ACTIVE","OFFLINE","FROZEN").contains(row.getStoreStatus()))
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Merchant scope facts unavailable");
        try {
            String version=merchant+":"+store+":"+row.getMerchantVersion()+":"+row.getStoreVersion()+":"+row.getProfileVersion()+":"+row.getCityCode();
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(version.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new ResourceScope(merchant,store,row.getCityCode(),digest);
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
