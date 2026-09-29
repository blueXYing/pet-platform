package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OrderAutoConfirmMapper {
    Row lock(@Param("id") long id);
    List<String> proofs(@Param("id") long id, @Param("requestId") String requestId);
    int confirm(@Param("id") long id, @Param("version") long version,
            @Param("round") int round, @Param("deadline") LocalDateTime deadline, @Param("now") LocalDateTime now);
    int log(@Param("id") long id, @Param("orderId") long orderId,
            @Param("from") String from, @Param("to") String to, @Param("type") String type,
            @Param("requestId") String requestId, @Param("remark") String remark);
    int anomalyCount(@Param("orderId") long orderId, @Param("requestId") String requestId,
            @Param("remark") String remark);
    List<Long> candidates(@Param("after") long after, @Param("limit") int limit);
    class Row {
        public Long serviceId;
        public String fulfillmentType;
        public LocalDateTime appointmentStartAt, appointmentEndAt;
        public Long id, storeId, userId, merchantId, reservationId, version;
        public Long refundOrderId, currentRefundApplicationId, currentAftersaleId;
        public Integer rescheduleCount;
        public String orderStage, paymentStatus, verificationStatus, confirmMode, cancelReason;
        public LocalDateTime confirmDeadline, paidAt, confirmedAt, canceledAt;
        public BigDecimal payAmount, refundedAmount;
    }
}
