package com.petplatform.boot.e2e;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
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
    int nodeId = Integer.parseInt(System.getenv().getOrDefault("E2E_SNOWFLAKE_NODE", "23"));
    Path directory = Files.createDirectories(Path.of(System.getenv()
        .getOrDefault("E2E_RUNTIME_DIR", "D:/Temp/e2e-core-loop")));
    String mysqlUser = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
    String mysqlPassword = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");

    JdbcTemplate adminJdbc = new JdbcTemplate(rawSource(server, mysqlUser, mysqlPassword));
    HikariDataSource source = null;
    ConfigurableApplicationContext context = null;
    // The shared local MySQL also serves sibling QA agents; the Snowflake single-flight
    // acquisition has a hard 1s budget, so a busy instance can time the first attempt out.
    // Retry the whole isolated fixture (fresh database each time) before giving up.
    String name = null;
    HutoolSnowflakeIdProvider ids = null;
    JdbcTemplate jdbc = null;
    // Unique namespace token: sibling QA fixtures on this machine use the same
    // auth001c_e2e_core_loop_ prefix shape and their Redis cleanup scans can wipe shared
    // keys; this run uses an exclusive prefix (still inside the auth001c[a-zA-Z0-9_-]* contract).
    String base = "auth001czq" + Long.toHexString(System.nanoTime() % 0xFFFFF);
    for (int attempt = 1; attempt <= 6; attempt++) {
      String candidate = base + "_e2e_core_loop_"
          + UUID.randomUUID().toString().replace("-", "");
      HikariDataSource candidateSource = null;
      try {
        adminJdbc.execute("CREATE DATABASE `" + candidate + "` CHARACTER SET utf8mb4");
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
        loadSchemas(candidateSource, root());
        String evidence = "e2e-core-loop:node" + nodeId + ":" + candidate;
        candidateJdbc.update("INSERT INTO snowflake_worker_state"
            + "(node_id,format_identity,enabled,initialization_ref,created_at,updated_at)"
            + " VALUES(?,?,TRUE,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
            nodeId, SnowflakeProviderSettings.FORMAT_IDENTITY, evidence);
        HutoolSnowflakeIdProvider candidateIds = new HutoolSnowflakeIdProvider(
            new JdbcSnowflakeNodeStore(candidateSource), new SnowflakeProviderSettings(nodeId),
            old -> {
              if (old.nodeId() != nodeId || old.incarnation() != null || old.fence() != 0
                  || old.reservedThrough() != -1 || !evidence.equals(old.initializationRef())) {
                throw new IllegalStateException("Not this fixture's virgin node");
              }
            });
        warmSnowflake(candidateIds);
        seedBookableFacts(candidateJdbc, candidateIds);
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
    final HutoolSnowflakeIdProvider finalIds = Objects.requireNonNull(ids);
    final RecoveringIds recoveringIds = new RecoveringIds(pool, nodeId, finalIds);
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
                beans.registerBean("e2eDataSource", DataSource.class, () -> pool);
                beans.registerBean("e2eIds", SnowflakeIdGenerator.class, () -> recoveringIds);
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
                  "--pet.order.expiry.worker.enabled=false",
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
                  "--pet.order.reschedule.enabled=false",
                  "--pet.verification.credential.enabled=true",
                  "--pet.verification.credential.http.enabled=true",
                  "--pet.verification.credential.key-id=e2e-key",
                  "--pet.verification.credential.encryption-key=" + base64(19),
                  "--pet.verification.credential.lookup-key=" + base64(23),
                  "--pet.verification.completion.enabled=true",
                  "--pet.verification.completion.http.enabled=true",
                  "--pet.refund.application.enabled=true",
                  "--pet.refund.application.http.enabled=true",
                  "--pet.refund.application.worker.enabled=false",
                  "--pet.refund.application.protection-key=" + base64(13),
                  "--pet.refund.application.reason-codes=SCHEDULE_CONFLICT,PLAN_CHANGED,SERVICE_ISSUE",
                  // Composition constraint found during bring-up: with pet.outbox enabled,
                  // LateRefundService registers an empty subscription unless refund.late is on,
                  // and OutboxDispatcher fails the whole boot (empty eventTypes is an error).
                  "--pet.refund.late.enabled=true",
                  "--pet.aftersale.enabled=false");
      String endpoint = "http://127.0.0.1:" + port;
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
              + "wechatApp=" + WECHAT_APP + System.lineSeparator(),
          StandardCharsets.UTF_8);
      System.out.println("E2E_CORE_LOOP result=READY endpoint=" + endpoint
          + " database=" + name + " metadata=" + metadata);
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
    // schedule kernel rejects seconds/millis in availability intervals).
    java.time.LocalDateTime availableFrom = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        .minusDays(1).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,"
        + "created_at,updated_at) VALUES(?,?,?,?,?,'AVAILABLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        ids.nextId(), STORE, STAFF, availableFrom, availableFrom.plusDays(10));
    // GENERAL windows: 60-minute slots over today and tomorrow 09:00-20:00 Beijing time
    // (business zone Asia/Shanghai == UTC+8, so 01:00-12:00 UTC).
    java.time.LocalDate beijingToday = java.time.LocalDate.now(CHANNEL_ZONE);
    DateTimeFormatter sql = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    for (int day = 0; day < 2; day++) {
      for (int hour = 9; hour <= 19; hour++) {
        String start = beijingToday.plusDays(day).atTime(hour, 0)
            .atZone(CHANNEL_ZONE).withZoneSameInstant(java.time.ZoneOffset.UTC)
            .format(sql);
        String end = beijingToday.plusDays(day).atTime(hour, 0).plusMinutes(60)
            .atZone(CHANNEL_ZONE).withZoneSameInstant(java.time.ZoneOffset.UTC)
            .format(sql);
        jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,"
            + "start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
            + " VALUES(?,?,?,?,?, ?,2,'OPEN','GENERAL',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
            ids.nextId(), MERCHANT, STORE, SERVICE, start, end);
      }
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
    for (Object[] row : List.of(new Object[] {creditMaterial, "BUSINESS_LICENSE"},
            new Object[] {identityMaterial, "ID_CARD_FRONT"})) {
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

  // ------------------------------------------------------------------ recovering snowflake

  /**
   * QA-only resilience wrapper: the production Snowflake single-flight lane is deliberately
   * fail-closed (1s budget, lease renewal), and this shared QA machine (sibling agent builds,
   * 4-way parallel acceptance) can blow that budget once in a while. The wrapper transparently
   * reopens a NEW virgin node when the current provider latches closed, so a long walkthrough
   * survives a transient stall. Both node ids are real Snowflake nodes; IDs stay unique.
   */
  private static final class RecoveringIds implements SnowflakeIdGenerator {
    private final HikariDataSource pool;
    private final int baseNodeId;
    private volatile HutoolSnowflakeIdProvider current;

    RecoveringIds(HikariDataSource pool, int baseNodeId, HutoolSnowflakeIdProvider first) {
      this.pool = pool;
      this.baseNodeId = baseNodeId;
      this.current = first;
    }

    @Override public long nextId() {
      HutoolSnowflakeIdProvider provider = current;
      try {
        return provider.nextId();
      } catch (RuntimeException closed) {
        synchronized (this) {
          if (current == provider) {
            for (int attempt = 1; attempt <= 8; attempt++) {
              int candidateNode = baseNodeId + attempt;
              if (candidateNode > 1023) candidateNode = 1 + (candidateNode % 1023);
              try {
                JdbcSnowflakeNodeStore store = new JdbcSnowflakeNodeStore(pool);
                final int recoveryNode = candidateNode;
                final String recoveryEvidence = "e2e-core-loop-recover:node" + candidateNode
                    + ":" + System.nanoTime();
                new JdbcTemplate(pool).update("INSERT INTO snowflake_worker_state"
                    + "(node_id,format_identity,enabled,initialization_ref,created_at,updated_at)"
                    + " VALUES(?,?,TRUE,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    candidateNode, SnowflakeProviderSettings.FORMAT_IDENTITY, recoveryEvidence);
                HutoolSnowflakeIdProvider reopened = new HutoolSnowflakeIdProvider(store,
                    new SnowflakeProviderSettings(recoveryNode),
                    old -> {
                      if (old.nodeId() != recoveryNode || old.incarnation() != null || old.fence() != 0
                          || old.reservedThrough() != -1 || !recoveryEvidence.equals(old.initializationRef())) {
                        throw new IllegalStateException("Not this fixture's recovery node");
                      }
                    });
                warmSnowflake(reopened);
                current = reopened;
                System.err.println("E2E_CORE_LOOP snowflake recovered on node " + candidateNode);
                break;
              } catch (Exception reopenFailure) {
                System.err.println("E2E_CORE_LOOP snowflake reopen attempt " + attempt
                    + " failed: " + reopenFailure);
                try {
                  Thread.sleep(2_000);
                } catch (InterruptedException stop) {
                  Thread.currentThread().interrupt();
                  throw closed;
                }
              }
            }
          }
          return current.nextId();
        }
      }
    }
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
        "49-Refund-Application-Schema-v0.1.sql"));
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
