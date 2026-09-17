package com.petplatform.thirdparty.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import com.petplatform.thirdparty.biz.infrastructure.persistence.AssetRegistryStore;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * CCR-OSS-001 amendment 1: private bucket + presigned GET URLs with a quantized
 * expiry window. Signing is a local computation (no network); the registry is the
 * permanent asset_key→object_key fact. Clients cache {url, expiresAt} and refresh
 * when roughly 10% of validity remains.
 */
public final class PresignedAssetUrlService implements AutoCloseable {

    public record SignedUrl(String assetKey, String url, long expiresAtEpochSeconds, String objectKey) {}

    private final AssetRegistryStore registry;
    private final S3Presigner presigner;
    private final OssConnection connection;
    private final Clock clock;
    private final long windowSeconds;

    public PresignedAssetUrlService(OssConnection connection, DataSource dataSource,
                                    com.petplatform.common.SnowflakeIdGenerator ids,
                                    Clock clock, long windowSeconds) {
        this.registry = new AssetRegistryStore(dataSource, ids);
        this.connection = Objects.requireNonNull(connection);
        this.clock = Objects.requireNonNull(clock);
        if (windowSeconds < 600) throw new IllegalArgumentException("window must be at least 600s");
        this.windowSeconds = windowSeconds;
        this.presigner = S3Presigner.builder()
                .endpointOverride(java.net.URI.create(connection.endpoint()))
                .region(software.amazon.awssdk.regions.Region.of(connection.region()))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                connection.accessKeyId(), connection.secretAccessKey())))
                .build();
    }

    public SignedUrl presign(String assetKey) {
        String objectKey = registry.activeObjectKey(assetKey);
        if (objectKey == null) {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "素材不存在");
        }
        Instant now = clock.instant();
        long boundary = (now.getEpochSecond() / windowSeconds + 1) * windowSeconds;
        // If the current window is nearly over, jump one window further to avoid churn.
        if (boundary - now.getEpochSecond() < windowSeconds / 8) {
            boundary += windowSeconds;
        }
        PresignedGetObjectRequest signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.between(now, Instant.ofEpochSecond(boundary)))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(connection.bucket()).key(objectKey).build())
                .build());
        return new SignedUrl(assetKey, signed.url().toString(), boundary, objectKey);
    }

    /** Canonical (unsigned) URL for records only; never served to clients. */
    public String canonicalUrl(String objectKey) {
        return connection.publicUrl(encode(objectKey));
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException("UTF-8 unavailable", error);
        }
    }

    @Override public void close() {
        presigner.close();
    }
}
