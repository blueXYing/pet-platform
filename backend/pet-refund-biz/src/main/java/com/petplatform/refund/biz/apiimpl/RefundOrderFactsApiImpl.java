package com.petplatform.refund.biz.apiimpl;

import com.petplatform.common.*;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.refund.biz.infrastructure.persistence.RefundExecutionStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import javax.sql.DataSource;

public final class RefundOrderFactsApiImpl implements RefundOrderFactsApi {
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final RefundExecutionStore store;
    public RefundOrderFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source = source; this.guard = guard; this.store = new RefundExecutionStore(source);
    }
    @Override public Fact findByOrder(String orderId, String storeId, QueryContext context) {
        var ids = new DecimalPublicIdCodec();
        long id = ids.fromApi(orderId); ids.fromApi(storeId);
        if (context == null || context.operatorType() != OperatorType.SYSTEM)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "SYSTEM refund facts only");
        guard.requireHeld(storeId, source);
        return new Fact(store.lockPresence(id).stream().map(row -> {
            if (row.id == null || row.id <= 0 || row.orderId == null || row.orderId != id
                    || row.status == null || row.status.isBlank())
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "Invalid refund fact");
            return new Refund(ids.toApi(row.id), orderId, row.status);
        }).toList());
    }
}
