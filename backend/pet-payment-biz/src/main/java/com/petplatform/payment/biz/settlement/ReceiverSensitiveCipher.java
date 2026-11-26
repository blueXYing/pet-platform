package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption/decryption (fresh 12-byte IV per encryption, merchant+field AAD) and
 * deterministic display masking for split-receiver sensitive fields (Ruling 40 §4.2).
 */
public final class ReceiverSensitiveCipher {
    static final String VERSION_PREFIX = "v1:";
    static final String AAD_DOMAIN = "RECEIVER_SENSITIVE_V1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int MAX_KEY_FILE_BYTES = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public ReceiverSensitiveCipher(SecretKey key) {
        Objects.requireNonNull(key, "key");
        byte[] encoded = key.getEncoded();
        if (!"AES".equalsIgnoreCase(key.getAlgorithm()) || encoded == null || encoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("receiver sensitive cipher requires an AES-256 key");
        }
        this.key = new SecretKeySpec(encoded, "AES");
        Arrays.fill(encoded, (byte) 0);
    }

    /** Loads a Base64-encoded 32-byte AES-256 key from a local file path. */
    public static SecretKey loadKeyFromFile(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("receiver AES key path is blank");
        }
        return loadKeyFromFile(Path.of(path.trim()));
    }

    /** Loads a Base64-encoded 32-byte AES-256 key from a local file path. */
    public static SecretKey loadKeyFromFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("receiver AES key file is missing");
        }
        byte[] decoded = null;
        try (var in = Files.newInputStream(path)) {
            byte[] raw = in.readNBytes(MAX_KEY_FILE_BYTES + 1);
            if (raw.length == 0 || raw.length > MAX_KEY_FILE_BYTES) {
                throw new IllegalArgumentException("receiver AES key file size is invalid");
            }
            String text = new String(raw, StandardCharsets.US_ASCII).strip();
            decoded = Base64.getDecoder().decode(text);
            if (decoded.length != KEY_BYTES) {
                throw new IllegalArgumentException("receiver AES key must decode to exactly 32 bytes");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException bad) {
            throw bad;
        } catch (Exception failure) {
            throw new IllegalArgumentException("failed to load receiver AES key file", failure);
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }

    public static ReceiverSensitiveCipher fromKeyFile(String path) {
        return new ReceiverSensitiveCipher(loadKeyFromFile(path));
    }

    public static ReceiverSensitiveCipher fromKeyFile(Path path) {
        return new ReceiverSensitiveCipher(loadKeyFromFile(path));
    }

    /** Encrypts {@code plaintext} using a fresh random 12-byte IV bound to {@code (merchantId, fieldName)}. */
    public String encrypt(long merchantId, String fieldName, String plaintext) {
        if (plaintext == null) return null;
        if (merchantId <= 0 || fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("invalid encryption scope");
        }
        byte[] plainBytes = null;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(merchantId, fieldName));
            byte[] encrypted = cipher.doFinal(plainBytes);
            byte[] combined = new byte[IV_BYTES + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_BYTES);
            System.arraycopy(encrypted, 0, combined, IV_BYTES, encrypted.length);
            String encoded = VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
            if (encoded.length() > 512) throw unavailable("encrypted receiver field exceeds column limit");
            return encoded;
        } catch (ApiException known) {
            throw known;
        } catch (Exception failed) {
            throw unavailable("receiver sensitive encryption failed");
        } finally {
            if (plainBytes != null) Arrays.fill(plainBytes, (byte) 0);
        }
    }

    /** Decrypts a {@code v1:...} AES-256-GCM ciphertext; fails closed on tamper or scope mismatch. */
    public String decrypt(long merchantId, String fieldName, String cipherText) {
        if (cipherText == null) return null;
        if (merchantId <= 0 || fieldName == null || fieldName.isBlank()) {
            throw unavailable("invalid decryption scope");
        }
        if (!cipherText.startsWith(VERSION_PREFIX)) {
            throw unavailable("unsupported receiver ciphertext version");
        }
        byte[] plainBytes = null;
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText.substring(VERSION_PREFIX.length()));
            if (combined.length < IV_BYTES + 16) {
                throw unavailable("corrupted receiver ciphertext");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] body = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(merchantId, fieldName));
            plainBytes = cipher.doFinal(body);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (ApiException known) {
            throw known;
        } catch (Exception failed) {
            throw unavailable("receiver sensitive decryption failed");
        } finally {
            if (plainBytes != null) Arrays.fill(plainBytes, (byte) 0);
        }
    }

    static byte[] aad(long merchantId, String fieldName) {
        return (AAD_DOMAIN + ":" + merchantId + ":" + fieldName).getBytes(StandardCharsets.US_ASCII);
    }

    // ---- Deterministic Masking Helpers -----------------------------------------------------

    public static String maskCard(String acctNo) {
        if (acctNo == null || acctNo.isBlank()) return null;
        String value = acctNo.trim();
        int len = value.length();
        if (len <= 4) return "****";
        if (len <= 9) return value.substring(0, 1) + "****" + value.substring(len - 4);
        return value.substring(0, 4) + "****" + value.substring(len - 4);
    }

    public static String last4(String acctNo) {
        if (acctNo == null || acctNo.isBlank()) {
            throw new IllegalArgumentException("account number is blank");
        }
        String value = acctNo.trim();
        if (value.length() < 4) {
            throw new IllegalArgumentException("account number shorter than 4 chars");
        }
        return value.substring(value.length() - 4);
    }

    public static String maskIdCard(String certNo) {
        if (certNo == null || certNo.isBlank()) return null;
        String value = certNo.trim();
        int len = value.length();
        if (len < 4) return "****";
        if (len < 8) return value.substring(0, 1) + "****" + value.substring(len - 2);
        return value.substring(0, 3) + "****" + value.substring(len - 4);
    }

    public static String maskPhone(String mobile) {
        if (mobile == null || mobile.isBlank()) return null;
        String value = mobile.trim();
        int len = value.length();
        if (len < 7) return "****";
        return value.substring(0, 3) + "****" + value.substring(len - 4);
    }

    public static String maskLicense(String licenseNo) {
        if (licenseNo == null || licenseNo.isBlank()) return null;
        String value = licenseNo.trim();
        int len = value.length();
        if (len < 4) return "****";
        if (len < 8) return value.substring(0, 1) + "****" + value.substring(len - 2);
        return value.substring(0, 4) + "****" + value.substring(len - 4);
    }

    public static String maskName(String name) {
        if (name == null || name.isBlank()) return null;
        String value = name.trim();
        int len = value.length();
        if (len == 1) return "*";
        if (len == 2) return "*" + value.substring(1);
        if (len <= 4) return value.substring(0, 1) + "*".repeat(len - 2) + value.substring(len - 1);
        return value.substring(0, 2) + "****" + value.substring(len - 2);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
