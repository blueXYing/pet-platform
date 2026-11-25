package com.petplatform.payment.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.WalletTypes.ListWithdrawalsQuery;
import com.petplatform.payment.api.dto.WalletTypes.MerchantWalletView;
import com.petplatform.payment.api.dto.WalletTypes.WalletPage;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawalOrderView;

/** Read-only merchant wallet balance (Lakala 1269) and withdrawal ledger queries (Contract 80 §4.4 & §5). */
public interface WalletQueryApi {
    MerchantWalletView getMerchantWallet(String merchantId, QueryContext context);
    WalletPage<WithdrawalOrderView> listWithdrawals(ListWithdrawalsQuery query);
}
