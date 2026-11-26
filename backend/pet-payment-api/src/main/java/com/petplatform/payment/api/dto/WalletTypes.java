package com.petplatform.payment.api.dto;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contract 80 §4.4 & §5 merchant wallet and D1 withdrawal DTOs (CCR-PAY-CENTER-001 stage 5).
 * Ruling 40 §5.5 / §9: merchant wallet never exposes platform subsidy; withdrawal requires no SMS OTP.
 */
public final class WalletTypes {
    private WalletTypes() {}

    public record MerchantWalletView(
            String receiverStatus,
            boolean receiverActive,
            BigDecimal availableBalance,
            BigDecimal pendingSettlementAmount,
            long pendingSettlementCount,
            String currency,
            OffsetDateTime balanceAsOf,
            boolean channelBalanceAvailable) {}

    public record WithdrawalOrderView(
            String withdrawalId,
            String merOrderNo,
            String merchantId,
            String receiverNo,
            BigDecimal amount,
            BigDecimal feeAmount,
            BigDecimal netAmount,
            String status,
            String drawJnl,
            String failReason,
            String channelState,
            String requestedByUserId,
            OffsetDateTime requestedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime createdAt) {}

    public record WithdrawCommand(
            CommandContext context,
            String merchantId,
            BigDecimal amount) {}

    public record ListWithdrawalsQuery(
            QueryContext context,
            String merchantId,
            String status,
            int page,
            int size) {}

    public record WalletPage<T>(List<T> items, long total, int page, int size) {
        public WalletPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
