package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedFallbackSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedQueryAmt;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSeparateSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ExpectedSplit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryPlusInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateInput;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

/** Adapts {@link LakalaSacsHttpClient} with fresh timestamp/nonce per outbound SACS request. */
public final class LakalaSettlementChannel implements SettlementChannel {
    private static final char[] ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final LakalaSacsHttpClient client;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LakalaSettlementChannel(LakalaSacsHttpClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VerifiedQueryAmt querySeparableAmount(QueryAmtInput input) {
        return client.queryAmt(input, nextNonce());
    }

    @Override
    public VerifiedSeparateSubmit submitSeparate(SeparateInput input) {
        return client.separate(input, nextNonce());
    }

    @Override
    public VerifiedFallbackSubmit submitFallback(FallbackInput input) {
        return client.fallback(input, nextNonce());
    }

    @Override
    public VerifiedSplitOutcome querySeparateOutcome(QueryPlusInput input, ExpectedSplit expected) {
        return client.queryPlus(input, expected, nextNonce());
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
