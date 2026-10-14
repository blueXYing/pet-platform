package com.petplatform.refund.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Read-only contract-63 platform refund statements; SQL lives in AdminRefundQueryMapper.xml.
 * refund_order LEFT JOIN refund_execution (0..1, late/aftersale funding fact) LEFT JOIN
 * refund_application (linked decision projection). Cipher columns are never selected.
 */
public interface AdminRefundQueryMapper {

    long countRefunds(
            @Param("status") String status,
            @Param("sourceType") String sourceType,
            @Param("refundType") String refundType,
            @Param("orderId") Long orderId,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    List<RefundRow> selectRefundPage(
            @Param("status") String status,
            @Param("sourceType") String sourceType,
            @Param("refundType") String refundType,
            @Param("orderId") Long orderId,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo,
            @Param("limit") int limit,
            @Param("offset") long offset);

    RefundRow selectRefundById(@Param("id") long id);

    /** Flattened read row; execution/application columns are null when the join misses. */
    class RefundRow {
        private Long id;
        private Long refundNo;
        private Long orderId;
        private String refundType;
        private String sourceType;
        private String status;
        private BigDecimal refundAmount;
        private String initiatorType;
        private String channelRefundNo;
        private LocalDateTime succeededAt;
        private LocalDateTime createdAt;
        private Long version;
        private Long applicationId;
        private String applicationStatus;
        private BigDecimal requestedAmount;
        private LocalDateTime merchantDeadline;
        private LocalDateTime decidedAt;
        private String channelTradeNo;
        private BigDecimal executionRefundAmount;
        private LocalDateTime channelPaidAt;
        private String currency;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getRefundNo() { return refundNo; }
        public void setRefundNo(Long refundNo) { this.refundNo = refundNo; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public String getRefundType() { return refundType; }
        public void setRefundType(String refundType) { this.refundType = refundType; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public BigDecimal getRefundAmount() { return refundAmount; }
        public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
        public String getInitiatorType() { return initiatorType; }
        public void setInitiatorType(String initiatorType) { this.initiatorType = initiatorType; }
        public String getChannelRefundNo() { return channelRefundNo; }
        public void setChannelRefundNo(String channelRefundNo) { this.channelRefundNo = channelRefundNo; }
        public LocalDateTime getSucceededAt() { return succeededAt; }
        public void setSucceededAt(LocalDateTime succeededAt) { this.succeededAt = succeededAt; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public Long getApplicationId() { return applicationId; }
        public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }
        public String getApplicationStatus() { return applicationStatus; }
        public void setApplicationStatus(String applicationStatus) { this.applicationStatus = applicationStatus; }
        public BigDecimal getRequestedAmount() { return requestedAmount; }
        public void setRequestedAmount(BigDecimal requestedAmount) { this.requestedAmount = requestedAmount; }
        public LocalDateTime getMerchantDeadline() { return merchantDeadline; }
        public void setMerchantDeadline(LocalDateTime merchantDeadline) { this.merchantDeadline = merchantDeadline; }
        public LocalDateTime getDecidedAt() { return decidedAt; }
        public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
        public String getChannelTradeNo() { return channelTradeNo; }
        public void setChannelTradeNo(String channelTradeNo) { this.channelTradeNo = channelTradeNo; }
        public BigDecimal getExecutionRefundAmount() { return executionRefundAmount; }
        public void setExecutionRefundAmount(BigDecimal executionRefundAmount) { this.executionRefundAmount = executionRefundAmount; }
        public LocalDateTime getChannelPaidAt() { return channelPaidAt; }
        public void setChannelPaidAt(LocalDateTime channelPaidAt) { this.channelPaidAt = channelPaidAt; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
    }
}
