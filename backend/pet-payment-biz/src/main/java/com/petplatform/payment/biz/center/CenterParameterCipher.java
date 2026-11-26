package com.petplatform.payment.biz.center;

import com.petplatform.payment.api.dto.PaymentInitiationTypes.WechatPayParameters;
import com.petplatform.payment.biz.application.PaymentParameterCipher;
import com.petplatform.user.api.dto.PaymentIdentity;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;

/**
 * AES-GCM storage of short-lived WeChat parameters for payment center rows, with its own AAD domain
 * ({@code PAYMENT_CENTER_PARAMETERS_V1}) so a ciphertext can never be replayed across the ORDER path,
 * and a domain-separated identity HMAC. Delegates cryptographic primitives to {@link PaymentParameterCipher}.
 */
final class CenterParameterCipher {
    static final String AAD_DOMAIN = PaymentParameterCipher.CENTER_AAD_DOMAIN;
    private final PaymentParameterCipher delegate;

    CenterParameterCipher(SecretKey key) {
        this.delegate = new PaymentParameterCipher(
                key,
                PaymentParameterCipher.CENTER_AAD_DOMAIN,
                PaymentParameterCipher.CENTER_IDENTITY_DOMAIN,
                "payment center parameters unavailable");
    }

    record Sealed(byte[] ciphertext, byte[] iv) {}

    Sealed encrypt(long paymentId, long paymentNo, WechatPayParameters value) {
        PaymentParameterCipher.Sealed sealed = delegate.encrypt(paymentId, paymentNo, value);
        return new Sealed(sealed.ciphertext(), sealed.iv());
    }

    WechatPayParameters decrypt(long paymentId, long paymentNo, byte[] ciphertext, byte[] iv) {
        return delegate.decrypt(paymentId, paymentNo, ciphertext, iv, true);
    }

    /** Length-prefixed (appId, openId) HMAC under a key derived for this domain only. */
    String identityHmac(PaymentIdentity identity) {
        return delegate.identityHmac(identity);
    }

    static boolean constantEqual(String left, String right) {
        return PaymentParameterCipher.constantEqual(left, right);
    }

    static byte[] aad(long paymentId, long paymentNo) {
        return (AAD_DOMAIN + ":" + paymentId + ":" + paymentNo).getBytes(StandardCharsets.US_ASCII);
    }
}
