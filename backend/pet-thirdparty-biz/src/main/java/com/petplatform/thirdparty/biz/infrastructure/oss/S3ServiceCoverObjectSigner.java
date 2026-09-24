package com.petplatform.thirdparty.biz.infrastructure.oss;

import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** Browser-compatible GET for a specific stored version; never a bare object-key URL. */
public final class S3ServiceCoverObjectSigner implements ServiceCoverObjectSigner, AutoCloseable {
  private final S3Presigner presigner;
  private final OssConnection connection;
  private final Clock clock;
  private final long windowSeconds;

  public S3ServiceCoverObjectSigner(OssConnection connection, Clock clock, long windowSeconds) {
    this.connection = Objects.requireNonNull(connection);
    this.clock = Objects.requireNonNull(clock);
    // Quantized expiry may extend to nearly 9/8 of a window; stay within S3's seven-day bound.
    if (windowSeconds < 600 || windowSeconds > 537600) throw new IllegalArgumentException("Invalid signing window");
    this.windowSeconds = windowSeconds;
    URI endpoint = URI.create(connection.endpoint());
    if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
        || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
      throw new IllegalArgumentException("Service cover endpoint must use HTTPS");
    presigner = S3Presigner.builder().endpointOverride(endpoint).region(Region.of(connection.region()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(connection.accessKeyId(), connection.secretAccessKey())))
        .build();
  }

  @Override
  public SignedObject sign(String objectKey, String versionRef) {
    if (objectKey == null || !objectKey.startsWith("merchant-materials/")
        || !objectKey.endsWith("/normalized-v1") || objectKey.contains("..") || objectKey.indexOf('\\') >= 0
        || objectKey.length() > 512 || objectKey.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid service cover object");
    // ETag-only assets require signed If-Match headers, which an Image URL cannot supply.
    // Reject rather than silently signing the latest (possibly replaced) object version.
    if (versionRef == null || versionRef.length() > 128
        || !versionRef.matches("version:[A-Za-z0-9._~+/=-]+") || "version:null".equals(versionRef))
      throw new IllegalStateException("Versioned service cover object required");
    Instant now = clock.instant();
    long boundary = (now.getEpochSecond() / windowSeconds + 1) * windowSeconds;
    if (boundary - now.getEpochSecond() < windowSeconds / 8) boundary += windowSeconds;
    // The SDK supplies its own signing timestamp, so derive reported expiry from its request.
    var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
        .signatureDuration(Duration.between(now, Instant.ofEpochSecond(boundary)))
        .getObjectRequest(GetObjectRequest.builder().bucket(connection.bucket()).key(objectKey)
            .versionId(versionRef.substring(8)).build()).build());
    if (!signed.isBrowserExecutable()) throw new IllegalStateException("Browser-compatible signature required");
    return new SignedObject(signed.url().toString(), signed.expiration());
  }

  @Override public void close() { presigner.close(); }
}
