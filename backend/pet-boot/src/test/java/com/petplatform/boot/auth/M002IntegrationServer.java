package com.petplatform.boot.auth;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.id.core.HutoolSnowflakeIdProvider;
import com.petplatform.id.core.JdbcSnowflakeNodeStore;
import com.petplatform.id.core.SnowflakeProviderSettings;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverAssetPort;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import com.petplatform.user.biz.application.WechatSessionProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-002 CI integration server (test scope ONLY): a long-running local boot
 * that assembles the ServiceWriteHttpTest/CStoreControllerHttpTest acceptance recipe on the
 * coordinator's shared MySQL/Redis, with the Hikari pool the MS1 local acceptance proved
 * mandatory for a long-lived Snowflake node. QA seams are the same ones the in-repo acceptance
 * tests use (FixedWechatProvider login codes, permissive private-asset/cover-asset fact stubs,
 * deterministic cover URL signer); the application→approval→signing→service write→review→C read
 * chain itself runs entirely over real HTTP against real Spring/MySQL/Redis. Opting into
 * M002_REAL_PRIVATE_ASSETS uses the real OSS/ClamAV upload pipeline and exact-version cover
 * signer instead of cover stubs; the WeChat and application-material seams stay explicit.
 */
public final class M002IntegrationServer {
  private static final String ORIGIN = "https://svcw.example.invalid";
  private static final String PASSWORD = "Example_ONLY_92!";
  private static final JsonMapper json = JsonMapper.builder().build();
  private static final HttpClient http = HttpClient.newHttpClient();

  private M002IntegrationServer() {}

  public static void main(String[] args) throws Exception {
    if (!"true".equalsIgnoreCase(System.getenv("M002_INTEGRATION_SERVER"))) {
      throw new IllegalStateException("M002_INTEGRATION_SERVER=true is required");
    }
    String server = required("AUTH_MYSQL_URL");
    if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
      throw new IllegalArgumentException("Dedicated local MySQL is required");
    }
    String redisHost = required("AUTH_REDIS_HOST");
    int redisPort = Integer.parseInt(required("AUTH_REDIS_PORT"));
    if (!List.of("127.0.0.1", "localhost").contains(redisHost)) {
      throw new IllegalArgumentException("Dedicated local Redis is required");
    }
    int port = Integer.parseInt(System.getenv().getOrDefault("M002_PORT", "18081"));
    boolean realPrivateAssets = "true".equalsIgnoreCase(System.getenv("M002_REAL_PRIVATE_ASSETS"));
    int nodeId = Integer.parseInt(System.getenv().getOrDefault("M002_SNOWFLAKE_NODE", "21"));
    String name = "auth001cm002ci_" + UUID.randomUUID().toString().replace("-", "");
    Path directory = Files.createDirectories(Path.of(System.getenv()
        .getOrDefault("M002_RUNTIME_DIR", "D:/Temp/m002-ci")));
    String mysqlUser = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
    String mysqlPassword = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");

