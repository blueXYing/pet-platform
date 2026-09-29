package com.petplatform.order.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.order.api.query.OrderMerchantRejectFactsApi;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;
/** Reads immutable ORDER source proof even after a verified refund has completed. */
public final class OrderMerchantRejectFactsApiImpl implements OrderMerchantRejectFactsApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source; private final ScheduleCapacityGuardApi guard;
    private final OrderPaymentStore payment; private final OrderAutoConfirmStore orders;
    private final OrderMerchantStore decisions;
    private final com.petplatform.schedule.api.command.ReservationConfirmApi reservations;
    public OrderMerchantRejectFactsApiImpl(DataSource source,ScheduleCapacityGuardApi guard){
        this(source,guard,null);
    }
    public OrderMerchantRejectFactsApiImpl(DataSource source,ScheduleCapacityGuardApi guard,com.petplatform.schedule.api.command.ReservationConfirmApi reservations){
        this.reservations=reservations;this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);
        payment=new OrderPaymentStore(source);orders=new OrderAutoConfirmStore(source);decisions=new OrderMerchantStore(source);
    }
    public String locateStore(String orderId,QueryContext context){
        var rows=payment.locate(IDS.fromApi(orderId));
        if(rows.size()!=1)throw unavailable();return IDS.toApi(rows.getFirst().storeId());
    }
    public OrderRefundOriginFact requireRejected(String orderId,String paymentId,String storeId,QueryContext context){
        guard.requireHeld(storeId,source);
        if(context==null||context.operatorType()!=OperatorType.SYSTEM)throw unavailable();
        var r=orders.lock(IDS.fromApi(orderId));var d=r==null||r.rescheduleCount==null?null:decisions.mapper().decision(IDS.fromApi(orderId),r.rescheduleCount);var p=payment.lockResult(IDS.fromApi(orderId));
        if(r==null||d==null||p==null||!"NORMAL".equals(p.resultType())||p.paymentId()!=IDS.fromApi(paymentId)
          ||!"CANCELED".equals(r.orderStage)||!"MERCHANT_REJECT_ORDER".equals(r.cancelReason)
          ||!"PAID".equals(r.paymentStatus)||!"UNVERIFIED".equals(r.verificationStatus)||(r.rescheduleCount==null||r.rescheduleCount<0||r.rescheduleCount>1)
          ||r.storeId!=IDS.fromApi(storeId)||!Objects.equals(r.storeId,d.storeId)||!Objects.equals(r.id,d.orderId)
          ||!"REJECT".equals(d.action)||!Objects.equals(d.confirmRound,r.rescheduleCount)||d.eventId==null||d.eventId<=0
          ||r.refundOrderId==null||!r.refundOrderId.equals(d.refundOrderId)||!Objects.equals(r.canceledAt,d.decidedAt)
          ||r.confirmMode!=null||r.confirmedAt!=null||r.paidAt==null||p.paidAt()==null
          ||!r.paidAt.atOffset(ZoneOffset.UTC).isEqual(p.paidAt())||r.payAmount==null||p.paidAmount()==null
          ||r.payAmount.compareTo(p.paidAmount())!=0||r.payAmount.signum()<=0||p.sourceEventId()<=0
          ||p.channelTradeNo()==null||p.channelTradeNo().isBlank()||d.reasonCode==null||d.reasonText==null)throw unavailable();
        com.petplatform.order.biz.application.OrderConfirmEpoch.requireSchedule(source,r,reservations,true,context);
        return new OrderRefundOriginFact(orderId,storeId,IDS.toApi(r.merchantId),IDS.toApi(r.userId),IDS.toApi(r.reservationId),
            paymentId,IDS.toApi(p.sourceEventId()),p.channelTradeNo(),p.paidAmount(),p.paidAt(),
            "MERCHANT_REJECT_ORDER",IDS.toApi(d.eventId),IDS.toApi(d.refundOrderId));
    }
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Merchant rejection proof unavailable");}
}
