package com.petplatform.payment.biz.application;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
/** Raw signed input -> immutable PAYMENT success and its outbox, in one guarded local transaction. */
public final class PaymentNotificationService {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> STATES=Set.of("INIT","CREATE","SUCCESS","FAIL","DEAL","UNKNOWN","CLOSE","PART_REFUND","REFUND","REVOKED");
    private final DataSource source;private final SnowflakeIdGenerator ids;private final ScheduleCapacityGuardApi guard;
    private final PaymentReceiptVerifier verifier;private final IntegrationEventPublisher publisher;
    private final PaymentFoundationStore store;private final TransactionTemplate tx;
    public PaymentNotificationService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            PaymentReceiptVerifier verifier,IntegrationEventPublisher publisher){
        this.source=source;this.ids=ids;this.guard=guard;this.verifier=verifier;this.publisher=publisher;
        store=new PaymentFoundationStore(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);
    }
    public ReceiptResult receive(Map<String,String> headers,byte[] body){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
        if(body==null||body.length==0||body.length>65_536||headers==null)throw invalid();
        body=body.clone();
        headers=Map.copyOf(headers);
        try{
            // Unverified data is used only as a lookup hint, never as an amount, actor or state.
            JsonNode raw=JSON.readTree(body);if(raw==null||!raw.isObject()||!raw.path("out_trade_no").isTextual())throw invalid();
            long no=new DecimalPublicIdCodec().fromApi(raw.get("out_trade_no").textValue());
            var hint=store.byNo(no);if(hint==null)throw unavailable();validRow(hint);
            var notice=verifier.verify(headers,body,new PaymentReceiptVerifier.ExpectedPayment(hint.merchantNo(),Long.toString(hint.no()),hint.amount()));
            validateNotice(notice,hint);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            return tx.execute(status->{
                store.session();String storeId=Long.toString(hint.storeId());
                guard.acquire(List.of(storeId),new QueryContext(null,OperatorType.SYSTEM,null));guard.requireHeld(storeId,source);
                var row=store.byId(hint.id(),true);validRow(row);
                if(row.storeId()!=hint.storeId() || row.orderId()!=hint.orderId() || row.userId()!=hint.userId()
                        || !row.termNo().equals(hint.termNo()) || !row.subAppId().equals(hint.subAppId()))throw unavailable();
                validateNotice(notice,row);
                OffsetDateTime now=store.now();
                OffsetDateTime paidAt=notice.channelTradeTime()==null?null:notice.channelTradeTime().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
                if("SUCCESS".equals(notice.status())&&(paidAt==null||paidAt.isAfter(now)))throw unavailable();
                boolean duplicate=store.jdbc.queryForObject("SELECT COUNT(*) FROM payment_channel_receipt WHERE payment_id=? AND receipt_sha256=?",Long.class,row.id(),digest)>0;
                if("PAID".equals(row.status())&&"SUCCESS".equals(notice.status())
                        && (!Objects.equals(row.tradeNo(),notice.channelTradeNo()) || row.paidAmount()==null
                        || row.paidAmount().compareTo(notice.paidAmount())!=0 || row.paidAt()==null
                        || !row.paidAt().equals(PaymentFoundationStore.utc(paidAt)) || row.successEventId()==null))throw unavailable();
                if(row.tradeNo()!=null && notice.channelTradeNo()!=null && !row.tradeNo().equals(notice.channelTradeNo()))throw unavailable();
                if(!duplicate)store.jdbc.update("INSERT INTO payment_channel_receipt(id,payment_id,receipt_sha256,channel_trade_no,channel_status,total_amount,paid_amount,paid_at,received_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    nextId(),row.id(),digest,notice.channelTradeNo(),notice.status(),notice.totalAmount(),notice.paidAmount(),paidAt==null?null:PaymentFoundationStore.utc(paidAt),PaymentFoundationStore.utc(now));
                if("PAID".equals(row.status()))return new ReceiptResult(Long.toString(row.id()),true,true);
                if("SUCCESS".equals(notice.status())){
                    long eventId=nextId();
                    store.jdbc.update("UPDATE payment_order SET status='PAID',dispatch_state='OBSERVED',channel_trade_no=?,channel_paid_amount=?,paid_at=?,success_event_id=?,version=version+1,updated_at=? WHERE id=?",
                        notice.channelTradeNo(),notice.paidAmount(),PaymentFoundationStore.utc(paidAt),eventId,PaymentFoundationStore.utc(now),row.id());
                    publisher.publish(new IntegrationEvent<>(Long.toString(eventId),"PaymentSucceededEvent.v1",1,now,"PAYMENT",Long.toString(row.id()),null,
                        Map.of("paymentOrderId",Long.toString(row.id()),"orderId",Long.toString(row.orderId()),"channelTradeNo",notice.channelTradeNo(),"paidAmount",notice.paidAmount(),"paidAt",paidAt.toString())));
                    return new ReceiptResult(Long.toString(row.id()),false,true);
                }
                // Pending/failure/unknown is never permission to release. Refund states need their own owner.
                String next=switch(notice.status()){case "FAIL"->"FAILED";case "CLOSE"->"CLOSED";default->"PAYING";};
                if(!duplicate)store.jdbc.update("UPDATE payment_order SET status=?,dispatch_state='OBSERVED',channel_trade_no=COALESCE(channel_trade_no,?),version=version+1,updated_at=? WHERE id=?",next,notice.channelTradeNo(),PaymentFoundationStore.utc(now),row.id());
                return new ReceiptResult(Long.toString(row.id()),duplicate,false);
            });
        }catch(ApiException known){throw known;}catch(Exception failure){throw unavailable();}
    }
    private static void validateNotice(PaymentReceiptVerifier.VerifiedNotice n,PaymentFoundationStore.Row r){
        if(n==null||!Objects.equals(r.merchantNo(),n.merchantNo())||!Long.toString(r.no()).equals(n.paymentNo())
                || !"WECHAT".equals(n.accountType())||!STATES.contains(n.status())||n.totalAmount()==null
                ||r.amount().compareTo(n.totalAmount())!=0)throw unavailable();
        if(n.channelTradeNo()!=null&&(n.channelTradeNo().isBlank()||n.channelTradeNo().length()>128||n.channelTradeNo().codePoints().anyMatch(Character::isISOControl)))throw unavailable();
        if("SUCCESS".equals(n.status())&&(n.channelTradeNo()==null||n.paidAmount()==null||n.paidAmount().signum()<=0
                ||n.paidAmount().scale()>2||n.paidAmount().compareTo(n.totalAmount())>0||n.channelTradeTime()==null))throw unavailable();
    }
    private static void validRow(PaymentFoundationStore.Row r){
        if(r==null||r.storeId()<=0||r.merchantId()<=0||r.userId()<=0||r.no()<=0||r.amount()==null||r.amount().signum()<=0
                ||r.merchantNo()==null||r.termNo()==null||r.subAppId()==null||!"CNY".equals(r.currency())||!"LAKALA_WECHAT".equals(r.channel())
                ||!Set.of("INIT","PAYING","PAID","FAILED","CLOSED").contains(r.status()))throw unavailable();
    }
    private long nextId(){long value=ids.nextId();if(value<=0)throw unavailable();return value;}
    private static ApiException invalid(){return new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid payment notification");}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"payment notification requires reconciliation");}
    public record ReceiptResult(String paymentId,boolean replayed,boolean paid){}
}
