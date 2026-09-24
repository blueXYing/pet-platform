package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.notification.biz.event.ServiceReviewedConsumer;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Role W wiring acceptance for the ServiceReviewedConsumer boot assembly (role E PR#69 consumer +
 * role A PR#68 reserved switch, wired 2026-09-23): with {@code pet.service.review.notifications-
 * enabled=true} the real Spring context registers the consumer behind the outbox dispatcher, a
 * ServiceReviewedEvent.v1 appended through the real TransactionalOutboxPublisher inside a caller
 * transaction is dispatched to the mandatory inbox row (receiver = event ownerUserId), and the
 * merchant owner sees it on the USER inbox HTTP surface while another user stays isolated.
 *
 * <p>Coverage-layer disclosure: the write-side decision-to-outbox append is covered by
 * ServiceWriteHttpTest (same-transaction ServiceReviewedEvent.v1 assertions) and the consumer's
 * strict payload/idempotency semantics by ServiceReviewedConsumerMySqlTest; this class proves the
 * missing middle — the pet-boot bean wiring routes real outbox rows into the real consumer and
 * the resulting rows are visible through the real inbox HTTP endpoints. The full write-side HTTP
 * chain (admin decision -> inbox) remains an uncovered seam recorded in the PR description; the
 * switch default-off is structural (same @ConditionalOnProperty pattern as the merchant
 * application consumer) and ServiceWriteHttpTest asserts the bean stays absent there.
 */
