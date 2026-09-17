package com.petplatform.merchant.biz.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Unambiguous UTF-8 encoding of supplement-23's logical idempotency tuple. */
public final class MerchantRequestKey {
    private static final byte[] VERSION = "request-key-v1|".getBytes(StandardCharsets.US_ASCII);

    private MerchantRequestKey() {}

    public static byte[] encode(
            String namespace,
            String actorType,
            String actorId,
            String authorityScope,
            String requestId
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(768);
        output.writeBytes(VERSION);
        component(output, namespace);
        component(output, actorType);
        component(output, actorId);
        component(output, authorityScope);
        component(output, requestId);
        byte[] encoded = output.toByteArray();
        if (encoded.length > 1_024) throw new IllegalArgumentException("request key exceeds storage boundary");
        return encoded;
    }

    private static void component(ByteArrayOutputStream output, String value) {
        if (value == null) throw new IllegalArgumentException("request key component is required");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        output.write(':');
        output.writeBytes(bytes);
        output.write('|');
    }
}
