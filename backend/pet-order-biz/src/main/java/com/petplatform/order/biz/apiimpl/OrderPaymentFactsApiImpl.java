package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderPaymentFact;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore.OrderRow;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current ORDER fact for payment admission and the same-transaction SCH confirmation proof. */
public final class OrderPaymentFactsApiImpl implements OrderPaymentFactsApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final OrderPaymentStore orders;

    public OrderPaymentFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source = Objects.requireNonNull(source);
        this.guard = Objects.requireNonNull(guard);
        this.orders = new OrderPaymentStore(source);
    }

    @Override public String locateStore(String orderId, QueryContext context) {
        long id = positive(orderId);
        requireActor(context);
        try {
            List<OrderPaymentStore.Locator> found = orders.locate(id);
            if (found.size() != 1) throw unavailable("ORDER payment target is absent");
            var row = found.getFirst();
            if (row.storeId() <= 0 || row.userId() <= 0) throw unavailable("ORDER payment owner is invalid");
            requireOwner(context, row.userId());
            return IDS.toApi(row.storeId());
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable("ORDER payment locator unavailable"); }
    }

    @Override public OrderPaymentFact readForPayment(String orderId, String storeId,
            QueryContext context) {
        long id = positive(orderId), expectedStore = positive(storeId);
        requireActor(context);
        requireGuard(storeId);
        try {
            orders.sessionDefaults();
            OrderRow row = orders.lock(id);
            if (row == null || row.storeId() != expectedStore || row.userId() <= 0
                    || row.merchantId() <= 0 || row.reservationId() <= 0
                    || row.stage() == null || row.paymentStatus() == null
                    || row.verificationStatus() == null || row.payAmount() == null
                    || row.discountAmount() == null || row.paymentExpireAt() == null
                    || row.version() < 0) throw unavailable("ORDER payment fact is incomplete");
            requireOwner(context, row.userId());
            return new OrderPaymentFact(orderId, IDS.toApi(row.userId()),
                    IDS.toApi(row.merchantId()), storeId, IDS.toApi(row.reservationId()),
                    row.stage(), row.paymentStatus(), row.verificationStatus(),
                    row.payAmount(), row.discountAmount(), row.paymentExpireAt());
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable("ORDER payment fact unavailable"); }
    }

    @Override public void assertPaymentCommitted(String orderId, String reservationId,
            String storeId, QueryContext context) {
        long id = positive(orderId), reservation = positive(reservationId);
        long store = positive(storeId);
        requireActor(context);
        requireGuard(storeId);
        try {
            com.petplatform.order.biz.application.OrderPaymentCommitProof.require(source,
                    orderId, reservationId);
            OrderRow row = orders.lock(id);
            if (row == null || row.reservationId() != reservation || row.storeId() != store
                    || !"PENDING_CONFIRM".equals(row.stage())
                    || !"PAID".equals(row.paymentStatus())
                    || !"UNVERIFIED".equals(row.verificationStatus())) {
                throw unavailable("ORDER payment confirmation is inconsistent");
            }
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable("ORDER payment proof unavailable"); }
    }

    private void requireGuard(String storeId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("ORDER payment fact requires a transaction");
        guard.requireHeld(storeId, source);
    }
    private static long positive(String raw) {
        try { return IDS.fromApi(raw); }
        catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid ORDER payment ID");
        }
    }
    private static void requireActor(QueryContext context) {
        if (context == null || (context.operatorType() != OperatorType.USER
                && context.operatorType() != OperatorType.SYSTEM))
            throw new ApiException(CommonApiCodes.FORBIDDEN, "invalid ORDER payment actor");
    }
    private static void requireOwner(QueryContext context, long owner) {
        if (context.operatorType() == OperatorType.USER
                && !IDS.toApi(owner).equals(context.operatorId()))
            throw new ApiException(CommonApiCodes.FORBIDDEN, "ORDER is not owned by actor");
    }
    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
