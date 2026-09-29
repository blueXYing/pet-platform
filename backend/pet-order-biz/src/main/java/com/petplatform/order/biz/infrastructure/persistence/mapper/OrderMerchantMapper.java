package com.petplatform.order.biz.infrastructure.persistence.mapper;
import java.util.Map;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Param;
public interface OrderMerchantMapper {
    int reserve(Map<String,Object> values);
    Binding binding(Map<String,Object> values);
    int succeed(@Param("id") long id,@Param("result") byte[] result);
    int insertDecision(Map<String,Object> values);
    Decision decision(@Param("orderId") long orderId,@Param("round") int round);
    int decide(Map<String,Object> values);
    int log(Map<String,Object> values);
    int project(@Param("orderId") long orderId,@Param("refundId") long refundId,@Param("amount") BigDecimal amount);
    final class Binding {
        public Long id; public String canonicalVersion,payloadSha256,state;
        public byte[] canonicalBytes,resultBytes; public Integer resultVersion;
    }
    final class Decision {
        public Long id,orderId,storeId,commandId,operatorId,eventId,refundOrderId;
        public Integer confirmRound; public String action,reasonCode;
        public byte[] reasonText,internalNote; public LocalDateTime decidedAt;
    }
}
