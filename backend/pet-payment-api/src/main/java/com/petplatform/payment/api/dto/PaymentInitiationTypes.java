package com.petplatform.payment.api.dto;

import java.time.OffsetDateTime;

public final class PaymentInitiationTypes {
    private PaymentInitiationTypes() {}

    public record WechatPayParameters(String timeStamp, String nonceStr, String packageValue,
            String signType, String paySign) {
        @Override public String toString() { return "WechatPayParameters[redacted]"; }
    }

    public record InitiatedPayment(String paymentId, String paymentNo, String channel,
            WechatPayParameters wechatPayParameters, OffsetDateTime parameterValidUntil) {
        @Override public String toString() { return "InitiatedPayment[redacted]"; }
    }
}
