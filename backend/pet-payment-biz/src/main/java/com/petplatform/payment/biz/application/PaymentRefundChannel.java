package com.petplatform.payment.biz.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Trusted, signature-verifying channel boundary. No business module may call the provider. */
public interface PaymentRefundChannel {
    VerifiedResult submit(RefundRequest request);
    VerifiedResult query(RefundRequest request);

    record RefundRequest(String merchantNo, String termNo, String refundNo, BigDecimal amount,
            String paymentNo, String originalChannelTradeNo, LocalDateTime requestTime,
            String requestIp, String notifyUrl) {
        @Override public String toString() { return "RefundRequest[redacted]"; }
    }

    record VerifiedResult(String state, String refundNo, String channelRefundNo,
            long requestedCents, Long actualRefundCents, LocalDateTime channelTime,
            String responseSha256) {
        @Override public String toString() { return "VerifiedResult[redacted]"; }
    }
}
