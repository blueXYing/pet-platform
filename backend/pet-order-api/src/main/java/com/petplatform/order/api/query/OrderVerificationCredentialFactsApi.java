package com.petplatform.order.api.query;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;
/** ORDER alone decides credential eligibility. locate only routes the caller to the store guard;
 * all authoritative eligibility and reschedule proofs require that guard to be held. */
public interface OrderVerificationCredentialFactsApi {
    Location locate(String orderId, QueryContext context);
    Fact requireEligible(String orderId, String storeId, QueryContext context);
    Location requireRescheduleSource(String orderId,String reservationId,String storeId,String userId,QueryContext context);
    void requireRescheduleCommitted(String orderId,String rescheduleId,String fenceId,OffsetDateTime at,QueryContext context);
    record Location(String orderId,String userId,String merchantId,String storeId,String reservationId) {}
    record Fact(Location location,String orderVersion,int confirmRound,String appointmentStart,String appointmentEnd,
                String pickupStart,String returnStart) {}
}
