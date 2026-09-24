package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.ApiException;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.schedule.api.query.AvailabilityQuery;
import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverAssetPort;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import com.petplatform.user.biz.application.WechatSessionProvider;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * CCR-W2-API-001 schedule availability acceptance (W2-SCH-001..007, ruling 2026-09-23). A REAL
 * approved-and-signed merchant/store pair drives the flow; services are created through the REAL
 * writer HTTP chain (draft -> submit -> admin APPROVE), while schedule windows, reservations,
 * employees, capabilities and availability rows are SQL-seeded until their writers exist.
 *
 * <p>SCH2-D5: the enabled real assembly computes staff facts through MER and SCH-owned tables.
 * W2-SCH-007 also exercises the defensive missing-provider constructor branch directly.
 */
class ScheduleAvailabilityHttpTest {
  private static final String ORIGIN = "https://sch.example.invalid";
  private static final String PASSWORD = "Example_ONLY_92!";
  private static final String COVER_ASSET = "590000000000000501";
  private static final Set<String> WINDOW_KEYS =
      Set.of("start", "end", "effectiveCapacity", "occupiedCount", "remainingCapacity", "available");
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<Long, PrivateAssetQueryPort.PrivateAssetRef> privateAssets = new HashMap<>();
  private final Map<String, ServiceCoverAssetPort.CoverAssetFact> coverAssets = new HashMap<>();
  private final byte[] adminMac = key(3), adminAes = key(7);
  private final AesGcmProtectedValueProvider protector =
      new AesGcmProtectedValueProvider("qa-only-v1", key(31), key(47));
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
    try (var connection = db.source.getConnection()) {
      for (String file :
          List.of(
              "26-Admin-Auth-Schema-v0.1.sql",
              "28-Merchant-Agreement-Schema-v0.1.sql",
              "29-Merchant-Application-Schema-v0.1.sql",
              "33-Service-Write-Schema-v0.1.sql")) {
        ScriptUtils.executeSqlScript(
            connection,
            new EncodedResource(
                new FileSystemResource(root().resolve("docs/03-database/" + file)),
                StandardCharsets.UTF_8));
      }
    }
    db.jdbc.update(
        "INSERT INTO merchant_subject_lookup_policy(policy_slot,key_version,algorithm,created_at)"
            + " VALUES(1,'qa-only-v1','HMAC-SHA-256',UTC_TIMESTAMP(3))");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", 0);
    props.put("spring.flyway.enabled", false);
    props.put("spring.main.banner-mode", "off");
    props.put("spring.jmx.enabled", false);
    props.put("pet.auth.c.enabled", true);
    props.put("pet.auth.c.redis-host", db.redisHost);
    props.put("pet.auth.c.redis-port", db.redisPort);
    props.put("pet.auth.c.cache-prefix", db.prefix);
    props.put("pet.auth.admin.enabled", true);
    props.put("pet.auth.admin.migration-enabled", false);
    props.put("pet.auth.admin.origin", ORIGIN);
    props.put("pet.auth.admin.redis-host", db.redisHost);
    props.put("pet.auth.admin.redis-port", db.redisPort);
    props.put("pet.auth.admin.cache-prefix", db.name + "_admin:");
    props.put("pet.auth.admin.key-id", "qa-key");
    props.put("pet.auth.admin.mac-key-base64", Base64.getEncoder().encodeToString(adminMac));
    props.put("pet.auth.admin.encryption-key-base64", Base64.getEncoder().encodeToString(adminAes));
    props.put("pet.auth.admin.audit-path", db.directory.resolve("sch-audit.bin"));
    props.put("pet.merchant.application.open-cities[0].code", "chengdu");
    props.put("pet.merchant.application.open-cities[0].name", "成都");
    props.put("pet.merchant.application.enabled", true);
    props.put("pet.outbox.enabled", true);
    props.put("pet.service.query.enabled", true);
    props.put("pet.service.command.enabled", true);
    props.put("pet.schedule.query.enabled", true);
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  ctx -> {
                    var beans = (GenericApplicationContext) ctx;
                    beans.registerBean(
                        "schDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("schIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "schWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "schProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "schPrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(privateAssets::get)
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "schMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () -> (city, address, lng, lat) -> "chengdu".equals(city));
                    beans.registerBean(
                        "schSubjects",
                        SubjectCredentialPort.class,
                        () ->
                            new MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63)));
                    beans.registerBean(
                        "schCoverAssets",
                        ServiceCoverAssetPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(
                                        id -> {
                                          ServiceCoverAssetPort.CoverAssetFact fact =
                                              coverAssets.get(id);
                                          return fact != null && fact.ownerUserId().equals(owner)
                                              ? fact
                                              : null;
                                        })
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "schCoverUrls",
                        ServiceCoverUrlPort.class,
                        () ->
                            assetId ->
                                new ServiceCoverUrlPort.CoverUrl(
                                    assetId,
                                    "https://cover.example.invalid/signed/" + assetId,
                                    java.time.Instant.now().getEpochSecond() + 3600));
                  })
              .run(
                  props.entrySet().stream()
                      .map(e -> "--" + e.getKey() + "=" + e.getValue())
                      .toArray(String[]::new));
      origin = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      context
          .getBean(AdminAuthService.class)
          .bootstrap(
              "qa-reviewer", "QA Reviewer", PASSWORD.toCharArray(),
              "Isolated schedule availability acceptance");
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
  void availabilityAggregationVisibilityAndFailClosedFollowApprovedContract() throws Exception {
    Map<String, Object> owner = consumerLogin("sch-owner", "13800007751");
    String token = str(owner, "accessToken");
    String ownerId = str(owner, "userId");
    for (long id = 501; id <= 504; id++)
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("sch-asset-" + id), "image/jpeg", 512, "READY"));
    coverAssets.put(
        COVER_ASSET,
        new ServiceCoverAssetPort.CoverAssetFact(
            COVER_ASSET, ownerId, "READY", "image/jpeg", 2048));
    long merchantId = Long.parseLong(approveApplication(token, ownerId));
    long storeId =
        db.jdbc.queryForObject(
            "SELECT id FROM merchant_store WHERE merchant_id=?", Long.class, merchantId);
    String store = String.valueOf(storeId);
    long categoryId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO service_category(id,category_name,created_at,updated_at)"
            + " VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        categoryId, "洗护美容");

    // Services through the REAL writer chain: create -> submit -> admin APPROVE -> ACTIVE.
    String grooming = createActiveService(token, store, String.valueOf(categoryId), "IN_STORE", 45);
    String pickup =
        createActiveService(
            token, store, String.valueOf(categoryId), "PICKUP_DELIVERY", 120);
    seedStaffFacts(merchantId, storeId, grooming); // pickup has no capability: qualified count 0

    // SCH-D3: no schedule writer exists, windows and reservations are SQL-seeded. Stored as UTC
    // DATETIME(3) (supplement 23 §2); the HTTP projection renders Asia/Shanghai +08:00.
    seedWindow(merchantId, storeId, grooming, "2026-10-12 01:15:00", "2026-10-12 10:10:00", 5, "OPEN");
    seedWindow(merchantId, storeId, grooming, "2026-10-13 02:00:00", "2026-10-13 03:00:00", 1, "OPEN");
    seedWindow(merchantId, storeId, grooming, "2026-10-13 06:00:00", "2026-10-13 07:00:00", 2, "CLOSED");
    // Cross-day boarding window crossing the query-range upper boundary: returned whole.
    seedWindow(merchantId, storeId, grooming, "2026-10-13 14:00:00", "2026-10-13 22:00:00", 4, "OPEN");
    // Occupancy reads the schedule_reservation authority: TEMP_LOCKED counts, RELEASED does not.
    seedReservation(storeId, grooming, "2026-10-12 02:00:00", "2026-10-12 03:00:00", "TEMP_LOCKED");
    seedReservation(storeId, grooming, "2026-10-12 02:30:00", "2026-10-12 03:30:00", "RELEASED");
    // Timezone boundary: 20:00-23:30 +08:00 on 2030-01-15 = 12:00-15:30 UTC the same day.
    seedWindow(merchantId, storeId, grooming, "2030-01-15 12:00:00", "2030-01-15 15:30:00", 5, "OPEN");
    // Pickup windows: a "return" candidate starting only 30 minutes after the pickup window -
    // the 120-minute rule is NOT applied on the query side (SCH-D4, SCH-003 owns the check).
    seedWindow(merchantId, storeId, pickup, "2026-10-12 01:00:00", "2026-10-12 02:30:00", 5, "OPEN");
    seedWindow(merchantId, storeId, pickup, "2026-10-12 01:30:00", "2026-10-12 03:00:00", 5, "OPEN");
    // Now-relative windows for the time-boundary assertions.
    seedWindow(
        merchantId, storeId, grooming, utcMinusMinutes(90), utcPlusMinutes(90), 5, "OPEN");
    seedWindow(merchantId, storeId, grooming, utcMinusMinutes(240), utcMinusMinutes(180), 5, "OPEN");

    String availability = "/api/v1/c/services/" + grooming + "/availability";

    // ---- W2-SCH-001/006: aggregation over real MER/SCH SQL facts (three qualified staff).
    Reply october =
        send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-13", null, bearer(token));
    assertEquals(200, october.status(), october.redacted());
    List<Map<String, Object>> items = list(october.data().get("items"));
    assertEquals(3, items.size(), "OPEN windows only: CLOSED excluded; cross-day whole");
    Map<String, Object> first = items.get(0);
    assertEquals("2026-10-12T09:15:00+08:00", first.get("start"), "minute level, business zone");
    assertEquals("2026-10-12T18:10:00+08:00", first.get("end"));
    assertEquals(3, ((Number) first.get("effectiveCapacity")).intValue(), "min(5, staff 3)");
    assertEquals(1, ((Number) first.get("occupiedCount")).intValue(), "TEMP_LOCKED overlap only");
    assertEquals(2, ((Number) first.get("remainingCapacity")).intValue());
    assertEquals(true, first.get("available"));
    assertEquals(WINDOW_KEYS, first.keySet(), "no windowId/version/status/configured/staff keys");
    Map<String, Object> second = items.get(1);
    assertEquals("2026-10-13T10:00:00+08:00", second.get("start"));
    assertEquals(1, ((Number) second.get("effectiveCapacity")).intValue(), "min(1, staff 3)");
    Map<String, Object> crossDay = items.get(2);
    assertEquals("2026-10-13T22:00:00+08:00", crossDay.get("start"), "starts inside the range");
    assertEquals("2026-10-14T06:00:00+08:00", crossDay.get("end"), "ends outside, still whole");
    assertTrue(
        String.valueOf(items.get(0).get("start")).compareTo(String.valueOf(items.get(1).get("start"))) < 0
            && String.valueOf(items.get(1).get("start")).compareTo(String.valueOf(items.get(2).get("start"))) < 0,
        "start-ascending");

    // ---- W2-SCH-004: pickup projection - no window kind, no 120-minute filtering here.
    Reply pickupReply =
        send(
            "GET",
            "/api/v1/c/services/" + pickup + "/availability?storeId=" + store
                + "&startDate=2026-10-12&endDate=2026-10-12",
            null,
            bearer(token));
    assertEquals(200, pickupReply.status(), pickupReply.redacted());
    List<Map<String, Object>> pickupItems = list(pickupReply.data().get("items"));
    assertEquals(2, pickupItems.size(), "both windows return, 120min rule is not query-side");
    assertEquals(WINDOW_KEYS, pickupItems.get(0).keySet());
    assertEquals(
        0, ((Number) pickupItems.get(0).get("effectiveCapacity")).intValue(), "min(5, staff 0)");
    assertEquals(false, pickupItems.get(0).get("available"), "no capacity -> not available");
    assertFalse(pickupReply.data().containsKey("fulfillmentType"));

    // ---- W2-SCH-005: time boundaries and the Asia/Shanghai day split (16:00 UTC).
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    Reply todayReply =
        send(
            "GET",
            availability + "?storeId=" + store + "&startDate=" + today + "&endDate=" + today,
            null,
            bearer(token));
    assertEquals(200, todayReply.status(), todayReply.redacted());
    List<Map<String, Object>> todayItems = list(todayReply.data().get("items"));
    assertEquals(1, todayItems.size(), "finished window filtered; in-progress stays");
    Map<String, Object> progress = todayItems.get(0);
    assertEquals(false, progress.get("available"), "in-progress window is not bookable");
    assertEquals(3, ((Number) progress.get("effectiveCapacity")).intValue());
    Reply tzInside =
        send(
            "GET",
            availability + "?storeId=" + store + "&startDate=2030-01-15&endDate=2030-01-15",
            null,
            bearer(token));
    assertEquals(1, list(tzInside.data().get("items")).size(), "20:00-23:30 +08 belongs to its day");
    Reply tzNextDay =
        send(
            "GET",
            availability + "?storeId=" + store + "&startDate=2030-01-16&endDate=2030-01-16",
            null,
            bearer(token));
    assertEquals(
        0,
        list(tzNextDay.data().get("items")).size(),
        "23:30 +08 end precedes the next Shanghai midnight (16:00 UTC)");

    // ---- W2-SCH-002: empty range stays a 200 empty page, never 404 or 503.
    Reply emptyRange =
        send(
            "GET",
            availability + "?storeId=" + store + "&startDate=2030-05-01&endDate=2030-05-02",
            null,
            bearer(token));
    assertEquals(200, emptyRange.status(), emptyRange.redacted());
    assertTrue(((List<?>) emptyRange.data().get("items")).isEmpty());

    // ---- W2-SCH-002: visibility negatives answer 404 SERVICE_NOT_FOUND, indistinguishable.
    assertHidden(
        send(
            "GET",
            "/api/v1/c/services/"
                + (Long.parseLong(grooming) + 999_999)
                + "/availability?storeId="
                + store
                + "&startDate=2026-10-12&endDate=2026-10-12",
            null,
            bearer(token)),
        "unknown service id");
    db.jdbc.update(
        "UPDATE service_item SET status='OFFLINE' WHERE id=?", Long.parseLong(grooming));
    assertHidden(send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12", null, bearer(token)));
    db.jdbc.update("UPDATE service_item SET status='ACTIVE' WHERE id=?", Long.parseLong(grooming));
    db.jdbc.update("UPDATE merchant SET status='OFFLINE' WHERE id=?", merchantId);
    assertHidden(send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12", null, bearer(token)));
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);
    // Unknown merchant status: fail-closed 503, never conflated with the 404 above.
    db.jdbc.update("UPDATE merchant SET status='CORRUPTED' WHERE id=?", merchantId);
    assertUnavailable(send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12", null, bearer(token)));
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);
    assertEquals(
        200,
        send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12", null, bearer(token))
            .status(),
        "restored facts are visible again");
    long seededStaff = db.jdbc.queryForObject(
        "SELECT id FROM merchant_staff WHERE store_id=? ORDER BY id LIMIT 1", Long.class, storeId);
    db.jdbc.update("UPDATE merchant_staff SET employment_status='UNKNOWN' WHERE id=?", seededStaff);
    assertUnavailable(send("GET", availability + "?storeId=" + store
        + "&startDate=2026-10-12&endDate=2026-10-12", null, bearer(token)));
    db.jdbc.update("UPDATE merchant_staff SET employment_status='ACTIVE' WHERE id=?", seededStaff);

    // ---- W2-SCH-007: defensive missing-provider branch remains fail-closed, while the enabled
    // boot assembly uses the real SQL-backed provider and answers the same visible window.
    ScheduleQueryApiImpl missingProviderAssembly =
        new ScheduleQueryApiImpl(db.source, context.getBean(ServiceQueryApiImpl.class), null);
    QueryContext ctx = new QueryContext("sch-test", OperatorType.USER, ownerId);
    ApiException closed =
        assertThrows(
            ApiException.class,
            () ->
                missingProviderAssembly.queryAvailability(
                    new AvailabilityQuery(
                        grooming, store, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), ctx)));
    assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", closed.code(), "fail closed, no placeholder");
    ApiException invisible =
        assertThrows(
            ApiException.class,
            () ->
                missingProviderAssembly.queryAvailability(
                    new AvailabilityQuery(
                        String.valueOf(db.ids.nextId()),
                        store,
                        LocalDate.of(2026, 10, 12),
                        LocalDate.of(2026, 10, 12),
                        ctx)));
    assertEquals("SERVICE_NOT_FOUND", invisible.code(), "visibility precedes the staff gate");
    // The boot context with the provider answers 200 for the same window.
    assertEquals(
        200,
        send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12", null, bearer(token))
            .status());

    // ---- W2-SCH-003: arguments and session. Login is mandatory (SCH-D1, outside STR-D8).
    assertEquals(
        401,
        send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12", null, Map.of())
            .status(),
        "anonymous stays 401 on the availability route");
    assertEquals(
        401,
        send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12", null, bearer("invalid-token"))
            .status());
    assertBad(
        send("GET", "/api/v1/c/services/not-a-number/availability?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12", null, bearer(token)));
    assertBad(
        send("GET", "/api/v1/c/services/" + grooming + "/availability?startDate=2026-10-12&endDate=2026-10-12", null, bearer(token)),
        "missing storeId");
    assertBad(
        send("GET", availability + "?storeId=" + store + "&startDate=2026/10/12&endDate=2026-10-13", null, bearer(token)));
    assertBad(
        send("GET", availability + "?storeId=" + store + "&startDate=2026-10-13&endDate=2026-10-12", null, bearer(token)));
    assertBad(
        send("GET", availability + "?storeId=" + store + "&startDate=2030-01-01&endDate=2030-02-01", null, bearer(token)),
        "32-day span");
    assertBad(
        send("GET", availability + "?storeId=" + store + "&startDate=2026-10-12&endDate=2026-10-12&extra=1", null, bearer(token)));
    assertEquals(
        200,
        send("GET", availability + "?storeId=" + store + "&startDate=2030-01-01&endDate=2030-01-31", null, bearer(token))
            .status(),
        "exactly 31 days is accepted");
    // SCH-D9: store mismatch is 404, indistinguishable from invisible.
    assertHidden(
        send(
            "GET",
            availability + "?storeId=" + (storeId + 999_999)
                + "&startDate=2026-10-12&endDate=2026-10-12",
            null,
            bearer(token)));
    // Unknown id: 404 with the registry code in the body.
    assertHidden(
        send(
            "GET",
            "/api/v1/c/services/" + (Long.parseLong(grooming) + 999_999)
                + "/availability?storeId="
                + store
                + "&startDate=2026-10-12&endDate=2026-10-12",
            null,
            bearer(token)));

    // Internal bookability keeps the same store-mismatch semantics the availability route uses:
    // the service-domain checkBookable itself throws SERVICE_NOT_FOUND (the route projects 404).
    ServiceQueryApiImpl serviceApi = context.getBean(ServiceQueryApiImpl.class);
    ApiException mismatch =
        assertThrows(
            ApiException.class,
            () ->
                serviceApi.checkBookable(
                    new ServiceBookabilityQuery(grooming, String.valueOf(storeId + 1), ctx)));
    assertEquals("SERVICE_NOT_FOUND", mismatch.code());
  }

  /** Real writer chain: draft -> submit -> admin APPROVE -> ACTIVE, returns the serviceId. */
  private String createActiveService(
      String token, String store, String category, String fulfillment, int minutes)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("merchantId", merchantOf(store));
    body.put("storeId", store);
    body.put("serviceName", fulfillment + "-排期验收-" + minutes);
    body.put("categoryId", category);
    body.put("fulfillmentType", fulfillment);
    body.put("price", "128.00");
    body.put("durationMinutes", minutes);
    body.put("coverAssetId", COVER_ASSET);
    body.put("applicablePetTypes", List.of("DOG"));
    body.put("description", "排期可用性验收服务");
    Reply created =
        send(
            "POST",
            "/api/v1/merchant/services",
            body,
            bearer(token, UUID.randomUUID().toString()));
    assertEquals(201, created.status(), created.redacted());
    String serviceId = str(created.data(), "serviceId");
    assertEquals("0", str(created.data(), "version"));
    Reply submitted =
        send(
            "POST",
            "/api/v1/merchant/services/" + serviceId + "/online",
            Map.of("expectedVersion", "0"),
            bearer(token, UUID.randomUUID().toString()));
    assertEquals(200, submitted.status(), submitted.redacted());
    assertEquals("REVIEWING", submitted.data().get("status"));
    Reply approved =
        send(
            "POST",
            "/api/v1/admin/services/" + serviceId + "/decision",
            Map.of("decisionType", "APPROVE", "expectedVersion", "1"),
            bearer(adminLogin()));
    assertEquals(200, approved.status(), approved.redacted());
    assertEquals("ACTIVE", approved.data().get("status"));
    return serviceId;
  }

  private String merchantOf(String store) {
    return String.valueOf(
        db.jdbc.queryForObject(
            "SELECT merchant_id FROM merchant_store WHERE id=?", Long.class, Long.parseLong(store)));
  }

  /** Explicit personnel SQL seed; it exercises MER's sixth query and SCH's real intersection. */
  private void seedStaffFacts(long merchantId, long storeId, String serviceId) {
    for (int i = 0; i < 3; i++) {
      long staffId = db.ids.nextId();
      db.jdbc.update(
          "INSERT INTO merchant_staff"
              + "(id,merchant_id,store_id,staff_name,employment_status,service_enabled,version,created_at,updated_at)"
              + " VALUES(?,?,?,?,'ACTIVE',1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          staffId, merchantId, storeId, "排期员工" + i);
      db.jdbc.update(
          "INSERT INTO staff_service_capability"
              + "(id,staff_id,service_id,status,created_at,updated_at)"
              + " VALUES(?,?,?,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          db.ids.nextId(), staffId, Long.parseLong(serviceId));
      seedStaffAvailability(storeId, staffId,
          "2026-10-12 00:00:00", "2026-10-14 00:00:00");
      seedStaffAvailability(storeId, staffId,
          "2030-01-15 11:00:00", "2030-01-15 16:00:00");
      seedStaffAvailability(storeId, staffId,
          utcMinusMinutes(100), utcPlusMinutes(100));
    }
  }

  private void seedStaffAvailability(long storeId, long staffId, String startUtc, String endUtc) {
    db.jdbc.update(
        "INSERT INTO staff_availability_window"
            + "(id,store_id,staff_id,start_at,end_at,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,'AVAILABLE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        db.ids.nextId(), storeId, staffId, startUtc, endUtc);
  }

  private long seedWindow(
      long merchantId,
      long storeId,
      String serviceId,
      String startUtc,
      String endUtc,
      int capacity,
      String status) {
    long id = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO"
            + " schedule_availability_window(id,merchant_id,store_id,service_id,start_at,end_at,"
            + " configured_capacity,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        id, merchantId, storeId, Long.parseLong(serviceId), startUtc, endUtc, capacity, status);
    return id;
  }

  private void seedReservation(
      long storeId, String serviceId, String startUtc, String endUtc, String status) {
    db.jdbc.update(
        "INSERT INTO schedule_reservation(id,order_id,merchant_id,store_id,service_id,"
            + " fulfillment_type,start_at,end_at,status,capacity_snapshot,"
            + " qualified_staff_count_snapshot,version,created_at,updated_at)"
            + " VALUES(?,?,(SELECT merchant_id FROM merchant_store WHERE id=?),?,?,?,?,?,?,?,?,0,"
            + " UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        db.ids.nextId(),
        db.ids.nextId(),
        storeId,
        storeId,
        Long.parseLong(serviceId),
        "IN_STORE",
        startUtc,
        endUtc,
        status,
        5,
        3);
  }

  private static String utcMinusMinutes(long minutes) {
    return java.sql.Timestamp.valueOf(
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(minutes))
        .toLocalDateTime()
        .withNano(0)
        .toString()
        .replace('T', ' ');
  }

  private static String utcPlusMinutes(long minutes) {
    return java.sql.Timestamp.valueOf(
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(minutes))
        .toLocalDateTime()
        .withNano(0)
        .toString()
        .replace('T', ' ');
  }

  /** Runs the real application -> review -> APPROVE chain and signs the agreement. */
  private String approveApplication(String token, String ownerId) throws Exception {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("merchantName", "星河排期生活馆");
    draft.put("contactName", "张三");
    draft.put("contactPhone", "13800007751");
    draft.put("email", "sch-owner@example.test");
    draft.put("merchantTypeCode", "PET_LIFE_STORE");
    draft.put("cityCode", "chengdu");
    draft.put("address", "测试服务地址");
    draft.put("longitude", "121.4");
    draft.put("latitude", "31.2");
    draft.put("storePhotoAssetIds", List.of("501"));
    draft.put("businessLicenseAssetId", "502");
    draft.put("idCardFrontAssetId", "503");
    draft.put("idCardBackAssetId", "504");
    Reply created = send("POST", "/api/v1/c/merchant-applications", draft, bearer(token));
    assertEquals(201, created.status(), created.redacted());
    Reply submitted =
        send(
            "POST",
            "/api/v1/c/merchant-applications/" + str(created.data(), "applicationId") + "/submit",
            Map.of(
                "expectedVersion", str(created.data(), "version"),
                "revisionId", str(created.data(), "currentRevisionId")),
            bearer(token));
    assertEquals(200, submitted.status(), submitted.redacted());
    String admin = adminLogin();
    String reviewPath =
        "/api/v1/admin/merchant-applications/" + str(created.data(), "applicationId");
    Reply review = send("GET", reviewPath, null, bearer(admin));
    Reply claimed =
        send(
            "POST",
            reviewPath + "/claim",
            Map.of("expectedTaskVersion", str(map(review.data().get("task")), "version")),
            bearer(admin));
    assertEquals(200, claimed.status(), claimed.redacted());
    Map<String, Object> revision = map(review.data().get("submittedRevision"));
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (Object referenceObject : (List<?>) revision.get("materialReferences")) {
      Map<String, Object> reference = map(referenceObject);
      String type = String.valueOf(reference.get("materialType"));
      boolean license = "BUSINESS_LICENSE".equals(type);
      if (!license && !"ID_CARD_BACK".equals(type)) continue;
      evidence.add(
          Map.of(
              "materialId", String.valueOf(reference.get("materialId")),
              "materialSha256", String.valueOf(reference.get("materialSha256")),
              "credentialType", license ? "CREDIT_CODE" : "IDENTITY_NUMBER",
              "subjectName", license ? "星河排期生活馆" : "张三",
              "identifier", license ? testCreditCode() : testIdentity(),
              "validFrom", "2020-01-01",
              "validityKind", "LONG_TERM"));
    }
    Reply verified =
        send(
            "POST",
            reviewPath + "/manual-verification",
            Map.of(
                "submissionRevisionId", str(revision, "revisionId"),
                "expectedVersion", str(review.data(), "version"),
                "expectedTaskVersion", str(claimed.data(), "version"),
                "evidenceItems", evidence,
                "reason", "已逐项核对当前提交的原件材料",
                "confirmed", true),
            bearer(admin));
    assertEquals(200, verified.status(), verified.redacted());
    Reply approved =
        send(
            "POST",
            reviewPath + "/decision",
            Map.of(
                "decisionType", "APPROVE",
                "submissionRevisionId", str(revision, "revisionId"),
                "expectedVersion", str(verified.data(), "version"),
                "expectedTaskVersion", str(claimed.data(), "version"),
                "confirmed", true),
            bearer(admin));
    assertEquals(200, approved.status(), approved.redacted());
    assertEquals("APPROVED", approved.data().get("status"));
    long merchantId =
        Long.parseLong(String.valueOf(approved.data().get("reservedMerchantId")));
    long agreementId = db.ids.nextId();
    String content = "测试环境商家服务协议", hash = sha(content);
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
        agreementId,
        "sch-http-v1",
        content,
        hash,
        db.jdbc.queryForObject(
            "SELECT id FROM admin_account WHERE account_display='qa-reviewer'", Long.class));
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_current(agreement_key,agreement_version_id,version,updated_at)"
            + " VALUES('MERCHANT',?,0,UTC_TIMESTAMP(3))",
        agreementId);
    Reply signed =
        send(
            "POST",
            "/api/v1/merchant/agreement/consent",
            Map.of(
                "merchantId", String.valueOf(merchantId),
                "agreementVersion", "sch-http-v1",
                "contentSha256", hash,
                "accepted", true),
            bearer(token));
    assertEquals(201, signed.status(), signed.redacted());
    return String.valueOf(merchantId);
  }

  private Map<String, Object> consumerLogin(String openid, String phone) throws Exception {
    Reply attempt =
        send(
            "POST",
            "/api/v1/c/auth/attempts",
            Map.of("purpose", "WECHAT_LOGIN"),
            Map.of("X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(201, attempt.status(), attempt.redacted());
    Reply grant =
        send(
            "POST",
            "/api/v1/c/auth/wechat-login",
            Map.of(
                "attemptId", str(attempt.data(), "attemptId"),
                "wechatCode", "ok:" + openid,
                "phoneCode", "phone:" + phone),
            Map.of(
                "X-Request-Id", UUID.randomUUID().toString(),
                "X-Auth-Attempt", str(attempt.data(), "attemptToken")));
    assertEquals(200, grant.status(), grant.redacted());
    return grant.data();
  }

  private String adminLogin() throws Exception {
    Reply attempt =
        send(
            "POST",
            "/api/v1/admin/auth/attempts",
            Map.of(),
            Map.of("Origin", ORIGIN, "X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(201, attempt.status(), attempt.redacted());
    String attemptId = str(attempt.data(), "attemptId");
    Map<String, String> headers =
        new HashMap<>(
            Map.of(
                "Origin", ORIGIN,
                "Cookie", attempt.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                "X-Auth-Attempt", str(attempt.data(), "attemptToken"),
                "X-Request-Id", UUID.randomUUID().toString()));
    Reply challenge =
        send(
            "POST",
            "/api/v1/admin/auth/captcha/challenges",
            Map.of("attemptId", attemptId),
            headers);
    assertEquals(200, challenge.status(), challenge.redacted());
    db.jdbc.update(
        "UPDATE admin_captcha SET answer_mac=? WHERE id=?",
        AdminSecretCodec.fixed("qa-key", adminMac, adminAes).mac("qa-key", "CAPTCHA", "ABC234"),
        Long.parseLong(str(challenge.data(), "captchaId")));
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply proof =
        send(
            "POST",
            "/api/v1/admin/auth/captcha/verify",
            Map.of(
                "attemptId", attemptId,
                "captchaId", str(challenge.data(), "captchaId"),
                "answer", "ABC234"),
            headers);
    assertEquals(200, proof.status(), proof.redacted());
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply grant =
        send(
            "POST",
            "/api/v1/admin/auth/login",
            Map.of(
                "attemptId", attemptId,
                "account", "qa-reviewer",
                "password", PASSWORD,
                "captchaProof", str(proof.data(), "captchaProof")),
            headers);
    assertEquals(200, grant.status(), grant.redacted());
    return str(grant.data(), "accessToken");
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
                        : HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Map<?, ?> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String);
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), (Map<String, Object>) envelope, response.headers());
  }

  private static Map<String, String> bearer(String token) {
    return Map.of("Authorization", "Bearer " + token);
  }

  private static Map<String, String> bearer(String token, String requestId) {
    return Map.of("Authorization", "Bearer " + token, "X-Request-Id", requestId);
  }

  /** SCH-D1b/SCH-D9: confirmed missing, ineligible or store-mismatched -> 404 SERVICE_NOT_FOUND. */
  private static void assertHidden(Reply reply) {
    assertHidden(reply, null);
  }

  private static void assertHidden(Reply reply, String hint) {
    assertEquals(404, reply.status(), (hint == null ? "" : hint + ": ") + reply.redacted());
    assertEquals(
        "SERVICE_NOT_FOUND",
        reply.envelope().get("code"),
        "hidden/missing must answer the registry-12 service code, indistinguishably");
  }

  /** Fail-closed facts failure -> 503 COMMON_DEPENDENCY_UNAVAILABLE, never conflated with 404. */
  private static void assertUnavailable(Reply reply) {
    assertEquals(503, reply.status(), reply.redacted());
    assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", reply.envelope().get("code"));
  }

  private static void assertBad(Reply reply) {
    assertBad(reply, null);
  }

  private static void assertBad(Reply reply, String hint) {
    assertEquals(400, reply.status(), (hint == null ? "" : hint + ": ") + reply.redacted());
    assertEquals("COMMON_INVALID_ARGUMENT", reply.envelope().get("code"));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static Map<String, Object> map(Object value) {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) value;
    return result;
  }

  private static String str(Map<String, Object> value, String field) {
    return String.valueOf(value.get(field));
  }

  private static String testIdentity() {
    String body = "51010419900101001";
    int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += (body.charAt(i) - '0') * weights[i];
    return body + "10X98765432".charAt(sum % 11);
  }

  private static String testCreditCode() {
    String body = "91510100MA0000000", alphabet = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += alphabet.indexOf(body.charAt(i)) * weights[i];
    return body + alphabet.charAt((31 - sum % 31) % 31);
  }

  private static byte[] key(int seed) {
    byte[] value = new byte[32];
    new SecureRandom(String.valueOf(seed).getBytes(StandardCharsets.UTF_8)).nextBytes(value);
    return value;
  }

  private static String sha(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private record Reply(int status, Map<String, Object> envelope, java.net.http.HttpHeaders headers) {
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    String redacted() {
      return "Reply[status=" + status + "]";
    }
  }
}
