package com.petplatform.order.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.order.api.query.OrderExpiryFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderExpiryStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import javax.sql.DataSource;
/** Used inside SCH's beforeCommit: same transaction, same guard, authoritative ORDER state and audit. */
public final class OrderExpiryFactsApiImpl implements OrderExpiryFactsApi {
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final OrderExpiryStore orders;
    public OrderExpiryFactsApiImpl(DataSource source,ScheduleCapacityGuardApi guard) {
        this.source=source; this.guard=guard; this.orders=new OrderExpiryStore(source);
    }
    @Override public void assertExpiryCommitted(String orderId,String reservationId,String storeId,QueryContext context) {
        guard.requireHeld(storeId,source);
        try {
            com.petplatform.order.biz.application.OrderExpiryCommitProof.require(source,orderId,reservationId);
            var ids=new DecimalPublicIdCodec(); var row=orders.lock(ids.fromApi(orderId));
            if(row==null || row.reservationId()!=ids.fromApi(reservationId) || row.storeId()!=ids.fromApi(storeId)
                    || !"CANCELED".equals(row.stage()) || !"INIT".equals(row.paymentStatus())
                    || !"UNVERIFIED".equals(row.verificationStatus())
                    || !orders.hasExpiryLog(ids.fromApi(orderId),"TASK:RESERVATION_HOLD_EXPIRE:"+reservationId+":0")) throw new IllegalStateException();
        } catch(RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"ORDER atomic expiry confirmation unavailable");
        }
    }
}
