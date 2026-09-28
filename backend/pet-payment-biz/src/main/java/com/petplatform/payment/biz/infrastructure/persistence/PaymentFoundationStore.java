package com.petplatform.payment.biz.infrastructure.persistence;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
/** PAYMENT-owned SQL only. Guard ownership and transaction orchestration live in the application. */
public final class PaymentFoundationStore {
    public final JdbcTemplate jdbc;
    public PaymentFoundationStore(DataSource source){jdbc=new JdbcTemplate(source);}
    public void session(){jdbc.execute("SET SESSION time_zone='+00:00'");jdbc.execute("SET SESSION innodb_lock_wait_timeout=2");}
    public OffsetDateTime now(){return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",LocalDateTime.class).atOffset(ZoneOffset.UTC);}
    public Row byNo(long no){return read("payment_no",no,false);}
    public Row byId(long id,boolean lock){return read("id",id,lock);}
    public Row byOrder(long id){return read("order_id",id,true);}
    private Row read(String field,long value,boolean lock){
        List<Row> rows=jdbc.query("SELECT id,payment_no,order_id,store_id,merchant_id,user_id,amount,status,expire_at,merchant_no,term_no,sub_appid,currency,dispatch_state,channel_trade_no,channel_paid_amount,paid_at,success_event_id,channel FROM payment_order WHERE "+field+"=?"+(lock?" FOR UPDATE":""),
            (rs,n)->new Row(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getBigDecimal(7),rs.getString(8),rs.getObject(9,LocalDateTime.class),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),rs.getBigDecimal(16),rs.getObject(17,LocalDateTime.class),rs.getObject(18,Long.class),rs.getString(19)),value);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public record Row(long id,long no,long orderId,long storeId,long merchantId,long userId,
        BigDecimal amount,String status,LocalDateTime expires,String merchantNo,String termNo,String subAppId,
        String currency,String dispatchState,String tradeNo,BigDecimal paidAmount,LocalDateTime paidAt,Long successEventId,String channel){}
    public static LocalDateTime utc(OffsetDateTime time){return time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}
}
