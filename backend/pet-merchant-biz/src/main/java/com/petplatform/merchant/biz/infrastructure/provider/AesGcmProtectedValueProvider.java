package com.petplatform.merchant.biz.infrastructure.provider;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Purpose-bound AES-256-GCM protection with a separate stable HMAC-SHA-256 equality token.
 *
 * <p>The caller must inject one externally managed, persistently pinned key version. Changing that
 * version changes equality tokens and therefore requires an explicit data migration; this adapter
 * never generates a key, silently rotates it, or reads secret material from files or the
 * environment.
 */
public final class AesGcmProtectedValueProvider
    implements ApplicationValidationPorts.ProtectedValuePort {
  private static final int MAGIC = 0x4d505631; // MPV1
  private static final byte FORMAT_VERSION = 1;
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BYTES = 16;
  private static final int MAX_PLAINTEXT_BYTES = 65_536;
  private static final int MAX_ENVELOPE_BYTES = 65_704;
  private static final byte[] ENCRYPTION_DOMAIN =
      "pet-platform:merchant:protected-value:v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] EQUALITY_DOMAIN =
      "pet-platform:merchant:equality-token:v1".getBytes(StandardCharsets.US_ASCII);

  private final String keyVersion;
  private final byte[] keyVersionBytes;
  private final byte[] encryptionKey;
  private final byte[] equalityKey;
  private final SecureRandom random;

  public AesGcmProtectedValueProvider(String keyVersion, byte[] encryptionKey, byte[] equalityKey) {
    this(keyVersion, encryptionKey, equalityKey, new SecureRandom());
  }

  AesGcmProtectedValueProvider(
      String keyVersion, byte[] encryptionKey, byte[] equalityKey, SecureRandom random) {
    if (keyVersion == null || !keyVersion.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("keyVersion must contain 1..64 safe characters");
    }
    if (encryptionKey == null
        || equalityKey == null
        || encryptionKey.length != KEY_BYTES
        || equalityKey.length != KEY_BYTES
        || MessageDigest.isEqual(encryptionKey, equalityKey)) {
      throw new IllegalArgumentException("separate 256-bit keys are required");
    }
    this.keyVersion = keyVersion;
    this.keyVersionBytes = keyVersion.getBytes(StandardCharsets.US_ASCII);
    this.encryptionKey = encryptionKey.clone();
    this.equalityKey = equalityKey.clone();
    this.random = Objects.requireNonNull(random, "random");
  }

  public String keyVersion() {
    return keyVersion;
  }

  @Override
  public ApplicationValidationPorts.ProtectedValue protect(String purpose, String plaintext) {
    byte[] purposeBytes = requirePurpose(purpose);
    byte[] valueBytes = requirePlaintext(plaintext);
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(encryptionKey, "AES"),
          new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(purposeBytes));
      byte[] encrypted = cipher.doFinal(valueBytes);

      ByteBuffer envelope =
          ByteBuffer.allocate(
              Integer.BYTES + 1 + 1 + keyVersionBytes.length + NONCE_BYTES + encrypted.length);
      envelope.putInt(MAGIC);
      envelope.put(FORMAT_VERSION);
      envelope.put((byte) keyVersionBytes.length);
      envelope.put(keyVersionBytes);
      envelope.put(nonce);
      envelope.put(encrypted);
      return new ApplicationValidationPorts.ProtectedValue(
          envelope.array(), equalityToken(purposeBytes, valueBytes));
    } catch (GeneralSecurityException failure) {
      throw unavailable();
    }
  }

  @Override
  public String reveal(String purpose, byte[] ciphertext) {
    byte[] purposeBytes = requirePurpose(purpose);
    if (ciphertext == null
        || ciphertext.length < Integer.BYTES + 1 + 1 + 1 + NONCE_BYTES + TAG_BYTES
        || ciphertext.length > MAX_ENVELOPE_BYTES) {
      throw unavailable();
    }
    try {
      ByteBuffer envelope = ByteBuffer.wrap(ciphertext);
      if (envelope.getInt() != MAGIC || envelope.get() != FORMAT_VERSION) throw unavailable();
      int versionLength = Byte.toUnsignedInt(envelope.get());
      if (versionLength == 0 || envelope.remaining() < versionLength + NONCE_BYTES + TAG_BYTES) {
        throw unavailable();
      }
      byte[] envelopeVersion = new byte[versionLength];
      envelope.get(envelopeVersion);
      if (!MessageDigest.isEqual(keyVersionBytes, envelopeVersion)) throw unavailable();
      byte[] nonce = new byte[NONCE_BYTES];
      envelope.get(nonce);
      byte[] encrypted = new byte[envelope.remaining()];
      envelope.get(encrypted);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(encryptionKey, "AES"),
          new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(purposeBytes));
      byte[] plaintext = cipher.doFinal(encrypted);
      if (plaintext.length > MAX_PLAINTEXT_BYTES) throw unavailable();
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (ApiException failure) {
      throw failure;
    } catch (GeneralSecurityException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private byte[] equalityToken(byte[] purpose, byte[] plaintext) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(equalityKey, "HmacSHA256"));
    updateFramed(mac, EQUALITY_DOMAIN);
    updateFramed(mac, keyVersionBytes);
    updateFramed(mac, purpose);
    updateFramed(mac, plaintext);
    return mac.doFinal();
  }

  private byte[] aad(byte[] purpose) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeFramed(out, ENCRYPTION_DOMAIN);
    writeFramed(out, keyVersionBytes);
    writeFramed(out, purpose);
    return out.toByteArray();
  }

  private static void updateFramed(Mac mac, byte[] value) {
    mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    mac.update(value);
  }

  private static void writeFramed(ByteArrayOutputStream out, byte[] value) {
    out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    out.writeBytes(value);
  }

  private static byte[] requirePurpose(String purpose) {
    if (purpose == null || purpose.isBlank() || purpose.length() > 128) {
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "protection purpose is invalid");
    }
    return purpose.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] requirePlaintext(String plaintext) {
    if (plaintext == null) {
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "protected value is required");
    }
    byte[] value = plaintext.getBytes(StandardCharsets.UTF_8);
    if (value.length > MAX_PLAINTEXT_BYTES) {
      Arrays.fill(value, (byte) 0);
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "protected value is too large");
    }
    return value;
  }

  private static ApiException unavailable() {
    return new ApiException(
        CommonApiCodes.DEPENDENCY_UNAVAILABLE, "protected value provider is unavailable");
  }
}
