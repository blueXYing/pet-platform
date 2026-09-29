package com.petplatform.order.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.command.MerchantOrderCommandApi;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmMapper.Row;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderMerchantMapper;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.command.MerchantRejectRefundApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static com.petplatform.order.biz.application.MerchantOrderPorts.*;

/** Public command admission is durable independently of the atomic business decision. */
public final class MerchantOrderService implements MerchantOrderCommandApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> REASONS=Set.of("SCHEDULE_CONFLICT","STAFF_UNAVAILABLE","PET_NOT_MATCHED","TEMPORARY_CLOSURE","OTHER");
    private final DataSource source; private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard; private final MerchantOrderAuthorityApi authority;
    private final PaymentSuccessFactsApi payments; private final ReservationConfirmApi reservations;
    private final RefundOrderFactsApi refunds; private final MerchantRejectRefundApi createRefund;
    private final IntegrationEventPublisher outbox; private final Protection protection;
    private final Moderation moderation; private final SessionAuthority sessions;
    private final OrderPaymentStore paidStore; private final OrderAutoConfirmStore orders;
    private final OrderMerchantMapper mapper; private final TransactionTemplate tx;

    public MerchantOrderService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
        MerchantOrderAuthorityApi authority,PaymentSuccessFactsApi payments,ReservationConfirmApi reservations,
        RefundOrderFactsApi refunds,MerchantRejectRefundApi createRefund,IntegrationEventPublisher outbox,
        Protection protection,Moderation moderation,SessionAuthority sessions){
        this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);this.guard=Objects.requireNonNull(guard);
        this.authority=Objects.requireNonNull(authority);this.payments=Objects.requireNonNull(payments);
        this.reservations=Objects.requireNonNull(reservations);this.refunds=Objects.requireNonNull(refunds);
        this.createRefund=Objects.requireNonNull(createRefund);this.outbox=Objects.requireNonNull(outbox);
        this.protection=Objects.requireNonNull(protection);this.moderation=Objects.requireNonNull(moderation);this.sessions=Objects.requireNonNull(sessions);
        paidStore=new OrderPaymentStore(source);orders=new OrderAutoConfirmStore(source);mapper=new OrderMerchantStore(source).mapper();
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public Receipt decide(Command c){
        validate(c);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
        try {
            // Do not hold the store guard while waiting on the independently reserved command row.
            String store=tx.execute(s -> {var r=lock(c);return IDS.toApi(r.storeId);});
            byte[] canonical=canonical(c);
            String purpose="ORDER_COMMAND:"+c.action()+":"+c.context().operatorId()+":"+store+":"+c.context().requestId();
            var key=values("namespace",bytes("merchant.order."+c.action()),"actor",IDS.fromApi(c.context().operatorId()),
                "scope",bytes("STORE:"+store),"requestId",bytes(c.context().requestId()));
            OrderMerchantMapper.Binding admitted=tx.execute(s -> {
                paidStore.sessionDefaults();var input=new HashMap<>(key);input.put("id",next());input.put("hash",sha(canonical));
                input.put("canonical",protection.protect(purpose,canonical));mapper.reserve(input);
                var binding=mapper.binding(key);same(binding,purpose,canonical);return binding;
            });
            Approval approval=null;
            if("REJECT".equals(c.action())&&!"SUCCEEDED".equals(admitted.state)){
                approval=moderation.check(c.reasonText());
                if(approval==null||approval.policyVersion()==null||approval.policyVersion().isBlank()
                    ||!sha(bytes(c.reasonText())).equals(approval.textSha256()))throw unavailable();
                if(!approval.allowed())throw invalid();
            }
            final Approval checked=approval;
            return tx.execute(s -> {
                paidStore.sessionDefaults();var binding=mapper.binding(key);same(binding,purpose,canonical);
                Row r=lock(c);
                if(!store.equals(IDS.toApi(r.storeId)))throw unavailable();
                if("SUCCEEDED".equals(binding.state))return replay(c,binding,purpose);
                if(!"RESERVED".equals(binding.state))throw unavailable();
                if("REJECT".equals(c.action())&&(checked==null||!checked.allowed()))throw unavailable();
                qualify(c,r);
                OffsetDateTime now=paidStore.databaseNow();
                if(!now.isBefore(r.confirmDeadline.atOffset(ZoneOffset.UTC)))throw error("ORDER_CONFIRM_DEADLINE_PASSED");
                long decision=next(),event=next(); Long refund="REJECT".equals(c.action())?next():null;
                String stage=refund==null?"PENDING_SERVICE":"CANCELED";
                var write=values("id",decision,"orderId",r.id,"storeId",r.storeId,"commandId",binding.id,
                    "action",c.action(),"operatorId",IDS.fromApi(c.context().operatorId()),"now",now.toLocalDateTime(),
                    "eventId",event,"refundId",refund,"reasonCode",c.reasonCode(),
                    "reasonText",protectText(decision,c.reasonText()),"internalNote",protectText(decision,c.internalNote()),
                    "stage",stage,"mode",refund==null?"MERCHANT":null,"confirmedAt",refund==null?now.toLocalDateTime():null,
                    "canceledAt",refund==null?null:now.toLocalDateTime(),"cancelReason",refund==null?null:"MERCHANT_REJECT_ORDER","version",r.version);
                if(mapper.insertDecision(write)!=1||mapper.decide(write)!=1)throw unavailable();
                QueryContext system=system(c);
                if(refund!=null){
                    var payment=paidStore.lockResult(r.id);
                    createRefund.create(c.orderId(),IDS.toApi(payment.paymentId()),store,IDS.toApi(refund),system);
                }
                Map<String,Object> payload=values("orderId",c.orderId(),"reservationId",IDS.toApi(r.reservationId),"storeId",store,"confirmRound",0);
                String eventType;
                if(refund==null){eventType="OrderConfirmedEvent.v1";payload.put("confirmMode","MERCHANT");
                    payload.put("confirmDeadline",r.confirmDeadline.atOffset(ZoneOffset.UTC).toString());payload.put("confirmedAt",now.toString());}
                else{eventType="OrderRejectedEvent.v1";payload.put("decisionId",IDS.toApi(decision));payload.put("refundOrderId",IDS.toApi(refund));
                    payload.put("rejectedAt",now.toString());payload.put("reasonCode",c.reasonCode());}
                outbox.publish(new IntegrationEvent<>(IDS.toApi(event),eventType,1,now,"ORDER",c.orderId(),c.context().traceId(),payload));
                write.put("id",next());write.put("eventType",refund==null?"ORDER_MERCHANT_CONFIRMED":"ORDER_MERCHANT_REJECTED");
                write.put("requestId",c.context().requestId());write.put("remark","decisionId="+decision+",eventId="+event);
                if(mapper.log(write)!=1)throw unavailable();
                var receipt=new Receipt(c.orderId(),IDS.toApi(decision),0,c.action(),stage,now.toString(),refund==null?null:IDS.toApi(refund));
                if(mapper.succeed(binding.id,protection.protect(purpose+":RESULT",json(receipt)))!=1)throw unavailable();
                return receipt;
            });
        }catch(ApiException known){throw known;}
        catch(org.springframework.dao.CannotAcquireLockException busy){throw error("ORDER_OPERATION_BUSY");}
        catch(RuntimeException failure){throw unavailable();}
    }

    private Row lock(Command c){
        paidStore.sessionDefaults();sessions.requireCurrent(c.context().operatorId());
        var location=paidStore.locate(IDS.fromApi(c.orderId()));if(location.size()!=1)throw new ApiException(CommonApiCodes.FORBIDDEN,"Order unavailable");
        String store=IDS.toApi(location.getFirst().storeId());guard.acquire(List.of(store),system(c));guard.requireHeld(store,source);
        Row r=orders.lock(IDS.fromApi(c.orderId()));if(r==null||!store.equals(IDS.toApi(r.storeId)))throw unavailable();
        authority.requireOwner(IDS.toApi(r.merchantId),store,new QueryContext(c.context().traceId(),OperatorType.USER,c.context().operatorId()));
        sessions.requireCurrent(c.context().operatorId());return r;
    }
    private void qualify(Command c,Row r){
        if(!"PENDING_CONFIRM".equals(r.orderStage)||r.rescheduleCount==null||r.rescheduleCount!=0)throw error("ORDER_STATE_NOT_ALLOWED");
        if(!"PAID".equals(r.paymentStatus)||!"UNVERIFIED".equals(r.verificationStatus)||r.confirmMode!=null||r.confirmedAt!=null
          ||r.canceledAt!=null||r.cancelReason!=null||r.refundedAmount==null||r.refundedAmount.signum()!=0
          ||r.confirmDeadline==null||r.paidAt==null||r.payAmount==null||r.payAmount.signum()<=0)throw unavailable();
        var proof=paidStore.lockResult(r.id);if(proof==null||!"NORMAL".equals(proof.resultType()))throw unavailable();
        String store=IDS.toApi(r.storeId);var p=payments.requireSucceeded(IDS.toApi(proof.paymentId()),c.orderId(),store,system(c));
        if(p==null||!c.orderId().equals(p.orderId())||!store.equals(p.storeId())||!IDS.toApi(r.userId).equals(p.userId())
          ||!IDS.toApi(r.merchantId).equals(p.merchantId())||!IDS.toApi(proof.paymentId()).equals(p.paymentId())
          ||!IDS.toApi(proof.sourceEventId()).equals(p.successEventId())||!Objects.equals(proof.channelTradeNo(),p.channelTradeNo())
          ||p.paidAmount()==null||proof.paidAmount()==null||r.payAmount.compareTo(p.paidAmount())!=0||proof.paidAmount().compareTo(p.paidAmount())!=0
          ||p.paidAt()==null||proof.paidAt()==null||!p.paidAt().isEqual(proof.paidAt())||!p.paidAt().isEqual(r.paidAt.atOffset(ZoneOffset.UTC))
          ||!p.paidAt().plusMinutes(30).isEqual(r.confirmDeadline.atOffset(ZoneOffset.UTC))||!"CNY".equals(p.currency()))throw unavailable();
        reservations.assertConfirmed(c.orderId(),IDS.toApi(r.reservationId),store,system(c));
        var refund=refunds.findByOrder(c.orderId(),store,system(c));if(refund==null)throw unavailable();
        if(r.refundOrderId!=null||refund.exists())throw error("ORDER_REFUND_ALREADY_CREATED");
        if(r.currentAftersaleId!=null||r.currentRefundApplicationId!=null)throw unavailable();
        if(mapper.decision(r.id)!=null)throw unavailable();
    }
    private Receipt replay(Command c,OrderMerchantMapper.Binding b,String purpose){
        try{
            if(!Objects.equals(b.resultVersion,1)||b.resultBytes==null)throw unavailable();
            Receipt receipt=JSON.readValue(protection.reveal(purpose+":RESULT",b.resultBytes),Receipt.class);
            var d=mapper.decision(IDS.fromApi(c.orderId()));
            if(d==null||!Objects.equals(d.commandId,b.id)||!c.action().equals(d.action)
              ||!c.orderId().equals(receipt.orderId())||!IDS.toApi(d.id).equals(receipt.decisionId())
              ||!c.action().equals(receipt.action())||receipt.confirmRound()!=0
              ||!Objects.equals(receipt.refundOrderId(),d.refundOrderId==null?null:IDS.toApi(d.refundOrderId)))throw unavailable();
            return receipt;
        }catch(Exception failure){throw unavailable();}
    }
    private void same(OrderMerchantMapper.Binding b,String purpose,byte[] canonical){
        if(b==null||!"canonical-v1".equals(b.canonicalVersion))throw unavailable();
        if(!sha(canonical).equals(b.payloadSha256)||!MessageDigest.isEqual(canonical,protection.reveal(purpose,b.canonicalBytes)))
            throw error(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT);
    }
    private byte[] protectText(long id,String text){return text==null?null:protection.protect("ORDER_DECISION:"+id,bytes(text));}
    private static byte[] canonical(Command c){
        var fields=new TreeMap<String,Object>();fields.put("orderId",c.orderId());fields.put("expectedConfirmRound",0);
        if("REJECT".equals(c.action())){fields.put("reasonCode",c.reasonCode());fields.put("reasonText",c.reasonText());}
        else if(c.internalNote()!=null)fields.put("internalNote",c.internalNote());
        return json(fields);
    }
    private static void validate(Command c){
        try{
            if(c==null||c.context()==null||c.context().operatorType()!=OperatorType.USER)throw invalid();
            IDS.fromApi(c.orderId());IDS.fromApi(c.context().operatorId());PublicContractChecks.requireTerminalRequestId(c.context().requestId());
            if(c.context().traceId()==null||c.context().traceId().isBlank()||c.expectedConfirmRound()!=0)throw invalid();
            if("CONFIRM".equals(c.action())){if(c.reasonCode()!=null||c.reasonText()!=null)throw invalid();if(c.internalNote()!=null)text(c.internalNote(),0);}
            else if("REJECT".equals(c.action())){if(!REASONS.contains(c.reasonCode())||c.internalNote()!=null)throw invalid();text(c.reasonText(),5);if(c.reasonText().isBlank())throw invalid();}
            else throw invalid();
        }catch(RuntimeException bad){throw invalid();}
    }
    private static void text(String value,int min){
        if(value==null)throw invalid();int count=value.codePointCount(0,value.length());if(count<min||count>200)throw invalid();
        if(value.codePoints().anyMatch(cp -> cp>=0xD800&&cp<=0xDFFF))throw invalid();
    }
    public static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw unavailable();}}
    private long next(){long id=ids.nextId();if(id<=0)throw unavailable();return id;}
    private static byte[] json(Object value){try{return JSON.writeValueAsBytes(value);}catch(Exception e){throw unavailable();}}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static QueryContext system(Command c){return new QueryContext(c.context().traceId(),OperatorType.SYSTEM,null);}
    private static Map<String,Object> values(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static ApiException error(String code){return new ApiException(code,"Order command cannot be applied; refresh or retry with original request ID");}
    private static ApiException invalid(){return error(CommonApiCodes.INVALID_ARGUMENT);}
    private static ApiException unavailable(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}
}
