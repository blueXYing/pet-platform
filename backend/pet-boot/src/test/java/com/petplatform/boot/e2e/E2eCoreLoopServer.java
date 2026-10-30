package com.petplatform.boot.e2e;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.id.core.SimpleSnowflakeIdProvider;
import com.petplatform.id.core.SnowflakeProviderSettings;
import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.aftersale.biz.application.AfterSalePorts;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.thirdparty.api.PetAvatarSigningApi;
import com.petplatform.thirdparty.biz.application.port.PrivateAssetScanner;
import com.petplatform.thirdparty.biz.application.port.PrivateObjectStore;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseAcknowledgement;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.TradeState;
import com.petplatform.refund.biz.application.RefundApplicationPorts;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverAssetPort;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import com.petplatform.user.biz.application.WechatSessionProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * E2E core-loop acceptance server (test scope ONLY): a long-running full-boot local backend for
 * the real WeChat DevTools simulator walkthrough (merchant workbench admission -> C browse ->
 * booking create -> sandbox payment initiation -> merchant confirm -> C order list/detail ->
 * verification credential -> merchant verify -> C refund application). Same QA seams as the
 * in-repo acceptance servers: any real wx.login code maps to the seeded WeChat identity, the
 * payment channel is a deterministic sandbox (a poller feeds a verified SUCCESS notification so
 * initiated payments complete like a sandbox callback), refund/moderation/cover/private-asset
 * ports are permissive fixtures. Every business rule still runs against real Spring + MySQL +
 * Redis over real HTTP.
 *
 * Round 2 (this extension): the switch set stays additive — the previously disabled reschedule
 * and aftersale slices are enabled alongside the review/appeal kernel, the contract-57 merchant
 * refund face and the schedule selection amendment (windowId/kind projection the reschedule
 * page consumes). A post-READY seeding thread drives the REAL loopback HTTP chain (no SQL row
 * fabrication) to leave walkthrough facts behind: a merchant-confirmed PENDING_SERVICE order
 * (reschedule target), a verified COMPLETED unreviewed order (review target), a published
 * review (M appeal target), a PENDING_MERCHANT refund application (M refund target) and a
 * PENDING aftersale case (C/M aftersale target). No existing chain semantics change.
 *
 * Round 3 (2026-10-16 E2E matrix): additive again — the merged new-capability slices the
 * walkthrough matrix exercises turn on here: contract-61 coupon lifecycle (freeze on create /
 * consume on payment / release on payment-timeout close / restore on full refund), the
 * contract-62 encyclopedia public read face (the 62 schema ships its own published seed
 * content), the contract-58/60 points earn kernel plus its HTTP face and the PTS-002 refund
 * clawback consumer. The refund execution chain must run end to end for the coupon-restore and
 * points-clawback consumers to observe a real RefundSucceededEvent.v1, so the refund
 * application worker and the pre-service auto-full-refund ruling switch are on, as is the
 * booking expiry worker (payment-timeout close releases the frozen coupon). The booking seeds
 * gain one ACTIVE coupon template (rule_json four-key amountOff/thresholdAmount/scopeSummary/
 * typeLabel vocabulary) and three AVAILABLE instances for the seeded C user. No existing chain
 * semantics change.
 *
 * Round 4 (2026-10-16 admin web main chain, QA-005): additive again — the operations-console
 * faces the admin walkthrough exercises turn on here: the contract-63 trade read face plus its
 * v0.2 disposal kernels and durable ADMIN_REFUND_* workers, the contract-64 governance read
 * face, the contract-65 ops kernels (encyclopedia/coupon/points/settings), and the A-004
 * ruling-32 aftersale original-route refund execution (worker + funding authority). The 63
 * disposal-audit schema joins the isolated schema load. A post-READY admin-chain seeding
 * thread (run after the round-2 seeder so the store-guard domain stays serial) drives the real
 * loopback HTTP chain to leave walkthrough facts behind: a verified COMPLETED order on a fully
 * past appointment (the A-004 adjudication + trade verification-log target — a COMPLETED order
 * with a future active claim would refuse to serve order facts), a second PENDING_SERVICE
 * order (disposition attempt target) and a third PENDING_SERVICE order (the C pre-service
 * refund-application target), plus a PENDING aftersale case (SERVICE_QUALITY/REFUND) created
 * by the buyer on the completed order. Availability windows gain yesterday so the completed
 * target's appointment can be fully past; staff availability widens to match. No existing
 * chain semantics change.
 */
public final class E2eCoreLoopServer {
  private static final JsonMapper json = JsonMapper.builder().build();
  private static final ZoneId CHANNEL_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter CHANNEL_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  // Seeded coordinates (stable so screenshots/reports can reference them).
  private static final long USER = 710100L;          // buyer AND merchant owner
  private static final long PET = 710200L;
  private static final long MERCHANT = 710301L;
  private static final long STORE = 710302L;
  private static final long STAFF = 710303L;
  private static final long CATEGORY = 710400L;
  private static final long SERVICE = 710401L;       // IN_STORE, 60 minutes
  private static final long COVER_ASSET = 710402L;
  private static final String WECHAT_APP = "e2e-wx-app";
  private static final String WECHAT_OPEN = "e2e-open-c-710100";
  private static final String MERCHANT_NO = "E2E830000012345678";
  private static final String TERM_NO = "T0000001";
  // Round-2 admin operator (bootstrapped as PLATFORM_SUPER_ADMIN scope ALL, SSOT §24: single
  // operator, no extra MFA). Written to server-metadata.txt for the HTTP admin walkthrough.
  private static final String ADMIN_ACCOUNT = "e2e-round2-operator";
  private static final String ADMIN_PASSWORD = "E2E_Round2_Operator_91!";
  private static final String ADMIN_ORIGIN = "https://e2e.example.invalid";

  private E2eCoreLoopServer() {}

