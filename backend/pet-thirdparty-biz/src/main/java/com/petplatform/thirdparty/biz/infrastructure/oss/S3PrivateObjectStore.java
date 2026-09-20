package com.petplatform.thirdparty.biz.infrastructure.oss;

import com.petplatform.thirdparty.biz.application.port.PrivateObjectStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

/** Private, immutable S3-compatible object store for merchant materials. */
public final class S3PrivateObjectStore implements PrivateObjectStore, AutoCloseable {
  private static final String PREFIX = "merchant-materials/";
  private static final int MAX_BYTES = 10 * 1024 * 1024;
  private final OssConnection connection;
  private final S3Client s3;

  public S3PrivateObjectStore(OssConnection connection) {
    this(connection, client(connection));
  }

  S3PrivateObjectStore(OssConnection connection, S3Client s3) {
    this.connection = java.util.Objects.requireNonNull(connection);
    this.s3 = java.util.Objects.requireNonNull(s3);
    validatePrivateBucketAcl();
  }

  private static S3Client client(OssConnection connection) {
    URI endpoint = URI.create(connection.endpoint());
    if (!"https".equals(endpoint.getScheme())
        || endpoint.getUserInfo() != null
        || endpoint.getHost() == null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null) {
      throw new IllegalArgumentException("Private object endpoint must use HTTPS");
    }
    return S3Client.builder()
        .endpointOverride(URI.create(connection.endpoint()))
        .region(Region.of(connection.region()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(connection.accessKeyId(), connection.secretAccessKey())))
        .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
        .overrideConfiguration(
            c ->
                c.apiCallTimeout(java.time.Duration.ofSeconds(30))
                    .apiCallAttemptTimeout(java.time.Duration.ofSeconds(10)))
        .build();
  }

  @Override
  public StoredObject putIfAbsent(
      String objectKey, byte[] content, String mediaType, String sha256) {
    requireKey(objectKey);
    requireContent(content, mediaType, sha256);
    try {
      // Existing objects are read, never overwritten; the digest is verified from actual bytes.
      Optional<StoredObject> prior = head(objectKey);
      if (prior.isPresent())
        return verifyExisting(objectKey, prior.get(), content, mediaType, sha256);
      PutObjectRequest.Builder put = PutObjectRequest.builder()
                  .bucket(connection.bucket())
                  .key(objectKey)
                  .contentType(mediaType)
                  .metadata(Map.of("sha256", sha256));
      String host = URI.create(connection.endpoint()).getHost();
      if (host != null && (host.endsWith(".aliyuncs.com") || host.endsWith(".aliyuncs.com.cn"))) {
        // OSS rejects the S3 If-None-Match PUT header with NotImplemented. Its native atomic
        // overwrite guard is supported through the S3-compatible endpoint (live verified).
        put.overrideConfiguration(c -> c.putHeader("x-oss-forbid-overwrite", "true"));
      } else {
        put.ifNoneMatch("*");
      }
      PutObjectResponse response = s3.putObject(put.build(), RequestBody.fromBytes(content));
      return new StoredObject(
          version(response.versionId(), response.eTag()), sha256, content.length, mediaType);
    } catch (S3Exception failure) {
      if (failure.statusCode() != 409 && failure.statusCode() != 412) throw unavailable();
      StoredObject existing = head(objectKey).orElseThrow(S3PrivateObjectStore::unavailable);
      return verifyExisting(objectKey, existing, content, mediaType, sha256);
    }
  }

  private StoredObject verifyExisting(
      String key, StoredObject existing, byte[] content, String mediaType, String digest) {
    if (!digest.equals(existing.sha256())
        || existing.bytes() != content.length
        || !mediaType.equals(existing.mediaType())) throw unavailable();
    StoredContent actual = get(key, existing.versionRef());
    if (!digest.equals(actual.sha256()) || !MessageDigest.isEqual(content, actual.content()))
      throw unavailable();
    return existing;
  }

