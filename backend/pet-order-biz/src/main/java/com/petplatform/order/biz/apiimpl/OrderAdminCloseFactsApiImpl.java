package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.*;
import com.petplatform.order.api.query.OrderAdminCloseFactsApi;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
import com.petplatform.order.biz.infrastructure.persistence.OrderAdminDispositionStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderAutoConfirmStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Reads the immutable ORDER source proof of a contract-63 v0.2 operator abnormal close — even
 * after the linked refund has completed. The proof is the closed pet_order row (CANCELED with
 * the ADMIN_ABNORMAL_CLOSE fact) plus its append-only order_admin_action row; never an event
 * payload. Joins the caller's store-guarded transaction like the merchant-reject facts.
 */
public final class OrderAdminCloseFactsApiImpl implements OrderAdminCloseFactsApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final OrderPaymentStore payment;
    private final OrderAutoConfirmStore orders;
    private final OrderAdminDispositionStore dispositions;

    public OrderAdminCloseFactsApiImpl(DataSource source,ScheduleCapacityGuardApi guard){
        this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);
        payment=new OrderPaymentStore(source);orders=new OrderAutoConfirmStore(source);
        dispositions=new OrderAdminDispositionStore(source);
    }

    @Override public String locateStore(String orderId,QueryContext context){
        var rows=payment.locate(IDS.fromApi(orderId));
        if(rows.size()!=1)throw unavailable();
        return IDS.toApi(rows.getFirst().storeId());
    }

    @Override public OrderRefundOriginFact requireClosed(String orderId,String paymentId,String storeId,QueryContext context){
        guard.requireHeld(storeId,source);
        if(context==null||context.operatorType()!=OperatorType.SYSTEM)throw unavailable();
        var r=orders.lock(IDS.fromApi(orderId));
        if(r==null)throw unavailable();
        var actions=dispositions.mapper().selectByOrder(r.id);
        var p=payment.lockResult(IDS.fromApi(orderId));
        if(r==null||p==null||actions.size()!=1||!"NORMAL".equals(p.resultType())
          ||p.paymentId()!=IDS.fromApi(paymentId)
          ||!"CANCELED".equals(r.orderStage)||!"ADMIN_ABNORMAL_CLOSE".equals(r.cancelReason)
          ||!"PAID".equals(r.paymentStatus)||!"UNVERIFIED".equals(r.verificationStatus)
          ||r.storeId!=IDS.fromApi(storeId)||r.refundOrderId==null
          ||r.refundOrderId!=actions.getFirst().refundOrderId
          ||r.canceledAt==null||!r.canceledAt.atOffset(ZoneOffset.UTC).isEqual(actions.getFirst().actedAt.atOffset(ZoneOffset.UTC))
          ||r.paidAt==null||p.paidAt()==null||!r.paidAt.atOffset(ZoneOffset.UTC).isEqual(p.paidAt())
          ||r.payAmount==null||p.paidAmount()==null||r.payAmount.compareTo(p.paidAmount())!=0
          ||r.payAmount.signum()<=0||p.sourceEventId()<=0
          ||p.channelTradeNo()==null||p.channelTradeNo().isBlank())throw unavailable();
        return new OrderRefundOriginFact(orderId,storeId,IDS.toApi(r.merchantId),IDS.toApi(r.userId),
            IDS.toApi(r.reservationId),paymentId,IDS.toApi(p.sourceEventId()),p.channelTradeNo(),
            p.paidAmount(),p.paidAt(),"ADMIN_ABNORMAL_CLOSE",IDS.toApi(actions.getFirst().id),
            IDS.toApi(r.refundOrderId));
    }

    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Admin abnormal-close proof unavailable");}
}
