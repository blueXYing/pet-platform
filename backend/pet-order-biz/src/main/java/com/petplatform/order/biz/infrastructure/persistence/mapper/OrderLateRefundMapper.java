package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

public interface OrderLateRefundMapper {
    OrderMapperRows.LateRefundOrder lockOrder(@Param("orderId") long orderId);
    OrderMapperRows.LateRefundResult lockResult(@Param("orderId") long orderId);
    int bindUnboundOrder(@Param("orderId") long orderId,
            @Param("refundOrderId") long refundOrderId);
    int bindRefundedOrder(@Param("orderId") long orderId,
            @Param("refundOrderId") long refundOrderId,
            @Param("refundedAmount") BigDecimal refundedAmount);
    int insertCreated(@Param("orderId") long orderId,
            @Param("refundOrderId") long refundOrderId,
            @Param("paymentId") long paymentId, @Param("amount") BigDecimal amount,
            @Param("eventId") long eventId);
    int noteCreatedAfterSuccess(@Param("orderId") long orderId, @Param("eventId") long eventId);
    int insertSucceeded(@Param("orderId") long orderId,
            @Param("refundOrderId") long refundOrderId,
            @Param("paymentId") long paymentId, @Param("amount") BigDecimal amount,
            @Param("eventId") long eventId, @Param("succeededAt") LocalDateTime succeededAt);
    int markSucceeded(@Param("orderId") long orderId,
            @Param("eventId") long eventId, @Param("succeededAt") LocalDateTime succeededAt);
    int statusLog(@Param("id") long id, @Param("orderId") long orderId,
            @Param("from") String from, @Param("to") String to,
            @Param("eventType") String eventType, @Param("requestId") String requestId);
}
