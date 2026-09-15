package com.petplatform.user.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * canonical-v1 parameter tree per supplement 23 §4: codepoint-sorted object keys (TreeMap over
 * ASCII field names), compact UTF-8 JSON without BOM or trailing newline, values verbatim
 * (no trim, no case folding, no Unicode normalization). The protected bytes are stored for
 * equality re-checks; the SHA-256 is only an identifier.
 */
public final class CanonicalParams {
    private static final ObjectMapper CANONICAL = new ObjectMapper();

    private CanonicalParams() {}

    public static final class Canonical {
        public final String version = "canonical-v1";
        public final String sha256;
        public final byte[] bytes;

        Canonical(String sha256, byte[] bytes) {
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }

    static Canonical of(Map<String, Object> protectedFields) {
        Map<String, Object> sorted = new TreeMap<>(protectedFields);
        sorted.values().removeIf(java.util.Objects::isNull);
        byte[] bytes;
        try {
            bytes = CANONICAL.writeValueAsBytes(sorted);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Parameters are not canonicalizable", error);
        }
        return new Canonical(sha256Hex(bytes), bytes);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
