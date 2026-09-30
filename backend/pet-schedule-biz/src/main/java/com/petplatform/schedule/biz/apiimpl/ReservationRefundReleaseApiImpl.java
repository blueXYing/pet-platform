package com.petplatform.schedule.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.command.ReservationRefundReleaseApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.biz.infrastructure.persistence.*;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleCommandMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Active claims are released by changing their owning reservation; history is retained. */
public final class ReservationRefundReleaseApiImpl implements ReservationRefundReleaseApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source; private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;private final RefundExecutionFactsApi refunds;
    private final ScheduleCommandMapper mapper;
    public ReservationRefundReleaseApiImpl(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,RefundExecutionFactsApi refunds){
        this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);this.guard=Objects.requireNonNull(guard);this.refunds=Objects.requireNonNull(refunds);
        mapper=ScheduleMybatis.template(source).getMapper(ScheduleCommandMapper.class);
    }
    public void release(String order,String reservation,String store,String refund,QueryContext ctx){
        guard.requireHeld(store,source);
        try{
            if(ctx==null||ctx.operatorType()!=OperatorType.SYSTEM)throw unavailable();
            var fact=refunds.requireSucceeded(refund,order,store,ctx);
            if(fact==null||!Set.of("MERCHANT_REJECT_ORDER","MERCHANT_APPROVED","MERCHANT_TIMEOUT_AUTO").contains(fact.refundSource())||!order.equals(fact.orderId())
                ||!store.equals(fact.storeId())||!refund.equals(fact.refundOrderId())||fact.refundAmount().signum()<=0
                ||fact.refundAmount().compareTo(fact.originalPaidAmount())!=0)throw unavailable();
            var row=mapper.lockReservation(IDS.fromApi(reservation));
            if(row==null||ScheduleSqlRows.number(row,"order_id")!=IDS.fromApi(order)||ScheduleSqlRows.number(row,"store_id")!=IDS.fromApi(store))throw unavailable();
            byte[] key=("EVENT:REFUND_RELEASE:"+refund).getBytes(StandardCharsets.UTF_8);
            String state=ScheduleSqlRows.text(row,"status");
            if("RELEASED".equals(state)){
                if(mapper.refundReleaseProof(IDS.fromApi(reservation),key)!=1)throw unavailable();return;
            }
            if(!"CONFIRMED".equals(state)||mapper.releaseRefund(IDS.fromApi(reservation),ScheduleSqlRows.number(row,"version"))!=1)throw unavailable();
            if(mapper.insertSystemAudit(ScheduleSqlRows.values("id",ids.nextId(),"reservationId",IDS.fromApi(reservation),
                "orderId",IDS.fromApi(order),"storeId",IDS.fromApi(store),"action","REFUND_RELEASE","requestId",key,
                "traceId",ctx.traceId(),"occurredAt",mapper.utcNow()))!=1)throw unavailable();
        }catch(RuntimeException failure){
            Object resource=TransactionSynchronizationManager.getResource(source);if(resource instanceof ConnectionHolder holder)holder.setRollbackOnly();
            throw unavailable();
        }
    }
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Verified refund release unavailable");}
}
