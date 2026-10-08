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
 * CCR-C006 P1 coupon/points reads over the FULL security chain (pet.auth.c.enabled=true plus the
 * coupon/points assembly, which rides the same switch). Regression for the 2026-10-08 acceptance
 * finding: #112 wired the routes into CBearerSessionFilter but missed the CSessionSecurityChain
 * whitelist, so real traffic hit anyRequest().denyAll() and answered 403. The controller-level
 * CouponPointsReadHttpTest cannot catch this — only a booted chain can. Also pins the two chain
 * properties the whitelist must keep: the routes stay session-mandatory (anonymous 401) and the
 * registration stays GET-only (non-GET keeps the deny).
 */
class CouponPointsReadSecurityChainHttpTest {
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
                    beans.registerBean(
                        "cpnptsChainDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean(
                        "cpnptsChainIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "cpnptsChainWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
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
  void chainLetsBearerReadsThroughForAllFourRoutes() throws Exception {
    String token = str(consumerLogin("cpnpts-chain-owner", "13800009901"), "accessToken");

    // Both list/balance reads pass the chain and answer the empty first state, not 403.
    Reply coupons = send("GET", "/coupons", null, bearer(token));
    assertEquals(200, coupons.status());
    assertEquals("SUCCESS", coupons.envelope().get("code"));
    assertEquals(0, ((Number) coupons.data().get("total")).intValue());
    assertEquals("no-store", coupons.raw().headers().firstValue("Cache-Control").orElseThrow());

    Reply balance = send("GET", "/points/balance", null, bearer(token));
    assertEquals(200, balance.status());
    assertEquals("0", balance.data().get("balance"));

    Reply ledger = send("GET", "/points/ledger", null, bearer(token));
    assertEquals(200, ledger.status());
    assertEquals(0, ((Number) ledger.data().get("total")).intValue());

    // Unknown detail crosses the chain and surfaces the business anti-enumeration 404,
    // never the chain's COMMON_FORBIDDEN 403.
    Reply absent =
        send("GET", "/coupons/940000000000999", null, bearer(token));
    assertEquals(404, absent.status());
    assertEquals("COMMON_NOT_FOUND", absent.envelope().get("code"));

    // The whitelist is GET-only: a non-GET method keeps the chain deny.
    assertEquals(403, send("POST", "/coupons", Map.of(), bearer(token)).status());
    assertEquals(403, send("DELETE", "/points/balance", null, bearer(token)).status());
  }

  @Test
  void chainStillRequiresTheMiniappBearerOnEveryRoute() throws Exception {
    consumerLogin("cpnpts-chain-anon", "13800009902");

    assertEquals(401, send("GET", "/coupons", null, Map.of()).status());
    assertEquals(401, send("GET", "/coupons/940000000000621", null, Map.of()).status());
    assertEquals(401, send("GET", "/points/balance", null, Map.of()).status());
    assertEquals(401, send("GET", "/points/ledger", null, Map.of()).status());
    assertEquals(401, send("GET", "/coupons", null,
        Map.of("Authorization", "Bearer not-a-real-session")).status());
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
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    HttpResponse<String> response =
        http.send(
            builder
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Map<?, ?> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String);
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), (Map<String, Object>) envelope, response);
  }

  private static Map<String, String> bearer(String token) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + token);
    return headers;
  }

  private static String str(Map<String, Object> value, String key) {
    Object found = value.get(key);
    if (found == null) throw new IllegalArgumentException("Missing " + key);
    return String.valueOf(found);
  }
}
