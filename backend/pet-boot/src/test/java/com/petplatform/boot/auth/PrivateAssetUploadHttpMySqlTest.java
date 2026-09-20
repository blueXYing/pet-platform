package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.*;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/** Real loopback HTTP -> private core -> SQL13/31; only OSS/scanner are controlled doubles. */
class PrivateAssetUploadHttpMySqlTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void multipartUploadReplayAndOwnerIsolationReachTheRealCoreAndDatabase() throws Exception {
    CAuthHttpTest.HttpFixture db = new CAuthHttpTest.HttpFixture();
    ConfigurableApplicationContext context = null;
    try {
      Path root = CAuthHttpTest.HttpFixture.root();
      try (var connection = db.source.getConnection()) {
        for (String file :
            List.of("13-Async-Infra-Schema-v0.1.sql", "31-Private-Asset-Schema-v0.1.sql")) {
          ScriptUtils.executeSqlScript(
              connection,
              new EncodedResource(
                  new FileSystemResource(root.resolve("docs/03-database/" + file)),
                  StandardCharsets.UTF_8));
        }
      }
      MemoryPrivateObjects objects = new MemoryPrivateObjects();
      String grantKey = Base64.getEncoder().encodeToString(key(41));
      String reasonKey = Base64.getEncoder().encodeToString(key(53));
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  configurable -> {
                    var beans = (GenericApplicationContext) configurable;
                    beans.registerBean("privateHttpDataSource", DataSource.class, () -> db.source);
                    beans.registerBean("privateHttpIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "privateHttpWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "privateHttpObjects", PrivateObjectStore.class, () -> objects);
                    beans.registerBean(
                        "privateHttpScanner",
                        PrivateAssetScanner.class,
                        () ->
                            content ->
                                new PrivateAssetScanner.ScanResult(true, "QA_SCANNER_1", "CLEAN"));
                    beans.registerBean(
                        "privateHttpReadAuthorizer",
                        PrivateAssetReadAuthorizer.class,
                        () ->
                            request -> {
                              throw new ApiException(
                                  CommonApiCodes.FORBIDDEN,
                                  "read authorization is outside this upload fixture");
                            });
                  })
              .run(
                  "--server.port=0",
                  "--spring.flyway.enabled=false",
                  "--spring.main.banner-mode=off",
                  "--spring.jmx.enabled=false",
                  "--pet.auth.admin.enabled=false",
                  "--pet.auth.c.enabled=true",
                  "--pet.auth.c.redis-host=" + db.redisHost,
                  "--pet.auth.c.redis-port=" + db.redisPort,
                  "--pet.auth.c.cache-prefix=" + db.prefix,
                  "--pet.private-assets.enabled=true",
                  "--pet.private-assets.worker-owner=qa-private-http-worker",
                  "--PRIVATE_ASSET_GRANT_KEY_VERSION=qa-grant-v1",
                  "--PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64=" + grantKey,
                  "--PRIVATE_ASSET_REASON_KEY_VERSION=qa-reason-v1",
                  "--PRIVATE_ASSET_REASON_AES_KEY_BASE64=" + reasonKey);
      String origin =
          "http://127.0.0.1:"
              + context.getEnvironment().getProperty("local.server.port")
              + "/api/v1/c";
      Map<String, Object> firstOwner = login(origin, "private-owner-a", "13800008801");
      Map<String, Object> otherOwner = login(origin, "private-owner-b", "13800008802");
      byte[] png = syntheticPng();
      String requestId = UUID.randomUUID().toString();

      Reply created = upload(origin, firstOwner, requestId, png);
      assertEquals(201, created.status());
      assertEquals(
          Set.of("assetId", "status", "objectSha256", "mediaType", "bytes"),
          created.data().keySet());
      assertEquals("READY", created.data().get("status"));
      assertEquals("image/png", created.data().get("mediaType"));
      String assetId = string(created.data(), "assetId");

      Reply replay = upload(origin, firstOwner, requestId, png);
      assertEquals(200, replay.status());
      assertEquals(created.data(), replay.data());
      Reply otherOwnerUpload = upload(origin, otherOwner, requestId, png);
      assertEquals(201, otherOwnerUpload.status());
      assertNotEquals(assetId, string(otherOwnerUpload.data(), "assetId"));
      assertEquals(
          1,
          db.jdbc.queryForObject(
              "SELECT COUNT(*) FROM private_asset_upload_request WHERE owner_user_id=?",
              Integer.class,
              Long.parseLong(string(firstOwner, "userId"))));
      assertEquals(
          "READY",
          db.jdbc.queryForObject(
              "SELECT status FROM private_asset WHERE id=?",
              String.class,
              Long.parseLong(assetId)));
      assertEquals(
          1,
          db.jdbc.queryForObject(
              "SELECT COUNT(*) FROM async_task WHERE task_key=?",
              Integer.class,
              "PRIVATE_ASSET_RECONCILE:" + assetId));

