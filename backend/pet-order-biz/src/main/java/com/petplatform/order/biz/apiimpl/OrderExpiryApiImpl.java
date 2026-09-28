package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.query.BookingCouponExposureApi;
import com.petplatform.order.api.command.OrderExpiryApi;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderResult;
import com.petplatform.order.api.dto.OrderExpiryTypes.ReconcileExpiryTasksCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.RecoveryScanResult;
import com.petplatform.order.biz.application.OrderExpiryTaskSubmission;
import com.petplatform.order.biz.infrastructure.persistence.OrderExpiryStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderExpiryStore.OrderRow;
import com.petplatform.payment.api.query.BookingPaymentExposureApi;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.dto.ReservationExpiryTypes.ExpireHoldCommand;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Closes one unpaid hold and its order atomically after both owners prove no exposure. */
public final class OrderExpiryApiImpl implements OrderExpiryApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final ReservationExpiryApi reservations;
    private final BookingPaymentExposureApi payments;
    private final BookingCouponExposureApi coupons;
    private final OrderExpiryStore orders;
    private final JdbcAsyncTaskSubmitter tasks;
    private final TransactionTemplate transaction;

    public OrderExpiryApiImpl(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, ReservationExpiryApi reservations,
            BookingPaymentExposureApi payments, BookingCouponExposureApi coupons) {
        this.source = Objects.requireNonNull(source);
        this.ids = Objects.requireNonNull(ids);
        this.guard = Objects.requireNonNull(guard);
        this.reservations = Objects.requireNonNull(reservations);
        this.payments = Objects.requireNonNull(payments);
        this.coupons = Objects.requireNonNull(coupons);
        this.orders = new OrderExpiryStore(source);
        this.tasks = new JdbcAsyncTaskSubmitter(source, ids);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    @Override public RecoveryScanResult reconcileMissingTasks(ReconcileExpiryTasksCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable("ORDER task recovery requires no outer transaction");
        }
        if (command == null || command.context() == null || command.limit() < 1
                || command.limit() > 1000) throw invalid("ORDER recovery scan is invalid");
        requireSystem(command.context());
        long afterId;
        try {
            afterId = "0".equals(command.afterOrderId()) ? 0L : IDS.fromApi(command.afterOrderId());
        } catch (IllegalArgumentException malformed) {
            throw invalid("ORDER recovery cursor is invalid");
        }
        try {
            List<Long> candidates = orders.scanPendingAfter(afterId, command.limit());
            QueryContext context = new QueryContext(command.context().traceId(),
                    OperatorType.SYSTEM, command.context().operatorId());
            for (long orderId : candidates) {
                transaction.execute(status -> {
                    recoverOne(orderId, context);
                    return null;
                });
            }
            return new RecoveryScanResult(candidates.size(),
                    candidates.size() < command.limit() ? null
                            : Long.toString(candidates.getLast()));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable("ORDER task recovery dependency unavailable");
        }
    }

    private void recoverOne(long orderId, QueryContext context) {
        orders.sessionDefaults();
        String storeId = orders.storeId(orderId);
        if (storeId == null) return;
        guard.acquire(List.of(storeId), context);
        guard.requireHeld(storeId, source);
        OrderRow order = orders.lock(orderId);
        if (order == null || order.storeId() != Long.parseLong(storeId)
                || !"PENDING_PAYMENT".equals(order.stage())
                || !"INIT".equals(order.paymentStatus())
                || !"UNVERIFIED".equals(order.verificationStatus())
                || order.paymentExpireAt() == null) return;
        if (order.reservationId() <= 0) throw unavailable("ORDER recovery binding is invalid");
        OrderExpiryTaskSubmission.submit(tasks, orderId, order.reservationId(),
                order.paymentExpireAt());
    }

    @Override public ExpireOrderResult expire(ExpireOrderCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable("ORDER expiry requires an independent transaction");
        }
        Validated input = validate(command);
        try {
            ExpireOrderResult result = transaction.execute(status -> close(input));
            if (result == null) throw unavailable("ORDER expiry did not return a result");
            return result;
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable("ORDER expiry dependency unavailable");
        }
    }

    private ExpireOrderResult close(Validated input) {
        orders.sessionDefaults();
        String storeId = orders.storeId(input.orderId());
        if (storeId == null) throw unavailable("ORDER expiry target is absent");
        QueryContext context = new QueryContext(input.command().context().traceId(),
                OperatorType.SYSTEM, input.command().context().operatorId());
        guard.acquire(List.of(storeId), context);
        guard.requireHeld(storeId, source);
        OrderRow order = orders.lock(input.orderId());
        if (order == null || order.storeId() != Long.parseLong(storeId)
                || order.reservationId() != input.reservationId()) {
            throw unavailable("ORDER expiry binding is inconsistent");
        }
        if (order.paymentExpireAt() == null || !order.paymentExpireAt().isEqual(input.deadline())) {
            throw unavailable("ORDER expiry generation does not match the current order");
        }
        if (!List.of("PENDING_PAYMENT", "PENDING_CONFIRM", "PENDING_SERVICE", "COMPLETED",
                "CANCELED").contains(order.stage())) {
            throw unavailable("ORDER stage is unknown");
        }
        if ("CANCELED".equals(order.stage())) {
            if (!"INIT".equals(order.paymentStatus()) || !"UNVERIFIED".equals(order.verificationStatus())
                    || !orders.hasExpiryLog(input.orderId(), input.command().context().requestId())) {
                throw unavailable("ORDER cancellation requires owner reconciliation");
            }
            reservations.assertExpired(input.command().orderId(), input.command().reservationId(), storeId, context);
            return ExpireOrderResult.NOOP;
        }
        if (!"PENDING_PAYMENT".equals(order.stage())) {
            String expectedVerification="COMPLETED".equals(order.stage()) ? "VERIFIED" : "UNVERIFIED";
            if(!"PAID".equals(order.paymentStatus()) || !expectedVerification.equals(order.verificationStatus())) {
                throw unavailable("ORDER later-stage facts are inconsistent");
            }
            return ExpireOrderResult.NOOP;
        }
        if (!"INIT".equals(order.paymentStatus())
                || !"UNVERIFIED".equals(order.verificationStatus())
                || order.payAmount() == null || order.payAmount().signum() <= 0
                || order.discountAmount() == null || order.discountAmount().signum() != 0) {
            throw unavailable("ORDER payment or verification state is uncertain");
        }
        OffsetDateTime now = orders.databaseNow();
        if (now.isBefore(input.deadline())) return ExpireOrderResult.NOT_DUE;
        // An UNKNOWN, initiated, frozen, or unreadable owner fact throws and rolls back.
        payments.requireNoPayment(input.command().orderId(), storeId, context);
        coupons.requireNoCoupon(input.command().orderId(), storeId, context);
        if (orders.cancel(input.orderId(), order.version(), input.deadline(), now) != 1) {
            throw unavailable("ORDER expiry compare-and-set failed");
        }
        reservations.expire(new ExpireHoldCommand(input.command().context(),
                input.command().orderId(), input.command().reservationId(), storeId,
                input.command().expectedReservationVersion(), input.deadline(), now));
        long logId = ids.nextId();
        if (logId <= 0) throw unavailable("ORDER status ID unavailable");
        orders.statusLog(logId, input.orderId(), input.command().context().requestId());
        return ExpireOrderResult.CLOSED;
    }

    private static Validated validate(ExpireOrderCommand command) {
        if (command == null || command.context() == null) throw invalid("expiry command is absent");
        CommandContext context = requireSystem(command.context());
        long orderId;
        long reservationId;
        try {
            orderId = IDS.fromApi(command.orderId());
            reservationId = IDS.fromApi(command.reservationId());
            PublicContractChecks.requireMillisecondPrecision(command.expectedPaymentExpireAt());
        } catch (IllegalArgumentException malformed) {
            throw invalid("expiry generation is invalid");
        }
        if (command.expectedReservationVersion() != 0
                || !context.requestId().equals("TASK:RESERVATION_HOLD_EXPIRE:"
                        + reservationId + ":0")) {
            throw invalid("expiry requestId does not match its reservation generation");
        }
        return new Validated(command, orderId, reservationId,
                command.expectedPaymentExpireAt());
    }

    private static CommandContext requireSystem(CommandContext context) {
        try { PublicContractChecks.requireCommandRequestId(context); }
        catch (IllegalArgumentException malformed) { throw invalid("SYSTEM requestId is invalid"); }
        if (context.operatorType() != OperatorType.SYSTEM) throw new ApiException(
                CommonApiCodes.FORBIDDEN, "only SYSTEM may run ORDER expiry");
        return context;
    }

    private static ApiException invalid(String detail) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, detail);
    }

    private static ApiException unavailable(String detail) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, detail);
    }

    private record Validated(ExpireOrderCommand command, long orderId, long reservationId,
            OffsetDateTime deadline) {}
}
