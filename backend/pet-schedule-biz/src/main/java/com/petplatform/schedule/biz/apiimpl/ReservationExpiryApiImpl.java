package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.*;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.dto.ReservationExpiryTypes.ExpireHoldCommand;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only ORDER's atomic cancellation may expire its original temporary hold. Claims remain history. */
public final class ReservationExpiryApiImpl implements ReservationExpiryApi {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final OrderProtectionFactsApi orders;
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    public ReservationExpiryApiImpl(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,OrderProtectionFactsApi orders) {
        this.source=Objects.requireNonNull(source); this.jdbc=new JdbcTemplate(source);
        this.ids=Objects.requireNonNull(ids); this.guard=Objects.requireNonNull(guard);
        this.orders=Objects.requireNonNull(orders);
    }
    @Override public void expire(ExpireHoldCommand c) {
        validate(c);
        guard.requireHeld(c.storeId(),source);
        try {
            Hold row=read(c.orderId(),c.reservationId(),c.storeId());
            if (!"TEMP_LOCKED".equals(row.status()) || row.version()!=c.expectedVersion()
                    || row.expires()==null || !row.expires().toInstant(java.time.ZoneOffset.UTC).equals(c.expectedExpireAt().toInstant())
                    || row.expires().toInstant(java.time.ZoneOffset.UTC).isAfter(c.observedNow().toInstant())) throw unavailable();
            // A caller-supplied future clock cannot cause an early release.
            java.time.LocalDateTime now=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.time.LocalDateTime.class);
            if (now==null || row.expires().isAfter(now) || c.observedNow().toInstant().isAfter(now.toInstant(java.time.ZoneOffset.UTC))) throw unavailable();
            int changed=jdbc.update("UPDATE schedule_reservation SET status='EXPIRED',version=version+1,updated_at=? "
                    +"WHERE id=? AND status='TEMP_LOCKED' AND version=? AND lock_expire_at=?",
                    now,IDS.fromApi(c.reservationId()),c.expectedVersion(),row.expires());
            if(changed!=1) throw unavailable();
            jdbc.update("INSERT INTO schedule_reservation_audit(id,reservation_id,order_id,actor_user_id,store_id,"
                    +"action,request_id,trace_id,occurred_at,actor_type) VALUES(?,?,?,NULL,?,'EXPIRE',?,?,?,'SYSTEM')",
                    ids.nextId(),IDS.fromApi(c.reservationId()),IDS.fromApi(c.orderId()),IDS.fromApi(c.storeId()),
                    c.context().requestId().getBytes(StandardCharsets.UTF_8),c.context().traceId(),now);
            QueryContext context=new QueryContext(c.context().traceId(),OperatorType.SYSTEM,c.context().operatorId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    try {
                        if(readOnly) throw unavailable();
                        assertExpired(c.orderId(),c.reservationId(),c.storeId(),context);
                        var facts=orders.getByReservations(c.storeId(),List.of(c.reservationId()),context);
                        if(facts==null || !facts.complete() || facts.items().size()!=1) throw unavailable();
                        var order=facts.items().getFirst();
                        if(!c.orderId().equals(order.orderId()) || !c.reservationId().equals(order.reservationId())
                                || !"CANCELED".equals(order.orderStage()) || order.protectRequired()) throw unavailable();
                    } catch(RuntimeException failure) { rollbackOnly(); throw unavailable(); }
                }
            });
        } catch(RuntimeException failure) { rollbackOnly(); throw unavailable(); }
    }
    @Override public void assertExpired(String orderId,String reservationId,String storeId,QueryContext context) {
        guard.requireHeld(storeId,source);
        try {
            if(!"EXPIRED".equals(read(orderId,reservationId,storeId).status())) throw unavailable();
        } catch(RuntimeException failure) { rollbackOnly(); throw unavailable(); }
    }
    private Hold read(String orderId,String reservationId,String storeId) {
        List<Hold> rows=jdbc.query("SELECT order_id,store_id,status,version,lock_expire_at FROM schedule_reservation WHERE id=? FOR UPDATE",
                (rs,n)->new Hold(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4),rs.getObject(5,java.time.LocalDateTime.class)),IDS.fromApi(reservationId));
        if(rows.size()!=1) throw unavailable();
        Hold row=rows.getFirst();
        if(row.orderId()!=IDS.fromApi(orderId) || row.storeId()!=IDS.fromApi(storeId)) throw unavailable();
        return row;
    }
    private static void validate(ExpireHoldCommand c) {
        try {
            if(c==null || c.context()==null || c.context().operatorType()!=OperatorType.SYSTEM) throw new IllegalArgumentException();
            IDS.fromApi(c.orderId()); IDS.fromApi(c.reservationId()); IDS.fromApi(c.storeId());
            PublicContractChecks.requireCommandRequestId(c.context());
            PublicContractChecks.requireMillisecondPrecision(c.expectedExpireAt());
            PublicContractChecks.requireMillisecondPrecision(c.observedNow());
            if(c.expectedVersion()!=0 || !("TASK:RESERVATION_HOLD_EXPIRE:"+c.reservationId()+":0").equals(c.context().requestId())) throw new IllegalArgumentException();
            if(c.context().traceId()!=null && (c.context().traceId().isBlank() || c.context().traceId().getBytes(StandardCharsets.UTF_8).length>128
                    || c.context().traceId().codePoints().anyMatch(Character::isISOControl))) throw new IllegalArgumentException();
        } catch(RuntimeException failure) { throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid booking expiry command"); }
    }
    private void rollbackOnly() {
        Object resource=TransactionSynchronizationManager.getResource(source);
        if(resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
    }
    private static ApiException unavailable() { return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"reservation expiry facts unavailable"); }
    private record Hold(long orderId,long storeId,String status,long version,java.time.LocalDateTime expires) {}
}