  @Override
  public StoredContent get(String objectKey, String versionRef) {
    requireKey(objectKey);
    requireVersion(versionRef);
    try {
      GetObjectRequest.Builder request =
          GetObjectRequest.builder().bucket(connection.bucket()).key(objectKey);
      if (versionRef.startsWith("version:")) request.versionId(versionRef.substring(8));
      else request.ifMatch(versionRef.substring(5));
      try (var response = s3.getObject(request.build())) {
        if (versionRef.startsWith("etag:")
            && !versionRef.substring(5).equals(response.response().eTag())) {
          throw unavailable();
        }
        byte[] bytes = readBounded(response);
        String digest = sha256Hex(bytes);
        String storedDigest = response.response().metadata().get("sha256");
        if (storedDigest == null || !storedDigest.equals(digest)) throw unavailable();
        return new StoredContent(bytes, response.response().contentType(), digest);
      }
    } catch (IOException | S3Exception failure) {
      throw unavailable();
    }
  }

  @Override
  public Optional<StoredObject> head(String objectKey) {
    requireKey(objectKey);
    try {
      HeadObjectResponse response =
          s3.headObject(
              HeadObjectRequest.builder().bucket(connection.bucket()).key(objectKey).build());
      String digest = response.metadata().get("sha256");
      if (digest == null
          || !digest.matches("[0-9a-f]{64}")
          || response.contentLength() == null
          || response.contentLength() < 1
          || response.contentLength() > MAX_BYTES
          || !("image/png".equals(response.contentType())
              || "image/jpeg".equals(response.contentType()))) throw unavailable();
      return Optional.of(
          new StoredObject(
              version(response.versionId(), response.eTag()),
              digest,
              response.contentLength(),
              response.contentType()));
    } catch (NoSuchKeyException failure) {
      return Optional.empty();
    } catch (S3Exception failure) {
      if (failure.statusCode() == 404) return Optional.empty();
      throw unavailable();
    }
  }

  private void validatePrivateBucketAcl() {
    try {
      GetBucketAclResponse acl =
          s3.getBucketAcl(GetBucketAclRequest.builder().bucket(connection.bucket()).build());
      if (acl.owner() == null
          || acl.owner().id() == null
          || acl.owner().id().isBlank()
          || acl.grants().isEmpty()) throw unavailable();
      for (Grant grant : acl.grants()) {
        Grantee grantee = grant.grantee();
        if (grantee == null
            || grantee.type() != Type.CANONICAL_USER
            || !acl.owner().id().equals(grantee.id())
            || grantee.uri() != null
            || grant.permission() != Permission.FULL_CONTROL) throw unavailable();
      }
    } catch (RuntimeException failure) {
      close();
      throw new IllegalStateException("Private object bucket ACL cannot be verified");
    }
  }

  private static void requireKey(String key) {
    if (key == null
        || !key.startsWith(PREFIX)
        || key.length() > 512
        || key.contains("..")
        || key.indexOf('\\') >= 0
        || key.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid private object key");
    }
  }

  private static void requireContent(byte[] content, String mediaType, String digest) {
    if (content == null
        || content.length < 1
        || content.length > MAX_BYTES
        || (!"image/jpeg".equals(mediaType) && !"image/png".equals(mediaType))
        || digest == null
        || !digest.matches("[0-9a-f]{64}")
        || !digest.equals(sha256Hex(content))) {
      throw new IllegalArgumentException("Invalid private object content");
    }
  }

  private static void requireVersion(String version) {
    if (version == null
        || version.length() > 128
        || version.codePoints().anyMatch(Character::isISOControl)
        || (!version.matches("version:[A-Za-z0-9._~+/=-]+")
            && !version.matches("etag:\"?[A-Fa-f0-9]{32}(-[0-9]+)?\"?"))) {
      throw new IllegalArgumentException("Invalid immutable object version");
    }
  }

  private static String version(String versionId, String eTag) {
    String result =
        versionId == null || versionId.isBlank() || "null".equals(versionId)
            ? "etag:" + eTag
            : "version:" + versionId;
    requireVersion(result);
    return result;
  }

  private static byte[] readBounded(java.io.InputStream input) throws IOException {
    var output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    for (int read; (read = input.read(buffer)) != -1; ) {
      total = Math.addExact(total, read);
      if (total > MAX_BYTES) throw unavailable();
      output.write(buffer, 0, read);
    }
    if (total == 0) throw unavailable();
    return output.toByteArray();
  }

  private static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Private object storage unavailable");
  }

  @Override
  public void close() {
    s3.close();
  }
}
