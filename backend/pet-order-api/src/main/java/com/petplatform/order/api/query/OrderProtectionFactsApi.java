package com.petplatform.order.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import java.util.List;
public interface OrderProtectionFactsApi {
    OrderProtectionSnapshot readStore(String storeId, QueryContext context);
    OrderProtectionSnapshot getByReservations(String storeId, List<String> reservationIds, QueryContext context);
    OrderProtectionSnapshot getCurrentAssignments(String storeId, List<String> affectedStaffIds, QueryContext context);
}
