package com.petplatform.community.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** Stable command parameters for requestId idempotency (sorted keys + SHA-256). */
public record CommunityCanonical(String version, String sha256, byte[] bytes) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public CommunityCanonical {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    public static CommunityCanonical of(Map<String, Object> params) {
        try {
            byte[] canonical = JSON.writeValueAsBytes(new TreeMap<>(params));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return new CommunityCanonical("com-v1", hex.toString(), canonical);
        } catch (Exception failure) {
            throw new IllegalStateException("community canonicalization failed", failure);
        }
    }

    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("community request-key hashing failed", failure);
        }
    }

    public boolean matches(byte[] storedCanonical, String storedSha256) {
        return storedCanonical != null && storedSha256 != null
                && storedSha256.equals(sha256)
                && java.util.Arrays.equals(storedCanonical, bytes);
    }
}
