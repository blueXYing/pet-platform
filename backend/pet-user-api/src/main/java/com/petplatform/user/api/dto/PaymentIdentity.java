package com.petplatform.user.api.dto;

/** Trusted current mini-program payment identity. Never include the openId in logs. */
public record PaymentIdentity(String appId, String openId) {
    @Override public String toString() { return "PaymentIdentity[redacted]"; }
}
