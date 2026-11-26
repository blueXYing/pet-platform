package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** MyBatis XML mapper for Contract 78 settlement tables. Zero SQL annotations. */
public interface PaymentSettlementMapper {
    // ---- Config & Audit ----
    ConfigRow selectConfigByMerchant(long merchantId);
    ConfigRow selectConfigByMerchantForUpdate(long merchantId);
    int insertConfig(Map<String, Object> params);
    int updateConfig(Map<String, Object> params);
    int insertConfigAudit(Map<String, Object> params);
    ConfigAuditRow selectConfigAuditByRequestKey(byte[] requestKey);

    // ---- Settlement Order ----
    OrderRow selectOrderById(long id);
    OrderRow selectOrderByIdForUpdate(long id);
    OrderRow selectOrderByPaymentId(long paymentId);
    OrderRow selectOrderByPaymentIdForUpdate(long paymentId);
    OrderRow selectOrderByOrderId(long orderId);
    OrderRow selectOrderByOrderIdForUpdate(long orderId);
    OrderRow selectOrderByBiz(Map<String, Object> params);
    int insertOrder(Map<String, Object> params);
    int markOrderWaiting(Map<String, Object> params);
    int markOrderOnHold(Map<String, Object> params);
    int markOrderCanceled(Map<String, Object> params);
    int updateOrderRefundAmounts(Map<String, Object> params);
    int markOrderSplitting(Map<String, Object> params);
    int markOrderSplitSucceeded(Map<String, Object> params);
    int markOrderSplitFailed(Map<String, Object> params);
    int markOrderReversing(Map<String, Object> params);
    int markOrderFallbackSucceeded(Map<String, Object> params);
    int markOrderReverseFailed(Map<String, Object> params);
    int updatePostSplitRefundProjection(Map<String, Object> params);
    int resumeOrRetryOrderToWaiting(Map<String, Object> params);
    List<OrderRow> listOrders(Map<String, Object> params);
    long countOrders(Map<String, Object> params);

    // ---- Settlement Instruction ----
    InstructionRow selectInstructionById(long id);
    InstructionRow selectInstructionByIdForUpdate(long id);
    InstructionRow selectInstructionByOutSeparateNo(String outSeparateNo);
    InstructionRow selectSucceededSplitInstruction(long settlementOrderId);
    List<InstructionRow> selectFallbackInstructionsByRefundOrderId(long refundOrderId);
    List<InstructionRow> selectInstructionsByOrderId(long settlementOrderId);
    Integer selectMaxAttemptNo(Map<String, Object> params);
    long countActiveSplitInstructions(long settlementOrderId);
    long countActiveFallbackInstructions(long settlementOrderId);
    int insertInstruction(Map<String, Object> params);
    int markInstructionProcessing(Map<String, Object> params);
    int markInstructionSucceeded(Map<String, Object> params);
    int markInstructionFailed(Map<String, Object> params);

    // ---- Instruction Receipt ----
    long countReceiptByDigest(Map<String, Object> params);
    int insertInstructionReceipt(Map<String, Object> params);

    // ---- Action Idempotency ----
    int insertActionRequest(Map<String, Object> params);
    ActionRequestRow selectActionRequestForUpdate(byte[] requestKey);

    final class ConfigRow {
        public long id;
        public long merchantId;
        public int settleDays;
        public BigDecimal feeRate;
        public boolean enabled;
        public String lakalaMerchantNo;
        public String lakalaTermNo;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    final class ConfigAuditRow {
        public long id;
        public long merchantId;
        public long configId;
        public Integer beforeSettleDays;
        public int afterSettleDays;
        public BigDecimal beforeFeeRate;
        public BigDecimal afterFeeRate;
        public Boolean beforeEnabled;
        public boolean afterEnabled;
        public String beforeLakalaMerchantNo;
        public String afterLakalaMerchantNo;
        public String beforeLakalaTermNo;
        public String afterLakalaTermNo;
        public String operatorType;
        public Long operatorId;
        public String reason;
        public LocalDateTime createdAt;
    }

    final class OrderRow {
        public long id;
        public long paymentId;
        public Long orderId;
        public String bizType;
        public String bizNo;
        public long merchantId;
        public Long storeId;
        public BigDecimal originalPrice;
        public BigDecimal flashDiscountAmount;
        public BigDecimal merchantDiscountAmount;
        public BigDecimal platformSubsidyAmount;
        public BigDecimal paidAmount;
        public BigDecimal refundedAmount;
        public BigDecimal splitBase;
        public BigDecimal feeRateSnapshot;
        public BigDecimal merchantShare;
        public BigDecimal splitAmount;
        public BigDecimal platformRetainedAmount;
        public BigDecimal platformSubsidyDue;
        public BigDecimal actualMerchantAmount;
        public BigDecimal channelSplitFee;
        public LocalDateTime verifiedAt;
        public Integer settleDaysSnapshot;
        public LocalDateTime eligibleAt;
        public int dueScheduleRound;
        public BigDecimal reversedAmount;
        public Long activeInstructionId;
        public String status;
        public String holdReason;
        public String lastErrorCode;
        public String lastErrorMessage;
        public Long sourcePaidEventId;
        public Long sourceVerifiedEventId;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    final class InstructionRow {
        public long id;
        public long settlementOrderId;
        public long paymentId;
        public Long refundOrderId;
        public Long originInstructionId;
        public long merchantId;
        public String instructionType;
        public int attemptNo;
        public String outSeparateNo;
        public String separateNo;
        public String merchantNo;
        public String receiverNo;
        public String logNo;
        public LocalDate logDate;
        public BigDecimal totalAmount;
        public BigDecimal merchantAmount;
        public BigDecimal platformAmount;
        public BigDecimal actualSeparateAmount;
        public BigDecimal actualMerchantAmount;
        public BigDecimal totalFeeAmount;
        public BigDecimal merchantFeeAmount;
        public String status;
        public String channelStatus;
        public String channelFinalStatus;
        public String accResultDesc;
        public String fallbackReason;
        public String submitResponseSha256;
        public String terminalReceiptSha256;
        public LocalDate finishDate;
        public LocalDateTime sentAt;
        public LocalDateTime terminalAt;
        public Long resultEventId;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    final class ActionRequestRow {
        public long id;
        public String actionType;
        public long settlementOrderId;
        public String requestFingerprint;
        public LocalDateTime createdAt;
    }
}
