package com.petplatform.merchant.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AesGcmProtectedValueProviderTest {
  private static final byte[] ENCRYPTION_KEY = bytes(0x11);
  private static final byte[] EQUALITY_KEY = bytes(0x55);

  @Test
  void protectsWithRandomCiphertextAndStablePurposeBoundEqualityToken() {
    var provider =
        new AesGcmProtectedValueProvider("merchant-pii-v1", ENCRYPTION_KEY, EQUALITY_KEY);

    var first = provider.protect("merchant-contact-phone", "13800138000");
    var second = provider.protect("merchant-contact-phone", "13800138000");
    var anotherPurpose = provider.protect("merchant-contact-email", "13800138000");

    assertFalse(Arrays.equals(first.ciphertext(), second.ciphertext()));
    assertArrayEquals(first.equalityToken(), second.equalityToken());
    assertFalse(Arrays.equals(first.equalityToken(), anotherPurpose.equalityToken()));
    assertEquals("13800138000", provider.reveal("merchant-contact-phone", first.ciphertext()));
  }

  @Test
  void bindsCiphertextToPurposeAndKeyVersionAndRejectsTamperingWithoutCause() {
    var provider =
        new AesGcmProtectedValueProvider("merchant-pii-v1", ENCRYPTION_KEY, EQUALITY_KEY);
    byte[] ciphertext = provider.protect("merchant-contact-phone", "13800138000").ciphertext();

    ApiException wrongPurpose =
        assertThrows(
            ApiException.class, () -> provider.reveal("merchant-contact-email", ciphertext));
    assertUnavailableAndRedacted(wrongPurpose);

    ciphertext[ciphertext.length - 1] ^= 1;
    ApiException tampered =
        assertThrows(
            ApiException.class, () -> provider.reveal("merchant-contact-phone", ciphertext));
    assertUnavailableAndRedacted(tampered);

    var anotherVersion =
        new AesGcmProtectedValueProvider("merchant-pii-v2", ENCRYPTION_KEY, EQUALITY_KEY);
    ApiException wrongVersion =
        assertThrows(
            ApiException.class, () -> anotherVersion.reveal("merchant-contact-phone", ciphertext));
    assertUnavailableAndRedacted(wrongVersion);
  }

  @Test
  void keyVersionParticipatesInStableEqualityPolicy() {
    var v1 = new AesGcmProtectedValueProvider("v1", ENCRYPTION_KEY, EQUALITY_KEY);
    var v2 = new AesGcmProtectedValueProvider("v2", ENCRYPTION_KEY, EQUALITY_KEY);

    assertNotEquals(
        hex(v1.protect("phone", "same").equalityToken()),
        hex(v2.protect("phone", "same").equalityToken()));
  }

  @Test
  void rejectsMissingWrongSizedOrReusedKeyMaterial() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AesGcmProtectedValueProvider("v1", null, EQUALITY_KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AesGcmProtectedValueProvider("v1", new byte[16], EQUALITY_KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AesGcmProtectedValueProvider("v1", ENCRYPTION_KEY, ENCRYPTION_KEY.clone()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AesGcmProtectedValueProvider("bad version!", ENCRYPTION_KEY, EQUALITY_KEY));
  }

  @Test
  void clonesCallerKeyBuffers() {
    byte[] encryption = ENCRYPTION_KEY.clone();
    byte[] equality = EQUALITY_KEY.clone();
    var provider = new AesGcmProtectedValueProvider("v1", encryption, equality);
    var before = provider.protect("phone", "same");

    Arrays.fill(encryption, (byte) 0);
    Arrays.fill(equality, (byte) 0);
    var after = provider.protect("phone", "same");

    assertArrayEquals(before.equalityToken(), after.equalityToken());
    assertEquals("same", provider.reveal("phone", after.ciphertext()));
  }

  @Test
  void rejectsMalformedEnvelopeAsTypedDependencyFailure() {
    var provider = new AesGcmProtectedValueProvider("v1", ENCRYPTION_KEY, EQUALITY_KEY);
    ApiException failure =
        assertThrows(
            ApiException.class,
            () -> provider.reveal("phone", "plaintext".getBytes(StandardCharsets.UTF_8)));
    assertUnavailableAndRedacted(failure);
  }

  private static void assertUnavailableAndRedacted(ApiException failure) {
    assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
    assertEquals("protected value provider is unavailable", failure.getMessage());
    assertNull(failure.getCause());
  }

  private static byte[] bytes(int value) {
    byte[] result = new byte[32];
    Arrays.fill(result, (byte) value);
    return result;
  }

  private static String hex(byte[] value) {
    return java.util.HexFormat.of().formatHex(value);
  }
}
