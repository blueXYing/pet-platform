package com.petplatform.boot.config;

import com.petplatform.community.biz.application.port.CommunityMediaObjectStore;
import java.net.URI;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3-compatible OSS adapter for public community images; reads unified pet.oss.* settings. */
public final class CommunityOssMediaStore implements CommunityMediaObjectStore, AutoCloseable {

    private final String bucket;
    private final S3Client client;

    public CommunityOssMediaStore() {
        this(env("OSS_ENDPOINT"), env("OSS_REGION"), env("OSS_BUCKET"),
                env("OSS_ACCESS_KEY_ID"), env("OSS_SECRET_ACCESS_KEY"));
    }

    public CommunityOssMediaStore(
            String endpoint, String region, String bucket, String accessKey, String secret) {
        String normEndpoint = normalize(endpoint);
        if (normEndpoint != null && !normEndpoint.contains("://")) normEndpoint = "https://" + normEndpoint;
        String normRegion = normalize(region);
        String normBucket = normalize(bucket);
        String normAccessKey = normalize(accessKey);
        String normSecret = normalize(secret);
        if (normEndpoint == null || normRegion == null || normBucket == null
                || normAccessKey == null || normSecret == null) {
            this.bucket = null;
            this.client = null;
            return;
        }
        this.bucket = normBucket;
        this.client = S3Client.builder()
                .endpointOverride(URI.create(normEndpoint))
                .region(Region.of(normRegion))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(normAccessKey, normSecret)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    @Override
    public boolean enabled() {
        return client != null;
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        if (!enabled()) throw new IllegalStateException("community OSS is not configured");
        client.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(objectKey).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String objectKey) {
        if (!enabled()) throw new IllegalStateException("community OSS is not configured");
        return client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(objectKey).build()).asByteArray();
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String env(String name) {
        return normalize(System.getenv(name));
    }
}
