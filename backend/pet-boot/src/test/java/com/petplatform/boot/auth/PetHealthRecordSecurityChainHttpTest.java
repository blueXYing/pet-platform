package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract 68 health-record sub-resources over the FULL C security chain. The pet controller
 * routes existed but the CSessionSecurityConfiguration whitelist only matched
 * /api/v1/c/pets and /api/v1/c/pets/*, so the real DevTools/UI calls to
 * /pets/{id}/vaccination-records and /pets/{id}/deworming-records hit anyRequest().denyAll()
 * and answered 403. This booted-chain regression pins both POST and GET, plus the mandatory
 * MINIAPP bearer for the /api/v1/c/pets/ protected prefix.
 */
class PetHealthRecordSecurityChainHttpTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private CAuthHttpTest.HttpFixture db;
  private ConfigurableApplicationContext context;
  private String base;

  @BeforeEach
  void start() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    // Contract 68 table pair is not part of the generic auth fixture (06+14+25).
    var schema = Files.list(CAuthHttpTest.HttpFixture.root().resolve("docs/03-database"))
        .filter(p -> p.getFileName().toString().startsWith("68-")).findFirst().orElseThrow();
    try (var connection = db.source.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new FileSystemResource(schema));
    }
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
                        "pethrChainDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean(
                        "pethrChainIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "pethrChainWechat",
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
  void chainLetsVaccinationAndDewormingReadWriteThrough() throws Exception {
    String token = str(consumerLogin("pethr-chain-owner", "13800009911"), "accessToken");
    Reply created = send("POST", "/pets",
        Map.of("name", "security-chain-pet", "petType", "DOG"), writeHeaders(token));
    assertEquals(201, created.status(), created.toString());
    String petId = str(created.data(), "petId");

    Reply listVaccine = send("GET", "/pets/" + petId + "/vaccination-records", null, bearer(token));
    assertEquals(200, listVaccine.status(), listVaccine.toString());
    assertEquals("SUCCESS", listVaccine.envelope().get("code"));

    Reply addVaccine = send("POST", "/pets/" + petId + "/vaccination-records",
        Map.of("recordDate", "2026-07-26", "vaccineName", "矿泉疫苗",
            "doseNumber", 3, "nextRecordDate", "2027-12-26"), writeHeaders(token));
    assertEquals(201, addVaccine.status(), addVaccine.toString());
    assertEquals("SUCCESS", addVaccine.envelope().get("code"));
    assertTrue(addVaccine.data().containsKey("recordId"));

    Reply listDeworm = send("GET", "/pets/" + petId + "/deworming-records", null, bearer(token));
    assertEquals(200, listDeworm.status(), listDeworm.toString());
    assertEquals("SUCCESS", listDeworm.envelope().get("code"));

    Reply addDeworm = send("POST", "/pets/" + petId + "/deworming-records",
        Map.of("recordDate", "2026-07-26", "dewormingType", "EXTERNAL"),
        writeHeaders(token));
    assertEquals(201, addDeworm.status(), addDeworm.toString());
  }

  @Test
  void chainStillRequiresTheMiniappBearerOnHealthRecordRoutes() throws Exception {
    consumerLogin("pethr-chain-anon", "13800009912");
    Reply created = send("POST", "/pets",
        Map.of("name", "security-chain-anon-pet", "petType", "CAT"), writeHeadersWithoutToken());
    assertEquals(401, created.status());

    assertEquals(401, send("GET", "/pets/710200/vaccination-records", null, Map.of()).status());
    assertEquals(401, send("POST", "/pets/710200/vaccination-records",
        Map.of("recordDate", "2026-07-26", "vaccineName", "x"), Map.of()).status());
    assertEquals(401, send("GET", "/pets/710200/vaccination-records", null,
        Map.of("Authorization", "Bearer not-a-real-session")).status());
  }

  private Map<String, Object> consumerLogin(String openid, String phone) throws Exception {
    Reply attempt =
        send("POST", "/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"),
            Map.of("X-Request-Id", UUID.randomUUID().toString()));
    Reply grant =
        send("POST", "/auth/wechat-login",
            Map.of("attemptId", str(attempt.data(), "attemptId"),
                "wechatCode", "ok:" + openid, "phoneCode", "phone:" + phone),
            Map.of("X-Auth-Attempt", str(attempt.data(), "attemptToken"),
                "X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(200, grant.status());
    return grant.data();
  }

  private static Map<String, String> bearer(String token) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + token);
    return headers;
  }

  private static Map<String, String> writeHeaders(String token) {
    Map<String, String> headers = bearer(token);
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    return headers;
  }

  private static Map<String, String> writeHeadersWithoutToken() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    return headers;
  }

  private static String str(Map<String, Object> value, String key) {
    Object found = value.get(key);
    if (found == null) throw new IllegalArgumentException("Missing " + key);
    return String.valueOf(found);
  }

  private record Reply(int status, Map<String, Object> envelope, HttpResponse<String> raw) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    @Override
    public String toString() {
      return "Reply[status=" + status + ", body=" + envelope + "]";
    }
  }

  private Reply send(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    HttpResponse<String> response =
        http.send(builder.method(method,
                body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    @SuppressWarnings("unchecked")
    Map<String, Object> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String);
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), envelope, response);
  }
}
