package com.petplatform.refund.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RefundExecutionMapper {
    List<RefundMapperRows.OrderPresence> lockPresence(@Param("orderId") long orderId);
    int insertMerchantRefund(java.util.Map<String,Object> row);
    int insertMerchantExecution(java.util.Map<String,Object> row);
    void setUtcTimeZone();
    void setLockWaitTimeout();
    LocalDateTime databaseNow();
    int insertRefundOrder(@Param("refundId") long refundId, @Param("refundNo") long refundNo,
            @Param("orderId") long orderId, @Param("amount") BigDecimal amount,
            @Param("now") LocalDateTime now);
    int insertExecution(@Param("refundId") long refundId, @Param("refundNo") long refundNo,
            @Param("orderId") long orderId, @Param("paymentId") long paymentId,
            @Param("paymentNo") long paymentNo, @Param("storeId") long storeId,
            @Param("merchantId") long merchantId, @Param("userId") long userId,
            @Param("paymentSuccessEventId") long paymentSuccessEventId,
            @Param("lateEventId") long lateEventId, @Param("channelTradeNo") String channelTradeNo,
            @Param("amount") BigDecimal amount, @Param("paidAt") LocalDateTime paidAt,
            @Param("requestId") String requestId, @Param("createdEventId") long createdEventId,
            @Param("now") LocalDateTime now);
    Integer successProofCount(@Param("refundId") long refundId,
            @Param("receipt") String receipt, @Param("refundNo") String refundNo);
    List<RefundMapperRows.Binding> lockById(@Param("refundId") long refundId);
    List<RefundMapperRows.Binding> lockByOrder(@Param("orderId") long orderId);
    int markUnknown(@Param("refundId") long refundId);
    int setNextQuery(@Param("refundId") long refundId, @Param("due") LocalDateTime due);
    LocalDateTime firstQueryAt(@Param("refundId") long refundId, @Param("due") LocalDateTime due);
    int setFirstQuery(@Param("refundId") long refundId, @Param("first") LocalDateTime first);
    int markRefundSucceeded(@Param("refundId") long refundId,
            @Param("channelRefundNo") String channelRefundNo, @Param("succeededAt") LocalDateTime succeededAt);
    int markExecutionSucceeded(@Param("refundId") long refundId,
            @Param("eventId") long eventId, @Param("receipt") String receipt);
    int insertSuccessTransaction(@Param("id") long id, @Param("refundId") long refundId,
            @Param("receipt") String receipt, @Param("action") String action,
            @Param("refundNo") String refundNo);
    int resolveIssue(@Param("refundId") long refundId);
    int insertIssue(@Param("refundId") long refundId, @Param("code") String code);
    List<RefundMapperRows.Candidate> scanOpen(@Param("after") long after,@Param("late") boolean late,@Param("merchant") boolean merchant,@Param("application") boolean application,@Param("aftersale")boolean aftersale);
    LocalDateTime persistedFirstQueryAt(@Param("refund")long refund);
}
