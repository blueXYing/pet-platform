package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Input;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Result;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.ChannelRejectedException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedV2Response;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.PreparedV2Request;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Lakala LAEP wallet & D1 withdrawal channel (1269/1319/1271) reusing Stage 4's
 * {@link LakalaV2HttpClient#postV2(PreparedV2Request, RequestNonce)}.
 */
public final class LakalaWalletChannel implements WalletChannel {
    private static final char[] ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final LakalaV2HttpClient client;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LakalaWalletChannel(LakalaV2HttpClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VerifiedBalance queryBalance(BalanceQueryInput input) {
        PreparedV2Request request = LakalaLaepProtocol.prepareBalanceQuery(input, nowMillis(), nextReqId());
        VerifiedV2Response response = requireSuccess(client.postV2(request, nextNonce()));
        BalanceResult result = LakalaLaepProtocol.parseBalanceRespData(response.respData());
        return new VerifiedBalance(result, response.rawResponseSha256());
    }

    @Override
    public VerifiedWithdrawSubmit withdrawD1(WithdrawD1Input input) {
        PreparedV2Request request = LakalaLaepProtocol.prepareWithdrawD1(input, nowMillis(), nextReqId());
        VerifiedV2Response response = requireSuccess(client.postV2(request, nextNonce()));
        WithdrawD1Result result = LakalaLaepProtocol.parseWithdrawD1RespData(
                response.respData(), input.merOrderNo());
        return new VerifiedWithdrawSubmit(result, response.rawResponseSha256());
    }

    @Override
    public VerifiedWithdrawOutcome queryWithdrawal(WithdrawQueryInput input) {
        PreparedV2Request request = LakalaLaepProtocol.prepareWithdrawQuery(input, nowMillis(), nextReqId());
        VerifiedV2Response response = requireSuccess(client.postV2(request, nextNonce()));
        WithdrawOutcome outcome = LakalaLaepProtocol.parseWithdrawOutcomeRespData(
                response.respData(), input.receiverNo(), input.merOrderNo(), input.drawJnl());
        return new VerifiedWithdrawOutcome(outcome, response.rawResponseSha256());
    }

    private static VerifiedV2Response requireSuccess(VerifiedV2Response response) {
        if (!response.isSuccess()) {
            throw new ChannelRejectedException(response.retCode(), response.retMsg());
        }
        return response;
    }

    private long nowMillis() {
        return Math.max(1L, clock.millis());
    }

    private String nextReqId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private RequestNonce nextNonce() {
        long seconds = Math.max(1L, clock.instant().getEpochSecond());
        char[] chars = new char[12];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALNUM[random.nextInt(ALNUM.length)];
        }
        return new RequestNonce(Long.toString(seconds), new String(chars));
    }
}
