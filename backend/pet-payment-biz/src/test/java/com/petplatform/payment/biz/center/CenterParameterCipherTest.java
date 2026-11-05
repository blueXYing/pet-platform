package com.petplatform.payment.biz.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.WechatPayParameters;
import com.petplatform.user.api.dto.PaymentIdentity;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Offline: AES-GCM parameter storage with the payment center AAD domain. Keys are generated in memory. */
class CenterParameterCipherTest {
    private static SecretKey key() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return new SecretKeySpec(raw, "AES");
    }

    private static final WechatPayParameters PARAMS =
            new WechatPayParameters("1730700000", "nonceX", "prepay_id=wx1", "RSA", "signY");

    @Test void roundTripRestoresTheParameters() {
        var cipher = new CenterParameterCipher(key());
        var sealed = cipher.encrypt(11L, 22L, PARAMS);
        assertEquals(12, sealed.iv().length);
        assertEquals(PARAMS, cipher.decrypt(11L, 22L, sealed.ciphertext(), sealed.iv()));
    }

    @Test void ciphertextIsBoundToItsPaymentIdAndNumber() {
        var cipher = new CenterParameterCipher(key());
        var sealed = cipher.encrypt(11L, 22L, PARAMS);
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> cipher.decrypt(12L, 22L, sealed.ciphertext(), sealed.iv())).code());
        assertThrows(ApiException.class, () -> cipher.decrypt(11L, 23L, sealed.ciphertext(), sealed.iv()));
    }

    @Test void tamperingAndForeignKeysFail() {
        var cipher = new CenterParameterCipher(key());
        var sealed = cipher.encrypt(11L, 22L, PARAMS);
        byte[] tampered = sealed.ciphertext().clone();
        tampered[0] ^= 1;
        assertThrows(ApiException.class, () -> cipher.decrypt(11L, 22L, tampered, sealed.iv()));
        assertThrows(ApiException.class, () -> new CenterParameterCipher(key()).decrypt(11L, 22L,
                sealed.ciphertext(), sealed.iv()));
        assertThrows(ApiException.class, () -> cipher.decrypt(11L, 22L, sealed.ciphertext(), new byte[11]));
        assertThrows(ApiException.class, () -> cipher.decrypt(11L, 22L, null, sealed.iv()));
    }

    /** The ORDER path uses AAD "PAYMENT_PARAMETERS_V1:{id}:{no}"; the two domains must never interoperate. */
    @Test void orderAadCannotOpenACenterCiphertextAndViceVersa() throws Exception {
        SecretKey key = key();
        var cipher = new CenterParameterCipher(key);
        var sealed = cipher.encrypt(11L, 22L, PARAMS);
        Cipher order = Cipher.getInstance("AES/GCM/NoPadding");
        order.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed.iv()));
        order.updateAAD("PAYMENT_PARAMETERS_V1:11:22".getBytes(StandardCharsets.US_ASCII));
        assertThrows(javax.crypto.AEADBadTagException.class, () -> order.doFinal(sealed.ciphertext()));

        byte[] iv = new byte[12];
        Cipher orderSeal = Cipher.getInstance("AES/GCM/NoPadding");
        orderSeal.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        orderSeal.updateAAD("PAYMENT_PARAMETERS_V1:11:22".getBytes(StandardCharsets.US_ASCII));
        byte[] orderCiphertext = orderSeal.doFinal(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(PARAMS));
        assertThrows(ApiException.class, () -> cipher.decrypt(11L, 22L, orderCiphertext, iv));
        assertTrue(new String(CenterParameterCipher.aad(11L, 22L), StandardCharsets.US_ASCII)
                .startsWith("PAYMENT_CENTER_PARAMETERS_V1:"));
    }

    @Test void identityHmacIsDeterministicAndIdentitySensitive() {
        var cipher = new CenterParameterCipher(key());
        String first = cipher.identityHmac(new PaymentIdentity("wxApp", "openA"));
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first, cipher.identityHmac(new PaymentIdentity("wxApp", "openA")));
        assertNotEquals(first, cipher.identityHmac(new PaymentIdentity("wxApp", "openB")));
        // Length prefixes prevent ("wxAppo","penA") from colliding with ("wxApp","openA").
        assertNotEquals(first, cipher.identityHmac(new PaymentIdentity("wxAppo", "penA")));
        assertTrue(CenterParameterCipher.constantEqual(first, first));
        assertFalse(CenterParameterCipher.constantEqual(first, null));
    }

    @Test void onlyAes256KeysAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> new CenterParameterCipher(new SecretKeySpec(new byte[16], "AES")));
        assertThrows(IllegalArgumentException.class, () -> new CenterParameterCipher(new SecretKeySpec(new byte[32], "HmacSHA256")));
    }
}
