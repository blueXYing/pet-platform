package com.petplatform.schedule.api.protection;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.StoreScheduleFacts;
/** Current SCH-owned facts; never calls ORDER or opens another transaction. */
public interface ScheduleProtectionFactsApi {
    StoreScheduleFacts readStore(String storeId, QueryContext context);
}
