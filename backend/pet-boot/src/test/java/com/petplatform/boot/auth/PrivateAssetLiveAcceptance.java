package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.PrivateAssetReadAuthorizer;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.thirdparty.biz.infrastructure.oss.*;
import com.petplatform.thirdparty.biz.infrastructure.provider.assetimage.ClamAvPrivateAssetScanner;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual-only HTTP -> MySQL -> real OSS/ClamAV acceptance.
 * Authentication deliberately uses FixedWechatProvider and does not prove real-device login.
 */
class PrivateAssetLiveAcceptance {
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void syntheticUploadAndReplayReachRealProviders() throws Exception {
    Assumptions.assumeTrue(
        "true".equalsIgnoreCase(System.getenv("PRIVATE_ASSET_LIVE_ACCEPTANCE")),
        "manual live acceptance flag is required");
    System.out.println("LIVE_ACCEPTANCE stage=auth-boundary result=FIXED_WECHAT_TEST_DOUBLE");
    CAuthHttpTest.HttpFixture db = new CAuthHttpTest.HttpFixture();
    ConfigurableApplicationContext context = null;
    OssConnection connection = OssConnection.fromEnv();
    Long assetId = null;
    int cleanupFailures = 2;
    try {
      PrivateAssetLiveSupport.initializePrivateSchemas(db.source);
      byte[] grantKey = new byte[32];
      Arrays.fill(grantKey, (byte) 41);
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  configurable -> {
                    var beans = (GenericApplicationContext) configurable;
                    beans.registerBean("liveDataSource", DataSource.class, () -> db.source);
                    beans.registerBean("liveIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "liveFixedWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "livePrivateObjects",
                        PrivateObjectStore.class,
                        () -> new S3PrivateObjectStore(connection));
                    beans.registerBean(
                        "liveClamAv",
                        PrivateAssetScanner.class,
                        () ->
                            new ClamAvPrivateAssetScanner(
                                "127.0.0.1", 13310, Duration.ofSeconds(10)));
                    beans.registerBean(
                        "liveGrantKey",
                        PrivateAssetGrantKeyProvider.class,
                        () ->
                            () ->
                                new PrivateAssetGrantKeyProvider.KeyMaterial(
                                    "live-test-v1",
                                    new SecretKeySpec(grantKey, "HmacSHA256")));
                    beans.registerBean(
                        "liveReasonProtector",
                        PrivateAssetReasonProtector.class,
                        () -> (purpose, value) -> (purpose + ":SYNTHETIC").getBytes(StandardCharsets.UTF_8));
                    beans.registerBean(
                        "liveReadDenied",
                        PrivateAssetReadAuthorizer.class,
                        () ->
                            request -> {
                              throw new ApiException(
                                  CommonApiCodes.FORBIDDEN,
                                  "admin read is outside this upload-only live acceptance");
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
                  "--pet.private-assets.worker-owner=live-private-acceptance");
      String origin =
          "http://127.0.0.1:"
              + context.getEnvironment().getProperty("local.server.port")
              + "/api/v1/c";
      Map<String, Object> owner = login(origin);
      System.out.println("LIVE_ACCEPTANCE stage=fixed-auth result=PASS");
      byte[] png = PrivateAssetLiveSupport.syntheticTestPng();
      String requestId = UUID.randomUUID().toString();
      Reply created = upload(origin, owner, requestId, png);
      assertEquals(201, created.status());
      assertEquals("READY", created.data().get("status"));
      assetId = Long.parseLong(string(created.data(), "assetId"));
      System.out.println("LIVE_ACCEPTANCE stage=real-oss-clam-upload result=PASS");
      Reply replay = upload(origin, owner, requestId, png);
      assertEquals(200, replay.status());
      assertEquals(created.data(), replay.data());
      System.out.println("LIVE_ACCEPTANCE stage=idempotent-replay result=PASS");
    } finally {
      if (assetId != null) cleanupFailures = PrivateAssetLiveSupport.cleanupFixtureAsset(db.jdbc, assetId, connection);
      System.out.println("LIVE_ACCEPTANCE stage=exact-object-cleanup failures=" + cleanupFailures);
      if (context != null) context.close();
      db.close();
    }
    assertEquals(0, cleanupFailures, "fixture-owned OSS objects must be removed exactly");
  }

  private Map<String, Object> login(String origin) throws Exception {
    Reply attempt = json(origin + "/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"),
        Map.of("X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(201, attempt.status());
    Reply login = json(origin + "/auth/wechat-login",
        Map.of("attemptId", string(attempt.data(), "attemptId"), "wechatCode", "ok:live-synthetic",
            "phoneCode", "phone:13800009901"),
        Map.of("X-Request-Id", UUID.randomUUID().toString(),
            "X-Auth-Attempt", string(attempt.data(), "attemptToken")));
    assertEquals(200, login.status());
    return login.data();
  }

  private Reply json(String url, Object body, Map<String, String> headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
    headers.forEach(request::header);
    var response = http.send(request.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
    return reply(response.statusCode(), response.body());
  }

  private Reply upload(String origin, Map<String, Object> owner, String requestId, byte[] content)
      throws Exception {
    String boundary = "----pet-live-" + UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"
        + "MERCHANT_APPLICATION_MATERIAL\r\n--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"file\"; filename=\"synthetic-test.png\"\r\n"
        + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    body.write(content);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    var response = http.send(HttpRequest.newBuilder(URI.create(origin + "/private-assets"))
        .timeout(Duration.ofSeconds(45))
        .header("Authorization", "Bearer " + string(owner, "accessToken"))
        .header("X-Request-Id", requestId)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
        HttpResponse.BodyHandlers.ofString());
    return reply(response.statusCode(), response.body());
  }

  @SuppressWarnings("unchecked")
  private Reply reply(int status, String body) throws Exception {
    Map<String, Object> envelope = json.readValue(body, Map.class);
    return new Reply(status, (Map<String, Object>) envelope.get("data"));
  }

  private static String string(Map<String, Object> values, String key) {
    return assertInstanceOf(String.class, values.get(key));
  }

  private record Reply(int status, Map<String, Object> data) {}
}
