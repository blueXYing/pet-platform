package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderLatePaymentFact;
import com.petplatform.order.api.query.OrderLatePaymentFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderExpiryStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads only ORDER rows; SCH expiry is verified through its public API. */
public final class OrderLatePaymentFactsApiImpl implements OrderLatePaymentFactsApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final ReservationExpiryApi expiry;
    private final OrderPaymentStore orders;
    private final OrderExpiryStore expiredOrders;

    public OrderLatePaymentFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard,
            ReservationExpiryApi expiry) {
        this.source = Objects.requireNonNull(source);
        this.guard = Objects.requireNonNull(guard);
        this.expiry = Objects.requireNonNull(expiry);
        this.orders = new OrderPaymentStore(source);
        this.expiredOrders = new OrderExpiryStore(source);
    }

    @Override public String locateStore(String orderId, QueryContext context) {
        requireSystem(context);
        long id = positive(orderId);
        try {
            var rows = orders.locate(id);
            if (rows.size() != 1 || rows.getFirst().storeId() <= 0
                    || rows.getFirst().userId() <= 0) throw unavailable();
            return IDS.toApi(rows.getFirst().storeId());
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    @Override public OrderLatePaymentFact requireLatePayment(String orderId, String paymentId,
            String storeId, QueryContext context) {
        requireSystem(context);
        long orderKey = positive(orderId), paymentKey = positive(paymentId), storeKey = positive(storeId);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)) throw unavailable();
        guard.requireHeld(storeId, source);
        try {
            orders.sessionDefaults();
            var order = orders.lock(orderKey);
            var result = orders.lockResult(orderKey);
            if (order == null || result == null || order.storeId() != storeKey
                    || order.userId() <= 0 || order.merchantId() <= 0
                    || order.reservationId() <= 0 || result.paymentId() != paymentKey
                    || result.sourceEventId() <= 0 || !"LATE".equals(result.resultType())
                    || !"CANCELED".equals(order.stage())
                    || !"PAYMENT_TIMEOUT".equals(order.cancelReason())
                    || !"PAID".equals(order.paymentStatus())
                    || !"UNVERIFIED".equals(order.verificationStatus())
                    || result.channelTradeNo() == null || result.channelTradeNo().isBlank()
                    || result.paidAmount() == null || result.paidAmount().signum() <= 0
                    || result.paidAmount().scale() > 2 || result.paidAt() == null)
                throw unavailable();
            PublicContractChecks.requireMillisecondPrecision(result.paidAt());
            String reservationId = IDS.toApi(order.reservationId());
            if (!expiredOrders.hasExpiryLog(orderKey,
                    "TASK:RESERVATION_HOLD_EXPIRE:" + reservationId + ":0")) throw unavailable();
            expiry.assertExpired(orderId, reservationId, storeId, context);
            return new OrderLatePaymentFact(orderId, storeId, IDS.toApi(order.merchantId()),
                    IDS.toApi(order.userId()), reservationId, paymentId,
                    IDS.toApi(result.sourceEventId()), result.channelTradeNo(),
                    result.paidAmount(), result.paidAt());
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    private static void requireSystem(QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.SYSTEM)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "late payment proof requires SYSTEM");
    }
    private static long positive(String id) {
        try { return IDS.fromApi(id); }
        catch (RuntimeException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid late payment ID");
        }
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "ORDER late payment proof unavailable");
    }
}
