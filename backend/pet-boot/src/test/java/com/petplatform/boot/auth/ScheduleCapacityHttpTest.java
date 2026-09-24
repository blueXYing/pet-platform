package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.ApiException;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantStoreStaffFactsApi;
import com.petplatform.merchant.api.query.StoreStaffFactsQuery;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.schedule.api.query.AvailabilityQuery;
import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import com.petplatform.schedule.biz.infrastructure.provider.ScheduleQualifiedStaffFactsProvider;
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
 * Independent SCH-002 HTTP integration: real MER staff facts and SCH capability/availability
 * reads drive capacity. Merchant approval, signing and service publication use the real HTTP
 * writers. Staff, capability, availability, schedule windows and reservations are SQL-seeded
 * because their writers are outside SCH-002. Each test has a random isolated MySQL database.
 */
class ScheduleCapacityHttpTest {
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
  void realStaffIntersectionCoversWholeWindowAndFailsClosedOnDamagedFacts() throws Exception {
    Map<String, Object> owner = consumerLogin("capacity-owner", "13800007751");
    String token = str(owner, "accessToken");
    String ownerId = str(owner, "userId");
    for (long asset = 501; asset <= 504; asset++) {
      privateAssets.put(
          asset,
          new PrivateAssetQueryPort.PrivateAssetRef(
              asset, Long.parseLong(ownerId), sha("sch-asset-" + asset), "image/jpeg", 512, "READY"));
    }
    coverAssets.put(
        COVER_ASSET,
        new ServiceCoverAssetPort.CoverAssetFact(
            COVER_ASSET, ownerId, "READY", "image/jpeg", 2048));
    long merchantId = Long.parseLong(approveApplication(token, ownerId));
    long storeId =
        db.jdbc.queryForObject(
            "SELECT id FROM merchant_store WHERE merchant_id=?", Long.class, merchantId);
    long otherStoreId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        otherStoreId, merchantId, "QA second store", "QA address");
    long category = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO service_category(id,category_name,created_at,updated_at)"
            + " VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        category, "SCH002-QA-" + UUID.randomUUID());
    String service =
        createActiveService(token, String.valueOf(storeId), String.valueOf(category), "IN_STORE", 45);
    String otherService =
        createActiveService(
            token, String.valueOf(storeId), String.valueOf(category), "PICKUP_DELIVERY", 120);

    LocalDate day = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(10);
    String start = utc(day, 9, 0), split = utc(day, 10, 0), end = utc(day, 11, 0);
    long window = seedWindow(merchantId, storeId, service, start, end, 5, "OPEN");
    String path = availability(service, storeId, day);

    // Three true positives: adjacent segments, one exact segment, and one wider segment.
    long adjacent = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedCapability(adjacent, service, "ENABLED");
    seedAvailability(storeId, adjacent, start, split, "AVAILABLE");
    long adjacentSecond = seedAvailability(storeId, adjacent, split, end, "AVAILABLE");
    long exact = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedCapability(exact, service, "ENABLED");
    long exactAvailability = seedAvailability(storeId, exact, start, end, "AVAILABLE");
    long wider = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedCapability(wider, service, "ENABLED");
    seedAvailability(storeId, wider, utc(day, 8, 0), utc(day, 12, 0), "AVAILABLE");

    // Every false positive has the other qualifying facts, isolating its failed predicate.
    long inactive = seedStaff(merchantId, storeId, "INACTIVE", 1);
    seedCapability(inactive, service, "ENABLED");
    seedAvailability(storeId, inactive, start, end, "AVAILABLE");
    long offDuty = seedStaff(merchantId, storeId, "ACTIVE", 0);
    seedCapability(offDuty, service, "ENABLED");
    seedAvailability(storeId, offDuty, start, end, "AVAILABLE");
    long withoutCapability = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedAvailability(storeId, withoutCapability, start, end, "AVAILABLE");
    long closedOnly = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedCapability(closedOnly, service, "ENABLED");
    seedAvailability(storeId, closedOnly, start, end, "CLOSED");
    long unscheduled = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedCapability(unscheduled, service, "ENABLED");
    long partialCoverage = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedCapability(partialCoverage, service, "ENABLED");
    seedAvailability(storeId, partialCoverage, utc(day, 9, 30), end, "AVAILABLE");
    long otherServiceOnly = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedCapability(otherServiceOnly, otherService, "ENABLED");
    seedAvailability(storeId, otherServiceOnly, start, end, "AVAILABLE");
    long otherStoreStaff = seedStaff(merchantId, otherStoreId, "ACTIVE", 1);
    long otherStoreCapability = seedCapability(otherStoreStaff, service, "ENABLED");
    long otherStoreAvailability =
        seedAvailability(otherStoreId, otherStoreStaff, start, end, "AVAILABLE");
    long wrongStoreAvailability = seedStaff(merchantId, storeId, "ACTIVE", 1);
    seedCapability(wrongStoreAvailability, service, "ENABLED");
    seedAvailability(otherStoreId, wrongStoreAvailability, start, end, "AVAILABLE");
    seedCapability(db.ids.nextId(), service, "ENABLED"); // legal dangling row cannot add a person

