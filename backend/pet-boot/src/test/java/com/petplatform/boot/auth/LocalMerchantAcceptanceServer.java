package com.petplatform.boot.auth;

import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.*;
import com.petplatform.id.core.*;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts.MapValidationPort;
import com.petplatform.thirdparty.biz.infrastructure.oss.*;
import com.petplatform.user.biz.application.WechatSessionProvider;
import com.petplatform.user.biz.infrastructure.provider.WechatMiniApiProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Manual local-LAN server for a real WeChat DevTools login and private upload acceptance.
 * Test scope only. Fresh mode drops its fixture on shutdown; explicit recovery mode adopts and
 * preserves an existing fixture database/Redis namespace after proving the old JVM exited.
 */
public final class LocalMerchantAcceptanceServer {
  private LocalMerchantAcceptanceServer() {}

  public static void main(String[] args) throws Exception {
    if (!"true".equalsIgnoreCase(required("LOCAL_MERCHANT_ACCEPTANCE"))) {
      throw new IllegalStateException("LOCAL_MERCHANT_ACCEPTANCE=true is required");
    }
    String bindAddress = System.getenv().getOrDefault("LOCAL_ACCEPTANCE_BIND_ADDRESS", "192.168.1.44");
    if (!bindAddress.matches("192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}")) {
      throw new IllegalArgumentException("An explicit private-LAN bind address is required");
    }
    int port = Integer.parseInt(System.getenv().getOrDefault("LOCAL_ACCEPTANCE_PORT", "18080"));
    AcceptanceFixture fixture = AcceptanceFixture.open();
    if (!fixture.adopted) PrivateAssetLiveSupport.initializePrivateSchemas(fixture.source);
    ConfigurableApplicationContext context = null;
    try {
      WechatSessionProvider wechat =
          new WechatMiniApiProvider(
              WechatMiniApiProvider.Settings.production(
                  required("AUTH_WECHAT_APP_ID"), required("AUTH_WECHAT_APP_SECRET")));
      AdminSecretCodec adminSecrets =
          AdminSecretCodec.fixed(
              "local-acceptance-v1",
              decodeKey("LOCAL_ADMIN_MAC_KEY_BASE64"),
              decodeKey("LOCAL_ADMIN_AES_KEY_BASE64"));
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  configurable -> {
                    var beans = (GenericApplicationContext) configurable;
                    beans.registerBean("localAcceptanceDataSource", DataSource.class, () -> fixture.source);
                    beans.registerBean("localAcceptanceIds", SnowflakeIdGenerator.class, () -> fixture.ids);
                    beans.registerBean("localAcceptanceWechat", WechatSessionProvider.class, () -> wechat);
                    beans.registerBean("localAcceptanceAdminSecrets", AdminSecretCodec.class, () -> adminSecrets);
                    beans.registerBean(
                        "localAcceptanceMapFailClosed",
                        MapValidationPort.class,
                        () ->
                            (city, address, longitude, latitude) -> {
                              throw new ApiException(
                                  CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                                  "地图校验未配置，商家申请提交保持关闭");
                            });
                  })
              .run(
                  "--server.address=" + bindAddress,
                  "--server.port=" + port,
                  "--spring.flyway.enabled=false",
                  "--spring.main.banner-mode=off",
                  "--spring.jmx.enabled=false",
                  "--pet.auth.c.enabled=true",
                  "--pet.auth.c.redis-host=" + fixture.redisHost,
                  "--pet.auth.c.redis-port=" + fixture.redisPort,
                  "--pet.auth.c.cache-prefix=" + fixture.prefix,
                  "--pet.auth.admin.enabled=true",
                  "--pet.auth.admin.origin=http://127.0.0.1:" + port,
                  "--pet.auth.admin.redis-host=" + fixture.redisHost,
                  "--pet.auth.admin.redis-port=" + fixture.redisPort,
                  "--pet.auth.admin.cache-prefix=" + adminCachePrefix(fixture),
                  "--pet.auth.admin.audit-path=" + auditPath(fixture),
                  "--pet.auth.admin.migration-enabled=false",
                  "--pet.private-assets.enabled=true",
                  "--pet.private-assets.worker-owner=local-merchant-acceptance",
                  "--PRIVATE_ASSET_GRANT_KEY_VERSION=local-acceptance-v1",
                  "--PRIVATE_ASSET_REASON_KEY_VERSION=local-acceptance-v1",
                  "--PRIVATE_ASSET_CLAMAV_HOST=127.0.0.1",
                  "--PRIVATE_ASSET_CLAMAV_PORT=13310",
                  "--PRIVATE_ASSET_CLAMAV_TIMEOUT_MILLIS=10000",
                  "--pet.merchant.application.enabled=true",
                  "--pet.merchant.application.open-cities[0].code=chengdu",
                  "--pet.merchant.application.open-cities[0].name=成都",
                  "--pet.merchant.protection.enabled=true",
                  "--pet.merchant.subject.enabled=true",
                  "--MERCHANT_PROTECTED_KEY_VERSION=local-acceptance-v1",
                  "--MERCHANT_SUBJECT_POLICY_VERSION=CN-ID15-18-USCC18-v1",
                  "--pet.outbox.enabled=true",
                  "--pet.merchant.map.enabled=false");
      writeMetadata(fixture, bindAddress, port);
      ConfigurableApplicationContext running = context;
      Runtime.getRuntime().addShutdownHook(new Thread(() -> close(running, fixture), "local-acceptance-cleanup"));
      System.out.println("LOCAL_ACCEPTANCE result=READY endpoint=http://" + bindAddress + ":" + port);
      System.out.println("LOCAL_ACCEPTANCE map=FAIL_CLOSED wechat=REAL oss=REAL clamav=REAL database=TEMPORARY");
      new CountDownLatch(1).await();
    } catch (Throwable failure) {
      close(context, fixture);
      throw failure;
    }
  }

  private static String auditPath(AcceptanceFixture fixture) {
    return fixture.directory.resolve("admin-audit.ndjson").toAbsolutePath().toString();
  }

  private static String adminCachePrefix(AcceptanceFixture fixture) {
    return "auth001_local_admin_" + fixture.name + ":";
  }

  private static void writeMetadata(AcceptanceFixture fixture, String address, int port)
      throws Exception {
    Path path = Path.of(System.getenv().getOrDefault(
        "LOCAL_ACCEPTANCE_METADATA_PATH", "D:/Temp/mer001-s9-runtime/server-metadata.txt"));
    Files.createDirectories(path.toAbsolutePath().getParent());
    Files.writeString(
        path,
        "endpoint=http://" + address + ":" + port + System.lineSeparator()
            + "database=" + fixture.name + System.lineSeparator()
            + "databaseLifecycle=" + (fixture.adopted ? "PRESERVE" : "DROP_ON_PROCESS_SHUTDOWN")
            + System.lineSeparator()
            + "processId=" + ProcessHandle.current().pid() + System.lineSeparator()
            + "snowflakeNodeId=" + (fixture.adopted ? 20 : 19) + System.lineSeparator()
            + "mapValidation=FAIL_CLOSED" + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static byte[] decodeKey(String name) {
    byte[] value = Base64.getDecoder().decode(required(name));
    if (value.length != 32) throw new IllegalStateException(name + " must contain a 256-bit key");
    return value;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }

  private static void close(ConfigurableApplicationContext context, AcceptanceFixture fixture) {
    try {
      if (context != null) context.close();
    } finally {
      if (!fixture.adopted) cleanupRedisPrefix(fixture.redisHost, fixture.redisPort, adminCachePrefix(fixture));
      fixture.close();
    }
  }

  private static final class AcceptanceFixture implements AutoCloseable {
    private static final int RECOVERY_NODE_ID = 20;
    final String name, prefix, redisHost;
    final int redisPort;
    final Path directory;
    final DataSource source;
    final JdbcTemplate jdbc;
    final SnowflakeIdGenerator ids;
    final boolean adopted;
    private final CAuthHttpTest.HttpFixture fresh;

    private AcceptanceFixture(CAuthHttpTest.HttpFixture fresh) {
      this.fresh = fresh;
      name = fresh.name;
      prefix = fresh.prefix;
      redisHost = fresh.redisHost;
      redisPort = fresh.redisPort;
      directory = fresh.directory;
      source = fresh.source;
      jdbc = fresh.jdbc;
      ids = fresh.ids;
      adopted = false;
    }

    private AcceptanceFixture(
        String name,
        String redisHost,
        int redisPort,
        Path directory,
        DataSource source,
        JdbcTemplate jdbc,
        SnowflakeIdGenerator ids) {
      this.fresh = null;
      this.name = name;
      this.prefix = name + ":";
      this.redisHost = redisHost;
      this.redisPort = redisPort;
      this.directory = directory;
      this.source = source;
      this.jdbc = jdbc;
      this.ids = ids;
      this.adopted = true;
    }

    static AcceptanceFixture open() throws Exception {
      String existing = System.getenv("LOCAL_ACCEPTANCE_EXISTING_DATABASE");
      if (existing == null || existing.isBlank()) return new AcceptanceFixture(new CAuthHttpTest.HttpFixture());
      return adopt(existing);
    }

    private static AcceptanceFixture adopt(String database) throws Exception {
      if (!database.matches("auth001c_http_[0-9a-f]{32}")) {
        throw new IllegalArgumentException("Recovery database must be an exact local fixture name");
      }
      long previousPid = Long.parseLong(required("LOCAL_ACCEPTANCE_PREVIOUS_PID"));
      if (ProcessHandle.of(previousPid).filter(ProcessHandle::isAlive).isPresent()) {
        throw new IllegalStateException("Previous local acceptance JVM is still alive");
      }
      Path metadata = metadataPath();
      String recorded = Files.readString(metadata, StandardCharsets.UTF_8);
      if (!recorded.lines().anyMatch(line -> line.equals("database=" + database))) {
        throw new IllegalStateException("Recovery database does not match local metadata");
      }
      String server = required("AUTH_MYSQL_URL");
      if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
        throw new IllegalArgumentException("Dedicated local MySQL is required");
      }
      DataSource source =
          new DriverManagerDataSource(
              server
                  + database
                  + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
                  + "&connectTimeout=1000&socketTimeout=5000",
              System.getenv().getOrDefault("AUTH_MYSQL_USER", "root"),
              System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", ""));
      JdbcTemplate jdbc = new JdbcTemplate(source);
      Integer databaseExists =
          jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database);
      if (!Integer.valueOf(1).equals(databaseExists)) throw new IllegalStateException("Recovery database is missing");
      Integer nodeExists =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM snowflake_worker_state WHERE node_id=?", Integer.class, RECOVERY_NODE_ID);
      if (!Integer.valueOf(0).equals(nodeExists)) {
        throw new IllegalStateException("Recovery Snowflake node must be previously unused");
      }
      String initialization =
          "local-recovery:node20:previous-pid=" + previousPid + ":database=" + database;
      jdbc.update(
          "INSERT INTO snowflake_worker_state"
              + "(node_id,format_identity,enabled,initialization_ref,created_at,updated_at)"
              + " VALUES(?,?,TRUE,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          RECOVERY_NODE_ID,
          SnowflakeProviderSettings.FORMAT_IDENTITY,
          initialization);
      var ids =
          new HutoolSnowflakeIdProvider(
              new JdbcSnowflakeNodeStore(source),
              new SnowflakeProviderSettings(RECOVERY_NODE_ID),
              old -> {
                if (old.nodeId() != RECOVERY_NODE_ID
                    || old.incarnation() != null
                    || old.fence() != 0
                    || old.reservedThrough() != -1
                    || !initialization.equals(old.initializationRef())) {
                  throw new IllegalStateException("Recovery node initialization proof mismatch");
                }
              });
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      for (;;) {
        try {
          ids.nextId();
          break;
        } catch (IllegalStateException warming) {
          if (!warming.getMessage().contains("WARMING") || System.nanoTime() >= deadline) {
            ids.close();
            throw warming;
          }
          Thread.sleep(20);
        }
      }
      return new AcceptanceFixture(
          database,
          required("AUTH_REDIS_HOST"),
          Integer.parseInt(required("AUTH_REDIS_PORT")),
          metadata.toAbsolutePath().getParent(),
          source,
          jdbc,
          ids);
    }

    @Override
    public void close() {
      if (fresh != null) fresh.close();
      else if (ids instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception ignored) {
          // The database and Redis namespace are intentionally preserved for further recovery.
        }
      }
    }
  }

  private static Path metadataPath() {
    return Path.of(
        System.getenv().getOrDefault(
            "LOCAL_ACCEPTANCE_METADATA_PATH", "D:/Temp/mer001-s9-runtime/server-metadata.txt"));
  }

  private static void cleanupRedisPrefix(String host, int port, String prefix) {
    RedisClient client = RedisClient.create("redis://" + host + ":" + port);
    try (var connection = client.connect()) {
      var commands = connection.sync();
      ScanCursor cursor = ScanCursor.INITIAL;
      do {
        KeyScanCursor<String> batch =
            commands.scan(cursor, ScanArgs.Builder.matches(prefix + "*").limit(100));
        for (String key : batch.getKeys()) commands.del(key);
        cursor = batch;
      } while (!cursor.isFinished());
    } catch (RuntimeException failure) {
      System.err.println("LOCAL_ACCEPTANCE cleanup=ADMIN_REDIS_FAILED");
    } finally {
      client.shutdown();
    }
  }
}
