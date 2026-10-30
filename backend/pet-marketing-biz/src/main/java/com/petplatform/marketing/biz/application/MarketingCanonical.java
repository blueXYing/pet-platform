package com.petplatform.marketing.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** Stable command parameters for requestId idempotency (sorted keys + SHA-256). */
public record MarketingCanonical(String version, String sha256, byte[] bytes) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public MarketingCanonical {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    public static MarketingCanonical of(Map<String, Object> params) {
        try {
            byte[] canonical = JSON.writeValueAsBytes(new TreeMap<>(params));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return new MarketingCanonical("mkt-v1", hex.toString(), canonical);
        } catch (Exception failure) {
            throw new IllegalStateException("marketing canonicalization failed", failure);
        }
    }

    /** Fixed-length key component so a long terminal requestId never overflows the 191-char column. */
    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("marketing request-key hashing failed", failure);
        }
    }

    public boolean matches(byte[] storedCanonical, String storedSha256) {
        return storedCanonical != null && storedSha256 != null
                && storedSha256.equals(sha256)
                && java.util.Arrays.equals(storedCanonical, bytes);
    }
}
