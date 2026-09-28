package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.dto.ReservationConfirmTypes.ConfirmReservationCommand;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Converts an intact original hold to a confirmed reservation only with the paid ORDER. */
public final class ReservationConfirmApiImpl implements ReservationConfirmApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final ScheduleProtectionFactsApi facts;
    private final OrderPaymentFactsApi orders;

    public ReservationConfirmApiImpl(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, ScheduleProtectionFactsApi facts,
            OrderPaymentFactsApi orders) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.ids = Objects.requireNonNull(ids);
        this.guard = Objects.requireNonNull(guard);
        this.facts = Objects.requireNonNull(facts);
        this.orders = Objects.requireNonNull(orders);
    }

    @Override public void confirm(ConfirmReservationCommand command) {
        validate(command);
        guard.requireHeld(command.storeId(), source);
        try {
            QueryContext context = context(command);
            // Verify original windows and all active claims before changing their parent status.
            var current = facts.readStore(command.storeId(), context);
            if (current == null || !current.complete() || current.reservations().stream()
                    .noneMatch(row -> command.reservationId().equals(row.reservationId()))) {
                throw unavailable();
            }
            Hold row = read(command.orderId(), command.reservationId(), command.storeId());
            LocalDateTime deadline = LocalDateTime.ofInstant(
                    command.expectedExpireAt().toInstant(), ZoneOffset.UTC);
            if (!"TEMP_LOCKED".equals(row.status()) || row.version() != command.expectedVersion()
                    || !deadline.equals(row.expireAt())) throw unavailable();
            LocalDateTime now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class);
            if (now == null) throw unavailable();
            int changed = jdbc.update("""
                    UPDATE schedule_reservation SET status='CONFIRMED',version=version+1,
                                                    updated_at=?
                    WHERE id=? AND order_id=? AND store_id=? AND status='TEMP_LOCKED'
                      AND version=? AND lock_expire_at=?
                    """, now, IDS.fromApi(command.reservationId()),
                    IDS.fromApi(command.orderId()), IDS.fromApi(command.storeId()),
                    command.expectedVersion(), deadline);
            if (changed != 1) throw unavailable();
            long auditId = ids.nextId();
            if (auditId <= 0) throw unavailable();
            if (jdbc.update("""
                    INSERT INTO schedule_reservation_audit
                      (id,reservation_id,order_id,actor_user_id,store_id,action,request_id,
                       trace_id,occurred_at,actor_type)
                    VALUES (?,?,?,NULL,?,'CONFIRM',?,?,?,'SYSTEM')
                    """, auditId, IDS.fromApi(command.reservationId()),
                    IDS.fromApi(command.orderId()), IDS.fromApi(command.storeId()),
                    command.context().requestId().getBytes(StandardCharsets.UTF_8),
                    command.context().traceId(), now) != 1) throw unavailable();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    try {
                        if (readOnly) throw unavailable();
                        assertConfirmed(command.orderId(), command.reservationId(),
                                command.storeId(), context);
                        orders.assertPaymentCommitted(command.orderId(), command.reservationId(),
                                command.storeId(), context);
                    } catch (RuntimeException failure) {
                        rollbackOnly();
                        throw unavailable();
                    }
                }
            });
        } catch (RuntimeException failure) {
            rollbackOnly();
            throw unavailable();
        }
    }

    @Override public void assertConfirmed(String orderId, String reservationId,
            String storeId, QueryContext context) {
        guard.requireHeld(storeId, source);
        try {
            if (!"CONFIRMED".equals(read(orderId, reservationId, storeId).status()))
                throw unavailable();
        } catch (RuntimeException failure) {
            rollbackOnly();
            throw unavailable();
        }
    }

    private Hold read(String orderId, String reservationId, String storeId) {
        List<Hold> rows = jdbc.query("""
                SELECT order_id,store_id,status,version,lock_expire_at
                FROM schedule_reservation WHERE id=? FOR UPDATE
                """, (rs, index) -> new Hold(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getLong(4), rs.getObject(5, LocalDateTime.class)),
                IDS.fromApi(reservationId));
        if (rows.size() != 1 || rows.getFirst().orderId() != IDS.fromApi(orderId)
                || rows.getFirst().storeId() != IDS.fromApi(storeId)) throw unavailable();
        return rows.getFirst();
    }

    private static void validate(ConfirmReservationCommand command) {
        try {
            if (command == null || command.context() == null
                    || command.context().operatorType() != OperatorType.SYSTEM) throw new IllegalArgumentException();
            IDS.fromApi(command.orderId()); IDS.fromApi(command.reservationId());
            IDS.fromApi(command.storeId());
            PublicContractChecks.requireCommandRequestId(command.context());
            PublicContractChecks.requireMillisecondPrecision(command.expectedExpireAt());
            if (command.expectedVersion() != 0
                    || !command.context().requestId().equals("EVENT:PAYMENT_SUCCEEDED:"
                            + command.orderId() + ":" + command.reservationId()))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,
                    "invalid reservation confirmation command");
        }
    }

    private static QueryContext context(ConfirmReservationCommand command) {
        return new QueryContext(command.context().traceId(), OperatorType.SYSTEM,
                command.context().operatorId());
    }
    private void rollbackOnly() {
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "reservation confirmation facts unavailable");
    }
    private record Hold(long orderId, long storeId, String status, long version,
            LocalDateTime expireAt) {}
}
