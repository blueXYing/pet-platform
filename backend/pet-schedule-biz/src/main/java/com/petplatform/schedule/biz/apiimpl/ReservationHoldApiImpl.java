package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.schedule.api.command.ReservationHoldApi;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HeldClaim;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldResult;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.CapacityProofQuery;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ReservationFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.StoreScheduleFacts;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl.HoldProofPlan;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl.ProvenClaim;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes one guarded temporary reservation, which cannot commit without its ORDER parent. */
public final class ReservationHoldApiImpl implements ReservationHoldApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final ScheduleProtectionFactsApi facts;
    private final ScheduleCapacityProofApiImpl proof;
    private final OrderProtectionFactsApi order;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public ReservationHoldApiImpl(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, ScheduleProtectionFactsApi facts,
            ScheduleCapacityProofApiImpl proof, OrderProtectionFactsApi order, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.ids = Objects.requireNonNull(ids);
        this.guard = Objects.requireNonNull(guard);
        this.facts = Objects.requireNonNull(facts);
        this.proof = Objects.requireNonNull(proof);
        this.order = Objects.requireNonNull(order);
        this.clock = Objects.requireNonNull(clock);
        this.jdbc = new JdbcTemplate(source);
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        QueryContext query = validate(command);
        guard.requireHeld(command.storeId(), source);
        try {
            if (!jdbc.query("SELECT id FROM schedule_reservation WHERE order_id=? FOR UPDATE",
                    (rs, n) -> rs.getLong(1), id(command.orderId())).isEmpty()) {
                throw new ApiException(CommonApiCodes.CONFLICT, "order already has a reservation");
            }
            HoldProofPlan plan = proof.prepareForHold(new CapacityProofQuery(
                    command.storeId(), command.serviceId(), command.fulfillmentType(),
                    command.appointmentStart(), command.appointmentEnd(),
                    command.selectedGeneralWindowId(), command.selectedPickupWindowId(),
                    command.selectedReturnWindowId(), query));
            for (ProvenClaim claim : plan.claims()) {
                if (!command.merchantId().equals(claim.merchantId())) {
                    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                            "selected window belongs to another merchant");
                }
            }
            OffsetDateTime start;
            OffsetDateTime end;
            OffsetDateTime pickup = null;
            OffsetDateTime returning = null;
            if ("IN_STORE".equals(command.fulfillmentType())) {
                if (plan.claims().size() != 1 || !"GENERAL".equals(plan.claims().getFirst().kind())) {
                    throw unavailable("GENERAL proof returned invalid claims");
                }
                start = command.appointmentStart();
                end = command.appointmentEnd();
            } else {
                if (plan.claims().size() != 2 || !"PICKUP".equals(plan.claims().getFirst().kind())
                        || !"RETURN".equals(plan.claims().get(1).kind())) {
                    throw unavailable("pickup proof returned invalid claims");
                }
                pickup = command.pickupStart();
                returning = command.returnStart();
                if (!pickup.isEqual(plan.claims().getFirst().startAt())
                        || !returning.isEqual(plan.claims().get(1).startAt())) {
                    throw new ApiException(CommonApiCodes.CONFLICT, "selected window start changed");
                }
                start = pickup;
                end = plan.claims().stream().map(ProvenClaim::endAt)
                        .max(OffsetDateTime::compareTo).orElseThrow();
            }
            long reservationId = nextId();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            OffsetDateTime expires = now.plus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC);
            jdbc.update("INSERT INTO schedule_reservation(id,order_id,user_id,merchant_id,store_id,"
                    + "service_id,fulfillment_type,start_at,end_at,pickup_start_at,return_start_at,"
                    + "status,lock_token,lock_expire_at,capacity_snapshot,qualified_staff_count_snapshot,"
                    + "version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,"
                    + "'TEMP_LOCKED',?,?,?,?,0,?,?)",
                    reservationId, id(command.orderId()), id(command.userId()),
                    id(command.merchantId()), id(command.storeId()), id(command.serviceId()),
                    command.fulfillmentType(), timestamp(start), timestamp(end), timestamp(pickup),
                    timestamp(returning), UUID.randomUUID().toString(), timestamp(expires),
                    Math.min(plan.configuredCapacity(), plan.qualifiedStaffCount()),
                    plan.qualifiedStaffCount(),
                    Timestamp.from(now), Timestamp.from(now));
            List<HeldClaim> claims = new ArrayList<>(plan.claims().size());
            for (ProvenClaim selected : plan.claims()) {
                long claimId = nextId();
                jdbc.update("INSERT INTO schedule_reservation_claim(id,reservation_id,window_id,"
                        + "store_id,service_id,kind,start_at,end_at) VALUES(?,?,?,?,?,?,?,?)",
                        claimId, reservationId, id(selected.windowId()), id(command.storeId()),
                        id(command.serviceId()), selected.kind(), timestamp(selected.startAt()),
                        timestamp(selected.endAt()));
                claims.add(new HeldClaim(IDS.toApi(claimId), selected.windowId(), selected.kind(),
                        selected.startAt(), selected.endAt()));
            }
            jdbc.update("INSERT INTO schedule_reservation_audit(id,reservation_id,order_id,"
                    + "actor_user_id,store_id,action,request_id,trace_id,occurred_at) "
                    + "VALUES(?,?,?,?,?,'HOLD',?,?,?)", nextId(), reservationId,
                    id(command.orderId()), id(command.userId()), id(command.storeId()),
                    command.context().requestId().getBytes(StandardCharsets.UTF_8),
                    command.context().traceId(), Timestamp.from(now));
            String reservation = IDS.toApi(reservationId);
            registerCommitProof(command, query, reservation);
            return new HoldResult(reservation, command.orderId(), start, end, expires, claims);
        } catch (ApiException known) {
            rollbackOnly();
            throw known;
        } catch (DuplicateKeyException duplicate) {
            rollbackOnly();
            throw new ApiException(CommonApiCodes.CONFLICT, "reservation already exists");
        } catch (RuntimeException failed) {
            rollbackOnly();
            throw unavailable("reservation hold dependency unavailable");
        }
    }

    private void registerCommitProof(HoldCommand command, QueryContext query, String reservationId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                try {
                    if (readOnly) throw unavailable("hold cannot commit read-only");
                    guard.requireHeld(command.storeId(), source);
                    OrderProtectionSnapshot snapshot = order.getByReservations(command.storeId(),
                            List.of(reservationId), query);
                    if (snapshot == null || !snapshot.complete() || snapshot.items().size() != 1) {
                        throw unavailable("order binding is incomplete");
                    }
                    OrderProtectionFact linked = snapshot.items().getFirst();
                    if (!reservationId.equals(linked.reservationId())
                            || !command.orderId().equals(linked.orderId())
                            || !command.userId().equals(linked.userId())
                            || !command.merchantId().equals(linked.merchantId())
                            || !command.storeId().equals(linked.storeId())
                            || !command.serviceId().equals(linked.serviceId())
                            || !command.fulfillmentType().equals(linked.fulfillmentType())) {
                        throw unavailable("reservation and order binding differ");
                    }
                    StoreScheduleFacts schedule = facts.readStore(command.storeId(), query);
                    ReservationFact held = schedule.reservations().stream()
                            .filter(item -> reservationId.equals(item.reservationId()))
                            .findFirst().orElseThrow(() -> unavailable("reservation disappeared"));
                    if (!command.orderId().equals(held.orderId())
                            || !command.userId().equals(held.userId())
                            || !command.merchantId().equals(held.merchantId())
                            || !command.serviceId().equals(held.serviceId())) {
                        throw unavailable("reservation changed before commit");
                    }
                } catch (RuntimeException failed) {
                    rollbackOnly();
                    throw unavailable("reservation requires matching committed order");
                }
            }
        });
    }

    private static QueryContext validate(HoldCommand command) {
        if (command == null || command.context() == null) throw invalid("hold context is required");
        CommandContext context = command.context();
        id(command.orderId()); id(command.userId()); id(command.merchantId());
        id(command.storeId()); id(command.serviceId());
        if (context.operatorType() != OperatorType.USER
                || !command.userId().equals(context.operatorId())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "hold user differs from actor");
        }
        if (context.requestId() == null || context.requestId().isBlank()
                || context.requestId().getBytes(StandardCharsets.UTF_8).length > 512
                || context.traceId() == null
                || context.traceId().getBytes(StandardCharsets.UTF_8).length > 128) {
            throw invalid("hold request metadata is invalid");
        }
        if ("IN_STORE".equals(command.fulfillmentType())) {
            if (!interval(command.appointmentStart(), command.appointmentEnd())
                    || command.pickupStart() != null || command.returnStart() != null
                    || command.selectedPickupWindowId() != null
                    || command.selectedReturnWindowId() != null) {
                throw invalid("invalid GENERAL hold interval");
            }
        } else if ("PICKUP_DELIVERY".equals(command.fulfillmentType())) {
            if (command.appointmentStart() != null || command.appointmentEnd() != null
                    || command.selectedGeneralWindowId() != null
                    || !minute(command.pickupStart()) || !minute(command.returnStart())
                    || command.returnStart().isBefore(command.pickupStart().plusMinutes(120))) {
                throw invalid("invalid pickup and return hold interval");
            }
        } else throw invalid("unknown fulfillment type");
        return new QueryContext(context.traceId(), context.operatorType(), context.operatorId());
    }

    private static boolean interval(OffsetDateTime start, OffsetDateTime end) {
        return minute(start) && minute(end) && end.isAfter(start);
    }

    private static boolean minute(OffsetDateTime value) {
        return value != null && value.getSecond() == 0 && value.getNano() == 0;
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static long id(String value) {
        try {
            long id = IDS.fromApi(value);
            if (id > 0) return id;
        } catch (RuntimeException ignored) { }
        throw invalid("positive public ID is required");
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable("Snowflake ID unavailable");
        return value;
    }

    private void rollbackOnly() {
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
