package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OrderAutoConfirmInspectionMapper {
    LocalDateTime databaseNow();
    List<Row> scan(@Param("afterOrderId") long afterOrderId, @Param("limit") int limit);

    final class Row {
        public Long id, refundOrderId, currentRefundApplicationId, currentAftersaleId, paymentId, sourceEventId;
        public Integer rescheduleCount;
        public String paymentStatus, verificationStatus, cancelReason, resultType, confirmMode;
        public BigDecimal payAmount, channelPaidAmount, refundedAmount;
        public LocalDateTime paidAt, confirmDeadline, channelPaidAt, confirmedAt, canceledAt;
    }
}
