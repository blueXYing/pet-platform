package com.petplatform.payment.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.WechatPayParameters;
import com.petplatform.user.api.dto.PaymentIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared AES-256-GCM sealing for short-lived WeChat pay parameters and domain-separated HMAC-SHA256
 * identity binding across ORDER dispatch ({@code PAYMENT_PARAMETERS_V1}) and payment center
 * ({@code PAYMENT_CENTER_PARAMETERS_V1}). Each domain supplies its own AAD and key-derivation label
 * so ciphertexts and HMACs never interoperate across domains.
 */
public final class PaymentParameterCipher {
    public static final String ORDER_AAD_DOMAIN = "PAYMENT_PARAMETERS_V1";
    public static final String ORDER_IDENTITY_DOMAIN = "PAYMENT_IDENTITY_KEY_V1";
    public static final String CENTER_AAD_DOMAIN = "PAYMENT_CENTER_PARAMETERS_V1";
    public static final String CENTER_IDENTITY_DOMAIN = "PAYMENT_CENTER_IDENTITY_KEY_V1";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String aadDomain;
    private final String identityDomain;
    private final String unavailableMessage;

    public PaymentParameterCipher(SecretKey key, String aadDomain, String identityDomain, String unavailableMessage) {
        Objects.requireNonNull(key, "key");
        byte[] encoded = key.getEncoded();
        if (!"AES".equalsIgnoreCase(key.getAlgorithm()) || encoded == null || encoded.length != 32) {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
            throw new IllegalArgumentException("payment parameter key must be AES-256");
        }
        Arrays.fill(encoded, (byte) 0);
        if (blank(aadDomain) || blank(identityDomain) || blank(unavailableMessage)) {
            throw new IllegalArgumentException("payment parameter cipher domains and message are required");
        }
        this.key = key;
        this.aadDomain = aadDomain;
        this.identityDomain = identityDomain;
        this.unavailableMessage = unavailableMessage;
    }

    public record Sealed(byte[] ciphertext, byte[] iv) {}

    public Sealed encrypt(long paymentId, long paymentNo, WechatPayParameters value) {
        byte[] plain = null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            plain = JSON.writeValueAsBytes(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(paymentId, paymentNo));
            byte[] encrypted = cipher.doFinal(plain);
            if (encrypted.length > 2048) throw unavailable();
            return new Sealed(encrypted, iv);
        } catch (ApiException known) {
            throw known;
        } catch (Exception failed) {
            throw unavailable();
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public WechatPayParameters decrypt(long paymentId, long paymentNo, byte[] ciphertext, byte[] iv,
            boolean requireAllFields) {
        if (ciphertext == null || iv == null || iv.length != 12) throw unavailable();
        byte[] plain = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(paymentId, paymentNo));
            plain = cipher.doFinal(ciphertext);
            WechatPayParameters result = JSON.readValue(plain, WechatPayParameters.class);
            if (result == null || blank(result.paySign()) || blank(result.packageValue())) {
                throw unavailable();
            }
            if (requireAllFields && (blank(result.nonceStr()) || blank(result.timeStamp()) || blank(result.signType()))) {
                throw unavailable();
            }
            return result;
        } catch (ApiException known) {
            throw known;
        } catch (Exception failed) {
            throw unavailable();
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    /** Length-prefixed UTF-8 (appId, openId) HMAC under a key derived for {@code identityDomain} only. */
    public String identityHmac(PaymentIdentity identity) {
        byte[] raw = key.getEncoded();
        byte[] derived = null;
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(raw, "HmacSHA256"));
            derived = derive.doFinal(identityDomain.getBytes(StandardCharsets.US_ASCII));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            byte[] app = identity.appId().getBytes(StandardCharsets.UTF_8);
            byte[] open = identity.openId().getBytes(StandardCharsets.UTF_8);
            mac.update(ByteBuffer.allocate(4).putInt(app.length).array());
            mac.update(app);
            mac.update(ByteBuffer.allocate(4).putInt(open.length).array());
            mac.update(open);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception failed) {
            throw unavailable();
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }

    public byte[] aad(long paymentId, long paymentNo) {
        return (aadDomain + ":" + paymentId + ":" + paymentNo).getBytes(StandardCharsets.US_ASCII);
    }

    public static boolean constantEqual(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailableMessage);
    }
}
