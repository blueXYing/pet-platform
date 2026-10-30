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

/** S3-compatible OSS adapter for public community images; credentials stay in process env. */
public final class CommunityOssMediaStore implements CommunityMediaObjectStore, AutoCloseable {

    private final String bucket;
    private final S3Client client;

    public CommunityOssMediaStore() {
        String endpoint = env("OSS_ENDPOINT");
        if (endpoint != null && !endpoint.contains("://")) endpoint = "https://" + endpoint;
        String region = env("OSS_REGION");
        String bucket = env("OSS_BUCKET");
        String accessKey = env("OSS_ACCESS_KEY_ID");
        String secret = env("OSS_SECRET_ACCESS_KEY");
        if (endpoint == null || region == null || bucket == null || accessKey == null || secret == null) {
            this.bucket = null;
            this.client = null;
            return;
        }
        this.bucket = bucket;
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secret)))
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

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
