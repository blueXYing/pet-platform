package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * SSOT §16.4 C-side preference surface over the real HTTP stack: unread schema defaults,
 * strict full-PUT bodies, terminal X-Request-Id idempotency (replay vs. parameter conflict)
 * and cross-user isolation. No external push is exercised — the switch only records intent.
 */
class NotificationPreferenceHttpTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private CAuthHttpTest.HttpFixture db;
  private ConfigurableApplicationContext context;
  private String base;

  @BeforeEach
  void start() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", 0);
    props.put("spring.flyway.enabled", false);
    props.put("spring.main.banner-mode", "off");
    props.put("spring.jmx.enabled", false);
    props.put("pet.auth.admin.enabled", false);
    props.put("pet.auth.c.enabled", true);
    props.put("pet.auth.c.redis-host", db.redisHost);
    props.put("pet.auth.c.redis-port", db.redisPort);
    props.put("pet.auth.c.cache-prefix", db.prefix);
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .properties(props)
              .initializers(
                  c -> {
                    var beans = (GenericApplicationContext) c;
                    beans.registerBean("prefDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("prefIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "prefWechat", WechatSessionProvider.class, CAuthHttpTest.FixedWechatProvider::new);
                  })
              .run(
                  "--pet.auth.c.enabled=true",
                  "--pet.auth.c.redis-host=" + db.redisHost,
                  "--pet.auth.c.redis-port=" + db.redisPort,
                  "--pet.auth.c.cache-prefix=" + db.prefix);
      base =
          "http://127.0.0.1:"
              + context.getEnvironment().getProperty("local.server.port")
              + "/api/v1/c";
    } catch (Exception failure) {
      if (context != null) context.close();
      db.close();
      throw failure;
    }
  }

  @AfterEach
  void stop() throws Exception {
    try {
      if (context != null) context.close();
    } finally {
      if (db != null) db.close();
    }
  }

  @Test
  void readsDefaultsUpdatesReplaysAndKeepsUsersIsolated() throws Exception {
    Map<String, Object> owner = consumerLogin("pref-owner", "13800008801");
    Map<String, Object> other = consumerLogin("pref-other", "13800008802");
    String token = str(owner, "accessToken");

    assertEquals(401, send("GET", "/notification-preferences", null, Map.of()).status());
    assertEquals(400, send("GET", "/notification-preferences?userId=1", null, bearer(token)).status());

    Reply initial = send("GET", "/notification-preferences", null, bearer(token));
    assertEquals(200, initial.status());
    assertEquals(Boolean.TRUE, initial.data().get("interactionEnabled"));
    assertEquals(Boolean.TRUE, initial.data().get("externalPushEnabled"));
    assertEquals("0", initial.data().get("version"));
    assertNull(initial.data().get("updatedAt"));
    assertEquals("no-store", initial.raw().headers().firstValue("Cache-Control").orElseThrow());

    // Strict body surface: unknown field, explicit null, wrong type, duplicate key, trailing
    // token, missing field and a missing X-Request-Id are all plain 400s.
    String requestId = UUID.randomUUID().toString();
    assertEquals(400, sendRaw("PUT", "/notification-preferences",
        "{\"interactionEnabled\":false,\"externalPushEnabled\":false,\"extra\":1}", bearer(token, requestId)).status());
    assertEquals(400, sendRaw("PUT", "/notification-preferences",
        "{\"interactionEnabled\":null,\"externalPushEnabled\":false}", bearer(token, requestId)).status());
    assertEquals(400, sendRaw("PUT", "/notification-preferences",
        "{\"interactionEnabled\":\"false\",\"externalPushEnabled\":false}", bearer(token, requestId)).status());
    assertEquals(400, sendRaw("PUT", "/notification-preferences",
        "{\"interactionEnabled\":false,\"interactionEnabled\":true,\"externalPushEnabled\":false}",
        bearer(token, requestId)).status());
    assertEquals(400, sendRaw("PUT", "/notification-preferences",
        "{\"interactionEnabled\":false,\"externalPushEnabled\":false} {}",
        bearer(token, requestId)).status());
    assertEquals(400, sendRaw("PUT", "/notification-preferences",
        "{\"interactionEnabled\":false}", bearer(token, requestId)).status());
    assertEquals(400, send("PUT", "/notification-preferences",
        Map.of("interactionEnabled", false, "externalPushEnabled", false), bearer(token)).status());
    assertEquals(400, send("PUT", "/notification-preferences?debug=1",
        Map.of("interactionEnabled", false, "externalPushEnabled", false),
        bearer(token, UUID.randomUUID().toString())).status());

    Reply saved =
        send(
            "PUT",
            "/notification-preferences",
            Map.of("interactionEnabled", false, "externalPushEnabled", true),
            bearer(token, requestId));
    assertEquals(200, saved.status());
    assertEquals(Boolean.FALSE, saved.data().get("interactionEnabled"));
    assertEquals(Boolean.TRUE, saved.data().get("externalPushEnabled"));
    assertEquals("0", saved.data().get("version"));
    String savedAt = str(saved.data(), "updatedAt");
    assertTrue(savedAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));

    Reply after = send("GET", "/notification-preferences", null, bearer(token));
    assertEquals(Boolean.FALSE, after.data().get("interactionEnabled"));
    assertEquals("0", after.data().get("version"));

    // Same requestId + same parameters replays the first receipt without a second write.
    Reply replay =
        send(
            "PUT",
            "/notification-preferences",
            Map.of("interactionEnabled", false, "externalPushEnabled", true),
            bearer(token, requestId));
    assertEquals(200, replay.status());
    assertEquals(savedAt, str(replay.data(), "updatedAt"));
    assertEquals("0", replay.data().get("version"));
    assertEquals(1, db.jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification_preference WHERE receiver_id=?",
        Integer.class, Long.parseLong(str(owner, "userId"))));

    // Same requestId + different parameters is the 409 idempotency conflict.
    Reply conflict =
        send(
            "PUT",
            "/notification-preferences",
            Map.of("interactionEnabled", true, "externalPushEnabled", true),
            bearer(token, requestId));
    assertEquals(409, conflict.status());
    assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.envelope().get("code"));

    // Other users keep their own (default) state.
    Reply otherView = send("GET", "/notification-preferences", null, bearer(str(other, "accessToken")));
    assertEquals(200, otherView.status());
    assertEquals(Boolean.TRUE, otherView.data().get("interactionEnabled"));
    assertEquals("0", otherView.data().get("version"));
    assertNull(otherView.data().get("updatedAt"));
    assertEquals(1, db.jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification_preference", Integer.class));
  }

  private Map<String, Object> consumerLogin(String openid, String phone) throws Exception {
    Reply attempt =
        send("POST", "/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"),
            Map.of("X-Request-Id", UUID.randomUUID().toString()));
    Reply grant =
        send(
            "POST",
            "/auth/wechat-login",
            Map.of(
                "attemptId", str(attempt.data(), "attemptId"),
                "wechatCode", "ok:" + openid,
                "phoneCode", "phone:" + phone),
            Map.of(
                "X-Auth-Attempt", str(attempt.data(), "attemptToken"),
                "X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(200, grant.status());
    return grant.data();
  }

  private record Reply(int status, Map<String, Object> envelope, HttpResponse<String> raw) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    @Override
    public String toString() {
      return "Reply[status=" + status + "]";
    }
  }

  private Reply send(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    return sendRaw(
        method,
        path,
        body == null ? null : json.writeValueAsString(body),
        headers,
        body != null);
  }

  private Reply sendRaw(
      String method, String path, String rawBody, Map<String, String> headers) throws Exception {
    return sendRaw(method, path, rawBody, headers, rawBody != null);
  }

  private Reply sendRaw(
      String method,
      String path,
      String rawBody,
      Map<String, String> headers,
      boolean jsonBody)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (jsonBody) builder.header("Content-Type", "application/json");
    HttpResponse<String> response =
        http.send(
            builder
                .method(
                    method,
                    rawBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(rawBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Map<?, ?> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String);
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(
        response.statusCode(), (Map<String, Object>) envelope, response);
  }

  private static Map<String, String> bearer(String token, String requestId) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + token);
    if (requestId != null) headers.put("X-Request-Id", requestId);
    return headers;
  }

  private static Map<String, String> bearer(String token) {
    return bearer(token, null);
  }

  private static String str(Map<String, Object> value, String key) {
    Object found = value.get(key);
    if (found == null) throw new IllegalArgumentException("Missing " + key);
    return String.valueOf(found);
  }
}
