package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** ORDER tables only. Store facts use locking current reads in the caller's guarded transaction. */
public final class OrderProtectionReadStore {
    private final JdbcTemplate jdbc;

    public OrderProtectionReadStore(DataSource source) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(source, "source is required"));
    }

    public List<OrderRow> readStoreOrders(String storeId) {
        try {
            return jdbc.query("""
                    SELECT id, reservation_id, user_id, merchant_id, store_id, service_id,
                           service_staff_id, fulfillment_type, order_stage, verification_status, version
                    FROM pet_order FORCE INDEX (idx_order_store_stage_created)
                    WHERE store_id = ?
                    ORDER BY id
                    FOR UPDATE
                    """, (rs, index) -> new OrderRow(
                    Long.toString(rs.getLong("id")), Long.toString(rs.getLong("reservation_id")),
                    Long.toString(rs.getLong("user_id")), Long.toString(rs.getLong("merchant_id")),
                    Long.toString(rs.getLong("store_id")), Long.toString(rs.getLong("service_id")),
                    nullableId(rs.getObject("service_staff_id", Long.class)),
                    rs.getString("fulfillment_type"), rs.getString("order_stage"),
                    rs.getString("verification_status"), rs.getLong("version")), Long.parseLong(storeId));
        } catch (DataAccessException failure) {
            throw unavailable("order current read unavailable");
        }
    }

    public List<AssignmentRow> readStoreAssignments(String storeId) {
        try {
            return jdbc.query("""
                    SELECT a.id, a.order_id, a.staff_id, a.is_current, a.version
                    FROM pet_order o FORCE INDEX (idx_order_store_stage_created)
                    STRAIGHT_JOIN order_staff_assignment a FORCE INDEX (idx_assignment_order_state)
                        ON a.order_id = o.id
                    WHERE o.store_id = ?
                    ORDER BY a.order_id, a.id
                    FOR UPDATE
                    """, (rs, index) -> new AssignmentRow(
                    Long.toString(rs.getLong("id")), Long.toString(rs.getLong("order_id")),
                    Long.toString(rs.getLong("staff_id")), rs.getInt("is_current"),
                    rs.getLong("version")), Long.parseLong(storeId));
        } catch (DataAccessException failure) {
            throw unavailable("assignment current read unavailable");
        }
    }

    /**
     * Assignment has no store column; an unowned current row contaminates every store answer.
     * READ_COMMITTED gives this integrity probe a fresh statement view without taking locks on
     * healthy assignments belonging to other stores. Store-scoped rows above remain FOR UPDATE.
     */
    public boolean hasGlobalCurrentOrphan() {
        try {
            return !jdbc.query("""
                    SELECT a.id
                    FROM order_staff_assignment a FORCE INDEX (idx_assignment_current_order)
                    LEFT JOIN pet_order o ON o.id = a.order_id
                    WHERE a.is_current = 1 AND o.id IS NULL
                    LIMIT 1
                    """, (rs, index) -> rs.getLong(1)).isEmpty();
        } catch (DataAccessException failure) {
            throw unavailable("global current assignment check unavailable");
        }
    }

    private static String nullableId(Long id) {
        return id == null ? null : Long.toString(id);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    public record OrderRow(String id, String reservationId, String userId, String merchantId,
            String storeId, String serviceId, String serviceStaffId, String fulfillmentType,
            String orderStage, String verificationStatus, long version) {}

    public record AssignmentRow(String id, String orderId, String staffId,
            int isCurrent, long version) {}
}
