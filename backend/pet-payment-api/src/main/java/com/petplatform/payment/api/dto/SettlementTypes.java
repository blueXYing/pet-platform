package com.petplatform.payment.api.dto;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Contract 78 settlement configuration, order and split instruction DTOs. All IDs are decimal strings. */
public final class SettlementTypes {
    private SettlementTypes() {}

    public record UpsertSettlementConfigCommand(
            CommandContext context,
            String merchantId,
            int settleDays,
            BigDecimal feeRate,
            boolean enabled,
            String reason) {}

    public record SettlementConfigView(
            String merchantId,
            int settleDays,
            BigDecimal feeRate,
            boolean enabled,
            boolean configured,
            long version,
            OffsetDateTime updatedAt) {}

    public record ResumeSettlementCommand(
            CommandContext context,
            String settlementOrderId,
            String reason) {}

    public record RetrySettlementCommand(
            CommandContext context,
            String settlementOrderId,
            String reason) {}

    public record RetryFallbackCommand(
            CommandContext context,
            String settlementOrderId,
            String refundOrderId,
            String reason) {}

    /** Registers a settlement order after verified payment success (used by ORDER event & DEMO closed loop). */
    public record RegisterSettlementCommand(
            CommandContext context,
            String paymentId,
            String orderId,
            String bizType,
            String bizNo,
            String merchantId,
            String storeId,
            BigDecimal originalPrice,
            BigDecimal flashDiscountAmount,
            BigDecimal merchantDiscountAmount,
            BigDecimal platformSubsidyAmount,
            BigDecimal paidAmount,
            String sourcePaidEventId) {}

    /** Starts the T+d countdown after verification completes. */
    public record MarkSettlementVerifiedCommand(
            CommandContext context,
            String settlementOrderId,
            OffsetDateTime verifiedAt,
            String sourceVerifiedEventId) {}

    public record SettlementOrderView(
            String settlementOrderId,
            String paymentId,
            String orderId,
            String bizType,
            String bizNo,
            String merchantId,
            String storeId,
            BigDecimal originalPrice,
            BigDecimal flashDiscountAmount,
            BigDecimal merchantDiscountAmount,
            BigDecimal platformSubsidyAmount,
            BigDecimal paidAmount,
            BigDecimal refundedAmount,
            BigDecimal splitBase,
            BigDecimal feeRateSnapshot,
            BigDecimal merchantShare,
            BigDecimal splitAmount,
            BigDecimal platformRetainedAmount,
            BigDecimal platformSubsidyDue,
            BigDecimal actualMerchantAmount,
            BigDecimal channelSplitFee,
            OffsetDateTime verifiedAt,
            Integer settleDaysSnapshot,
            OffsetDateTime eligibleAt,
            BigDecimal reversedAmount,
            String activeInstructionId,
            String status,
            String holdReason,
            String lastErrorCode,
            String lastErrorMessage,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record SettlementInstructionView(
            String instructionId,
            String settlementOrderId,
            String paymentId,
            String refundOrderId,
            String originInstructionId,
            String merchantId,
            String instructionType,
            int attemptNo,
            String outSeparateNo,
            String separateNo,
            String merchantNo,
            String receiverNo,
            String logNo,
            LocalDate logDate,
            BigDecimal totalAmount,
            BigDecimal merchantAmount,
            BigDecimal platformAmount,
            BigDecimal actualSeparateAmount,
            BigDecimal actualMerchantAmount,
            BigDecimal totalFeeAmount,
            BigDecimal merchantFeeAmount,
            String status,
            String channelStatus,
            String channelFinalStatus,
            String accResultDesc,
            String fallbackReason,
            LocalDate finishDate,
            OffsetDateTime sentAt,
            OffsetDateTime terminalAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record ListSettlementOrdersQuery(
            QueryContext context,
            String merchantId,
            String status,
            int page,
            int size) {}

    public record SettlementPage<T>(List<T> items, long total, int page, int size) {
        public SettlementPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
