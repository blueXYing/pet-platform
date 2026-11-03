package com.petplatform.coupon.biz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * canonical-v1 protected parameter bytes for coupon admin command bindings (14号 storage).
 * Mirror of the points/community-domain canonicalizer: same version tag, same SHA-256
 * identifier rule and the same "bytes are the equality fact" contract; duplicated per the
 * in-module-sharing rule (biz never depends on biz).
 */
public final class AdminCouponCanonicalParams {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private AdminCouponCanonicalParams() {}

    public record Canonical(String version, String sha256, byte[] bytes) {
        public Canonical {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }

    public static Canonical of(Map<String, Object> protectedFields) {
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(new TreeMap<>(protectedFields));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("parameters are not canonicalizable", failure);
        }
        if (bytes.length > 65_536)
            throw new IllegalArgumentException("canonical parameters exceed 64 KiB");
        return new Canonical("canonical-v1", sha256Hex(bytes), bytes);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : hash) {
                result.append(Character.forDigit(value >>> 4 & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
