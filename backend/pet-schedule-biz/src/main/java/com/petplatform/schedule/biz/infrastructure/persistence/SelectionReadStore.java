package com.petplatform.schedule.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A single read-only RR view of SCH windows and original reservation claims for display. */
public final class SelectionReadStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public SelectionReadStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
    }

    public <T> T read(Function<JdbcTemplate, T> work) {
        try {
            return transaction.execute(status -> work.apply(jdbc));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "schedule selection facts are unavailable");
        }
    }

    public List<WindowRow> windows(JdbcTemplate connection, long storeId, long serviceId,
            LocalDateTime from, LocalDateTime to) {
        return connection.query("SELECT id,merchant_id,store_id,service_id,window_kind,start_at,"
                + "end_at,configured_capacity,status,version FROM schedule_availability_window "
                + "WHERE store_id=? AND service_id=? AND start_at<? AND end_at>? "
                + "ORDER BY start_at,id", (rs, index) -> new WindowRow(
                        rs.getLong("id"), rs.getLong("merchant_id"), rs.getLong("store_id"),
                        rs.getLong("service_id"), rs.getString("window_kind"),
                        rs.getObject("start_at", LocalDateTime.class),
                        rs.getObject("end_at", LocalDateTime.class),
                        rs.getInt("configured_capacity"), rs.getString("status"),
                        rs.getLong("version")), storeId, serviceId, to, from);
    }

    public List<ClaimRow> claims(JdbcTemplate connection, long storeId, long serviceId,
            LocalDateTime from, LocalDateTime to) {
        return connection.query("SELECT r.id reservation_id,r.merchant_id,r.store_id,r.service_id,"
                + "r.fulfillment_type,r.start_at,r.end_at,r.pickup_start_at,r.return_start_at,r.status,"
                + "c.id claim_id,c.window_id,c.store_id claim_store_id,c.service_id claim_service_id,"
                + "c.kind claim_kind,c.start_at claim_start_at,c.end_at claim_end_at,"
                + "w.merchant_id original_merchant_id,w.store_id original_store_id,"
                + "w.service_id original_service_id,w.window_kind original_kind,"
                + "w.start_at original_start_at,w.end_at original_end_at,w.status original_status "
                + "FROM schedule_reservation r LEFT JOIN schedule_reservation_claim c "
                + "ON c.reservation_id=r.id LEFT JOIN schedule_availability_window w ON w.id=c.window_id "
                + "WHERE r.store_id=? AND r.service_id=? AND "
                + "((r.start_at<? AND r.end_at>?) OR EXISTS ("
                + "SELECT 1 FROM schedule_reservation_claim x WHERE x.reservation_id=r.id "
                + "AND x.start_at<? AND x.end_at>?)) "
                + "ORDER BY r.id,c.id", (rs, index) -> new ClaimRow(
                        rs.getLong("reservation_id"), rs.getLong("merchant_id"),
                        rs.getLong("store_id"), rs.getLong("service_id"),
                        rs.getString("fulfillment_type"),
                        rs.getObject("start_at", LocalDateTime.class),
                        rs.getObject("end_at", LocalDateTime.class),
                        rs.getObject("pickup_start_at", LocalDateTime.class),
                        rs.getObject("return_start_at", LocalDateTime.class), rs.getString("status"),
                        nullableLong(rs, "claim_id"), nullableLong(rs, "window_id"),
                        nullableLong(rs, "claim_store_id"), nullableLong(rs, "claim_service_id"),
                        rs.getString("claim_kind"),
                        rs.getObject("claim_start_at", LocalDateTime.class),
                        rs.getObject("claim_end_at", LocalDateTime.class),
                        nullableLong(rs, "original_merchant_id"),
                        nullableLong(rs, "original_store_id"),
                        nullableLong(rs, "original_service_id"), rs.getString("original_kind"),
                        rs.getObject("original_start_at", LocalDateTime.class),
                        rs.getObject("original_end_at", LocalDateTime.class),
                rs.getString("original_status")), storeId, serviceId, to, from, to, from);
    }

    /** Claims naming this service or one of its windows must have an intact parent and owner. */
    public boolean hasBrokenClaimLinks(JdbcTemplate connection, long storeId, long serviceId,
            LocalDateTime from, LocalDateTime to) {
        Integer broken = connection.queryForObject("SELECT COUNT(*) FROM schedule_reservation_claim c "
                + "LEFT JOIN schedule_reservation r ON r.id=c.reservation_id "
                + "LEFT JOIN schedule_availability_window w ON w.id=c.window_id "
                + "WHERE ((c.store_id=? AND c.service_id=?) "
                + "OR (w.store_id=? AND w.service_id=?)) "
                + "AND c.start_at<? AND c.end_at>? AND "
                + "(r.id IS NULL OR w.id IS NULL OR r.store_id<>c.store_id "
                + "OR r.service_id<>c.service_id OR w.store_id<>c.store_id "
                + "OR w.service_id<>c.service_id OR w.window_kind<>c.kind "
                + "OR r.merchant_id<>w.merchant_id)", Integer.class,
                storeId, serviceId, storeId, serviceId, to, from);
        return broken == null || broken > 0;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record WindowRow(long id, long merchantId, long storeId, long serviceId, String kind,
            LocalDateTime start, LocalDateTime end, int configuredCapacity, String status,
            long version) {}

    public record ClaimRow(long reservationId, long merchantId, long storeId, long serviceId,
            String fulfillmentType, LocalDateTime start, LocalDateTime end,
            LocalDateTime pickupStart, LocalDateTime returnStart, String status,
            Long claimId, Long windowId, Long claimStoreId, Long claimServiceId, String claimKind,
            LocalDateTime claimStart, LocalDateTime claimEnd, Long originalMerchantId,
            Long originalStoreId, Long originalServiceId, String originalKind,
            LocalDateTime originalStart, LocalDateTime originalEnd, String originalStatus) {}
}
