package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.*;
import com.petplatform.order.api.query.OrderExpiryFactsApi;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.dto.ReservationExpiryTypes.ExpireHoldCommand;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleMybatis;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleCommandMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only ORDER's atomic cancellation may expire its original temporary hold. Claims remain history. */
public final class ReservationExpiryApiImpl implements ReservationExpiryApi {
    private final DataSource source;
    private final ScheduleCommandMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final OrderExpiryFactsApi orders;
    private final com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi facts;
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    public ReservationExpiryApiImpl(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,OrderExpiryFactsApi orders,
            com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi facts) {
        this.source=Objects.requireNonNull(source);
        this.mapper=ScheduleMybatis.template(source).getMapper(ScheduleCommandMapper.class);
        this.ids=Objects.requireNonNull(ids); this.guard=Objects.requireNonNull(guard);
        this.orders=Objects.requireNonNull(orders); this.facts=Objects.requireNonNull(facts);
    }
    @Override public void expire(ExpireHoldCommand c) {
        validate(c);
        guard.requireHeld(c.storeId(),source);
        try {
            // Validate while the hold is still active: history readers intentionally allow incomplete old claims.
            var current=facts.readStore(c.storeId(),new QueryContext(c.context().traceId(),OperatorType.SYSTEM,c.context().operatorId()));
            if(current==null || !current.complete() || current.reservations().stream().noneMatch(r -> c.reservationId().equals(r.reservationId()))) throw unavailable();
            Hold row=read(c.orderId(),c.reservationId(),c.storeId());
            if (!"TEMP_LOCKED".equals(row.status()) || row.version()!=c.expectedVersion()
                    || row.expires()==null || !row.expires().toInstant(java.time.ZoneOffset.UTC).equals(c.expectedExpireAt().toInstant())
                    || row.expires().toInstant(java.time.ZoneOffset.UTC).isAfter(c.observedNow().toInstant())) throw unavailable();
            // A caller-supplied future clock cannot cause an early release.
            java.time.LocalDateTime now=mapper.utcNow();
            if (now==null || row.expires().isAfter(now) || c.observedNow().toInstant().isAfter(now.toInstant(java.time.ZoneOffset.UTC))) throw unavailable();
            int changed=mapper.expireReservation(IDS.fromApi(c.reservationId()),
                    c.expectedVersion(),row.expires(),now);
            if(changed!=1) throw unavailable();
            long auditId=ids.nextId();
            if(auditId<=0) throw unavailable();
            mapper.insertSystemAudit(ScheduleSqlRows.values("id",auditId,
                    "reservationId",IDS.fromApi(c.reservationId()),"orderId",IDS.fromApi(c.orderId()),
                    "storeId",IDS.fromApi(c.storeId()),"action","EXPIRE",
                    "requestId",c.context().requestId().getBytes(StandardCharsets.UTF_8),
                    "traceId",c.context().traceId(),"occurredAt",now));
            // The expired hold frees its windows in this same transaction (Contract53 §3).
            WindowSoldOutDeriver.rederive(c.storeId(),
                    mapper.claimWindowIds(IDS.fromApi(c.reservationId())),
                    new QueryContext(c.context().traceId(),OperatorType.SYSTEM,c.context().operatorId()),
                    facts,mapper::setWindowDerivedStatus,now);
            QueryContext context=new QueryContext(c.context().traceId(),OperatorType.SYSTEM,c.context().operatorId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    try {
                        if(readOnly) throw unavailable();
                        assertExpired(c.orderId(),c.reservationId(),c.storeId(),context);
                        orders.assertExpiryCommitted(c.orderId(),c.reservationId(),c.storeId(),context);
                } catch(RuntimeException failure) { rollbackOnly(); throw unavailable(); }
            }
        });
        } catch(RuntimeException failure) { rollbackOnly(); throw unavailable(failure); }
    }
    /** Same failure with the swallowed inner cause chained for operations diagnosis. */
    private static ApiException unavailable(Throwable cause){
        ApiException failure=unavailable();failure.initCause(cause);return failure;
    }
    @Override public void assertExpired(String orderId,String reservationId,String storeId,QueryContext context) {
        guard.requireHeld(storeId,source);
        try {
            if(!"EXPIRED".equals(read(orderId,reservationId,storeId).status())) throw unavailable();
        } catch(RuntimeException failure) { rollbackOnly(); throw unavailable(); }
    }
    private Hold read(String orderId,String reservationId,String storeId) {
        Map<String,Object> columns=mapper.lockReservation(IDS.fromApi(reservationId));
        if(columns==null) throw unavailable();
        Hold row=new Hold(ScheduleSqlRows.number(columns,"order_id"),
                ScheduleSqlRows.number(columns,"store_id"),ScheduleSqlRows.text(columns,"status"),
                ScheduleSqlRows.number(columns,"version"),ScheduleSqlRows.dateTime(columns,"lock_expire_at"));
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
