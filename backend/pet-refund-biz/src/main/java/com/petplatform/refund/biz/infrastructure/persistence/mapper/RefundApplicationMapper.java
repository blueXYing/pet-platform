package com.petplatform.refund.biz.infrastructure.persistence.mapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface RefundApplicationMapper {
    void utc();void lockWait();LocalDateTime now();
    int reserve(Map<String,Object> values);Binding binding(Map<String,Object> key);Binding bindingById(@Param("id")long id);
    int succeed(@Param("id")long id,@Param("result")byte[] result);
    Row hint(@Param("id")long id);Row row(@Param("id")long id);Row active(@Param("order")long order);
    int insertApplication(Map<String,Object> values);int insertDecision(Map<String,Object> values);int decide(Map<String,Object> values);
    Decision decision(@Param("id")long id);Created created(@Param("refund")long refund);
    int insertRefund(Map<String,Object> values);int insertExecution(Map<String,Object> values);int bindRefund(Map<String,Object> values);
    List<Row> scan(@Param("after")long after);
    Row latestRejected(@Param("order")long order);
    int recordRecoveryIssue(@Param("application")long application,@Param("order")long order,@Param("store")long store,@Param("code")String code);
    int resolveRecoveryIssues(@Param("application")long application);
    class Binding {public Long id,actorId;public String state,payloadSha256,canonicalVersion;public byte[] commandNamespace,actorType,scope,requestId,canonicalBytes,resultBytes;public Integer resultVersion;}
    class Row {
        public Long id,applicationNo,orderId,applicantUserId,storeId,merchantId,reservationId,paymentId,paymentNo,paymentSuccessEventId,
            createdCommandId,createdEventId,version,decisionId,refundOrderId;
        public String status,reasonCode,channelTradeNo,requestId;
        public byte[] reasonTextCipher;
        public BigDecimal requestedAmount;
        public LocalDateTime paidAt,createdAt,merchantDeadline,decidedAt;
    }
    class Decision {public Long id,applicationId,commandId,eventId,operatorId;public String status,operatorType,requestId;public byte[] reasonCipher;public LocalDateTime decidedAt;}
    class Created {public Long id,refundNo,orderId,applicationId,sourceBizId,sourceDecisionId,paymentId,paymentNo,storeId,merchantId,userId,paymentSuccessEventId,createdEventId,bindingVersion;
        public String sourceType,executionSourceType,channelTradeNo,currency,requestId,refundType,channel;
        public Long lateEventId,sourceEventId;public BigDecimal refundAmount,paidAmount,businessAmount,refundRatio;public LocalDateTime paidAt,createdAt,businessCreatedAt;}
}
