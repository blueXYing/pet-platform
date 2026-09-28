package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OrderPaymentMapper {
    LocalDateTime databaseNow();
    void setUtcTimeZone();
    void setLockWaitTimeout();
    List<OrderMapperRows.Locator> locate(@Param("orderId") long orderId);
    OrderMapperRows.PaymentOrder lock(@Param("orderId") long orderId);
    OrderMapperRows.PaymentResult lockResult(@Param("orderId") long orderId);
    int markPaid(@Param("id") long id, @Param("version") long version,
            @Param("reservationId") long reservationId, @Param("paidAt") LocalDateTime paidAt,
            @Param("confirmDeadline") LocalDateTime confirmDeadline);
    int recordLatePayment(@Param("id") long id, @Param("version") long version,
            @Param("reservationId") long reservationId, @Param("paidAt") LocalDateTime paidAt);
    int insertResult(@Param("id") long id, @Param("orderId") long orderId,
            @Param("paymentId") long paymentId, @Param("sourceEventId") long sourceEventId,
            @Param("channelTradeNo") String channelTradeNo, @Param("paidAmount") BigDecimal paidAmount,
            @Param("paidAt") LocalDateTime paidAt, @Param("resultType") String resultType);
    int statusLog(@Param("id") long id, @Param("orderId") long orderId,
            @Param("dimension") String dimension, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("eventType") String eventType,
            @Param("requestId") String requestId);
}