class ServiceReviewedNotificationWiringHttpTest {
  private static final DateTimeFormatter EVENT_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private CAuthHttpTest.HttpFixture db;
  private ConfigurableApplicationContext context;
  private String origin;

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
    return Objects.requireNonNull(p);
  }

  @BeforeEach
  void start() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", 0);
    props.put("spring.flyway.enabled", false);
    props.put("spring.main.banner-mode", "off");
    props.put("spring.jmx.enabled", false);
    props.put("pet.auth.c.enabled", true);
    props.put("pet.auth.c.redis-host", db.redisHost);
    props.put("pet.auth.c.redis-port", db.redisPort);
    props.put("pet.auth.c.cache-prefix", db.prefix);
    props.put("pet.outbox.enabled", true);
    props.put("pet.service.review.notifications-enabled", true);
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  ctx -> {
                    var beans = (GenericApplicationContext) ctx;
                    beans.registerBean(
                        "ntfwDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean(
                        "ntfwIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "ntfwWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                  })
              .run(
                  props.entrySet().stream()
                      .map(e -> "--" + e.getKey() + "=" + e.getValue())
                      .toArray(String[]::new));
      origin = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    } catch (Exception failure) {
      if (context != null) context.close();
      db.close();
      db = null;
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
  void enabledSwitchRegistersConsumerAndDispatchesOutboxEventToOwnerInbox() throws Exception {
    // Assembly smoke: the gated bean exists and is the PR#69 consumer.
    ServiceReviewedConsumer consumer = context.getBean(ServiceReviewedConsumer.class);
    assertEquals("notification.service-reviewed.v1", consumer.consumerName());
    assertTrue(consumer.eventTypes().contains("ServiceReviewedEvent.v1"));

    // The merchant main account receives as a plain C-end USER; another user is the isolation
    // negative. Both are real wechat sessions from the fixed provider.
    Map<String, Object> owner = consumerLogin("ntfw-owner", "13800007741");
    Map<String, Object> other = consumerLogin("ntfw-other", "13800007742");
    String ownerToken = str(owner, "accessToken");
    long ownerId = Long.parseLong(str(owner, "userId"));

    // Append a ServiceReviewedEvent.v1 through the real publisher inside a caller transaction,
    // mirroring the write-side producer shape (nine-field payload, Snowflake String IDs).
    long serviceId = db.ids.nextId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("serviceId", Long.toUnsignedString(serviceId));
    payload.put("serviceName", "宠物基础洗护");
    payload.put("merchantId", Long.toUnsignedString(db.ids.nextId()));
    payload.put("storeId", Long.toUnsignedString(db.ids.nextId()));
    payload.put("submissionNo", 1);
    payload.put("decisionType", "REJECT");
    payload.put("opinion", "服务图片与门类不符，请补充真实拍摄图片后重新提交");
    payload.put("decidedAt", EVENT_TIME.format(OffsetDateTime.now(ZoneOffset.UTC)));
    payload.put("ownerUserId", Long.toUnsignedString(ownerId));
    TransactionalOutboxPublisher publisher = context.getBean(TransactionalOutboxPublisher.class);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(db.source));
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    transaction.executeWithoutResult(
        status ->
            publisher.publish(
                new IntegrationEvent<>(
                    null,
                    "ServiceReviewedEvent.v1",
                    1,
                    occurredAt,
                    "SERVICE",
                    Long.toUnsignedString(serviceId),
                    "ntfw-wiring-trace",
                    payload)));

    // The outbox dispatcher (started by the boot assembly) delivers to the consumer.
    for (long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos(); ; ) {
      Integer count =
          db.jdbc.queryForObject(
              "SELECT COUNT(*) FROM notification WHERE receiver_id=? AND message_type='SERVICE_REVIEWED'"
                  + " AND mandatory_inbox=1",
              Integer.class, ownerId);
      String outboxStatus =
          db.jdbc.queryForObject(
              "SELECT status FROM integration_event_outbox WHERE aggregate_id=?", String.class,
              serviceId);
      if (count != null && count == 1 && "PUBLISHED".equals(outboxStatus)) break;
      if (count != null && count > 1) fail("duplicate service review notifications: count=" + count);
      if (System.nanoTime() >= deadline) {
        fail("service review dispatch did not finish: notificationCount=" + count
            + ", outboxStatus=" + outboxStatus);
      }
      Thread.sleep(200);
    }
    assertEquals(
        "PUBLISHED",
        db.jdbc.queryForObject(
            "SELECT status FROM integration_event_outbox WHERE aggregate_id=?", String.class,
            serviceId));
    assertEquals(
        1,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='notification.service-reviewed.v1'",
            Integer.class));

    // The owner sees the mandatory row on the USER inbox HTTP surface.
    String inbox = "/api/v1/c/notifications";
    Reply list = send("GET", inbox, null, bearer(ownerToken));
    assertEquals(200, list.status(), list.redacted());
    assertEquals(1, ((Number) list.data().get("total")).intValue());
    List<?> items = (List<?>) list.data().get("items");
    assertEquals(1, items.size());
    Map<String, Object> item = map(items.get(0));
    assertEquals("SERVICE_REVIEWED", item.get("messageType"));
    assertEquals("SERVICE", item.get("bizType"));
    assertEquals("SYSTEM", item.get("category"));
    assertEquals(Long.toUnsignedString(serviceId), item.get("bizId"));
    assertEquals("服务审核结果", item.get("title"));
    assertEquals("服务《宠物基础洗护》：审核未通过。服务图片与门类不符，请补充真实拍摄图片后重新提交",
        item.get("content"));
    assertNull(item.get("readAt"));
    String notificationId = str(item, "id");
    assertEquals(
        200, send("GET", inbox + "/" + notificationId, null, bearer(ownerToken)).status());

    // Isolation: another user (any other merchant's account) sees nothing, and detail/read stay
    // behind the anti-enumeration 404.
    Reply otherList = send("GET", inbox, null, bearer(str(other, "accessToken")));
    assertEquals(200, otherList.status(), otherList.redacted());
    assertEquals(0, ((Number) otherList.data().get("total")).intValue());
    assertTrue(((List<?>) otherList.data().get("items")).isEmpty());
    assertEquals(
        404,
        send("GET", inbox + "/" + notificationId, null, bearer(str(other, "accessToken")))
            .status());
    assertEquals(
        404,
        send(
                "POST",
                inbox + "/" + notificationId + "/read",
                Map.of(),
                bearer(str(other, "accessToken"), UUID.randomUUID().toString()))
            .status());
  }

  private Map<String, Object> consumerLogin(String openid, String phone) throws Exception {
    Reply attempt =
        send("POST", "/api/v1/c/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"), Map.of());
    Reply grant =
        send(
            "POST",
            "/api/v1/c/auth/wechat-login",
            Map.of(
                "attemptId", str(attempt.data(), "attemptId"),
                "wechatCode", "ok:" + openid,
                "phoneCode", "phone:" + phone),
            Map.of("X-Auth-Attempt", str(attempt.data(), "attemptToken")));
    assertEquals(200, grant.status(), grant.redacted());
    return grant.data();
  }

  private record Reply(int status, Map<String, Object> envelope, HttpResponse<String> raw) {
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    String redacted() {
      return "Reply[status=" + status + "]";
    }
  }

  private Reply send(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    if (!headers.containsKey("X-Request-Id") && !method.equals("GET"))
      builder.header("X-Request-Id", UUID.randomUUID().toString());
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

  private static Map<String, String> bearer(String token, String requestId) {
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", "Bearer " + token);
    if (requestId != null) headers.put("X-Request-Id", requestId);
    return headers;
  }

  private static Map<String, String> bearer(String token) {
    return bearer(token, null);
  }

  private static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> result = new LinkedHashMap<>();
      m.forEach((k, v) -> result.put(String.valueOf(k), v));
      return result;
    }
    throw new IllegalArgumentException("Expected object, got: " + value);
  }

  private static String str(Map<String, Object> value, String key) {
    Object found = value.get(key);
    if (found == null) throw new IllegalArgumentException("Missing " + key);
    return String.valueOf(found);
  }
}
