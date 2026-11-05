package com.petplatform.payment.biz.center;

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
 * AES-GCM storage of short-lived WeChat parameters for payment center rows, with its own AAD domain
 * ({@code PAYMENT_CENTER_PARAMETERS_V1}) so a ciphertext can never be replayed across the ORDER path,
 * and a domain-separated identity HMAC. Duplicates about 40 lines of the ORDER implementation on
 * purpose: stage 1 must not modify ORDER files (merge planned for stage 8).
 */
final class CenterParameterCipher {
    static final String AAD_DOMAIN = "PAYMENT_CENTER_PARAMETERS_V1";
    private static final String IDENTITY_DOMAIN = "PAYMENT_CENTER_IDENTITY_KEY_V1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKey key;

    CenterParameterCipher(SecretKey key) {
        Objects.requireNonNull(key);
        if (!"AES".equalsIgnoreCase(key.getAlgorithm()) || key.getEncoded() == null || key.getEncoded().length != 32)
            throw new IllegalArgumentException("payment center parameter key must be AES-256");
        this.key = key;
    }

    record Sealed(byte[] ciphertext, byte[] iv) {}

    Sealed encrypt(long paymentId, long paymentNo, WechatPayParameters value) {
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
        } catch (ApiException known) { throw known; }
        catch (Exception failed) { throw unavailable(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    WechatPayParameters decrypt(long paymentId, long paymentNo, byte[] ciphertext, byte[] iv) {
        if (ciphertext == null || iv == null || iv.length != 12) throw unavailable();
        byte[] plain = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(paymentId, paymentNo));
            plain = cipher.doFinal(ciphertext);
            WechatPayParameters result = JSON.readValue(plain, WechatPayParameters.class);
            if (result == null || blank(result.paySign()) || blank(result.packageValue())
                    || blank(result.nonceStr()) || blank(result.timeStamp()) || blank(result.signType()))
                throw unavailable();
            return result;
        } catch (ApiException known) { throw known; }
        catch (Exception failed) { throw unavailable(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    /** Length-prefixed (appId, openId) HMAC under a key derived for this domain only. */
    String identityHmac(PaymentIdentity identity) {
        byte[] raw = key.getEncoded();
        byte[] derived = null;
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(raw, "HmacSHA256"));
            derived = derive.doFinal(IDENTITY_DOMAIN.getBytes(StandardCharsets.US_ASCII));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            byte[] app = identity.appId().getBytes(StandardCharsets.UTF_8);
            byte[] open = identity.openId().getBytes(StandardCharsets.UTF_8);
            mac.update(ByteBuffer.allocate(4).putInt(app.length).array());
            mac.update(app);
            mac.update(ByteBuffer.allocate(4).putInt(open.length).array());
            mac.update(open);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception failed) { throw unavailable(); }
        finally {
            Arrays.fill(raw, (byte) 0);
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }

    static boolean constantEqual(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] aad(long paymentId, long paymentNo) {
        return (AAD_DOMAIN + ":" + paymentId + ":" + paymentNo).getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "payment center parameters unavailable");
    }
}