      PrivateAssetApi api = context.getBean(PrivateAssetApi.class);
      PrivateAssetFact fact =
          api.resolveOwned(
                  new ResolveOwnedPrivateAssetsQuery(
                      string(firstOwner, "userId"),
                      List.of(assetId),
                      "MERCHANT_APPLICATION_MATERIAL"))
              .getFirst();
      assertEquals(created.data().get("objectSha256"), fact.objectSha256());
      ApiException denied =
          assertThrows(
              ApiException.class,
              () ->
                  api.resolveOwned(
                      new ResolveOwnedPrivateAssetsQuery(
                          string(otherOwner, "userId"),
                          List.of(assetId),
                          "MERCHANT_APPLICATION_MATERIAL")));
      assertEquals(CommonApiCodes.NOT_FOUND, denied.code());
    } finally {
      if (context != null) context.close();
      db.close();
    }
  }

  private Map<String, Object> login(String origin, String openid, String phone) throws Exception {
    Reply attempt =
        json(
            origin + "/auth/attempts",
            Map.of("purpose", "WECHAT_LOGIN"),
            Map.of("X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(201, attempt.status());
    Reply grant =
        json(
            origin + "/auth/wechat-login",
            Map.of(
                "attemptId",
                string(attempt.data(), "attemptId"),
                "wechatCode",
                "ok:" + openid,
                "phoneCode",
                "phone:" + phone),
            Map.of(
                "X-Request-Id",
                UUID.randomUUID().toString(),
                "X-Auth-Attempt",
                string(attempt.data(), "attemptToken")));
    assertEquals(200, grant.status());
    return grant.data();
  }

  private Reply json(String url, Object body, Map<String, String> headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
    headers.forEach(request::header);
    HttpResponse<String> response =
        http.send(
            request
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return reply(response.statusCode(), response.body());
  }

  private Reply upload(String origin, Map<String, Object> owner, String requestId, byte[] content)
      throws Exception {
    String boundary = "----pet-private-" + UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                + "MERCHANT_APPLICATION_MATERIAL\r\n--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"evidence.png\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    body.write(content);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(origin + "/private-assets"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + string(owner, "accessToken"))
                .header("X-Request-Id", requestId)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals("no-store, private", response.headers().firstValue("Cache-Control").orElse(null));
    return reply(response.statusCode(), response.body());
  }

  @SuppressWarnings("unchecked")
  private Reply reply(int status, String body) throws Exception {
    Map<String, Object> envelope = json.readValue(body, Map.class);
    assertInstanceOf(String.class, envelope.get("traceId"));
    return new Reply(status, (Map<String, Object>) envelope.get("data"));
  }

  private static byte[] syntheticPng() throws IOException {
    BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++)
        image.setRGB(x, y, (x * 7 << 16) | (y * 9 << 8) | 0x55);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", output));
    return output.toByteArray();
  }

  private static String string(Map<String, Object> values, String key) {
    return assertInstanceOf(String.class, values.get(key));
  }

  private static byte[] key(int fill) {
    byte[] value = new byte[32];
    Arrays.fill(value, (byte) fill);
    return value;
  }

  private record Reply(int status, Map<String, Object> data) {}

  private static final class MemoryPrivateObjects implements PrivateObjectStore {
    private final Map<String, StoredContent> values = new ConcurrentHashMap<>();

    @Override
    public StoredObject putIfAbsent(
        String objectKey, byte[] content, String mediaType, String sha256) {
      StoredContent candidate = new StoredContent(content, mediaType, sha256);
      StoredContent existing = values.putIfAbsent(objectKey, candidate);
      StoredContent stored = existing == null ? candidate : existing;
      if (!stored.sha256().equals(sha256)
          || !stored.mediaType().equals(mediaType)
          || !Arrays.equals(stored.content(), content))
        throw new IllegalStateException("immutable object mismatch");
      return new StoredObject("version:qa-v1", sha256, content.length, mediaType);
    }

    @Override
    public StoredContent get(String objectKey, String versionRef) {
      if (!"version:qa-v1".equals(versionRef)) throw new IllegalStateException("version mismatch");
      return values.get(objectKey);
    }

    @Override
    public Optional<StoredObject> head(String objectKey) {
      StoredContent stored = values.get(objectKey);
      return stored == null
          ? Optional.empty()
          : Optional.of(
              new StoredObject(
                  "version:qa-v1", stored.sha256(), stored.content().length, stored.mediaType()));
    }
  }
}