    JdbcTemplate adminJdbc = new JdbcTemplate(rawSource(server, mysqlUser, mysqlPassword));
    HikariDataSource source = null;
    ConfigurableApplicationContext context = null;
    try {
      adminJdbc.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
      // Long-lived pool (MS1 lesson: per-query DriverManager connections stall the Snowflake
      // single-flight lease renewal ~3 minutes in and fail closed permanently).
      HikariConfig poolConfig = new HikariConfig();
      poolConfig.setJdbcUrl(server + name
          + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
          + "&connectTimeout=1000&socketTimeout=9000");
      poolConfig.setUsername(mysqlUser);
      poolConfig.setPassword(mysqlPassword);
      poolConfig.setPoolName("m002-integration");
      poolConfig.setMinimumIdle(4);
      poolConfig.setMaximumPoolSize(8);
      poolConfig.setConnectionTimeout(500);
      poolConfig.setValidationTimeout(250);
      final HikariDataSource pool = new HikariDataSource(poolConfig);
      source = pool;
      JdbcTemplate jdbc = new JdbcTemplate(pool);
      Path root = root();
      try (var connection = source.getConnection();
           var files = Files.list(root.resolve("docs/03-database"))) {
        Path idSchema = files.filter(p -> p.getFileName().toString().startsWith("25-")
            && p.toString().endsWith(".sql")).findFirst().orElseThrow();
        for (String file : List.of(
            idSchema.getFileName().toString(),
            "06-核心数据库Schema-v0.1.sql",
            "14-Command-Idempotency-Schema-v0.1.sql",
            "26-Admin-Auth-Schema-v0.1.sql",
            "28-Merchant-Agreement-Schema-v0.1.sql",
            "29-Merchant-Application-Schema-v0.1.sql",
            "13-Async-Infra-Schema-v0.1.sql",
            "31-Private-Asset-Schema-v0.1.sql",
            "33-Service-Write-Schema-v0.1.sql")) {
          ScriptUtils.executeSqlScript(connection, new EncodedResource(
              new FileSystemResource(root.resolve("docs/03-database/" + file)),
              StandardCharsets.UTF_8));
        }
      }
      jdbc.update("INSERT INTO merchant_subject_lookup_policy"
          + "(policy_slot,key_version,algorithm,created_at)"
          + " VALUES(1,'qa-only-v1','HMAC-SHA-256',UTC_TIMESTAMP(3))");
      String evidence = "m002-ci-virgin:" + name + ":node" + nodeId;
      jdbc.update("INSERT INTO snowflake_worker_state"
          + "(node_id,format_identity,enabled,initialization_ref,created_at,updated_at)"
          + " VALUES(?,?,TRUE,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          nodeId, SnowflakeProviderSettings.FORMAT_IDENTITY, evidence);
      HutoolSnowflakeIdProvider ids = new HutoolSnowflakeIdProvider(
          new JdbcSnowflakeNodeStore(source), new SnowflakeProviderSettings(nodeId),
          old -> {
            if (old.nodeId() != nodeId || old.incarnation() != null || old.fence() != 0
                || old.reservedThrough() != -1 || !evidence.equals(old.initializationRef())) {
              throw new IllegalStateException("Not this fixture's virgin node");
            }
          });
      warmSnowflake(ids);

      byte[] adminMac = key(3), adminAes = key(7);
      AesGcmProtectedValueProvider protector =
          new AesGcmProtectedValueProvider("qa-only-v1", key(31), key(47));
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(ctx -> {
                var beans = (GenericApplicationContext) ctx;
                beans.registerBean("m002DataSource", DataSource.class, () -> pool);
                beans.registerBean("m002Ids", SnowflakeIdGenerator.class, () -> ids);
                beans.registerBean("m002Wechat", WechatSessionProvider.class,
                    CAuthHttpTest.FixedWechatProvider::new);
                beans.registerBean("m002Protection",
                    ApplicationValidationPorts.ProtectedValuePort.class, () -> protector);
                // QA fact stub (same seam the acceptance tests stub): every requested asset id
                // resolves as a READY image owned by the requester.
                beans.registerBean("m002PrivateAssets", PrivateAssetQueryPort.class,
                    () -> (owner, assetIds) -> assetIds.stream()
                        .map(id -> new PrivateAssetQueryPort.PrivateAssetRef(
                            id, owner, sha("m002-asset-" + id), "image/jpeg", 512, "READY"))
                        .toList());
                beans.registerBean("m002Map", ApplicationValidationPorts.MapValidationPort.class,
                    () -> (city, address, lng, lat) -> "chengdu".equals(city));
                beans.registerBean("m002Subjects", SubjectCredentialPort.class,
                    () -> new MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63)));
                if (realPrivateAssets) {
                  beans.registerBean("m002OssConnection", OssConnection.class, OssConnection::fromEnv);
                } else {
                beans.registerBean("m002CoverAssets", ServiceCoverAssetPort.class,
                    () -> (owner, assetIds) -> assetIds.stream()
                        .map(id -> new ServiceCoverAssetPort.CoverAssetFact(
                            id, owner, "READY", "image/jpeg", 2048))
                        .toList());
                beans.registerBean("m002CoverUrls", ServiceCoverUrlPort.class,
                    () -> assetId -> new ServiceCoverUrlPort.CoverUrl(
                        assetId,
                        "https://cover.example.invalid/signed/" + assetId
                            + "?m002ci=1",
                        java.time.Instant.now().getEpochSecond() + 3600));
                }
              })
              .run(
                  "--server.address=127.0.0.1",
                  "--server.port=" + port,
                  "--spring.flyway.enabled=false",
                  "--spring.main.banner-mode=off",
                  "--spring.jmx.enabled=false",
                  "--pet.auth.c.enabled=true",
                  "--pet.auth.c.redis-host=" + redisHost,
                  "--pet.auth.c.redis-port=" + redisPort,
                  "--pet.auth.c.cache-prefix=" + name + ":",
                  "--pet.auth.admin.enabled=true",
                  "--pet.auth.admin.migration-enabled=false",
                  "--pet.auth.admin.origin=" + ORIGIN,
                  "--pet.auth.admin.redis-host=" + redisHost,
                  "--pet.auth.admin.redis-port=" + redisPort,
                  "--pet.auth.admin.cache-prefix=" + name + "_admin:",
                  "--pet.auth.admin.key-id=qa-key",
                  "--pet.auth.admin.mac-key-base64="
                      + Base64.getEncoder().encodeToString(adminMac),
                  "--pet.auth.admin.encryption-key-base64="
                      + Base64.getEncoder().encodeToString(adminAes),
                  "--pet.auth.admin.audit-path="
                      + directory.resolve("m002-audit.bin"),
                  "--pet.merchant.application.enabled=true",
                  "--pet.merchant.application.open-cities[0].code=chengdu",
                  "--pet.merchant.application.open-cities[0].name=成都",
                  "--pet.merchant.application.notifications-enabled=true",
                  "--pet.outbox.enabled=true",
                  "--pet.service.query.enabled=true",
                  "--pet.service.command.enabled=true",
                  "--pet.service.review.notifications-enabled=true",
                  "--pet.private-assets.enabled=" + realPrivateAssets,
                  "--pet.service.cover-signing.enabled=" + realPrivateAssets,
                  "--pet.service.cover-signing.window-seconds=600",
                  "--PRIVATE_ASSET_CLAMAV_HOST=127.0.0.1",
                  "--PRIVATE_ASSET_CLAMAV_PORT=13310",
                  "--PRIVATE_ASSET_CLAMAV_TIMEOUT_MILLIS=10000",
                  "--PRIVATE_ASSET_GRANT_KEY_VERSION=qa-live-v1",
                  "--PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64=" + base64(71),
                  "--PRIVATE_ASSET_REASON_KEY_VERSION=qa-live-v1",
                  "--PRIVATE_ASSET_REASON_AES_KEY_BASE64=" + base64(83),
                  "--pet.store.query.enabled=true");
      String endpoint = "http://127.0.0.1:" + port;
      context.getBean(AdminAuthService.class).bootstrap(
          "qa-reviewer", "QA Reviewer", PASSWORD.toCharArray(),
          "M-002 CI integration acceptance");
      // Fixture seed rows the acceptance tests also insert via JDBC: the published merchant
      // agreement version (the signing route validates version+content hash) and the ENABLED
      // service category dictionary the workbench reads.
      long adminId = jdbc.queryForObject(
          "SELECT id FROM admin_account WHERE account_display='qa-reviewer'", Long.class);
      String agreementContent = "M-002 CI local acceptance agreement";
      jdbc.update(
          "INSERT INTO merchant_agreement_version"
              + "(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
              + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
          ids.nextId(), "m002-ci-v1", agreementContent, sha(agreementContent), adminId);
      jdbc.update(
          "INSERT INTO merchant_agreement_current"
              + "(agreement_key,agreement_version_id,version,updated_at)"
              + " VALUES('MERCHANT',?,0,UTC_TIMESTAMP(3))",
          jdbc.queryForObject(
              "SELECT id FROM merchant_agreement_version WHERE agreement_version='m002-ci-v1'",
              Long.class));
      for (String[] category : new String[][] {
          {"宠物美容", "1"}, {"宠物寄养", "2"}, {"遛狗陪护", "3"}, {"宠物训练", "4"},
          {"上门喂养", "5"}, {"兽医助理", "6"}}) {
        jdbc.update(
            "INSERT INTO service_category(id,category_name,created_at,updated_at)"
                + " VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
            ids.nextId(), category[0]);
      }
      String adminToken = adminLogin(endpoint, jdbc, adminMac, adminAes);
      Path metadata = directory.resolve("server-metadata.txt");
      Files.writeString(metadata,
          "endpoint=" + endpoint + System.lineSeparator()
              + "database=" + name + System.lineSeparator()
              + "databaseLifecycle=DROP_ON_PROCESS_SHUTDOWN" + System.lineSeparator()
              + "processId=" + ProcessHandle.current().pid() + System.lineSeparator()
              + "snowflakeNodeId=" + nodeId + System.lineSeparator()
              + "privateAssetsMode=" + (realPrivateAssets ? "REAL_OSS_CLAMAV" : "FIXTURE") + System.lineSeparator()
              + "adminToken=" + adminToken + System.lineSeparator()
              + "adminOrigin=" + ORIGIN + System.lineSeparator(),
          StandardCharsets.UTF_8);
      System.out.println("M002_CI result=READY endpoint=" + endpoint
          + " database=" + name + " metadata=" + metadata);
      ConfigurableApplicationContext running = context;
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          running.close();
        } finally {
          try {
            adminJdbc.execute("DROP DATABASE IF EXISTS `" + name + "`");
          } catch (RuntimeException dropFailure) {
            System.err.println("M002_CI cleanup=DROP_FAILED " + dropFailure.getMessage());
          }
        }
      }, "m002-ci-cleanup"));
      new CountDownLatch(1).await();
    } catch (Throwable failure) {
      // Close the context FIRST (Tomcat/id workers stop touching the database), then the
      // pool, then drop the fixture database; finally force JVM exit so no zombie serves on.
      try {
        if (context != null) context.close();
      } catch (RuntimeException ignored) {
        // best-effort shutdown on the failure path
      }
      try {
        if (source != null) source.close();
      } catch (RuntimeException ignored) {
        // best-effort shutdown on the failure path
      }
      try {
        adminJdbc.execute("DROP DATABASE IF EXISTS `" + name + "`");
      } catch (RuntimeException ignored) {
        // best-effort cleanup on the failure path
      }
      throw failure;
    }
  }

  /** Real HTTP admin login (attempts → captcha challenge → MAC fixup → verify → login). */
  private static String adminLogin(String endpoint, JdbcTemplate jdbc, byte[] mac, byte[] aes)
      throws Exception {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Origin", ORIGIN);
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    HttpReply attempt = send("POST", endpoint + "/api/v1/admin/auth/attempts",
        Map.of(), headers);
    String attemptId = str(attempt.data(), "attemptId");
    // The session cookie comes from the attempts response (Set-Cookie), never invented.
    headers.put("Cookie", attempt.response().headers().firstValue("Set-Cookie")
        .orElseThrow(() -> new IllegalStateException("admin attempt set no session cookie"))
        .split(";", 2)[0]);
    headers.put("X-Auth-Attempt", str(attempt.data(), "attemptToken"));
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Map<String, Object> challenge = send("POST",
        endpoint + "/api/v1/admin/auth/captcha/challenges",
        Map.of("attemptId", attemptId), headers).data();
    jdbc.update("UPDATE admin_captcha SET answer_mac=? WHERE id=?",
        AdminSecretCodec.fixed("qa-key", mac, aes).mac("qa-key", "CAPTCHA", "ABC234"),
        Long.parseLong(str(challenge, "captchaId")));
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Map<String, Object> proof = send("POST",
        endpoint + "/api/v1/admin/auth/captcha/verify",
        Map.of("attemptId", attemptId, "captchaId", str(challenge, "captchaId"),
            "answer", "ABC234"),
        headers).data();
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Map<String, Object> grant = send("POST", endpoint + "/api/v1/admin/auth/login",
        Map.of("attemptId", attemptId, "account", "qa-reviewer", "password", PASSWORD,
            "captchaProof", str(proof, "captchaProof")),
        headers).data();
    return str(grant, "accessToken");
  }

  private record HttpReply(Map<String, Object> data, HttpResponse<String> response) {}

  private static HttpReply send(String method, String url, Object body,
      Map<String, String> headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    HttpResponse<String> response = http.send(builder.method(method,
            body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),
                    StandardCharsets.UTF_8))
        .build(), HttpResponse.BodyHandlers.ofString());
    Map<?, ?> envelope = json.readValue(response.body(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) envelope;
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("admin login step " + method + " " + url
          + " -> " + response.statusCode() + " " + envelope.get("code"));
    }
    return new HttpReply((Map<String, Object>) data.get("data"), response);
  }

  private static DataSource rawSource(String server, String user, String password) {
    org.springframework.jdbc.datasource.DriverManagerDataSource raw =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(server
            + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
            + "&connectTimeout=1000&socketTimeout=5000&createDatabaseIfNotExist=false",
            user, password);
    return raw;
  }

  private static void warmSnowflake(HutoolSnowflakeIdProvider ids) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    for (;;) {
      try {
        ids.nextId();
        break;
      } catch (IllegalStateException warming) {
        if (!warming.getMessage().contains("WARMING") || System.nanoTime() >= deadline) {
          throw warming;
        }
        Thread.sleep(20);
      }
    }
  }

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
    return Objects.requireNonNull(p);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }

  private static String str(Map<String, Object> value, String field) {
    return String.valueOf(value.get(field));
  }

  private static byte[] key(int seed) {
    byte[] value = new byte[32];
    new SecureRandom(String.valueOf(seed).getBytes(StandardCharsets.UTF_8)).nextBytes(value);
    return value;
  }

  private static String base64(int seed) {
    return Base64.getEncoder().encodeToString(key(seed));
  }

  private static String sha(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }
}
