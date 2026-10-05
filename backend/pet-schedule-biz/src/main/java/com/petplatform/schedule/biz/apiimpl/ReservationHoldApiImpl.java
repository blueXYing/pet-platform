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
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleMybatis;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleCommandMapper;
import java.nio.charset.StandardCharsets;
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
    private final ScheduleCommandMapper mapper;

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
        this.mapper = ScheduleMybatis.template(source).getMapper(ScheduleCommandMapper.class);
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        QueryContext query = validate(command);
        guard.requireHeld(command.storeId(), source);
        try {
            if (!mapper.lockReservationByOrder(id(command.orderId())).isEmpty()) {
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
            mapper.insertReservation(ScheduleSqlRows.values(
                    "id", reservationId, "orderId", id(command.orderId()),
                    "userId", id(command.userId()), "merchantId", id(command.merchantId()),
                    "storeId", id(command.storeId()), "serviceId", id(command.serviceId()),
                    "fulfillmentType", command.fulfillmentType(), "startAt", timestamp(start),
                    "endAt", timestamp(end), "pickupStartAt", timestamp(pickup),
                    "returnStartAt", timestamp(returning), "lockToken", UUID.randomUUID().toString(),
                    "lockExpireAt", timestamp(expires),
                    "capacitySnapshot", Math.min(plan.configuredCapacity(), plan.qualifiedStaffCount()),
                    "qualifiedStaffCountSnapshot", plan.qualifiedStaffCount(),
                    "createdAt", java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                    "updatedAt", java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
            List<HeldClaim> claims = new ArrayList<>(plan.claims().size());
            List<Long> claimedWindows = new ArrayList<>(plan.claims().size());
            for (ProvenClaim selected : plan.claims()) {
                long claimId = nextId();
                mapper.insertClaim(ScheduleSqlRows.values("id", claimId,
                        "reservationId", reservationId, "windowId", id(selected.windowId()),
                        "storeId", id(command.storeId()), "serviceId", id(command.serviceId()),
                        "kind", selected.kind(), "startAt", timestamp(selected.startAt()),
                        "endAt", timestamp(selected.endAt())));
                claimedWindows.add(id(selected.windowId()));
                claims.add(new HeldClaim(IDS.toApi(claimId), selected.windowId(), selected.kind(),
                        selected.startAt(), selected.endAt()));
            }
            mapper.insertHoldAudit(ScheduleSqlRows.values("id", nextId(),
                    "reservationId", reservationId, "orderId", id(command.orderId()),
                    "actorUserId", id(command.userId()), "storeId", id(command.storeId()),
                    "requestId", command.context().requestId().getBytes(StandardCharsets.UTF_8),
                    "traceId", command.context().traceId(),
                    "occurredAt", java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
            // Derived SOLD_OUT flips with the same transaction that consumed the capacity
            // (Contract53 §3): a hold that fills its windows marks them sold out immediately.
            WindowSoldOutDeriver.rederive(command.storeId(), claimedWindows, query, facts,
                    mapper::setWindowDerivedStatus,
                    java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC));
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
                || !validOptionalTrace(context.traceId())) {
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

    private static boolean validOptionalTrace(String traceId) {
        if (traceId == null) return true;
        if (traceId.isBlank() || traceId.getBytes(StandardCharsets.UTF_8).length > 128) return false;
        for (int index = 0; index < traceId.length(); index++) {
            char value = traceId.charAt(index);
            if (Character.isISOControl(value)) return false;
            if (Character.isHighSurrogate(value)) {
                if (++index >= traceId.length() || !Character.isLowSurrogate(traceId.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(value)) return false;
        }
        return true;
    }

    private static java.time.LocalDateTime timestamp(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
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
