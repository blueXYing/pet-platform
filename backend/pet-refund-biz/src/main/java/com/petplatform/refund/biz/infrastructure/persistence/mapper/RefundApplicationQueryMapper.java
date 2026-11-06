package com.petplatform.refund.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Contract 57 merchant read side: lock-free projections of refund_application for one store.
 * CCR-M-REFUND-VIEW-001: applicantUserId is an internal join key for the user-basics read only
 * (never rendered); refundCreatedAt is the bound refund_order row's own created_at (same module).
 */
public interface RefundApplicationQueryMapper {
    long countPending(@Param("merchant") long merchant, @Param("store") long store);
    List<Row> selectPendingPage(@Param("merchant") long merchant, @Param("store") long store,
            @Param("limit") int limit, @Param("offset") long offset);
    Row selectStoreApplication(@Param("merchant") long merchant, @Param("store") long store,
            @Param("application") long application);
    class Row {
        public Long id,applicationNo,orderId,version,decisionId,refundOrderId,applicantUserId;
        public String status,reasonCode;
        public BigDecimal requestedAmount;
        public LocalDateTime createdAt,merchantDeadline,decidedAt,refundCreatedAt;
    }
}