    MerchantStoreStaffFactsApi merchantStaff = context.getBean(MerchantStoreStaffFactsApi.class);
    var staffFacts =
        merchantStaff.listActiveStoreStaffFacts(
            new StoreStaffFactsQuery(
                String.valueOf(storeId), new QueryContext("sch002-qa", OperatorType.SYSTEM, null)));
    assertEquals(String.valueOf(storeId), staffFacts.storeId());
    assertEquals(9, staffFacts.activeStaffIds().size(), "MER returns staff facts, not SCH capacity");
    assertEquals(
        staffFacts.activeStaffIds().stream()
            .sorted(java.util.Comparator.comparingLong(Long::parseLong))
            .toList(),
        staffFacts.activeStaffIds(),
        "MER staff IDs are sorted numerically");
    assertThrows(
        UnsupportedOperationException.class,
        () -> staffFacts.activeStaffIds().add(String.valueOf(db.ids.nextId())));
    ApiException missingStore =
        assertThrows(
            ApiException.class,
            () ->
                merchantStaff.listActiveStoreStaffFacts(
                    new StoreStaffFactsQuery(
                        String.valueOf(db.ids.nextId()),
                        new QueryContext("sch002-qa", OperatorType.SYSTEM, null))));
    assertEquals("COMMON_NOT_FOUND", missingStore.code());

    assertCapacity(send("GET", path, null, bearer(token)), 3, 0, 3, true);
    db.jdbc.update(
        "UPDATE staff_availability_window SET start_at=? WHERE id=?",
        utc(day, 10, 1), adjacentSecond);
    assertCapacity(send("GET", path, null, bearer(token)), 2, 0, 2, true);
    db.jdbc.update("UPDATE staff_availability_window SET start_at=? WHERE id=?", split, adjacentSecond);
    seedAvailability(storeId, adjacent, utc(day, 9, 30), utc(day, 10, 30), "AVAILABLE");
    assertCapacity(send("GET", path, null, bearer(token)), 3, 0, 3, true);
    db.jdbc.update(
        "UPDATE staff_availability_window SET status='UNKNOWN' WHERE id=?",
        otherStoreAvailability);
    assertCapacity(send("GET", path, null, bearer(token)), 3, 0, 3, true);
    db.jdbc.update(
        "UPDATE staff_availability_window SET status='AVAILABLE' WHERE id=?",
        otherStoreAvailability);
    db.jdbc.update(
        "UPDATE schedule_availability_window SET configured_capacity=2 WHERE id=?", window);
    assertCapacity(send("GET", path, null, bearer(token)), 2, 0, 2, true);
    db.jdbc.update(
        "UPDATE schedule_availability_window SET configured_capacity=5 WHERE id=?", window);
    seedReservation(storeId, service, utc(day, 9, 15), utc(day, 9, 45), "TEMP_LOCKED");
    seedReservation(storeId, service, utc(day, 9, 20), utc(day, 9, 50), "RELEASED");
    assertCapacity(send("GET", path, null, bearer(token)), 3, 1, 2, true);

    // The same employee can appear in two services' read projections. This is not a hold-time
    // cross-service allocation or concurrency guarantee; SCH-003 owns that separate protocol.
    seedCapability(adjacent, otherService, "ENABLED");
    seedWindow(merchantId, storeId, otherService, start, end, 1, "OPEN");
    assertCapacity(
        send("GET", availability(otherService, storeId, day), null, bearer(token)),
        1, 0, 1, true);

    db.jdbc.update(
        "UPDATE merchant_staff SET employment_status='UNKNOWN' WHERE id=?", inactive);
    assertUnavailable(send("GET", path, null, bearer(token)));
    db.jdbc.update("UPDATE merchant_staff SET employment_status='INACTIVE' WHERE id=?", inactive);
    db.jdbc.update("UPDATE merchant_staff SET service_enabled=2 WHERE id=?", offDuty);
    assertUnavailable(send("GET", path, null, bearer(token)));
    db.jdbc.update("UPDATE merchant_staff SET service_enabled=0 WHERE id=?", offDuty);
    db.jdbc.update(
        "UPDATE merchant_staff SET merchant_id=? WHERE id=?", db.ids.nextId(), withoutCapability);
    assertUnavailable(send("GET", path, null, bearer(token)));
    db.jdbc.update(
        "UPDATE merchant_staff SET merchant_id=? WHERE id=?", merchantId, withoutCapability);
    db.jdbc.update(
        "UPDATE staff_service_capability SET status='UNKNOWN' WHERE id=?", otherStoreCapability);
    assertUnavailable(send("GET", path, null, bearer(token)));
    db.jdbc.update(
        "UPDATE staff_service_capability SET status='DISABLED' WHERE id=?", otherStoreCapability);
    assertUnavailable(send("GET", path, null, bearer(token)));
    db.jdbc.update(
        "UPDATE staff_service_capability SET status='ENABLED' WHERE id=?", otherStoreCapability);
    db.jdbc.update(
        "UPDATE staff_availability_window SET status='UNKNOWN' WHERE id=?", exactAvailability);
    assertUnavailable(send("GET", path, null, bearer(token)));
    db.jdbc.update(
        "UPDATE staff_availability_window SET status='AVAILABLE' WHERE id=?", exactAvailability);
    assertCapacity(send("GET", path, null, bearer(token)), 3, 1, 2, true);
    db.jdbc.update(
        "UPDATE staff_availability_window SET end_at=? WHERE id=?",
        utc(day, 8, 59), exactAvailability);
    assertUnavailable(send("GET", path, null, bearer(token)));
    db.jdbc.update(
        "UPDATE staff_availability_window SET end_at=? WHERE id=?", end, exactAvailability);

