package com.petplatform.schedule.api.protection;
import com.petplatform.common.QueryContext;
import java.util.List;
import javax.sql.DataSource;
/** Infrastructure-only contract: caller already owns the shared writable transaction. */
public interface ScheduleCapacityGuardApi {
    void acquire(List<String> storeIds, QueryContext context);
    void requireHeld(String storeId, DataSource callerSource);
}
