package com.petplatform.payment.api.command;

import com.petplatform.payment.api.dto.WalletTypes.WithdrawCommand;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawalOrderView;
import java.util.Map;

/**
 * Merchant-OWNER D1 withdrawal command & Lakala 1272 notification intake (Contract 80 §5 & §6).
 * Ruling 22 / Ruling 40 §5.5: admin cannot initiate withdrawals on behalf of merchants; no SMS OTP.
 */
public interface WalletCommandApi {
    WithdrawalOrderView withdraw(WithdrawCommand command);
    void handleWithdrawalNotification(Map<String, String> headers, byte[] rawBody);
}
