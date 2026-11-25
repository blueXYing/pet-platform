package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedDetailPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedResultListPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListInput;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

/** Adapts {@link LakalaSacsReconcileHttpClient} with fresh timestamp/nonce per outbound 1879/1877 query. */
public final class LakalaSettlementReconcileChannel implements SettlementReconcileChannel {
    private static final char[] ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final LakalaSacsReconcileHttpClient client;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LakalaSettlementReconcileChannel(LakalaSacsReconcileHttpClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VerifiedResultListPage queryResultList(QueryResultListInput input) {
        return client.queryResultList(input, nextNonce());
    }

    @Override
    public VerifiedDetailPage queryDetail(QueryDetailInput input) {
        return client.queryDetail(input, nextNonce());
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
