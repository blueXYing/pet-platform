package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Input;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Result;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawQueryInput;

/** Outbound channel port for Lakala LAEP merchant wallet & D1 withdrawal (1269/1319/1271). */
public interface WalletChannel {
    record VerifiedBalance(BalanceResult result, String rawResponseSha256) {}
    record VerifiedWithdrawSubmit(WithdrawD1Result result, String rawResponseSha256) {}
    record VerifiedWithdrawOutcome(WithdrawOutcome outcome, String rawResponseSha256) {}

    VerifiedBalance queryBalance(BalanceQueryInput input);
    VerifiedWithdrawSubmit withdrawD1(WithdrawD1Input input);
    VerifiedWithdrawOutcome queryWithdrawal(WithdrawQueryInput input);
}
