package com.petplatform.payment.api.dto;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contract 80 §4.5 & §4.6 read-only platform subsidy payable report and daily reconciliation DTOs.
 * All IDs are decimal strings; amounts are BigDecimal with 2 decimal places (yuan); timestamps are UTC.
 */
public final class ReconcileTypes {
    private ReconcileTypes() {}

    public record SubsidyReportQuery(
            QueryContext context,
            String merchantId,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size) {}

    public record SubsidyReportSummary(
            long totalOrderCount,
            BigDecimal totalMerchantShare,
            BigDecimal totalSplitAmount,
            BigDecimal totalSubsidyDue) {}

    public record SubsidyReportRow(
            String merchantId,
            String period,
            long orderCount,
            BigDecimal totalPaidAmount,
            BigDecimal totalMerchantShare,
            BigDecimal totalSplitAmount,
            BigDecimal totalReversedAmount,
            BigDecimal totalSubsidyDue) {}

    public record SubsidyReportResponse(
            SubsidyReportSummary summary,
            List<SubsidyReportRow> items,
            long total,
            int page,
            int size) {
        public SubsidyReportResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ReconcileDiffView(
            String diffId,
            LocalDate reconcileDate,
            String bizType,
            String diffType,
            String settlementOrderId,
            String instructionId,
            String outSeparateNo,
            String separateNo,
            String merchantId,
            BigDecimal localAmount,
            BigDecimal channelAmount,
            BigDecimal localFeeAmount,
            BigDecimal channelFeeAmount,
            String localStatus,
            String channelStatus,
            String detail,
            String status,
            String resolvedByOperatorId,
            String resolveNote,
            OffsetDateTime resolvedAt,
            OffsetDateTime createdAt) {}

    public record ListReconcileDiffsQuery(
            QueryContext context,
            LocalDate reconcileDate,
            String diffType,
            String status,
            int page,
            int size) {}

    public record ResolveReconcileDiffCommand(
            CommandContext context,
            String diffId,
            String note) {}

    public record ReconcilePage<T>(List<T> items, long total, int page, int size) {
        public ReconcilePage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ReconcileRunSummary(
            String runId,
            LocalDate reconcileDate,
            String merchantNo,
            String status,
            int channelCount,
            int localCount,
            int diffCount,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {}
}
