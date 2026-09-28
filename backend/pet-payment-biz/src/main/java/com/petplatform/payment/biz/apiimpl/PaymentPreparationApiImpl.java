package com.petplatform.payment.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.payment.api.command.PaymentPreparationApi;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.*;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.BookingUserFactsApi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
/** A durable internal intent, never a successful createPayment response or permission to send money. */
public final class PaymentPreparationApiImpl implements PaymentPreparationApi {
    private final DataSource source; private final SnowflakeIdGenerator ids; private final ScheduleCapacityGuardApi guard;
    private final OrderPaymentFactsApi orders; private final BookingUserFactsApi users; private final PaymentMerchantBindings bindings;
    private final PaymentFoundationStore store; private final TransactionTemplate tx;
    public PaymentPreparationApiImpl(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            OrderPaymentFactsApi orders,BookingUserFactsApi users,PaymentMerchantBindings bindings){
        this.source=source;this.ids=ids;this.guard=guard;this.orders=orders;this.users=users;this.bindings=bindings;
        store=new PaymentFoundationStore(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);
    }
    @Override public PreparedPayment prepare(PreparePaymentCommand c){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
        try{PublicContractChecks.requireCommandRequestId(c==null?null:c.context());id(c.orderId());id(c.context().operatorId());}
        catch(RuntimeException bad){throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid payment intent");}
        if(c.context().operatorType()!=OperatorType.USER)throw new ApiException(CommonApiCodes.FORBIDDEN,"payment intent requires USER");
        var q=new QueryContext(c.context().traceId(),OperatorType.USER,c.context().operatorId());
        users.checkActor(c.context().operatorId(),q);
        byte[] key=("payment.prepare/USER/"+c.context().operatorId()+"/"+c.context().requestId()).getBytes(StandardCharsets.UTF_8);
        try{
            // Persist the original binding even if later eligibility/configuration fails.
            tx.execute(status->{store.session();store.jdbc.update("INSERT INTO payment_intent_request(id,request_key,order_id,user_id,created_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE id=id",nextId(),key,id(c.orderId()),id(c.context().operatorId()));checkBinding(key,c);return null;});
            return execute(c,key,q);
        }catch(ApiException known){throw known;}catch(RuntimeException failed){
            if(unknownCommit(failed)){
                // One authoritative retry under the original binding; no new requestId/paymentNo.
                try{return execute(c,key,q);}
                catch(ApiException known){throw known;}
                catch(RuntimeException rereadFailed){throw databaseFailure(rereadFailed);}
            }
            throw databaseFailure(failed);
        }
    }
    private PreparedPayment execute(PreparePaymentCommand c,byte[] key,QueryContext q){
        return tx.execute(status->{
                store.session();checkBinding(key,c);
                String storeId=orders.locateStore(c.orderId(),q);guard.acquire(List.of(storeId),q);guard.requireHeld(storeId,source);
                users.requireCurrentActor(c.context().operatorId(),storeId,q);
                var order=orders.readForPayment(c.orderId(),storeId,q);
                var existing=store.byOrder(id(c.orderId()));
                if(existing!=null){
                    if(existing.userId()!=id(c.context().operatorId()) || existing.storeId()!=id(storeId)
                            || existing.merchantId()!=id(order.merchantId()))throw unavailable();
                    store.jdbc.update("UPDATE payment_intent_request SET payment_id=? WHERE request_key=?",existing.id(),key);
                    return result(existing,true);
                }
                orders.requirePayableForPreparation(c.orderId(),storeId,q);
                var binding=bindings.require(order.merchantId(),storeId);
                if(binding==null || !valid(binding.merchantNo(),32) || !valid(binding.termNo(),32) || !valid(binding.subAppId(),64))throw unavailable();
                long paymentId=nextId(),paymentNo=nextId();
                store.jdbc.update("INSERT INTO payment_order(id,payment_no,order_id,amount,status,channel,expire_at,created_at,updated_at,store_id,merchant_id,user_id,merchant_no,term_no,sub_appid,currency,dispatch_state) VALUES(?,?,?,?,'INIT','LAKALA_WECHAT',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?,?,?,?,?,'CNY','PREPARED')",paymentId,paymentNo,id(c.orderId()),order.payAmount(),PaymentFoundationStore.utc(order.paymentExpireAt()),id(storeId),id(order.merchantId()),id(order.userId()),binding.merchantNo(),binding.termNo(),binding.subAppId());
                store.jdbc.update("UPDATE payment_intent_request SET payment_id=? WHERE request_key=?",paymentId,key);
                return result(store.byId(paymentId,true),false);

        });
    }
    private void checkBinding(byte[] key,PreparePaymentCommand c){
        var rows=store.jdbc.query("SELECT order_id,user_id FROM payment_intent_request WHERE request_key=? FOR UPDATE",(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},key);
        if(rows.size()!=1)throw unavailable();
        if(rows.getFirst()[0]!=id(c.orderId()) || rows.getFirst()[1]!=id(c.context().operatorId()))throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,"payment requestId already bound");
    }
    private PreparedPayment result(PaymentFoundationStore.Row r,boolean replay){
        if(r==null || r.expires()==null || r.amount()==null || !"LAKALA_WECHAT".equals(r.channel()) || !"CNY".equals(r.currency()))throw unavailable();
        return new PreparedPayment(Long.toString(r.id()),Long.toString(r.no()),Long.toString(r.orderId()),r.amount(),r.expires().atOffset(java.time.ZoneOffset.UTC),replay);
    }
    private long nextId(){long n=ids.nextId();if(n<=0)throw unavailable();return n;}
    private static long id(String s){return new DecimalPublicIdCodec().fromApi(s);}
    private static boolean valid(String s,int max){return s!=null&&!s.isBlank()&&s.length()<=max&&!s.codePoints().anyMatch(Character::isISOControl);}
    private static boolean unknownCommit(Throwable failure){
        for(Throwable at=failure;at!=null;at=at.getCause()){
            if(at instanceof java.sql.SQLException sql && sql.getSQLState()!=null && sql.getSQLState().startsWith("08"))return true;
        }
        return false;
    }
    private static ApiException databaseFailure(Throwable failure){
        for(Throwable at=failure;at!=null;at=at.getCause()){
            if(at instanceof java.sql.SQLException sql && (sql.getErrorCode()==1205||sql.getErrorCode()==1213))
                return new ApiException(CommonApiCodes.CONFLICT,"payment intent busy; retry the original requestId");
        }
        return unavailable();
    }
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"payment intent facts unavailable");}
}
