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
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleMybatis;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleCommandMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Converts an intact original hold to a confirmed reservation only with the paid ORDER. */
public final class ReservationConfirmApiImpl implements ReservationConfirmApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCommandMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final ScheduleProtectionFactsApi facts;
    private final OrderPaymentFactsApi orders;

    public ReservationConfirmApiImpl(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, ScheduleProtectionFactsApi facts,
            OrderPaymentFactsApi orders) {
        this.source = Objects.requireNonNull(source);
        this.mapper = ScheduleMybatis.template(source).getMapper(ScheduleCommandMapper.class);
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
            LocalDateTime now = mapper.utcNow();
            if (now == null) throw unavailable();
            int changed = mapper.confirmReservation(IDS.fromApi(command.reservationId()),
                    IDS.fromApi(command.orderId()), IDS.fromApi(command.storeId()),
                    command.expectedVersion(), deadline, now);
            if (changed != 1) throw unavailable();
            long auditId = ids.nextId();
            if (auditId <= 0) throw unavailable();
            if (mapper.insertSystemAudit(ScheduleSqlRows.values("id", auditId,
                    "reservationId", IDS.fromApi(command.reservationId()),
                    "orderId", IDS.fromApi(command.orderId()), "storeId", IDS.fromApi(command.storeId()),
                    "action", "CONFIRM", "requestId", command.context().requestId().getBytes(StandardCharsets.UTF_8),
                    "traceId", command.context().traceId(), "occurredAt", now)) != 1) throw unavailable();
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

    @Override public void assertRescheduled(String orderId,String reservationId,String storeId,String changeId,long version,
            java.time.OffsetDateTime start,java.time.OffsetDateTime end,boolean releasedAllowed,QueryContext context) {
        guard.requireHeld(storeId,source);
        try {
            var m=ScheduleMybatis.template(source).getMapper(com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleSwapMapper.class);
            String raw=m.snapshot(IDS.fromApi(changeId),IDS.fromApi(orderId),IDS.fromApi(reservationId),IDS.fromApi(storeId),version);
            if(raw==null)throw unavailable();
            var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            var node=json.readTree(raw);
            var recorded=json.treeToValue(node.path("reservation"),com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ReservationFact.class);
            var current=facts.readStore(storeId,context);
            if(current==null||!current.complete())throw unavailable();
            var r=current.reservations().stream().filter(x->reservationId.equals(x.reservationId())).findFirst().orElseThrow(ReservationConfirmApiImpl::unavailable);
            boolean released="RELEASED".equals(r.status());
            if(!orderId.equals(recorded.orderId())||!reservationId.equals(recorded.reservationId())||!storeId.equals(recorded.storeId())
                ||!Long.toString(version).equals(recorded.version())||!"CONFIRMED".equals(recorded.status())
                ||!start.isEqual(r.startAt())||!end.isEqual(r.endAt())||!recorded.startAt().isEqual(start)||!recorded.endAt().isEqual(end)
                ||!recorded.userId().equals(r.userId())||!recorded.merchantId().equals(r.merchantId())||!recorded.serviceId().equals(r.serviceId())
                ||!recorded.fulfillmentType().equals(r.fulfillmentType())||!Objects.equals(recorded.pickupStartAt(),r.pickupStartAt())||!Objects.equals(recorded.returnStartAt(),r.returnStartAt())
                ||!(releasedAllowed&&released||"CONFIRMED".equals(r.status()))||Long.parseLong(r.version())!=version+(released?1:0))throw unavailable();
            var expected=new java.util.HashSet<com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ClaimFact>();
            for(var claim:node.path("claims"))expected.add(json.treeToValue(claim,com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ClaimFact.class));
            var actual=new java.util.HashSet<>(current.claims().stream().filter(x->reservationId.equals(x.reservationId())).toList());
            if(expected.isEmpty()||!expected.equals(actual))throw unavailable();
        } catch(Exception failure){rollbackOnly();throw unavailable();}
    }

    private Hold read(String orderId, String reservationId, String storeId) {
        Map<String, Object> row = mapper.lockReservation(IDS.fromApi(reservationId));
        if (row == null) throw unavailable();
        Hold hold = new Hold(ScheduleSqlRows.number(row, "order_id"),
                ScheduleSqlRows.number(row, "store_id"), ScheduleSqlRows.text(row, "status"),
                ScheduleSqlRows.number(row, "version"), ScheduleSqlRows.dateTime(row, "lock_expire_at"));
        if (hold.orderId() != IDS.fromApi(orderId)
                || hold.storeId() != IDS.fromApi(storeId)) throw unavailable();
        return hold;
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
