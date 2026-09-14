package com.petplatform.admin.biz.infrastructure.provider;

import com.petplatform.admin.biz.application.AdminAuthFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Separate-purpose keys are injected, never persisted in audit, SQL, logs or exceptions. */
public final class AdminSecretCodec {
  public interface KeySource {
    String currentId();

    Keys get(String id);
  }

  public static final class Keys {
    private final byte[] mac, encryption;

    public Keys(byte[] mac, byte[] encryption) {
      if (mac == null
          || encryption == null
          || mac.length != 32
          || encryption.length != 32
          || MessageDigest.isEqual(mac, encryption))
        throw new IllegalArgumentException("Separate 256-bit auth keys required");
      this.mac = mac.clone();
      this.encryption = encryption.clone();
    }

    @Override
    public String toString() {
      return "AuthKeys[REDACTED]";
    }
  }

  private final KeySource keys;
  private final SecureRandom random = new SecureRandom();

  public AdminSecretCodec(KeySource keys) {
    this.keys = Objects.requireNonNull(keys);
    requireKey(currentKeyId());
  }

  public static AdminSecretCodec fixed(String id, byte[] mac, byte[] encryption) {
    Keys value = new Keys(mac, encryption);
    return new AdminSecretCodec(
        new KeySource() {
          public String currentId() {
            return id;
          }

          public Keys get(String requested) {
            if (!id.equals(requested)) throw AdminAuthFailure.unavailable();
            return value;
          }
        });
  }

  public String currentKeyId() {
    try {
      String id = keys.currentId();
      if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw AdminAuthFailure.unavailable();
      return id;
    } catch (RuntimeException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  private Keys requireKey(String id) {
    try {
      return Objects.requireNonNull(keys.get(id));
    } catch (RuntimeException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  public String token(String kind) {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return kind + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static byte[] digest(String secret) {
    if (secret == null || secret.isBlank() || secret.length() > 1024)
      throw AdminAuthFailure.unauthorized();
    try {
      return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  public byte[] mac(String id, String... fields) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(requireKey(id).mac, "HmacSHA256"));
      for (String field : fields) {
        byte[] b = Objects.requireNonNullElse(field, "").getBytes(StandardCharsets.UTF_8);
        m.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
        m.update(b);
      }
      return m.doFinal();
    } catch (GeneralSecurityException | NullPointerException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  public byte[] encrypt(String id, String aad, Map<String, Object> values) {
    try {
      ByteArrayOutputStream raw = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(raw)) {
        out.writeInt(values.size());
        for (var v : values.entrySet()) {
          out.writeUTF(v.getKey());
          out.writeUTF(String.valueOf(v.getValue()));
        }
      }
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(requireKey(id).encryption, "AES"),
          new GCMParameterSpec(128, nonce));
      c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] data = c.doFinal(raw.toByteArray());
      byte[] result = new byte[nonce.length + data.length];
      System.arraycopy(nonce, 0, result, 0, 12);
      System.arraycopy(data, 0, result, 12, data.length);
      return result;
    } catch (IOException | GeneralSecurityException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  public Map<String, Object> decrypt(String id, String aad, byte[] encrypted) {
    try {
      if (encrypted == null || encrypted.length < 28 || encrypted.length > 65536)
        throw AdminAuthFailure.unavailable();
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(requireKey(id).encryption, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
      c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] raw = c.doFinal(encrypted, 12, encrypted.length - 12);
      Map<String, Object> values = new LinkedHashMap<>();
      try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
        int n = in.readInt();
        if (n < 0 || n > 20) throw AdminAuthFailure.unavailable();
        for (int i = 0; i < n; i++) {
          String k = in.readUTF();
          if (values.put(k, in.readUTF()) != null) throw AdminAuthFailure.unavailable();
        }
        if (in.available() != 0) throw AdminAuthFailure.unavailable();
      }
      return values;
    } catch (IOException | GeneralSecurityException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  public String captchaAnswer() {
    String a = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 6; i++) b.append(a.charAt(random.nextInt(a.length())));
    return b.toString();
  }
}
