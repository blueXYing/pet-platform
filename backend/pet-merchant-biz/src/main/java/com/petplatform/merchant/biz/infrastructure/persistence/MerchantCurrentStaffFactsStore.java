package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantCurrentStaffFactsMapper;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins only the guarded caller transaction; no independent read snapshot. */
public final class MerchantCurrentStaffFactsStore {
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final SqlSessionTemplate sessions;

    public MerchantCurrentStaffFactsStore(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source=Objects.requireNonNull(source);
        this.guard=Objects.requireNonNull(guard);
        sessions=MerchantMybatis.joiningTemplate(source);
    }

    public <T> T read(String storeId, Function<MerchantCurrentStaffFactsMapper,T> work) {
        guard.requireHeld(storeId, source);
        try { return work.apply(sessions.getMapper(MerchantCurrentStaffFactsMapper.class)); }
        catch (ApiException known) { markRollback(); throw known; }
        catch (RuntimeException failure) {
            markRollback();
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "current merchant staff facts unavailable");
        }
    }

    private void markRollback() {
        Object resource=TransactionSynchronizationManager.getResource(source);
        if(resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
    }
}
