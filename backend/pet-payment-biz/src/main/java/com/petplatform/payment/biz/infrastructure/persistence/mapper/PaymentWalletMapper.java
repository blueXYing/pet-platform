package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * MyBatis XML mapper for Stage 5 wallet & D1 withdrawal tables ({@code withdrawal_order},
 * {@code withdrawal_receipt}) plus read-only pending settlement & receiver status projections.
 * Zero SQL annotations; all SQL lives in {@code mapper/PaymentWalletMapper.xml}.
 */
public interface PaymentWalletMapper {

    // ---- withdrawal_order ----
    int insertWithdrawal(Map<String, Object> params);
    WithdrawalRow selectById(long id);
    WithdrawalRow selectByIdForUpdate(long id);
    WithdrawalRow selectByRequestKeyForUpdate(byte[] requestKey);
    WithdrawalRow selectByMerOrderNoForUpdate(String merOrderNo);
    WithdrawalRow selectByDrawJnlForUpdate(String drawJnl);
    int markProcessing(Map<String, Object> params);
    int markProcessingUnknown(Map<String, Object> params);
    int updateNonTerminalState(Map<String, Object> params);
    int markSucceeded(Map<String, Object> params);
    int markFailed(Map<String, Object> params);
    long countWithdrawals(Map<String, Object> params);
    List<WithdrawalRow> listWithdrawals(Map<String, Object> params);
    List<StuckWithdrawalRow> selectStuckWithdrawals(Map<String, Object> params);

    // ---- withdrawal_receipt ----
    int insertReceiptIgnore(Map<String, Object> params);

    // ---- Read-only Wallet Projections ----
    PendingSettlementRow selectPendingSettlementSummary(long merchantId);
    ReceiverSnapshotRow selectReceiverSnapshot(long merchantId);
    AcquiringMerchantRow selectMerchantAcquiringAccount(long merchantId);

    final class WithdrawalRow {
        public long id;
        public String merOrderNo;
        public long merchantId;
        public String receiverNo;
        public BigDecimal amount;
        public BigDecimal feeAmount;
        public BigDecimal netAmount;
        public String drawMode;
        public String status;
        public String drawJnl;
        public String channelState;
        public String settleNo;
        public String failReason;
        public long requestedByUserId;
        public String requestFingerprint;
        public String submitResponseSha256;
        public String terminalReceiptSha256;
        public Long resultEventId;
        public LocalDateTime requestedAt;
        public LocalDateTime sentAt;
        public LocalDateTime finishedAt;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    final class StuckWithdrawalRow {
        public long id;
        public String status;
        public LocalDateTime sentAt;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    final class PendingSettlementRow {
        public long pendingCount;
        public BigDecimal pendingAmount;
    }

    final class ReceiverSnapshotRow {
        public String status;
        public String receiverNo;
    }

    final class AcquiringMerchantRow {
        public boolean enabled;
        public String lakalaMerchantNo;
        public String lakalaTermNo;
    }
}
