package com.petplatform.payment.biz.center;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPaymentSuccessFact;
import com.petplatform.payment.api.query.PaymentCenterFactsApi;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.CenterRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Contract 75 §4.2: locked authoritative success inside the caller's transaction, otherwise 503. */
public final class PaymentCenterFactsApiImpl implements PaymentCenterFactsApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final PaymentCenterStore store;

    public PaymentCenterFactsApiImpl(DataSource source) {
        this.source = Objects.requireNonNull(source);
        this.store = new PaymentCenterStore(source);
    }

    @Override public CenterPaymentSuccessFact requireSucceeded(String bizType, String bizNo, QueryContext context) {
        if (context == null || (context.operatorType() != OperatorType.SYSTEM
                && context.operatorType() != OperatorType.USER))
            throw new ApiException(CommonApiCodes.FORBIDDEN, "payment center facts actor forbidden");
        PaymentCenterBizPolicy.requireBizType(bizType);
        PaymentCenterBizPolicy.requireBizNo(bizNo);
        if (!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction()) throw unavailable();
        try {
            CenterRow r = store.center.selectCenterByBizForUpdate(bizType, bizNo);
            if (r == null || !r.isCenter() || r.userId() == null || r.userId() <= 0
                    || !"PAID".equals(r.status()) || !"OBSERVED".equals(r.dispatchState())
                    || !"CNY".equals(r.currency()) || !"LAKALA_WECHAT".equals(r.channel())
                    || r.tradeNo() == null || r.tradeNo().isBlank() || r.paidAmount() == null
                    || r.paidAmount().signum() <= 0 || r.paidAt() == null
                    || r.successEventId() == null || r.successEventId() <= 0) throw new IllegalStateException();
            if (context.operatorType() == OperatorType.USER && r.userId() != IDS.fromApi(context.operatorId()))
                throw new IllegalStateException();
            Long proofs = store.shared.countSuccessProofs(PaymentFoundationStore.values("paymentId", r.id(),
                    "tradeNo", r.tradeNo(), "paidAmount", r.paidAmount(), "paidAt", r.paidAt()));
            if (proofs == null || proofs < 1) throw new IllegalStateException();
            return new CenterPaymentSuccessFact(Long.toString(r.id()), Long.toString(r.no()), r.bizType(),
                    r.bizNo(), Long.toString(r.userId()), r.amount(), r.paidAmount(),
                    r.paidAt().atOffset(ZoneOffset.UTC), r.tradeNo(), Long.toString(r.successEventId()),
                    r.currency());
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "authoritative payment center success unavailable");
    }
}
