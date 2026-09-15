package com.petplatform.thirdparty.biz.infrastructure.oss;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/** S3-compatible client (Aliyun OSS per CCR-OSS-001): standard SDK, custom endpoint. */
public final class S3OssAssetClient implements AutoCloseable, OssAssetClient {
    private final OssConnection connection;
    private final S3Client s3;

    public S3OssAssetClient(OssConnection connection) {
        this.connection = connection;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(connection.endpoint()))
                .region(Region.of(connection.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(connection.accessKeyId(), connection.secretAccessKey())))
                // Aliyun's S3-compatible gateway rejects aws-chunked payloads.
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .chunkedEncodingEnabled(false).build())
                .build();
    }

    @Override public boolean exists(String objectKey) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(connection.bucket()).key(objectKey).build());
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException absent) {
            return false;
        }
    }

    @Override public void put(String objectKey, byte[] content, String contentType) {
        s3.putObject(builder -> builder.bucket(connection.bucket()).key(objectKey)
                        .contentType(contentType),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(content));
    }

    @Override public OssConnection connection() {
        return connection;
    }

    @Override public void close() {
        s3.close();
    }
}
