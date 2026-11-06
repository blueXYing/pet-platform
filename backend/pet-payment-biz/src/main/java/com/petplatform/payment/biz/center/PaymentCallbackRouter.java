package com.petplatform.payment.biz.center;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.application.PaymentNotificationService.ReceiptResult;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes one raw 1112 notification by the stored row kind: payment center rows to
 * {@link PaymentCenterReceiptWriter}, ORDER rows to the unchanged {@link PaymentNotificationService}.
 * The unverified out_trade_no is only a lookup hint; each writer verifies the signature itself.
 * Stage 1 registers no callback HTTP (Contract 75 §8); this is the entry a later callback controller uses.
 */
public final class PaymentCallbackRouter {
    private final PaymentCenterStore store;
    private final PaymentCenterReceiptWriter center;
    private final PaymentNotificationService orders;

    public PaymentCallbackRouter(DataSource source, PaymentCenterReceiptWriter center,
            PaymentNotificationService orders) {
        this.store = new PaymentCenterStore(Objects.requireNonNull(source));
        this.center = Objects.requireNonNull(center);
        this.orders = Objects.requireNonNull(orders);
    }

    public ReceiptResult receive(Map<String, String> headers, byte[] body) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
        if (body == null || body.length == 0 || body.length > 65_536 || headers == null)
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid payment notification");
        PaymentCenterStore.Route route;
        try {
            route = store.center.selectRouteByNo(PaymentCenterReceiptWriter.hintNo(body));
        } catch (ApiException known) { throw known; }
        catch (Exception failure) { throw unavailable(); }
        if (route == null) throw unavailable();
        if (route.bizType() != null && route.orderId() == null) return center.receiveNotification(headers, body);
        if (route.bizType() == null && route.orderId() != null) return orders.receive(headers, body);
        throw unavailable();
    }

    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "payment notification requires reconciliation");
    }
}
