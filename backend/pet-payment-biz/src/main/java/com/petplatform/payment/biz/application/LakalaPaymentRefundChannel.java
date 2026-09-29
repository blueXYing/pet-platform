package com.petplatform.payment.biz.application;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundHttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundProtocol;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

/** Offline-capable adapter for the signed 1826/1827/1828 protocol implementation. */
public final class LakalaPaymentRefundChannel implements PaymentRefundChannel {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final LakalaRefundHttpClient client;
    private final Clock clock;

    public LakalaPaymentRefundChannel(LakalaRefundHttpClient client, Clock clock) {
        this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public VerifiedResult submit(RefundRequest request) {
        var input = new LakalaRefundProtocol.RefundInput(request.requestTime(),
                request.merchantNo(), request.termNo(), request.refundNo(), request.amount(),
                request.paymentNo(), request.originalChannelTradeNo(), request.requestIp(),
                request.reason(), request.notifyUrl());
        return map(client.submit(input, nonce()));
    }

    @Override public VerifiedResult query(RefundRequest request) {
        var input = new LakalaRefundProtocol.QueryInput(request.requestTime(),
                request.merchantNo(), request.termNo(), request.refundNo());
        var expected = new LakalaRefundProtocol.ExpectedRefund(request.merchantNo(),
                request.refundNo(), request.amount().movePointRight(2).longValueExact(),
                request.paymentNo(), request.originalChannelTradeNo());
        return map(client.query(input, expected, nonce()));
    }

    private VerifiedResult map(LakalaRefundHttpClient.VerifiedResult result) {
        var value = result.result();
        return new VerifiedResult(value.state().name(), value.refundNo(), value.channelRefundNo(),
                value.requestedCents(), value.actualRefundCents(), value.channelTime(),
                result.rawResponseSha256());
    }

    private RequestNonce nonce() {
        char[] chars = new char[32];
        for (int i = 0; i < chars.length; i++) chars[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new RequestNonce(Long.toString(clock.instant().getEpochSecond()), new String(chars));
    }
}