  public static void main(String[] args) throws Exception {
    if (!"true".equalsIgnoreCase(System.getenv("E2E_CORE_LOOP_SERVER"))) {
      throw new IllegalStateException("E2E_CORE_LOOP_SERVER=true is required");
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
    int port = Integer.parseInt(System.getenv().getOrDefault("E2E_PORT", "18081"));
    // E2E_BIND (QA-only seam, 2026-10-19 real-device walkthrough): optional bind-address
    // override. The default stays 127.0.0.1 — loopback-only, behavior unchanged for every
    // existing run; setting E2E_BIND=0.0.0.0 exposes the port on the LAN so the WeChat
    // DevTools PHONE simulator can reach this backend (the desktop simulator talks loopback
    // either way). No other boot property changes with it.
    String bindAddress = System.getenv().getOrDefault("E2E_BIND", "127.0.0.1");
    int nodeId = Integer.parseInt(System.getenv().getOrDefault("E2E_SNOWFLAKE_NODE", "23"));
    Path directory = Files.createDirectories(Path.of(System.getenv()
        .getOrDefault("E2E_RUNTIME_DIR", "D:/Temp/e2e-core-loop")));
    String mysqlUser = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
    String mysqlPassword = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");

    JdbcTemplate adminJdbc = new JdbcTemplate(rawSource(server, mysqlUser, mysqlPassword));
    HikariDataSource source = null;
    ConfigurableApplicationContext context = null;
    // Keep the isolated-fixture retry for ordinary boot/schema/seed contention. Snowflake is now
    // a stateless single-instance provider, so it no longer participates in this retry.
    String name = null;
    SnowflakeIdGenerator ids = null;
    JdbcTemplate jdbc = null;
    // Unique namespace token: sibling QA fixtures on this machine use the same
    // auth001c_e2e_core_loop_ prefix shape and their Redis cleanup scans can wipe shared
    // keys; this run uses an exclusive prefix (still inside the auth001c[a-zA-Z0-9_-]* contract).
    String base = "auth001czq" + Long.toHexString(System.nanoTime() % 0xFFFFF);
    // Crash-recovery seam (QA only): set E2E_DATABASE_NAME to REOPEN the database a previous
    // process left behind (force-killed servers skip the DROP hook) instead of creating a fresh
    // one. Schema, bookable facts and the round-2 seeds are skipped on reuse — the surviving
    // facts are the point. Keep E2E_SNOWFLAKE_NODE unique per concurrently running server.
    String reuseDatabase = System.getenv("E2E_DATABASE_NAME");
    for (int attempt = 1; attempt <= 6; attempt++) {
      String candidate = reuseDatabase != null && !reuseDatabase.isBlank() ? reuseDatabase
          : base + "_e2e_core_loop_" + UUID.randomUUID().toString().replace("-", "");
      HikariDataSource candidateSource = null;
      try {
        adminJdbc.execute("CREATE DATABASE IF NOT EXISTS `" + candidate + "` CHARACTER SET utf8mb4");
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(server + candidate
            + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
            + "&connectTimeout=2000&socketTimeout=15000");
        poolConfig.setUsername(mysqlUser);
        poolConfig.setPassword(mysqlPassword);
        poolConfig.setPoolName("e2e-core-loop");
        poolConfig.setMinimumIdle(8);
        poolConfig.setMaximumPoolSize(24);
        poolConfig.setConnectionTimeout(5_000);
        poolConfig.setValidationTimeout(2_000);
        candidateSource = new HikariDataSource(poolConfig);
        JdbcTemplate candidateJdbc = new JdbcTemplate(candidateSource);
        if (reuseDatabase == null || reuseDatabase.isBlank()) {
          loadSchemas(candidateSource, root());
        }
        SnowflakeIdGenerator candidateIds =
            new SimpleSnowflakeIdProvider(new SnowflakeProviderSettings(nodeId));
        if (reuseDatabase == null || reuseDatabase.isBlank()) {
          seedBookableFacts(candidateJdbc, candidateIds);
        }
        name = candidate;
        source = candidateSource;
        jdbc = candidateJdbc;
        ids = candidateIds;
        break;
      } catch (Throwable busy) {
        System.err.println("E2E_CORE_LOOP boot attempt " + attempt + " failed: " + busy);
        if (candidateSource != null) {
          try {
            candidateSource.close();
          } catch (RuntimeException ignored) {
            // best-effort cleanup before the next attempt
          }
        }
        try {
          adminJdbc.execute("DROP DATABASE IF EXISTS `" + candidate + "`");
        } catch (RuntimeException ignored) {
          // best-effort cleanup before the next attempt
        }
        if (attempt == 6) throw busy;
        Thread.sleep(15_000);
      }
    }
    final HikariDataSource pool = source;
    final SnowflakeIdGenerator finalIds = Objects.requireNonNull(ids);
    try {
      // Payment dispatch requires a real parameter-key file (AES-128 key material, Base64).
      byte[] parameterKey = key(41);
      Path parameterKeyFile = directory.resolve("payment-parameter-key.b64");
      Files.writeString(parameterKeyFile, Base64.getEncoder().encodeToString(parameterKey),
          StandardCharsets.US_ASCII);

      byte[] adminMac = key(3), adminAes = key(7);
      AesGcmProtectedValueProvider protector =
          new AesGcmProtectedValueProvider("e2e-only-v1", key(31), key(47));
      String cPrefix = name + ":";
      String adminPrefix = name + "_admin:";
      SandboxPay sandbox = new SandboxPay(pool);
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(ctx -> {
                var beans = (GenericApplicationContext) ctx;
                // Round-4 composition tie-break (QA finding FA-4, test-domain only): with
                // pet.admin.trade.http.enabled AND pet.order.merchant.enabled both on, two
                // identical ReservationRefundReleaseApi beans register (dispositionRefundRelease
                // guards with @ConditionalOnMissingBean but merchantRefundRelease does not, and
                // registration order is not guaranteed) — the context fails to boot on the
                // ambiguity. Both construct the same
                // ReservationRefundReleaseApiImpl(source, ids, guard, refunds), so marking the
                // merchant one primary changes no behavior; production defaults keep the trade
                // face off and never hit the collision.
                beans.registerBean("e2eReleasePrimaryTieBreak",
                    org.springframework.beans.factory.config.BeanFactoryPostProcessor.class,
                    () -> beanFactory -> {
                      if (beanFactory.containsBeanDefinition("merchantRefundRelease")) {
                        beanFactory.getBeanDefinition("merchantRefundRelease").setPrimary(true);
                      }
                    });
                beans.registerBean("e2eDataSource", DataSource.class, () -> pool);
                beans.registerBean("e2eIds", SnowflakeIdGenerator.class, () -> finalIds);
                // Any real wx.login code from the DevTools simulator maps to the seeded identity;
                // the seeded account already carries a verified phone so login completes in one shot.
                beans.registerBean("e2eWechat", WechatSessionProvider.class, () ->
                    new WechatSessionProvider() {
                      @Override public WechatIdentity exchangeIdentity(String code) {
                        if (code == null || code.isBlank() || code.length() > 512) {
                          throw new ProofRejected();
                        }
                        return new WechatIdentity(WECHAT_APP, WECHAT_OPEN, null);
                      }
                      @Override public String exchangePhone(String code) {
                        if (code != null && code.matches("phone:1[0-9]{10}")) {
                          return code.substring(6);
                        }
                        throw new ProofRejected();
                      }
                    });
                beans.registerBean("e2ePaymentBindings", PaymentMerchantBindings.class, () ->
                    (merchantId, storeId) -> {
                      if (!Long.toString(STORE).equals(storeId)
                          || !Long.toString(MERCHANT).equals(merchantId)) {
                        throw new IllegalStateException("E2E fixture store only");
                      }
                      return new PaymentMerchantBindings.Binding(MERCHANT_NO, TERM_NO, WECHAT_APP);
                    });
                beans.registerBean("e2ePaymentChannel", PaymentChannel.class,
                    () -> sandbox.channel());
                beans.registerBean("e2eReceiptVerifier", PaymentReceiptVerifier.class,
                    () -> sandbox.verifier());
                beans.registerBean("e2eRefundChannel", PaymentRefundChannel.class,
                    () -> new PaymentRefundChannel() {
                      @Override public VerifiedResult submit(RefundRequest request) {
                        sandbox.refunds.add(request.refundNo());
                        return new VerifiedResult("SUCCESS", request.refundNo(),
                            "e2e-refund-" + request.refundNo(),
                            request.amount().movePointRight(2).longValueExact(),
                            request.amount().movePointRight(2).longValueExact(),
                            LocalDateTime.now(CHANNEL_ZONE).withNano(0), "e2e".repeat(32));
                      }
                      @Override public VerifiedResult query(RefundRequest request) {
                        return new VerifiedResult("SUCCESS", request.refundNo(),
                            "e2e-refund-" + request.refundNo(),
                            request.amount().movePointRight(2).longValueExact(),
                            request.amount().movePointRight(2).longValueExact(),
                            LocalDateTime.now(CHANNEL_ZONE).withNano(0), "e2e".repeat(32));
                      }
                    });
                beans.registerBean("e2eRefundModeration", RefundApplicationPorts.Moderation.class,
                    () -> value -> new RefundApplicationPorts.Approval(
                        sha(value), "E2E_ONLY_MODERATION", true));
                beans.registerBean("e2eMerchantOrderModeration",
                    com.petplatform.order.biz.application.MerchantOrderPorts.Moderation.class,
                    () -> value -> new com.petplatform.order.biz.application.MerchantOrderPorts.Approval(
                        sha(value), "E2E_ONLY_MODERATION", true));
                // Booking remark review seam: the production composition expects an external
                // remark moderation provider; the sandbox accepts every remark (same seam the
                // in-repo acceptance tests stub for moderation ports).
                beans.registerBean("e2eRemarkPolicy",
                    com.petplatform.order.biz.application.OrderCreationRemarkPolicy.class,
                    () -> remark -> { });
                beans.registerBean("e2ePrivateAssets", PrivateAssetQueryPort.class,
                    () -> (owner, assetIds) -> assetIds.stream()
                        .map(id -> new PrivateAssetQueryPort.PrivateAssetRef(
                            id, owner, sha("e2e-asset-" + id), "image/jpeg", 512, "READY"))
                        .toList());
                beans.registerBean("e2eMap", ApplicationValidationPorts.MapValidationPort.class,
                    () -> (city, address, lng, lat) -> "chengdu".equals(city));
                beans.registerBean("e2eSubjects", SubjectCredentialPort.class,
                    () -> new MainlandSubjectCredentialProvider(protector, "e2e-only-v1", key(63)));
                beans.registerBean("e2eCoverAssets", ServiceCoverAssetPort.class,
                    () -> (owner, assetIds) -> assetIds.stream()
                        .map(id -> new ServiceCoverAssetPort.CoverAssetFact(
                            id, owner, "READY", "image/jpeg", 2048))
                        .toList());
                beans.registerBean("e2eCoverUrls", ServiceCoverUrlPort.class,
                    () -> assetId -> new ServiceCoverUrlPort.CoverUrl(
                        assetId,
                        "https://cover.example.invalid/signed/" + assetId + "?e2e=1",
                        Instant.now().getEpochSecond() + 3600));
                // Round 2: aftersale workflow QA seams (same shape as AfterSaleHttpFixture) —
                // permissive moderation plus an in-memory private object store/scanner so the
                // aftersale evidence pipeline boots without OSS/ClamAV. No evidence is uploaded
                // in this walkthrough, so the store stays empty; it exists only so the real
                // composition starts.
                beans.registerBean("e2eAfterSaleModeration", AfterSalePorts.Moderation.class,
                    () -> value -> new AfterSalePorts.Approval(
                        sha(value), "E2E_ONLY_MODERATION", true));
                var e2eAssetObjects = new PrivateObjectStore() {
                      private final Map<String, PrivateObjectStore.StoredContent> objects =
                          new ConcurrentHashMap<>();
                      @Override public PrivateObjectStore.StoredObject putIfAbsent(
                          String objectKey, byte[] content, String mediaType, String sha256) {
                        var candidate = new PrivateObjectStore.StoredContent(
                            content, mediaType, sha256);
                        var previous = objects.putIfAbsent(objectKey, candidate);
                        var stored = previous == null ? candidate : previous;
                        if (!sha256.equals(stored.sha256())
                            || !Objects.deepEquals(stored.content(), content)) {
                          throw new IllegalStateException("Immutable E2E object differs");
                        }
                        return new PrivateObjectStore.StoredObject(
                            "e2e-only-version", sha256, content.length, mediaType);
                      }
                      @Override public PrivateObjectStore.StoredContent get(
                          String objectKey, String versionRef) {
                        if (!"e2e-only-version".equals(versionRef)) {
                          throw new IllegalStateException("Wrong object version");
                        }
                        return Objects.requireNonNull(objects.get(objectKey));
                      }
                      @Override public java.util.Optional<PrivateObjectStore.StoredObject> head(
                          String objectKey) {
                        var stored = objects.get(objectKey);
                        return stored == null ? java.util.Optional.empty()
                            : java.util.Optional.of(new PrivateObjectStore.StoredObject(
                                "e2e-only-version", stored.sha256(),
                                stored.content().length, stored.mediaType()));
                      }
                    };
                beans.registerBean("e2eAssetObjects", PrivateObjectStore.class, () -> e2eAssetObjects);
                // CCR-C-PET-AVATAR-001 local E2E seam: no OSS in this fixture; return a data URL
                // for the owner's READY PET_AVATAR object so the real upload/save path is testable.
                beans.registerBean("e2ePetAvatarSigner", PetAvatarSigningApi.class,
                    () -> (ownerUserId, assetId) -> {
                      var row = new JdbcTemplate(pool).queryForMap(
                          "SELECT owner_user_id,purpose,status,object_key,object_version_ref,media_type"
                              + " FROM private_asset WHERE id=?", Long.parseLong(assetId));
                      String media = (String) row.get("media_type");
                      if (!"PET_AVATAR".equals(row.get("purpose")) || !"READY".equals(row.get("status"))
                          || ((Number) row.get("owner_user_id")).longValue() != Long.parseLong(ownerUserId)
                          || (!"image/jpeg".equals(media) && !"image/png".equals(media))) {
                        throw new IllegalStateException("E2E pet avatar asset is not owned READY PET_AVATAR");
                      }
                      var stored = e2eAssetObjects.get(
                          (String) row.get("object_key"), (String) row.get("object_version_ref"));
                      return new PetAvatarSigningApi.SignedPetAvatar(assetId,
                          "data:" + stored.mediaType() + ";base64,"
                              + Base64.getEncoder().encodeToString(stored.content()),
                          Instant.now().plusSeconds(3600));
                    });
                beans.registerBean("e2eAssetScanner", PrivateAssetScanner.class,
                    () -> bytes -> new PrivateAssetScanner.ScanResult(
                        true, "E2E_ONLY_SCANNER", "CLEAN"));
                // Integration-surface events with no in-process subscriber in the current V1
                // composition: without a registered consumer the dispatcher takes the
                // no-consumer release lane and re-claims the same oldest event forever (a
                // livelock that starves every later event — registered as a finding). This
                // fixture-only sink lets the dispatcher mark those events PUBLISHED so the
                // walkthrough chains keep flowing; it asserts nothing and writes nothing.
                beans.registerBean("e2eIntegrationEventSink",
                    com.petplatform.event.api.IntegrationEventConsumer.class,
                    () -> new com.petplatform.event.api.IntegrationEventConsumer() {
                      @Override public String consumerName() { return "E2E_INTEGRATION_SINK"; }
                      @Override public Set<String> eventTypes() {
                        // Integration-surface events with no in-process subscriber in the
                        // current V1 composition (PaymentSucceeded / RefundSucceeded /
                        // LatePaymentSucceededAfterTimeout keep their real consumers).
                        return Set.of("OrderPaidEvent.v1", "OrderConfirmedEvent.v1",
                            "OrderRejectedEvent.v1", "OrderRescheduledEvent.v1",
                            "OrderVerifiedEvent.v2", "RefundApplicationCreatedEvent.v1",
                            "RefundApplicationDecidedEvent.v1", "RefundOrderCreatedEvent.v1",
                            "AfterSaleCreatedEvent", "AfterSaleProgressChangedEvent",
                            "AfterSaleInvalidatedEvent");
                      }
                      @Override public void consume(
                          com.petplatform.event.api.DispatchedEvent event) {
                        System.out.println("E2E_EVENT_SINK " + event.eventType()
                            + " aggregate=" + event.aggregateId());
                      }
                    });
              })
              .run(
                  "--server.address=" + bindAddress,
                  "--server.port=" + port,
                  "--spring.flyway.enabled=false",
                  "--spring.main.banner-mode=off",
                  "--spring.jmx.enabled=false",
                  "--pet.auth.c.enabled=true",
                  "--pet.auth.c.redis-host=" + redisHost,
                  "--pet.auth.c.redis-port=" + redisPort,
                  "--pet.auth.c.cache-prefix=" + cPrefix,
                  "--pet.auth.admin.enabled=true",
                  "--pet.auth.admin.migration-enabled=false",
                  "--pet.auth.admin.origin=https://e2e.example.invalid",
                  "--pet.auth.admin.redis-host=" + redisHost,
                  "--pet.auth.admin.redis-port=" + redisPort,
                  "--pet.auth.admin.cache-prefix=" + adminPrefix,
                  "--pet.auth.admin.key-id=e2e-key",
                  "--pet.auth.admin.mac-key-base64="
                      + Base64.getEncoder().encodeToString(adminMac),
                  "--pet.auth.admin.encryption-key-base64="
                      + Base64.getEncoder().encodeToString(adminAes),
                  "--pet.auth.admin.audit-path=" + directory.resolve("admin-audit.bin"),
                  "--pet.merchant.application.enabled=true",
                  "--pet.merchant.application.open-cities[0].code=chengdu",
                  "--pet.merchant.application.open-cities[0].name=成都",
                  "--pet.merchant.application.notifications-enabled=false",
                  "--pet.merchant.protection.enabled=true",
                  "--pet.merchant.subject.enabled=true",
                  "--MERCHANT_PROTECTED_KEY_VERSION=e2e-only-v1",
                  "--MERCHANT_PROTECTED_AES_KEY_BASE64=" + base64(31),
                  "--MERCHANT_PROTECTED_HMAC_KEY_BASE64=" + base64(47),
                  "--MERCHANT_SUBJECT_POLICY_VERSION=CN-ID15-18-USCC18-v1",
                  "--MERCHANT_SUBJECT_HMAC_KEY_BASE64=" + base64(63),
                  "--pet.outbox.enabled=true",
                  "--pet.outbox.owner=e2e-outbox-" + UUID.randomUUID(),
                  "--pet.service.query.enabled=true",
                  "--pet.service.command.enabled=true",
                  "--pet.service.review.notifications-enabled=false",
                  "--pet.store.query.enabled=true",
                  "--pet.schedule.query.enabled=true",
                  "--pet.schedule.protection.enabled=true",
                  "--pet.order.creation.enabled=true",
                  "--pet.order.expiry.enabled=true",
                  // Round 3: the expiry worker runs so a payment-timeout close releases the
                  // frozen coupon (contract-61 release rides the guarded close).
                  "--pet.order.expiry.worker.enabled=true",
                  "--pet.payment.foundation.enabled=true",
                  "--pet.payment.lakala.channel-time-zone=Asia/Shanghai",
                  "--pet.payment.dispatch.enabled=true",
                  "--pet.payment.dispatch.parameterKeyPath=" + parameterKeyFile,
                  "--pet.payment.dispatch.outOrgCode=E2E-ORG-001",
                  "--pet.payment.dispatch.subject=E2E宠物服务订单",
                  "--pet.payment.dispatch.requestIp=127.0.0.1",
                  "--pet.payment.dispatch.notify-url=https://e2e.example.invalid/notify",
                  "--pet.order.auto-confirm.enabled=true",
                  "--pet.order.auto-confirm.worker.enabled=true",
                  "--pet.order.merchant.enabled=true",
                  "--pet.order.merchant.http.enabled=true",
                  // 45号 switch validation: the merchant-order HTTP face requires its worker
                  // and the auto-confirm worker to be on (complete dependency composition).
                  "--pet.order.merchant.worker.enabled=true",
                  "--pet.order.merchant.protection-key=" + base64(17),
                  "--pet.order.reschedule.enabled=true",
                  "--pet.order.reschedule.http.enabled=true",
                  // 39号 §1 selection amendment: the availability items carry windowId/kind so
                  // the C reschedule page can build the 46号 request without guessing identity.
                  "--pet.schedule.selection.enabled=true",
                  "--pet.verification.credential.enabled=true",
                  "--pet.verification.credential.http.enabled=true",
                  "--pet.verification.credential.key-id=e2e-key",
                  "--pet.verification.credential.encryption-key=" + base64(19),
                  "--pet.verification.credential.lookup-key=" + base64(23),
                  "--pet.verification.completion.enabled=true",
                  "--pet.verification.completion.http.enabled=true",
                  "--pet.refund.application.enabled=true",
                  "--pet.refund.application.http.enabled=true",
                  // Round 3: the application worker runs so an approved application becomes a
                  // real refund_order executed over the sandbox refund channel, publishing
                  // RefundSucceededEvent.v1 for the coupon restore + points clawback consumers.
                  "--pet.refund.application.worker.enabled=true",
                  "--pet.refund.application.protection-key=" + base64(13),
                  "--pet.refund.application.reason-codes=SCHEDULE_CONFLICT,PLAN_CHANGED,SERVICE_ISSUE",
                  // Composition constraint found during bring-up: with pet.outbox enabled,
                  // LateRefundService registers an empty subscription unless refund.late is on,
                  // and OutboxDispatcher fails the whole boot (empty eventTypes is an error).
                  "--pet.refund.late.enabled=true",
                  // Round 2 additions (all default-off faces the first round left disabled):
                  // contract-57 merchant refund list/detail/approve/reject HTTP face.
                  "--pet.refund.merchant.http.enabled=true",
                  // REV-001 review kernel + C review face, REV-002 appeal faces (M + admin).
                  "--pet.review.enabled=true",
                  "--pet.review.http.enabled=true",
                  "--pet.review.appeal.enabled=true",
                  "--pet.review.appeal.http.enabled=true",
                  // Aftersale workflow + HTTP faces (needs private-assets composition below).
                  "--pet.private-assets.enabled=true",
                  "--PRIVATE_ASSET_GRANT_KEY_VERSION=e2e-grant-v1",
                  "--PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64=" + base64(53),
                  "--PRIVATE_ASSET_REASON_KEY_VERSION=e2e-reason-v1",
                  "--PRIVATE_ASSET_REASON_AES_KEY_BASE64=" + base64(59),
                  "--pet.aftersale.enabled=true",
                  "--pet.aftersale.http.enabled=true",
                  // Round 4: the ruling-32 original-route funding authority turns the switch
                  // on (the false default below was the round-2 fixture setting; duplicate
                  // command-line keys are joined and fail Boolean conversion, so the old line
                  // is replaced, not repeated).
                  "--pet.aftersale.protection-key=" + base64(29),
                  "--pet.aftersale.type-codes=SERVICE_QUALITY,OTHER",
                  "--pet.aftersale.demand-codes=REFUND,RESERVICE,OTHER",
                  "--pet.aftersale.type-labels[SERVICE_QUALITY]=服务质量问题",
                  "--pet.aftersale.type-labels[OTHER]=其他问题",
                  "--pet.aftersale.demand-labels[REFUND]=退款",
                  "--pet.aftersale.demand-labels[RESERVICE]=重新服务",
                  "--pet.aftersale.demand-labels[OTHER]=其他诉求",
                  // Round 3 additions (additive new-capability faces, all default-off):
                  // contract-61 coupon lifecycle kernel — freeze joins the 38 create
                  // transaction, consume rides the 41 payment result, release rides the 39
                  // close, the restore consumer is dispatched by the outbox.
                  "--pet.coupon.lifecycle.enabled=true",
                  // contract-62 encyclopedia anonymous read face (categories/breeds/detail).
                  "--pet.encyclopedia.query.enabled=true",
                  // contract-58/60 points earn kernel + C HTTP face + PTS-002 refund clawback.
                  "--pet.points.task.enabled=true",
                  "--pet.points.task.http.enabled=true",
                  "--pet.points.refund-clawback.enabled=true",
          // The 2026-10-08 ruling turns pre-service refunds into automatic full
          // approvals, so the C refund-apply walks the real execution chain.
          "--pet.refund.pre-service-auto-refund.enabled=true",
          // Round 4 additions (admin web main chain, additive; test-domain only — the
          // production defaults stay unchanged):
          // contract-63 trade read face + v0.2 disposal kernels and their durable
          // ADMIN_REFUND_SUBMIT/ADMIN_REFUND_CHANNEL_QUERY workers (worker requires the
          // payment foundation, which is already on).
          "--pet.admin.trade.http.enabled=true",
          "--pet.admin.trade.worker.enabled=true",
          // contract-64 governance read face (merchants/schedule/staff).
          "--pet.admin.governance.http.enabled=true",
          // contract-65 ops face CANNOT be enabled in this fixture (QA finding, no production
          // change allowed here): with pet.admin.ops.http.enabled=true the context fails to
          // boot — AdminOpsController's AdminEncyclopediaCommandApi constructor parameter
          // matches TWO beans (AdminEncyclopediaApiImpl implements both the query and the
          // command face, and AdminOpsConfiguration exposes it twice) with no @Primary and no
          // retained parameter names. The switch stays off; the ops routes still answer the
          // walkthrough with the security chain's deny (403), which is the documented finding.
          // "--pet.admin.ops.http.enabled=true",
          // A-004 ruling-32 aftersale original-route refund execution: the funding authority
          // rides the succeeded payment's WeChat route; the worker family executes the
          // AFTERSALE_REFUND_SUBMIT/CHANNEL_QUERY tasks and supplement timeouts.
          "--pet.aftersale.refund.enabled=true",
          "--pet.aftersale.worker.enabled=true");
      String endpoint = "http://127.0.0.1:" + port;
      // Round-2 admin operator: the real bootstrap lane (26号 maintenance-mode first account,
      // PLATFORM_SUPER_ADMIN + scope ALL per SSOT §24 single-operator adjudication). Skipped on
      // database reuse — the account already exists from the first process.
      boolean reused = reuseDatabase != null && !reuseDatabase.isBlank();
      if (!reused) {
        context.getBean(AdminAuthService.class).bootstrap(ADMIN_ACCOUNT, "E2E二轮验收运营",
            ADMIN_PASSWORD.toCharArray(), "E2E round-2 simulator walkthrough");
      }
      PaymentNotificationService notifications =
          context.getBean(PaymentNotificationService.class);
      Thread payer = new Thread(() -> sandbox.completePaymentsForever(notifications), "e2e-sandbox-pay");
      payer.setDaemon(true);
      payer.start();
      Path metadata = directory.resolve("server-metadata.txt");
      Files.writeString(metadata,
          "endpoint=" + endpoint + System.lineSeparator()
              + "database=" + name + System.lineSeparator()
              + "databaseLifecycle=DROP_ON_PROCESS_SHUTDOWN" + System.lineSeparator()
              + "processId=" + ProcessHandle.current().pid() + System.lineSeparator()
              + "snowflakeNodeId=" + nodeId + System.lineSeparator()
              + "userId=" + USER + System.lineSeparator()
              + "petId=" + PET + System.lineSeparator()
              + "merchantId=" + MERCHANT + System.lineSeparator()
              + "storeId=" + STORE + System.lineSeparator()
              + "serviceId=" + SERVICE + System.lineSeparator()
              + "staffId=" + STAFF + System.lineSeparator()
              + "wechatApp=" + WECHAT_APP + System.lineSeparator()
              + "adminAccount=" + ADMIN_ACCOUNT + System.lineSeparator()
              + "adminPassword=" + ADMIN_PASSWORD + System.lineSeparator()
              + "adminOrigin=" + ADMIN_ORIGIN + System.lineSeparator(),
          StandardCharsets.UTF_8);
      System.out.println("E2E_CORE_LOOP result=READY endpoint=" + endpoint
          + " bind=" + bindAddress + " database=" + name + " metadata=" + metadata);
      // Round-2 walkthrough facts: driven through the real loopback HTTP chain (the same
      // requests the pages issue), never raw SQL. Failures only log — the server itself stays
      // up and the manual core-loop regression remains fully walkable. Skipped on reuse.
      if (!reused) {
        Thread seeder = new Thread(() -> {
          seedRound2Facts(endpoint, metadata);
          seedAdminChainFacts(endpoint, metadata);
        }, "e2e-round2-seeds");
        seeder.setDaemon(true);
        seeder.start();
      }
      ConfigurableApplicationContext running = context;
      final String database = name;
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          running.close();
        } finally {
          try {
            adminJdbc.execute("DROP DATABASE IF EXISTS `" + database + "`");
          } catch (RuntimeException dropFailure) {
            System.err.println("E2E_CORE_LOOP cleanup=DROP_FAILED " + dropFailure.getMessage());
          }
        }
      }, "e2e-core-loop-cleanup"));
      new CountDownLatch(1).await();
    } catch (Throwable failure) {
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

  // ------------------------------------------------------------------ sandbox payment

  /**
   * Deterministic sandbox: the channel accepts preorders, and a poller feeds the notification
   * service a verified SUCCESS receipt for every PARAMETERS_READY paying order, exactly like a
   * sandbox payment callback would. All kernel rules (amounts, deadlines, idempotent receipts,
   * outbox PaymentSucceededEvent) still run for real.
   */
  private static final class SandboxPay {
    private final JdbcTemplate jdbc;
    final List<String> refunds = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> completed = ConcurrentHashMap.newKeySet();

    SandboxPay(DataSource source) {
      this.jdbc = new JdbcTemplate(source);
    }

    PaymentChannel channel() {
      return new PaymentChannel() {
        @Override public VerifiedPreorder submitPreorder(PreorderInput input, RequestNonce nonce) {
          PreorderResult result = new PreorderResult(input.merchantNo(), input.outTradeNo(),
              "e2e-trade-" + input.outTradeNo(), input.subAppId(), "e2e-prepay-"
              + input.outTradeNo(), "E2E" + "0".repeat(60), nonce.timestampSeconds(),
              nonce.nonce(), "prepay_id=e2e-prepay-" + input.outTradeNo(), "RSA");
          return new VerifiedPreorder(result, sha("e2e-preorder-" + input.outTradeNo()));
        }
        @Override public VerifiedQuery lookup(QueryInput input, ExpectedPayment expected,
            RequestNonce nonce) {
          return new VerifiedQuery(new QueryResult(expected.merchantNo(), expected.outTradeNo(),
              "e2e-trade-" + expected.outTradeNo(), TradeState.UNKNOWN,
              expected.expectedTotalCents(), null, null, null),
              sha("e2e-query-" + expected.outTradeNo()));
        }
        @Override public VerifiedClose requestClose(CloseInput input, RequestNonce nonce) {
          return new VerifiedClose(new CloseAcknowledgement(input.originOutTradeNo(),
              "e2e-trade-" + input.originOutTradeNo(),
              LocalDateTime.now(CHANNEL_ZONE).withNano(0)),
              sha("e2e-close-" + input.originOutTradeNo()));
        }
      };
    }

    PaymentReceiptVerifier verifier() {
      return (headers, body, expected) -> {
        try {
          Map<?, ?> raw = json.readValue(new String(body, StandardCharsets.UTF_8), Map.class);
          String tradeTime = String.valueOf(raw.get("trade_time"));
          String channelTradeNo = String.valueOf(raw.get("channel_trade_no"));
          LocalDateTime time = LocalDateTime.parse(tradeTime, CHANNEL_TIME);
          long cents = expected.expectedAmount().movePointRight(2).longValueExact();
          return new PaymentReceiptVerifier.VerifiedNotice(expected.merchantNo(),
              expected.paymentNo(), channelTradeNo, "SUCCESS",
              java.math.BigDecimal.valueOf(cents, 2), java.math.BigDecimal.valueOf(cents, 2),
              time, "WECHAT");
        } catch (RuntimeException failure) {
          throw new IllegalStateException("E2E sandbox notification malformed", failure);
        }
      };
    }

    void completePaymentsForever(PaymentNotificationService notifications) {
      for (;;) {
        try {
          List<Map<String, Object>> rows = jdbc.queryForList(
              "SELECT p.id, p.payment_no, p.merchant_no, p.amount FROM payment_order p "
              + "JOIN payment_dispatch d ON d.payment_id = p.id "
              + "WHERE p.status IN ('INIT', 'PAYING') AND d.state = 'PARAMETERS_READY' "
              + "AND d.parameter_valid_until > UTC_TIMESTAMP(3)");
          for (Map<String, Object> row : rows) {
            String paymentId = String.valueOf(row.get("id"));
            if (!completed.add(paymentId)) continue;
            try {
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("out_trade_no", String.valueOf(row.get("payment_no")));
              body.put("trade_status", "SUCCESS");
              body.put("total_amount",
                  ((java.math.BigDecimal) row.get("amount")).movePointRight(2).longValueExact());
              body.put("payer_amount",
                  ((java.math.BigDecimal) row.get("amount")).movePointRight(2).longValueExact());
              body.put("trade_time",
                  LocalDateTime.now(CHANNEL_ZONE).minusSeconds(2).withNano(0).format(CHANNEL_TIME));
              body.put("channel_trade_no", "e2e-trade-" + row.get("payment_no"));
              body.put("merchant_no", row.get("merchant_no"));
              byte[] raw = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
              var receipt = notifications.receive(Map.of("Authorization", "E2E-SANDBOX"), raw);
              System.out.println("E2E_SANDBOX_PAY paymentId=" + paymentId
                  + " paid=" + receipt.paid() + " replayed=" + receipt.replayed());
            } catch (RuntimeException failure) {
              completed.remove(paymentId); // retry on the next tick
              System.err.println("E2E_SANDBOX_PAY paymentId=" + paymentId
                  + " deferred: " + failure.getMessage());
            }
          }
        } catch (RuntimeException failure) {
          System.err.println("E2E_SANDBOX_PAY poll failed: " + failure.getMessage());
        }
        try {
          Thread.sleep(2000);
        } catch (InterruptedException stop) {
          return;
        }
      }
    }
  }

  // ------------------------------------------------------------------ round-2 walkthrough facts

  /**
   * Drives the real loopback HTTP chain to leave the round-2 walkthrough facts behind (same
   * requests the pages issue; no SQL fabrication): login -> book -> sandbox-paid -> merchant
   * confirm -> (verify) and then the review / refund-application / aftersale submissions on the
   * verified orders. All appointment times are RELATIVE (tomorrow, business zone Asia/Shanghai)
   * — no hardcoded calendar dates anywhere in this fixture.
   */
  private static void seedRound2Facts(String endpoint, Path metadata) {
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        seedRound2FactsOnce(endpoint, metadata, attempt);
        return;
      } catch (Throwable failure) {
        System.err.println("E2E_ROUND2_SEEDS attempt " + attempt + " failed: " + failure);
        if (attempt == 3) System.err.println("E2E_ROUND2_SEEDS result=FAILED " + failure);
        else try {
          Thread.sleep(20_000);
        } catch (InterruptedException stop) {
          return;
        }
      }
    }
  }

  private static void seedRound2FactsOnce(String endpoint, Path metadata, int round)
      throws Exception {
    // Raw-socket transport (hand-written HTTP/1.1, one connection per request): the JDK
    // HttpClient stack behaves differently inside the server JVM on this QA machine (its
    // requests fall out of the /api/v1/c/** security matcher into the catch-all deny with an
    // empty 403), while byte-identical requests over a plain loopback socket are served
    // normally. The seeds only talk loopback to this process, so the minimal client is exact
    // and keeps the walkthrough facts flowing through the real HTTP faces.
    awaitLive(endpoint);
    String token = login(endpoint);
    System.out.println("E2E_ROUND2_SEEDS step=login round=" + round + " ok");
      // Tomorrow 10:00-19:00 Beijing (capacity 6 per window). One seed order only (see the
      // comment at the seed step). A settle pause after the order lets the outbox event chain
      // (OrderPaid/OrderConfirmed consumers, the late-payment lane, the store guard) drain
      // before the simulator walkthrough books the regression order in the same store-guard
      // domain.
      java.time.LocalDate beijingTomorrow = java.time.LocalDate.now(CHANNEL_ZONE).plusDays(1);
      DateTimeFormatter slotTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
      java.util.function.IntFunction<String> slot = index -> beijingTomorrow
          .atTime(10 + (5 * (round - 1) + index) % 10, 0)
          .atZone(CHANNEL_ZONE).format(slotTime);
      // 1) The ONLY seed order: paid + merchant-confirmed, still PENDING_SERVICE — the
      //    reschedule walkthrough target. Everything else is created through the pages: the
      //    kernel denies any further booking for this user while a COMPLETED order still
      //    holds a future active claim ("completed order has future active claim" — a served
      //    slot stays consumed), so a pre-verified seed would block the core-loop regression
      //    booking entirely. The regression's own order (verified in the simulator) becomes
      //    the review/appeal/refund/aftersale target.
      String rescheduleOrder = confirmedOrder(endpoint, token, slot.apply(0));
      settle();
      System.out.println("E2E_ROUND2_SEEDS step=rescheduleOrder id=" + rescheduleOrder);
      String extra = "rescheduleOrderId=" + rescheduleOrder + System.lineSeparator();
      Files.writeString(metadata, extra, StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.APPEND);
      System.out.println("E2E_ROUND2_SEEDS result=READY rescheduleOrder=" + rescheduleOrder);
  }

  /** Drain pause between seeded orders (see the comment at the first seed step). */
  private static void settle() throws InterruptedException {
    Thread.sleep(30_000);
  }

  // ------------------------------------------------------------------ round-4 admin-chain facts

  /**
   * Round-4 admin walkthrough facts through the same real loopback HTTP chain (same transport
   * and helpers as the round-2 seeder; runs AFTER it so the store-guard domain stays serial):
   * a past-appointment order booked, sandbox-paid, merchant-confirmed, credential-issued and
   * merchant-verified to COMPLETED (the A-004 adjudication + trade verification-log target),
   * a second PENDING_SERVICE order (the disposition attempt target) and a third PENDING_SERVICE
   * order (the C pre-service refund-application target), then a PENDING aftersale case
   * (SERVICE_QUALITY/REFUND, requested 60.00) created by the buyer on the completed order.
   * Every id lands in server-metadata.txt for the probe driver. Failures only log — the server
   * stays up and the walkthrough remains walkable. Skipped on reuse.
   */
  private static void seedAdminChainFacts(String endpoint, Path metadata) {
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        seedAdminChainFactsOnce(endpoint, metadata, attempt);
        return;
      } catch (Throwable failure) {
        System.err.println("E2E_ADMIN_SEEDS attempt " + attempt + " failed: " + failure);
        if (attempt == 3) System.err.println("E2E_ADMIN_SEEDS result=FAILED " + failure);
        else try {
          Thread.sleep(20_000);
        } catch (InterruptedException stop) {
          return;
        }
      }
    }
  }

  private static void seedAdminChainFactsOnce(String endpoint, Path metadata, int attempt)
      throws Exception {
    awaitLive(endpoint);
    String token = login(endpoint);
    System.out.println("E2E_ADMIN_SEEDS step=login attempt=" + attempt);
    // The A-004 target: a fully past appointment (yesterday evening Beijing) so the COMPLETED
    // order keeps no future active claim — OrderProtectionFactsApiImpl refuses to serve facts
    // for one, which would block the aftersale kernel with a 503. Slots shift with the attempt
    // index: a failed earlier attempt leaves CONFIRMED claims on its slots and the capacity
    // guard refuses the same user's overlapping rebooking (409 SCHEDULE_CAPACITY_EXCEEDED).
    DateTimeFormatter slotTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    String pastSlot = java.time.LocalDate.now(CHANNEL_ZONE).minusDays(1).atTime(9 + attempt, 0)
        .atZone(CHANNEL_ZONE).format(slotTime);
    String completed = verifiedOrder(endpoint, token, pastSlot);
    System.out.println("E2E_ADMIN_SEEDS step=completedOrder id=" + completed);
    java.time.LocalDate beijingTomorrow = java.time.LocalDate.now(CHANNEL_ZONE).plusDays(1);
    java.util.function.IntFunction<String> futureSlot = hour -> beijingTomorrow.atTime(hour, 0)
        .atZone(CHANNEL_ZONE).format(slotTime);
    String disposition = confirmedOrder(endpoint, token, futureSlot.apply(9 + 2 * attempt));
    System.out.println("E2E_ADMIN_SEEDS step=dispositionTarget id=" + disposition);
    String refundTarget = confirmedOrder(endpoint, token, futureSlot.apply(10 + 2 * attempt));
    System.out.println("E2E_ADMIN_SEEDS step=refundTarget id=" + refundTarget);
    // Order ids land in the metadata BEFORE the case creation so a partial failure still
    // leaves the walkthrough targets behind.
    Files.writeString(metadata,
        "adminChainCompletedOrderId=" + completed + System.lineSeparator()
            + "adminChainDispositionOrderId=" + disposition + System.lineSeparator()
            + "adminChainRefundTargetOrderId=" + refundTarget + System.lineSeparator(),
        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    Map<String, Object> aftersale = post(endpoint, token, "/c/orders/" + completed + "/aftersales",
        Map.of("typeCode", "SERVICE_QUALITY", "demandCode", "REFUND",
            "description", "E2E运营链种子：服务完成后发起的服务质量退款诉求",
            "requestedAmount", "60.00", "evidenceAssetIds", List.of()), 201);
    String caseId = String.valueOf(aftersale.get("afterSaleId"));
    System.out.println("E2E_ADMIN_SEEDS step=aftersaleCase id=" + caseId);
    Files.writeString(metadata, "adminChainAfterSaleId=" + caseId + System.lineSeparator(),
        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    System.out.println("E2E_ADMIN_SEEDS result=READY completed=" + completed
        + " disposition=" + disposition + " refundTarget=" + refundTarget
        + " aftersale=" + caseId);
  }

  /**
   * Books the 60-minute IN_STORE appointment, lets the sandbox callback pay it, merchant-confirms,
   * issues the C verification credential (INITIAL, version 0) and completes the merchant verify
   * with the issued code (the buyer doubles as the merchant owner in this fixture). The code
   * expires five minutes after issuance, so the verify runs immediately.
   */
  private static String verifiedOrder(String endpoint, String token, String appointmentStart)
      throws Exception {
    String order = confirmedOrder(endpoint, token, appointmentStart);
    Map<String, Object> issued = post(endpoint, token,
        "/c/orders/" + order + "/verification-code",
        Map.of("expectedCredentialVersion", "0", "refreshKind", "INITIAL"), 200);
    Map<String, Object> verified = post(endpoint, token,
        "/merchant/orders/" + order + "/verification",
        Map.of("verificationCode", issued.get("code")), 200);
    if (!"VERIFIED".equals(verified.get("resultCode"))) {
      throw new IllegalStateException("verification failed for order " + order + ": " + verified);
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    for (;;) {
      String stage = String.valueOf(get(endpoint, token, "/c/orders/" + order).get("orderStage"));
      if (stage.contains("COMPLETED")) return order;
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("order " + order + " never completed (stage=" + stage + ")");
      }
      Thread.sleep(2_000);
    }
  }

  /** Liveness probe: the real C auth face answers a body-less attempts call with 400. */
  private static void awaitLive(String endpoint) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    for (;;) {
      try {
        int status = (int) roundtrip(endpoint, "POST", "/c/auth/attempts", "",
            Map.of("Content-Type", "application/json"))[0];
        if (status == 400) return;
      } catch (RuntimeException unavailable) {
        // retry until the deadline
      }
      if (System.nanoTime() >= deadline) throw new IllegalStateException("backend never became live");
      Thread.sleep(2_000);
    }
  }

  /** One-shot C login (seeded identity already carries a verified phone, no phoneCode round). */
  private static String login(String endpoint) throws Exception {
    Map<String, Object> attempt = post(endpoint, null,
        "/c/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"), 201);
    Map<String, Object> session = send(endpoint, "POST", "/c/auth/wechat-login",
        Map.of("attemptId", attempt.get("attemptId"), "wechatCode", "e2e-round2-real-login-code"),
        Map.of("Content-Type", "application/json",
            "X-Request-Id", UUID.randomUUID().toString(),
            "X-Auth-Attempt", String.valueOf(attempt.get("attemptToken"))), 200);
    return String.valueOf(session.get("accessToken"));
  }

  /** Books tomorrow's 60-minute IN_STORE slot and lets the sandbox callback pay it. */
  private static String paidOrder(String endpoint, String token, String appointmentStart)
      throws Exception {
    String order = String.valueOf(post(endpoint, token, "/c/orders",
        Map.of("storeId", Long.toString(STORE), "serviceId", Long.toString(SERVICE),
            "petId", Long.toString(PET), "fulfillmentType", "IN_STORE",
            "appointmentStart", appointmentStart, "appointmentEnd", plusHour(appointmentStart)),
        201).get("orderId"));
    post(endpoint, token, "/c/orders/" + order + "/payments",
        Map.of("channel", "WECHAT_MINI_PROGRAM"), 200);
    // 180s: the shared QA MySQL can stall the Snowflake lane for tens of seconds (the
    // recovering wrapper then reopens a new node), and the outbox-driven auto-confirm lags
    // behind the sandbox payment by that margin. The deadline must outlive it.
    long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
    for (;;) {
      Map<String, Object> detail = get(endpoint, token, "/c/orders/" + order);
      String stage = String.valueOf(detail.get("orderStage"));
      if (stage.contains("PENDING_CONFIRM") || stage.contains("PENDING_SERVICE")
          || stage.contains("COMPLETED")) return order;
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("sandbox payment never completed for order " + order
            + " (stage=" + stage + ")");
      }
      Thread.sleep(2_000);
    }
  }

  private static String confirmedOrder(String endpoint, String token, String appointmentStart)
      throws Exception {
    String order = paidOrder(endpoint, token, appointmentStart);
    post(endpoint, token, "/merchant/orders/" + order + "/confirm",
        Map.of("expectedConfirmRound", 0), 200);
    return order;
  }

  private static String plusHour(String isoOffset) {
    return java.time.OffsetDateTime.parse(isoOffset).plusMinutes(60)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"));
  }

  private static Map<String, Object> post(String endpoint, String token, String path,
      Map<String, Object> body, int expected) throws Exception {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    if (token != null) headers.put("Authorization", "Bearer " + token);
    return send(endpoint, "POST", path, body, headers, expected);
  }

  private static Map<String, Object> get(String endpoint, String token, String path)
      throws Exception {
    return send(endpoint, "GET", path, null,
        token == null ? Map.of() : Map.of("Authorization", "Bearer " + token), 200);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> send(String endpoint, String method, String path,
      Map<String, Object> body, Map<String, String> headers, int expected) throws Exception {
    String payload = body == null ? "" : json.writeValueAsString(body);
    Object[] response = roundtrip(endpoint, method, path, payload, headers);
    int status = (int) response[0];
    String raw = String.valueOf(response[1]);
    Map<String, Object> envelope;
    try {
      envelope = raw.isBlank() ? Map.of() : json.readValue(raw, Map.class);
    } catch (RuntimeException malformed) {
      throw new IllegalStateException(method + " " + path + " -> " + status
          + " body-not-json (" + raw.length() + " bytes)");
    }
    if (status != expected) {
      throw new IllegalStateException(method + " " + path + " -> " + status
          + " (expected " + expected + "): " + raw);
    }
    Map<String, Object> data = (Map<String, Object>) envelope.get("data");
    if (data == null) {
      throw new IllegalStateException(method + " " + path + " -> " + status
          + " without data envelope: " + raw);
    }
    return data;
  }

  /**
   * Minimal HTTP/1.1 client over a fresh loopback socket per request (Connection: close):
   * writes the request, reads to EOF, decodes chunked transfer if present and returns
   * {status, body}. Loopback-only by construction; no keep-alive state, no proxy stack.
   */
  private static Object[] roundtrip(String endpoint, String method, String path, String payload,
      Map<String, String> headers) throws Exception {
    java.net.URI base = java.net.URI.create(endpoint);
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    StringBuilder request = new StringBuilder(method + " /api/v1" + path + " HTTP/1.1\r\n"
        + "Host: " + base.getHost() + ":" + base.getPort() + "\r\n");
    for (var header : headers.entrySet()) {
      request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
    }
    request.append("Content-Length: ").append(body.length).append("\r\n")
        .append("Connection: close\r\n\r\n");
    try (java.net.Socket socket = new java.net.Socket()) {
      socket.connect(new java.net.InetSocketAddress(base.getHost(), base.getPort()), 5_000);
      socket.setSoTimeout(30_000);
      java.io.OutputStream out = socket.getOutputStream();
      out.write(request.toString().getBytes(StandardCharsets.UTF_8));
      out.write(body);
      out.flush();
      ByteArrayOutputStream received = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = socket.getInputStream().read(buffer)) >= 0) received.write(buffer, 0, read);
      String response = received.toString(StandardCharsets.ISO_8859_1);
      int split = response.indexOf("\r\n\r\n");
      if (split < 0) throw new IllegalStateException("malformed HTTP response head");
      int status = Integer.parseInt(response.substring(9, 12).trim());
      String head = response.substring(0, split);
      String rest = response.substring(split + 4);
      boolean chunked = false;
      for (String line : head.split("\r\n")) {
        int colon = line.indexOf(':');
        if (colon > 0 && "transfer-encoding".equalsIgnoreCase(line.substring(0, colon).trim())
            && line.substring(colon + 1).toLowerCase().contains("chunked")) {
          chunked = true;
        }
      }
      if (chunked) rest = dechunk(rest);
      return new Object[] {status, new String(rest.getBytes(StandardCharsets.ISO_8859_1),
          StandardCharsets.UTF_8)};
    }
  }

  private static String dechunk(String chunked) {
    StringBuilder out = new StringBuilder();
    int position = 0;
    while (position < chunked.length()) {
      int lineEnd = chunked.indexOf("\r\n", position);
      if (lineEnd < 0) break;
      int size = Integer.parseInt(chunked.substring(position, lineEnd).split(";")[0].trim(), 16);
      if (size == 0) break;
      out.append(chunked, lineEnd + 2, Math.min(lineEnd + 2 + size, chunked.length()));
      position = lineEnd + 2 + size + 2;
    }
    return out.toString();
  }

  // ------------------------------------------------------------------ seed

  /**
   * SQL projection of an approved + signed merchant with one ACTIVE store, one reviewed ACTIVE
   * IN_STORE service (60 minutes, DOG), minute-level GENERAL windows for today and tomorrow
   * (Beijing business zone) and one qualified staff; plus the C buyer account (phone verified so
   * the DevTools wx.login completes in one shot) and a pet archive. Same shape as the in-repo
   * BookingCreate/M002 fixtures.
   */
  private static void seedBookableFacts(JdbcTemplate jdbc, SnowflakeIdGenerator ids) {
    jdbc.update("INSERT INTO user_account(id,phone,nickname,status,created_at,updated_at)"
        + " VALUES(?,'13800000100','E2E体验用户','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", USER);
    jdbc.update("INSERT INTO user_auth_identity(id,user_id,identity_type,app_id,open_id,"
        + "created_at,updated_at) VALUES(?,?,'WECHAT_MINI',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        ids.nextId(), USER, WECHAT_APP, WECHAT_OPEN);
    jdbc.update("INSERT INTO user_pet(id,user_id,name,pet_type,breed_name,sex,weight_kg,"
        + "health_note,is_default,status,created_at,updated_at) VALUES(?,?,?,'DOG','柯基','MALE',"
        + "12.50,'性格温顺',1,'ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", PET, USER, "豆豆");
    jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,created_at,updated_at)"
        + " VALUES(?,?,'E2E体验商家','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        MERCHANT, USER);
    jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,created_at,"
        + "updated_at) VALUES(?,?,?,'成都市锦江区E2E验收路1号','ACTIVE',UTC_TIMESTAMP(3),"
        + "UTC_TIMESTAMP(3))", STORE, MERCHANT, "E2E体验门店");
    seedApprovedApplicationAndAgreement(jdbc, ids);
    jdbc.update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,employment_status,"
        + "service_enabled,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),"
        + "UTC_TIMESTAMP(3))", STAFF, MERCHANT, STORE, "E2E服务师");
    jdbc.update("INSERT INTO service_category(id,category_name,status,created_at,updated_at)"
        + " VALUES(?,'E2E洗护陪伴','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", CATEGORY);
    // IN_STORE, 60 minutes, DOG: one minute-level window == one selectable slot whose bounds
    // the order kernel accepts as the appointment (duration must match exactly).
    jdbc.update("INSERT INTO service_item(id,merchant_id,store_id,category_id,service_name,"
        + "description,price,duration_minutes,fulfillment_type,status,cover_asset_id,"
        + "applicable_pet_types,verification_required,submission_no,owner_user_id,submitted_at,"
        + "created_at,updated_at) VALUES(?,?,?,?,?,'E2E真实服务描述',60.00,60,'IN_STORE','ACTIVE',?,"
        + "'DOG',1,1,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        SERVICE, MERCHANT, STORE, CATEGORY, "E2E到店精细洗护", COVER_ASSET, USER);
    jdbc.update("INSERT INTO service_review_decision(id,service_id,submission_no,decision_type,"
        + "decided_by_operator_id,decided_at,authz_version,scope_version,request_id,created_at)"
        + " VALUES(?,?,1,'APPROVE',9001,UTC_TIMESTAMP(3),'e2e-authz','e2e-scope',?,"
        + "UTC_TIMESTAMP(3))", ids.nextId(), SERVICE,
        ("e2e-service-approve").getBytes(StandardCharsets.UTF_8));
    jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,created_at,"
        + "updated_at) VALUES(?,?,?,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        ids.nextId(), STAFF, SERVICE);
    // Wide staff availability covering all seeded service windows (whole-minute bounds: the
    // schedule kernel rejects seconds/millis in availability intervals). Round 4: widened to
    // start two days back so the past-appointment completed target stays staff-covered.
    java.time.LocalDateTime availableFrom = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        .minusDays(2).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,"
        + "created_at,updated_at) VALUES(?,?,?,?,?,'AVAILABLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        ids.nextId(), STORE, STAFF, availableFrom, availableFrom.plusDays(12));
    // GENERAL windows: 60-minute slots over yesterday, today and tomorrow 09:00-20:00 Beijing
    // time (business zone Asia/Shanghai == UTC+8, so 01:00-12:00 UTC). Round 4: yesterday
    // joined so the admin-chain completed target can book a fully past appointment.
    java.time.LocalDate beijingToday = java.time.LocalDate.now(CHANNEL_ZONE);
    DateTimeFormatter sql = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    for (int day = -1; day <= 1; day++) {
      for (int hour = 9; hour <= 19; hour++) {
        String start = beijingToday.plusDays(day).atTime(hour, 0)
            .atZone(CHANNEL_ZONE).withZoneSameInstant(java.time.ZoneOffset.UTC)
            .format(sql);
        String end = beijingToday.plusDays(day).atTime(hour, 0).plusMinutes(60)
            .atZone(CHANNEL_ZONE).withZoneSameInstant(java.time.ZoneOffset.UTC)
            .format(sql);
        jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,"
            + "start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
            + " VALUES(?,?,?,?,?, ?,6,'OPEN','GENERAL',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
            ids.nextId(), MERCHANT, STORE, SERVICE, start, end);
      }
    }
    // Round 3: contract-61 coupon walkthrough facts for the seeded C user. The template carries
    // the CCR-C006 frozen four-key rule vocabulary; amountOff 10 stays below the 60.00 service
    // price and the 50 threshold below it, so the coupon is eligible on every seeded slot.
    // Three AVAILABLE instances: A (create->pay->used->full-refund restore), B (concurrent
    // freeze lose/lose-win + payment-timeout close release), C (spare for list rendering).
    jdbc.update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
        + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at) VALUES"
        + "(?,'E2E立减10元券','ACTIVE',100,3,UTC_TIMESTAMP(3) - INTERVAL 1 DAY,"
        + "UTC_TIMESTAMP(3) + INTERVAL 30 DAY,'{\"amountOff\":10.00,\"thresholdAmount\":50.00,"
        + "\"scopeSummary\":\"全部门店通用\",\"typeLabel\":\"满减券\"}',0,"
        + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", 716001L);
    for (long instance : new long[] {716011L, 716012L, 716013L}) {
      jdbc.update("INSERT INTO coupon_instance(id,coupon_template_id,user_id,status,order_id,"
          + "original_expire_at,expire_at,version,created_at,updated_at) VALUES"
          + "(?,?,'710100','AVAILABLE',NULL,UTC_TIMESTAMP(3) + INTERVAL 30 DAY,"
          + "UTC_TIMESTAMP(3) + INTERVAL 30 DAY,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          instance, 716001L);
    }
  }

  /** Approved application + published and signed agreement projection (29/28 SQL shapes). */
  private static void seedApprovedApplicationAndAgreement(JdbcTemplate jdbc, SnowflakeIdGenerator ids) {
    long application = 710600L, revision = 710601L, creditMaterial = 710602L;
    long identityMaterial = 710603L, creditEvidence = 710604L;
    long identityEvidence = 710605L, creditClaim = 710606L;
    long identityClaim = 710607L, task = 710608L, decision = 710609L, audit = 710610L;
    byte[] creditDigest = digest(1), identityDigest = digest(2);
    jdbc.update("INSERT INTO merchant_subject_lookup_policy(policy_slot,key_version,algorithm,created_at)"
        + " VALUES(1,'e2e-v1','HMAC-SHA-256',UTC_TIMESTAMP(3))");
    jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,"
        + "subject_verification_status,created_at,updated_at)"
        + " VALUES(?,?,?,'DRAFT','NOT_STARTED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        application, USER, MERCHANT);
    jdbc.update("INSERT INTO merchant_application_revision(id,application_id,revision_no,"
        + "merchant_name,created_by_user_id,created_at) VALUES(?,?,1,'E2E体验商家',?,UTC_TIMESTAMP(3))",
        revision, application, USER);
    jdbc.update("UPDATE merchant_application SET current_revision_id=? WHERE id=?", revision, application);
    seedMaterial(jdbc, ids, application, creditMaterial, 710700L, "BUSINESS_LICENSE", "a".repeat(64), USER);
    seedMaterial(jdbc, ids, application, identityMaterial, 710701L, "ID_CARD_FRONT", "b".repeat(64), USER);
    // Round 4: the review-detail face (MerchantApplicationService detail invariant) refuses a
    // submitted revision without STORE_PHOTO >= 1 and exactly one ID_CARD_BACK — the two-shot
    // seed projection above left the admin application detail 503ing fail-closed. The store
    // photo and ID back ride the same revision as first-class materials.
    long storePhotoMaterial = 710611L, idBackMaterial = 710612L;
    seedMaterial(jdbc, ids, application, storePhotoMaterial, 710702L, "STORE_PHOTO", "c".repeat(64), USER);
    seedMaterial(jdbc, ids, application, idBackMaterial, 710703L, "ID_CARD_BACK", "d".repeat(64), USER);
    for (Object[] row : List.of(new Object[] {creditMaterial, "BUSINESS_LICENSE"},
            new Object[] {identityMaterial, "ID_CARD_FRONT"},
            new Object[] {storePhotoMaterial, "STORE_PHOTO"},
            new Object[] {idBackMaterial, "ID_CARD_BACK"})) {
      jdbc.update("INSERT INTO merchant_application_revision_material(application_id,revision_id,"
          + "material_id,material_type,position) VALUES(?,?,?,?,1)", application, revision,
          row[0], row[1]);
    }
    seedEvidence(jdbc, application, revision, creditMaterial, creditEvidence,
        "BUSINESS_LICENSE", "a".repeat(64), "CREDIT_CODE", creditDigest);
    seedEvidence(jdbc, application, revision, identityMaterial, identityEvidence,
        "ID_CARD_FRONT", "b".repeat(64), "IDENTITY_NUMBER", identityDigest);
    seedClaim(jdbc, application, revision, creditEvidence, creditClaim, "CREDIT_CODE", creditDigest);
    seedClaim(jdbc, application, revision, identityEvidence, identityClaim,
        "IDENTITY_NUMBER", identityDigest);
    jdbc.update("INSERT INTO merchant_application_review_task(id,application_id,submitted_revision_id,"
        + "submission_no,status,version,updated_at) VALUES(?,?,?,1,'AVAILABLE',0,UTC_TIMESTAMP(3))",
        task, application, revision);
    jdbc.update("UPDATE merchant_application_review_task SET status='CLAIMED',"
        + "claimed_by_operator_id=9001,claimed_at=UTC_TIMESTAMP(3),version=1 WHERE id=?", task);
    jdbc.update("UPDATE merchant_application SET application_no='SQ20261007E2ELOOP1',"
        + "status='REVIEWING',current_revision_id=?,submitted_revision_id=?,"
        + "current_review_task_id=?,subject_verification_status='VERIFIED',"
        + "submitted_at=UTC_TIMESTAMP(3),version=1 WHERE id=?",
        revision, revision, task, application);
    jdbc.update("INSERT INTO merchant_application_review_decision(id,application_id,"
        + "submitted_revision_id,task_id,decision_type,decided_by_operator_id,decided_at,"
        + "authz_version,scope_version,request_id,credit_evidence_id,credit_evidence_type,"
        + "credit_evidence_status,credit_evidence_digest,credit_evidence_policy_slot,"
        + "credit_evidence_key_version,identity_evidence_id,identity_evidence_type,"
        + "identity_evidence_status,identity_evidence_digest,identity_evidence_policy_slot,"
        + "identity_evidence_key_version,credit_claim_id,credit_claim_type,credit_claim_status,"
        + "identity_claim_id,identity_claim_type,identity_claim_status) VALUES(?,?,?,?,"
        + "'APPROVE',9001,UTC_TIMESTAMP(3),'e2e-authz','e2e-scope',?,"
        + "?,'CREDIT_CODE','VERIFIED',?,1,'e2e-v1',"
        + "?,'IDENTITY_NUMBER','VERIFIED',?,1,'e2e-v1',"
        + "?,'CREDIT_CODE','ACTIVE',?,'IDENTITY_NUMBER','ACTIVE')",
        decision, application, revision, task, "e2e-decision".getBytes(StandardCharsets.UTF_8),
        creditEvidence, creditDigest, identityEvidence, identityDigest, creditClaim, identityClaim);
    jdbc.update("UPDATE merchant_application_review_task SET status='CLOSED',"
        + "closed_at=UTC_TIMESTAMP(3),version=2 WHERE id=?", task);
    jdbc.update("INSERT INTO merchant_application_audit(id,application_id,revision_id,actor_type,"
        + "actor_id,action_code,from_status,to_status,request_id,occurred_at,decision_id)"
        + " VALUES(?,?,?,'PLATFORM_OPERATOR',9001,'DECISION','REVIEWING','APPROVED',?,"
        + "UTC_TIMESTAMP(3),?)", audit, application, revision,
        "e2e-review-audit".getBytes(StandardCharsets.UTF_8), decision);
    jdbc.update("UPDATE merchant_application SET status='APPROVED',current_decision_id=?,"
        + "current_decision_type='APPROVE',review_audit_id=?,current_credit_claim_id=?,"
        + "current_credit_claim_type='CREDIT_CODE',current_credit_claim_status='ACTIVE',"
        + "current_identity_claim_id=?,current_identity_claim_type='IDENTITY_NUMBER',"
        + "current_identity_claim_status='ACTIVE',reviewed_at=UTC_TIMESTAMP(3),version=2"
        + " WHERE id=?", decision, audit, creditClaim, identityClaim, application);
    jdbc.update("INSERT INTO merchant_profile_compat(merchant_id,application_id,source_revision_id,"
        + "merchant_type_code,city_code,source_kind,version,created_at,updated_at)"
        + " VALUES(?,?,?,'PET_SHOP','chengdu','APPLICATION',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        MERCHANT, application, revision);
    String agreementContent = "E2E验收服务协议";
    String agreementHash = sha(agreementContent);
    jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,"
        + "published_at,published_by_operator_id) VALUES(710800,'e2e-v1',?,?,"
        + "UTC_TIMESTAMP(3),9001)", agreementContent, agreementHash);
    jdbc.update("INSERT INTO merchant_agreement_current(agreement_key,agreement_version_id,"
        + "updated_at) VALUES('MERCHANT',710800,UTC_TIMESTAMP(3))");
    jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,"
        + "accepted_by_user_id,accepted_at,content_sha256)"
        + " VALUES(710801,?,710800,?,UTC_TIMESTAMP(3),?)",
        MERCHANT, USER, agreementHash);
  }

  private static void seedMaterial(JdbcTemplate jdbc, SnowflakeIdGenerator ids, long application,
      long material, long asset, String type, String hash, long owner) {
    jdbc.update("INSERT INTO merchant_application_material(id,application_id,material_type,"
        + "private_asset_id,sha256,media_type,bytes,uploaded_by_user_id,created_at)"
        + " VALUES(?,?,?,?,?,'image/jpeg',1024,?,UTC_TIMESTAMP(3))",
        material, application, type, asset, hash, owner);
  }

  private static void seedEvidence(JdbcTemplate jdbc, long application, long revision,
      long material, long evidence, String materialType, String hash, String credentialType,
      byte[] digest) {
    jdbc.update("INSERT INTO merchant_credential_evidence(id,application_id,revision_id,"
        + "material_id,material_type,material_sha256,evidence_source,evidence_status,"
        + "credential_type,subject_name_protected,identifier_protected,identifier_lookup_digest,"
        + "lookup_policy_slot,lookup_key_version,valid_from,valid_to,validity_kind,"
        + "verified_by_operator_id,verification_reason,observed_at)"
        + " VALUES(?,?,?,?,?,?,'MANUAL','VERIFIED',?,?,?, ?,1,'e2e-v1',"
        + "'2020-01-01','2035-01-01','DATED',9001,'E2E人工核验原件与主体一致',UTC_TIMESTAMP(3))",
        evidence, application, revision, material, materialType, hash, credentialType,
        new byte[] {1}, new byte[] {2}, digest);
  }

  private static void seedClaim(JdbcTemplate jdbc, long application, long revision,
      long evidence, long claim, String type, byte[] digest) {
    jdbc.update("INSERT INTO merchant_subject_claim(id,claim_type,lookup_digest,"
        + "lookup_policy_slot,lookup_key_version,application_id,revision_id,evidence_id,"
        + "evidence_status,status,claimed_at) VALUES(?,?,?,1,'e2e-v1',?,?,?,'VERIFIED',"
        + "'ACTIVE',UTC_TIMESTAMP(3))", claim, type, digest, application, revision, evidence);
  }

  private static byte[] digest(int endByte) {
    byte[] digest = new byte[32];
    digest[31] = (byte) endByte;
    return digest;
  }

  // ------------------------------------------------------------------ helpers

  private static void loadSchemas(DataSource source, Path root) throws Exception {
    Path idSchema;
    try (var files = Files.list(root.resolve("docs/03-database"))) {
      idSchema = files.filter(p -> p.getFileName().toString().startsWith("25-")
          && p.toString().endsWith(".sql")).findFirst().orElseThrow();
    }
    List<String> scripts = new ArrayList<>();
    scripts.add(idSchema.getFileName().toString());
    scripts.addAll(List.of(
        "06-核心数据库Schema-v0.1.sql",
        "14-Command-Idempotency-Schema-v0.1.sql",
        "26-Admin-Auth-Schema-v0.1.sql",
        "28-Merchant-Agreement-Schema-v0.1.sql",
        "29-Merchant-Application-Schema-v0.1.sql",
        "13-Async-Infra-Schema-v0.1.sql",
        "31-Private-Asset-Schema-v0.1.sql",
        "33-Service-Write-Schema-v0.1.sql",
        "35-Merchant-Staff-Audit-Schema-v0.1.sql",
        // Round 4: the contract-52/54 staff identity + binding tables back the 64号 governance
        // staff-members/invitations read face (without them those routes 500 on a missing
        // table — fixture gap, not a product defect; the routes answer empty lists once the
        // tables exist).
        "52-Merchant-Staff-Identity-Schema-v0.1.sql",
        "54-Merchant-Staff-Binding-Schema-v0.1.sql",
        "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
        "38-Booking-Create-Schema-v0.1.sql",
        "39-Booking-Expiry-Schema-v0.1.sql",
        "40-Payment-Foundation-Schema-v0.1.sql",
        "41-Payment-Dispatch-Schema-v0.1.sql",
        "42-Late-Refund-Execution-Schema-v0.1.sql",
        "43-Payment-Refund-Dispatch-Schema-v0.1.sql",
        "44-Late-Refund-Order-Projection-Schema-v0.1.sql",
        "45-Merchant-Order-Actions-Schema-v0.1.sql",
        "46-Order-Reschedule-Schema-v0.1.sql",
        "47-Verification-Credential-Schema-v0.1.sql",
        "48-Verification-Completion-Schema-v0.1.sql",
        "49-Refund-Application-Schema-v0.1.sql",
        // Round 2: the aftersale workflow + HTTP schemas (50/51) ride the enabled aftersale
        // slice; the refund application's admission consults the active-aftersale projection,
        // so these ALTERs are load-bearing for the whole C write face once aftersale is on.
        "50-AfterSale-Workflow-Schema-v0.1.sql",
        "51-AfterSale-Http-Schema-v0.1.sql",
        // Round 4: the contract-63 v0.2 disposal audit schema (order_admin_action) behind the
        // enabled admin trade face; the Flyway opt-in lane stays off in this fixture.
        "63-Admin-Trade-Disposition-Schema-v0.1.sql",
        // Round 3: points earn kernel tables (58/60号; balances/ledger stay in SQL06 §9) and
        // the encyclopedia content tables (62号, whose seed INSERTs ship the published breed
        // content the C read face serves). Coupon tables live in SQL06 and need no extra file.
        "60-Points-Task-Schema-v0.1.sql",
        "62-Baike-Content-Schema-v0.1.sql",
        // Contract 68 health-record tables (vaccine/deworm add+list).
        "68-Pet-Health-Record-Schema-v0.1.sql",
        // CCR-MKT-001/002/003 marketing center + home blocks and CCR-COM-001 community.
        "63-Marketing-Storage-v0.1.sql",
        "64-Community-Storage-v0.1.sql"));
    try (var connection = source.getConnection()) {
      for (String file : scripts) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
            new FileSystemResource(root.resolve("docs/03-database/" + file)),
            StandardCharsets.UTF_8));
      }
    }
  }

  private static DataSource rawSource(String server, String user, String password) {
    org.springframework.jdbc.datasource.DriverManagerDataSource raw =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(server
            + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
            + "&connectTimeout=1000&socketTimeout=5000&createDatabaseIfNotExist=false",
            user, password);
    return raw;
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
