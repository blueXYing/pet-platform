package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderProtectionReadMapper;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;

/** ORDER tables only. Store facts use locking current reads in the caller's guarded transaction. */
public final class OrderProtectionReadStore {
    private final OrderProtectionReadMapper mapper;

    public OrderProtectionReadStore(DataSource source) {
        mapper = OrderMybatis.template(source).getMapper(OrderProtectionReadMapper.class);
    }

    public List<OrderRow> readStoreOrders(String storeId) {
        try {
            return mapper.readStoreOrders(Long.parseLong(storeId)).stream().map(row ->
                    new OrderRow(row.id.toString(), row.reservationId.toString(),
                            row.userId.toString(), row.merchantId.toString(),
                            row.storeId.toString(), row.serviceId.toString(),
                            nullableId(row.serviceStaffId), row.fulfillmentType, row.orderStage,
                            row.verificationStatus, row.version)).toList();
        } catch (DataAccessException failure) {
            throw unavailable("order current read unavailable");
        }
    }

    public List<AssignmentRow> readStoreAssignments(String storeId) {
        try {
            return mapper.readStoreAssignments(Long.parseLong(storeId)).stream().map(row ->
                    new AssignmentRow(row.id.toString(), row.orderId.toString(),
                            row.staffId.toString(), row.isCurrent, row.version)).toList();
        } catch (DataAccessException failure) {
            throw unavailable("assignment current read unavailable");
        }
    }

    /**
     * Assignment has no store column; an unowned current row contaminates every store answer.
     * READ_COMMITTED gives this probe a fresh statement view without locking other stores.
     */
    public boolean hasGlobalCurrentOrphan() {
        try {
            return mapper.findGlobalCurrentOrphan() != null;
        } catch (DataAccessException failure) {
            throw unavailable("global current assignment check unavailable");
        }
    }

    private static String nullableId(Long id) { return id == null ? null : id.toString(); }
    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    public record OrderRow(String id, String reservationId, String userId, String merchantId,
            String storeId, String serviceId, String serviceStaffId, String fulfillmentType,
            String orderStage, String verificationStatus, long version) {}
    public record AssignmentRow(String id, String orderId, String staffId,
            int isCurrent, long version) {}
}
