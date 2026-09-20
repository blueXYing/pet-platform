package com.petplatform.boot.auth;

import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

final class PrivateAssetLiveSupport {
  private PrivateAssetLiveSupport() {}

  static void initializePrivateSchemas(DataSource source) throws Exception {
    Path root = CAuthHttpTest.HttpFixture.root();
    try (Connection connection = source.getConnection()) {
      for (String file :
          List.of(
              "13-Async-Infra-Schema-v0.1.sql",
              "26-Admin-Auth-Schema-v0.1.sql",
              "28-Merchant-Agreement-Schema-v0.1.sql",
              "29-Merchant-Application-Schema-v0.1.sql",
              "31-Private-Asset-Schema-v0.1.sql")) {
        ScriptUtils.executeSqlScript(
            connection,
            new EncodedResource(
                new FileSystemResource(root.resolve("docs/03-database/" + file)),
                StandardCharsets.UTF_8));
      }
    }
  }

  static byte[] syntheticTestPng() throws Exception {
    BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(new Color(245, 248, 252));
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      graphics.setColor(new Color(190, 25, 45));
      graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 52));
      graphics.drawString("SYNTHETIC TEST", 76, 170);
      graphics.setColor(Color.DARK_GRAY);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 25));
      graphics.drawString("PRIVATE ASSET ACCEPTANCE - NOT A REAL DOCUMENT", 28, 225);
    } finally {
      graphics.dispose();
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder unavailable");
    return output.toByteArray();
  }

  /** Deletes only the two exact objects recorded for the supplied fixture-owned asset. Never lists. */
  static int cleanupFixtureAsset(JdbcTemplate jdbc, long assetId, OssConnection connection) {
    var rows =
        jdbc.queryForList(
            "SELECT source_object_key,source_object_version_ref,object_key,object_version_ref "
                + "FROM private_asset WHERE id=?",
            assetId);
    if (rows.size() != 1) return 2;
    var row = rows.getFirst();
    int failures = 0;
    try (S3Client s3 = client(connection)) {
      failures += delete(s3, connection.bucket(), (String) row.get("source_object_key"),
          (String) row.get("source_object_version_ref"));
      failures += delete(s3, connection.bucket(), (String) row.get("object_key"),
          (String) row.get("object_version_ref"));
    } catch (RuntimeException unavailable) {
      return 2;
    }
    return failures;
  }

  private static int delete(S3Client s3, String bucket, String key, String versionRef) {
    if (key == null || !key.startsWith("merchant-materials/") || versionRef == null) return 1;
    try {
      DeleteObjectRequest.Builder request = DeleteObjectRequest.builder().bucket(bucket).key(key);
      if (versionRef.startsWith("version:")) request.versionId(versionRef.substring(8));
      s3.deleteObject(request.build());
      return 0;
    } catch (RuntimeException failure) {
      return 1;
    }
  }

  private static S3Client client(OssConnection connection) {
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
}
