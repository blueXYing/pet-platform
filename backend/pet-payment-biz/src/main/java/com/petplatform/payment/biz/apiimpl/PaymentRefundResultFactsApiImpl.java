package com.petplatform.payment.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.PaymentRefundResultFact;
import com.petplatform.payment.api.query.PaymentRefundResultFactsApi;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentRefundStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;

/** Current PAYMENT-owned signed refund SUCCESS fact; a channel DTO is never enough. */
public final class PaymentRefundResultFactsApiImpl implements PaymentRefundResultFactsApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final PaymentRefundStore store;

    public PaymentRefundResultFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source = Objects.requireNonNull(source);
        this.guard = Objects.requireNonNull(guard);
        this.store = new PaymentRefundStore(source);
    }

    @Override public PaymentRefundResultFact requireVerified(String refundOrderId,
            String refundNo, String paymentId, String storeId, QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.SYSTEM) throw unavailable();
        guard.requireHeld(storeId, source);
        try {
            store.session();
            var row = store.byRefund(IDS.fromApi(refundOrderId), true);
            if (row == null || row.refundNo() != IDS.fromApi(refundNo)
                    || row.paymentId() != IDS.fromApi(paymentId)
                    || row.storeId() != IDS.fromApi(storeId)
                    || !"VERIFIED_SUCCESS".equals(row.state())
                    || row.terminalReceiptSha256() == null
                    || row.channelRefundNo() == null || row.channelRefundNo().isBlank()
                    || row.terminalResultAt() == null || !"CNY".equals(row.currency())
                    || row.refundAmount() == null || row.refundAmount().signum() <= 0
                    || row.originalPaidAmount() == null || row.originalPaidAmount().signum() <= 0
                    || row.refundAmount().compareTo(row.originalPaidAmount()) > 0) throw unavailable();
            // Source admission binds FULL/PARTIAL before dispatch; this leaf proves the exact
            // channel result. REFUND also compares it with the independently authorized amount.
            var receipt = store.terminalReceipt(row.refundOrderId(), row.terminalReceiptSha256());
            if (receipt == null || !"SUCCESS".equals(receipt.state())
                    || !row.channelRefundNo().equals(receipt.channelRefundNo())
                    || receipt.amount() == null
                    || receipt.amount().compareTo(row.refundAmount()) != 0
                    || receipt.resultAt() == null
                    || !receipt.resultAt().equals(row.terminalResultAt())
                    || !row.originalChannelTradeNo().equals(receipt.originalTradeNo())
                    || !row.channelRequestNo().equals(receipt.channelRequestNo())
                    || !receipt.sha().matches("[0-9a-f]{64}")
                    || !java.util.Set.of("SUBMIT", "QUERY", "CALLBACK").contains(receipt.source()))
                throw unavailable();
            return new PaymentRefundResultFact(refundOrderId, refundNo, paymentId,
                    Long.toString(row.orderId()), storeId, row.originalChannelTradeNo(),
                    row.refundAmount(), row.currency(), row.channelRefundNo(), "SUCCESS",
                    row.terminalResultAt().atOffset(ZoneOffset.UTC), receipt.source(),
                    receipt.sha());
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "verified refund channel fact unavailable");
    }
}
