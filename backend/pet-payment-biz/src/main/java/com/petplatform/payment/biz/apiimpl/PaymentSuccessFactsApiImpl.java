package com.petplatform.payment.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.api.dto.PaymentSuccessFact;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.ZoneOffset;
import javax.sql.DataSource;
public final class PaymentSuccessFactsApiImpl implements PaymentSuccessFactsApi {
    private final DataSource source;private final ScheduleCapacityGuardApi guard;private final PaymentFoundationStore store;
    public PaymentSuccessFactsApiImpl(DataSource source,ScheduleCapacityGuardApi guard){this.source=source;this.guard=guard;store=new PaymentFoundationStore(source);}
    @Override public PaymentSuccessFact requireSucceeded(String paymentId,String orderId,String storeId,QueryContext context){
        guard.requireHeld(storeId,source);
        try{
            var r=store.byId(id(paymentId),true);
            if(r==null||r.orderId()!=id(orderId)||r.storeId()!=id(storeId)||r.userId()<=0||r.merchantId()<=0
                    ||!"PAID".equals(r.status())||!"OBSERVED".equals(r.dispatchState())||!"CNY".equals(r.currency())
                    ||!"LAKALA_WECHAT".equals(r.channel())||r.tradeNo()==null||r.tradeNo().isBlank()||r.paidAmount()==null
                    ||r.paidAmount().signum()<=0||r.paidAt()==null||r.successEventId()==null||r.successEventId()<=0)throw new IllegalStateException();
            Long proofs=store.mapper.countSuccessProofs(PaymentFoundationStore.values("paymentId",r.id(),"tradeNo",r.tradeNo(),"paidAmount",r.paidAmount(),"paidAt",r.paidAt()));
            if(proofs==null||proofs<1)throw new IllegalStateException();
            return new PaymentSuccessFact(Long.toString(r.id()),Long.toString(r.no()),Long.toString(r.orderId()),Long.toString(r.storeId()),Long.toString(r.merchantId()),Long.toString(r.userId()),r.tradeNo(),r.paidAmount(),r.paidAt().atOffset(ZoneOffset.UTC),Long.toString(r.successEventId()),r.currency());
        }catch(RuntimeException failure){throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"authoritative payment success unavailable");}
    }
    private static long id(String s){return new DecimalPublicIdCodec().fromApi(s);}
}
