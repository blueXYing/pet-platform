package com.petplatform.thirdparty.biz.infrastructure.oss;

import java.util.Objects;

/**
 * CCR-OSS-001 connection facts. Secrets arrive via environment (ops/oss.env.local
 * is parsed by the ops wrapper) and are never logged or committed.
 */
public record OssConnection(String endpoint, String region, String bucket,
                            String accessKeyId, String secretAccessKey, String publicBaseUrl) {

    public OssConnection {
        Objects.requireNonNull(endpoint, "OSS_ENDPOINT");
        Objects.requireNonNull(region, "OSS_REGION");
        Objects.requireNonNull(bucket, "OSS_BUCKET");
        Objects.requireNonNull(accessKeyId, "OSS_ACCESS_KEY_ID");
        Objects.requireNonNull(secretAccessKey, "OSS_SECRET_ACCESS_KEY");
    }

    public static OssConnection fromEnv() {
        return new OssConnection(System.getenv("OSS_ENDPOINT"), System.getenv("OSS_REGION"),
                System.getenv("OSS_BUCKET"), System.getenv("OSS_ACCESS_KEY_ID"),
                System.getenv("OSS_SECRET_ACCESS_KEY"), System.getenv("OSS_PUBLIC_BASE_URL"));
    }

    /** Public base overrides endpoint拼接, so a later CDN swap needs no data migration. */
    public String publicUrl(String objectKey) {
        String base = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? "https://" + bucket + "." + endpoint.replaceFirst("^https?://", "")
                : publicBaseUrl.replaceAll("/$", "");
        return base + "/" + objectKey;
    }
}
