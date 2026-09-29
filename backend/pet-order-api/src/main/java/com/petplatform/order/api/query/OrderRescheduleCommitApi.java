package com.petplatform.order.api.query;
import com.petplatform.common.QueryContext;
/** SCH's before-commit check that its exchange has an atomic ORDER round-one counterpart. */
public interface OrderRescheduleCommitApi {
 void requireCommitted(String orderId,String storeId,String reservationId,String commandId,String scheduleChangeId,long reservationVersion,QueryContext context);
}
