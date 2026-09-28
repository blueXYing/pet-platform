package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OrderExpiryMapper {
    void setUtcTimeZone();
    void setLockWaitTimeout();
    Long storeId(@Param("orderId") long orderId);
    List<Long> scanPendingAfter(@Param("afterOrderId") long afterOrderId, @Param("limit") int limit);
    BigDecimal databaseEpochSeconds();
    OrderMapperRows.ExpiryOrder lock(@Param("orderId") long orderId);
    Long findExpiryLog(@Param("orderId") long orderId, @Param("requestId") String requestId);
    Long findLatePaymentResult(@Param("orderId") long orderId);
    int cancel(@Param("orderId") long orderId, @Param("version") long version,
            @Param("deadline") LocalDateTime deadline, @Param("observedNow") LocalDateTime observedNow);
    int statusLog(@Param("id") long id, @Param("orderId") long orderId,
            @Param("requestId") String requestId);
}
