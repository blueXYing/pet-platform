package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * MyBatis XML mapper for Stage 6 reconciliation tables ({@code reconcile_job_run}, {@code reconcile_diff})
 * plus strictly read-only reporting/scanning queries over {@code settlement_order} and {@code settlement_instruction}.
 * Zero SQL annotations; zero mutations on settlement ledger tables.
 */
public interface PaymentReconcileMapper {

    // ---- reconcile_job_run ----
    JobRunRow selectJobRunByDateAndMerchantForUpdate(Map<String, Object> params);
    JobRunRow selectJobRunById(long id);
    int insertJobRun(Map<String, Object> params);
    int restartJobRun(Map<String, Object> params);
    int completeJobRun(Map<String, Object> params);
    int failJobRun(Map<String, Object> params);

    // ---- reconcile_diff ----
    int insertDiffIgnore(Map<String, Object> params);
    DiffRow selectDiffById(long id);
    DiffRow selectDiffByIdForUpdate(long id);
    DiffRow selectDiffByResolveRequestKeyForUpdate(byte[] resolveRequestKey);
    int resolveDiff(Map<String, Object> params);
    long countDiffs(Map<String, Object> params);
    List<DiffRow> listDiffs(Map<String, Object> params);

    // ---- Read-only Platform Subsidy Payable Report (settlement_order) ----
    SubsidySummaryRow selectSubsidyReportSummary(Map<String, Object> params);
    long countSubsidyReportGroups(Map<String, Object> params);
    List<SubsidyGroupRow> listSubsidyReportRows(Map<String, Object> params);

    // ---- Read-only Reconciliation & Compensation Scan Queries ----
    List<String> selectDistinctReconcileMerchantNos(Map<String, Object> params);
    List<ReconcileInstructionRow> selectInstructionsForReconcileDate(Map<String, Object> params);
    ReconcileInstructionRow selectInstructionByOutSeparateNo(String outSeparateNo);
    ReconcileInstructionRow selectInstructionBySeparateNo(String separateNo);
    List<WaitingOrderScanRow> selectOverdueWaitingOrders(Map<String, Object> params);
    WaitingOrderScanRow selectWaitingOrderByIdForUpdate(long id);
    List<StuckInstructionScanRow> selectStuckInstructions(Map<String, Object> params);
    StuckInstructionScanRow selectStuckInstructionByIdForUpdate(long id);

    final class JobRunRow {
        public long id;
        public LocalDate reconcileDate;
        public String merchantNo;
        public String status;
        public int channelCount;
        public int localCount;
        public int diffCount;
        public String errorMessage;
        public LocalDateTime startedAt;
        public LocalDateTime finishedAt;
        public LocalDateTime createdAt;
    }

    final class DiffRow {
        public long id;
        public LocalDate reconcileDate;
        public String diffKey;
        public String bizType;
        public String diffType;
        public Long settlementOrderId;
        public Long instructionId;
        public String outSeparateNo;
        public String separateNo;
        public Long merchantId;
        public BigDecimal localAmount;
        public BigDecimal channelAmount;
        public BigDecimal localFeeAmount;
        public BigDecimal channelFeeAmount;
        public String localStatus;
        public String channelStatus;
        public String detail;
        public String status;
        public Long resolvedByOperatorId;
        public String resolveNote;
        public LocalDateTime resolvedAt;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    final class SubsidySummaryRow {
        public long totalOrderCount;
        public BigDecimal totalMerchantShare;
        public BigDecimal totalSplitAmount;
        public BigDecimal totalSubsidyDue;
    }

    final class SubsidyGroupRow {
        public long merchantId;
        public String period;
        public long orderCount;
        public BigDecimal totalPaidAmount;
        public BigDecimal totalMerchantShare;
        public BigDecimal totalSplitAmount;
        public BigDecimal totalReversedAmount;
        public BigDecimal totalSubsidyDue;
    }

    final class ReconcileInstructionRow {
        public long id;
        public long settlementOrderId;
        public long merchantId;
        public String instructionType;
        public String outSeparateNo;
        public String separateNo;
        public String merchantNo;
        public String receiverNo;
        public String logNo;
        public LocalDate logDate;
        public BigDecimal totalAmount;
        public BigDecimal merchantAmount;
        public BigDecimal actualSeparateAmount;
        public BigDecimal totalFeeAmount;
        public BigDecimal merchantFeeAmount;
        public String status;
        public LocalDate finishDate;
        public LocalDateTime sentAt;
        public LocalDateTime createdAt;
    }

    final class WaitingOrderScanRow {
        public long id;
        public String status;
        public LocalDateTime eligibleAt;
        public int dueScheduleRound;
        public LocalDateTime updatedAt;
    }

    final class StuckInstructionScanRow {
        public long id;
        public long settlementOrderId;
        public String status;
        public LocalDateTime sentAt;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }
}