    // Confirmed empty authority is a normal 200/zero; deleting the authority is a 503.
    db.jdbc.update("DELETE FROM staff_service_capability WHERE service_id=?", Long.parseLong(service));
    assertCapacity(send("GET", path, null, bearer(token)), 0, 1, 0, false);
    assertHidden(
        send(
            "GET",
            availability(service, otherStoreId, day),
            null,
            bearer(token)),
        "service/store mismatch must be 404 before staff facts");
    var disappearedStore =
        new ScheduleQualifiedStaffFactsProvider(
            query -> {
              throw new ApiException("COMMON_NOT_FOUND", "store disappeared between snapshots");
            },
            db.source);
    ApiException projectedMissingStore =
        assertThrows(
            ApiException.class,
            () ->
                disappearedStore.countQualifiedAvailableStaff(
                    String.valueOf(storeId),
                    service,
                    day.atTime(9, 0).atOffset(java.time.ZoneOffset.ofHours(8)),
                    day.atTime(11, 0).atOffset(java.time.ZoneOffset.ofHours(8))));
    assertEquals("SERVICE_NOT_FOUND", projectedMissingStore.code());
    ScheduleQueryApiImpl absent =
        new ScheduleQueryApiImpl(db.source, context.getBean(ServiceQueryApiImpl.class), null);
    ApiException missing =
        assertThrows(
            ApiException.class,
            () ->
                absent.queryAvailability(
                    new AvailabilityQuery(
                        service,
                        String.valueOf(storeId),
                        day,
                        day,
                        new QueryContext("sch002-qa", OperatorType.USER, ownerId))));
    assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", missing.code());
    seedCapability(exact, service, "ENABLED");
    assertCapacity(send("GET", path, null, bearer(token)), 1, 1, 0, false);
    db.jdbc.execute(
        "RENAME TABLE staff_service_capability TO staff_service_capability_qa_hidden");
    try {
      assertUnavailable(send("GET", path, null, bearer(token)));
    } finally {
      db.jdbc.execute(
          "RENAME TABLE staff_service_capability_qa_hidden TO staff_service_capability");
    }
    assertCapacity(send("GET", path, null, bearer(token)), 1, 1, 0, false);
  }

  private String availability(String service, long store, LocalDate day) {
    return "/api/v1/c/services/" + service + "/availability?storeId=" + store
        + "&startDate=" + day + "&endDate=" + day;
  }

  private static String utc(LocalDate day, int hour, int minute) {
    return day.atTime(hour, minute)
        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
        .withZoneSameInstant(java.time.ZoneOffset.UTC)
        .toLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
  }

  private long seedStaff(long merchantId, long storeId, String status, int enabled) {
    long id = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,employment_status,"
            + "service_enabled,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        id, merchantId, storeId, "QA-" + id, status, enabled);
    return id;
  }

  private long seedCapability(long staffId, String service, String status) {
    long id = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO staff_service_capability(id,staff_id,service_id,status,created_at,updated_at)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        id, staffId, Long.parseLong(service), status);
    return id;
  }

  private long seedAvailability(
      long storeId, long staffId, String start, String end, String status) {
    long id = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,"
            + "version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        id, storeId, staffId, start, end, status);
    return id;
  }

  private static void assertCapacity(
      Reply reply, int effective, int occupied, int remaining, boolean available) {
    assertEquals(200, reply.status(), reply.redacted());
    List<Map<String, Object>> items = list(reply.data().get("items"));
    assertEquals(1, items.size(), "one seeded OPEN window");
    Map<String, Object> window = items.getFirst();
    assertEquals(effective, ((Number) window.get("effectiveCapacity")).intValue());
    assertEquals(occupied, ((Number) window.get("occupiedCount")).intValue());
    assertEquals(remaining, ((Number) window.get("remainingCapacity")).intValue());
    assertEquals(available, window.get("available"));
    assertEquals(WINDOW_KEYS, window.keySet());
  }
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
