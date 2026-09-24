package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Independent MER staff acceptance over real HTTP sessions, an approved application/agreement,
 * MySQL 8 and Redis. Capability and availability rows are test seeds only; this does not exercise
 * SCH-004 maintenance or SCH-003 hold.
 */
class MerchantStaffAcceptanceHttpTest {
  private static final String ORIGIN = "https://staff-acceptance.example.invalid";
  private static final String PASSWORD = "Example_ONLY_92!";
  private static final String COVER_ASSET = "590000000000000501";
  private static final String STAFF_PHONE = "13800007888";
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
              "33-Service-Write-Schema-v0.1.sql",
              "35-Merchant-Staff-Audit-Schema-v0.1.sql")) {
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
    props.put("pet.auth.admin.audit-path", db.directory.resolve("staff-audit.bin"));
    props.put("pet.merchant.application.open-cities[0].code", "chengdu");
    props.put("pet.merchant.application.open-cities[0].name", "成都");
    props.put("pet.merchant.application.enabled", true);
    props.put("pet.merchant.staff.enabled", true);
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
                    beans.registerBean("staffAcceptanceDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("staffAcceptanceIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "staffAcceptanceWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "staffAcceptanceProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "staffAcceptancePrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream().map(privateAssets::get).filter(Objects::nonNull).toList());
                    beans.registerBean(
                        "staffAcceptanceMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () -> (city, address, lng, lat) -> "chengdu".equals(city));
                    beans.registerBean(
                        "staffAcceptanceSubjects",
                        SubjectCredentialPort.class,
                        () -> new MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63)));
                    beans.registerBean(
                        "staffAcceptanceCoverAssets",
                        ServiceCoverAssetPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(coverAssets::get)
                                    .filter(Objects::nonNull)
                                    .filter(fact -> fact.ownerUserId().equals(owner))
                                    .toList());
                    beans.registerBean(
                        "staffAcceptanceCoverUrls",
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
          .bootstrap("qa-reviewer", "QA Reviewer", PASSWORD.toCharArray(), "Isolated staff acceptance");
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
  void ownerStaffLifecycleIsolationIdempotencyAndScheduleRead() throws Exception {
    Map<String, Object> owner = consumerLogin("staff-owner", "13800007781");
    String token = str(owner, "accessToken"), ownerId = str(owner, "userId");
    addPrivateAssets(ownerId, 501);
    coverAssets.put(
        COVER_ASSET,
        new ServiceCoverAssetPort.CoverAssetFact(COVER_ASSET, ownerId, "READY", "image/jpeg", 2048));
    String merchantId = approveApplication(token, 501, "星河员工验收店", 0);
    String storeId =
        String.valueOf(
            db.jdbc.queryForObject(
                "SELECT id FROM merchant_store WHERE merchant_id=?", Long.class, Long.parseLong(merchantId)));
    String listPath = "/api/v1/merchant/staff?merchantId=" + merchantId + "&storeId=" + storeId;
    String createPath = "/api/v1/merchant/staff";
    assertError(send("GET", listPath, null, Map.of()), 401, "COMMON_UNAUTHORIZED");
    assertEquals(200, send("GET", listPath, null, bearer(token)).status());
    Map<String, Object> firstInput = staffInput(merchantId, storeId, "阿橙", STAFF_PHONE, "ACTIVE", false);
    assertError(
        send("POST", createPath, firstInput, bearer(token, UUID.randomUUID().toString())),
        409,
        "COMMON_CONFLICT");
    sign(merchantId, token);
    assertEquals("ALLOWED", admission(merchantId, storeId, token).data().get("admission"));
    long sameMerchantOtherStore = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        sameMerchantOtherStore, Long.parseLong(merchantId), "同商家第二门店", "测试服务地址二");

    String createKey = UUID.randomUUID().toString();
    Reply created = send("POST", createPath, firstInput, bearer(token, createKey));
    assertEquals(201, created.status(), created.redacted());
    assertEquals("138****7888", created.data().get("phoneMasked"));
    assertFalse(created.raw().body().contains(STAFF_PHONE));
    assertStaffStrings(created.data(), merchantId, storeId);
    String staffId = str(created.data(), "staffId");
    assertTrue(Long.parseLong(staffId) > 9_007_199_254_740_991L);
    assertEquals("0", str(created.data(), "version"));
    assertEquals(false, created.data().get("serviceEnabled"));
    Reply replayCreate = send("POST", createPath, firstInput, bearer(token, createKey));
    assertEquals(200, replayCreate.status(), replayCreate.redacted());
    assertEquals(staffId, replayCreate.data().get("staffId"));
    assertEquals("0", replayCreate.data().get("version"));
    assertEquals(1L, count("SELECT COUNT(*) FROM merchant_staff WHERE id=?", Long.parseLong(staffId)));
    assertEquals(1L, count("SELECT COUNT(*) FROM merchant_staff_audit WHERE staff_id=?", Long.parseLong(staffId)));

    Map<String, Object> changedCreate = new LinkedHashMap<>(firstInput);
    changedCreate.put("staffName", "另一名字");
    assertError(
        send("POST", createPath, changedCreate, bearer(token, createKey)),
        409,
        "IDEMPOTENCY_KEY_CONFLICT");
    assertError(
        send("POST", createPath, staffInput(merchantId, storeId, "离岗", null, "INACTIVE", true), bearer(token)),
        400,
        "COMMON_INVALID_ARGUMENT");
    Map<String, Object> unexpected = new LinkedHashMap<>(firstInput);
    unexpected.put("ownerUserId", ownerId);
    assertError(send("POST", createPath, unexpected, bearer(token)), 400, "COMMON_INVALID_ARGUMENT");
    Map<String, Object> coercedBoolean = new LinkedHashMap<>(firstInput);
    coercedBoolean.put("serviceEnabled", "false");
    assertError(send("POST", createPath, coercedBoolean, bearer(token)), 400, "COMMON_INVALID_ARGUMENT");
    Map<String, Object> invalidStatus = new LinkedHashMap<>(firstInput);
    invalidStatus.put("employmentStatus", "LEFT");
    assertError(send("POST", createPath, invalidStatus, bearer(token)), 400, "COMMON_INVALID_ARGUMENT");
    String rawCreate = json.writeValueAsString(firstInput);
    assertError(
        sendRaw(
            "POST",
            createPath,
            rawCreate.replace("\"staffName\":\"阿橙\"", "\"staffName\":\"阿橙\",\"staffName\":\"阿橙\""),
            bearer(token)),
        400,
        "COMMON_INVALID_ARGUMENT");
    assertError(sendRaw("POST", createPath, rawCreate + " {}", bearer(token)), 400, "COMMON_INVALID_ARGUMENT");

    String detail = "/api/v1/merchant/staff/" + staffId + "?merchantId=" + merchantId + "&storeId=" + storeId;
    Reply fetched = send("GET", detail, null, bearer(token));
    assertEquals(200, fetched.status(), fetched.redacted());
    assertEquals("138****7888", fetched.data().get("phoneMasked"));
    assertFalse(fetched.raw().body().contains(STAFF_PHONE));
    assertError(
        send(
            "GET",
            "/api/v1/merchant/staff/0001?merchantId=" + merchantId + "&storeId=" + storeId,
            null,
            bearer(token)),
        400,
        "COMMON_INVALID_ARGUMENT");
    Reply list = send("GET", listPath, null, bearer(token));
    assertEquals(200, list.status(), list.redacted());
    assertEquals(1, ((Number) list.data().get("total")).intValue());
    assertEquals(staffId, str(map(listItems(list).getFirst()), "staffId"));
    assertEquals("no-store", list.raw().headers().firstValue("Cache-Control").orElseThrow());
    Reply otherStoreList =
        send(
            "GET",
            "/api/v1/merchant/staff?merchantId=" + merchantId + "&storeId=" + sameMerchantOtherStore,
            null,
            bearer(token));
    assertEquals(200, otherStoreList.status(), otherStoreList.redacted());
    assertEquals(0, ((Number) otherStoreList.data().get("total")).intValue());
    assertError(
        send(
            "GET",
            "/api/v1/merchant/staff/" + staffId + "?merchantId=" + merchantId
                + "&storeId=" + sameMerchantOtherStore,
            null,
            bearer(token)),
        404,
        "COMMON_NOT_FOUND");
    assertEquals(1, listItems(send("GET", listPath + "&serviceEnabled=false", null, bearer(token))).size());
    assertEquals(0, listItems(send("GET", listPath + "&employmentStatus=INACTIVE", null, bearer(token))).size());
    assertError(send("GET", listPath + "&page=10001", null, bearer(token)), 400, "COMMON_INVALID_ARGUMENT");
    assertError(send("GET", listPath + "&staffId=" + staffId, null, bearer(token)), 400, "COMMON_INVALID_ARGUMENT");

    Map<String, Object> stranger = consumerLogin("staff-person", STAFF_PHONE);
    String strangerToken = str(stranger, "accessToken");
    assertError(send("GET", detail, null, bearer(strangerToken)), 404, "COMMON_NOT_FOUND");
    assertError(
        send("POST", createPath, firstInput, bearer(strangerToken)), 404, "COMMON_NOT_FOUND");
    Map<String, Object> otherOwner = consumerLogin("staff-other-owner", "13800007782");
    addPrivateAssets(str(otherOwner, "userId"), 601);
    String otherMerchant =
        approveApplication(str(otherOwner, "accessToken"), 601, "另一真实商家", 1);
    String otherStore =
        String.valueOf(
            db.jdbc.queryForObject(
                "SELECT id FROM merchant_store WHERE merchant_id=?",
                Long.class,
                Long.parseLong(otherMerchant)));
    sign(otherMerchant, str(otherOwner, "accessToken"));
    assertError(send("GET", detail, null, bearer(str(otherOwner, "accessToken"))), 404, "COMMON_NOT_FOUND");
    assertError(
        send(
            "GET",
            "/api/v1/merchant/staff/" + staffId + "?merchantId=" + otherMerchant + "&storeId=" + otherStore,
            null,
            bearer(str(otherOwner, "accessToken"))),
        404,
        "COMMON_NOT_FOUND");

    Map<String, Object> replacement = updateInput(merchantId, storeId, "阿橙二", "0");
    String updateKey = UUID.randomUUID().toString();
    Reply updated = send("PUT", "/api/v1/merchant/staff/" + staffId, replacement, bearer(token, updateKey));
    assertEquals(200, updated.status(), updated.redacted());
    assertEquals("1", updated.data().get("version"));
    assertNull(updated.data().get("phoneMasked"));
    assertNull(send("GET", detail, null, bearer(token)).data().get("phoneMasked"));
    assertError(
        send("PUT", "/api/v1/merchant/staff/" + staffId, replacement, bearer(token)),
        409,
        "COMMON_CONFLICT");
    Reply replayUpdate = send("PUT", "/api/v1/merchant/staff/" + staffId, replacement, bearer(token, updateKey));
    assertEquals(200, replayUpdate.status(), replayUpdate.redacted());
    assertEquals("1", replayUpdate.data().get("version"));
    assertEquals(2L, count("SELECT COUNT(*) FROM merchant_staff_audit WHERE staff_id=?", Long.parseLong(staffId)));

    Map<String, Object> enableInput = Map.of("merchantId", merchantId, "storeId", storeId, "expectedVersion", "1");
    String enableKey = UUID.randomUUID().toString();
    String enablePath = "/api/v1/merchant/staff/" + staffId + "/enable";
    Reply enabled = send("POST", enablePath, enableInput, bearer(token, enableKey));
    assertEquals(200, enabled.status(), enabled.redacted());
    assertEquals(true, enabled.data().get("serviceEnabled"));
    assertEquals("2", enabled.data().get("version"));
    assertEquals("2", send("POST", enablePath, enableInput, bearer(token, enableKey)).data().get("version"));
    assertEquals(3L, count("SELECT COUNT(*) FROM merchant_staff_audit WHERE staff_id=?", Long.parseLong(staffId)));
    Reply enabledAgain =
        send(
            "POST",
            enablePath,
            Map.of("merchantId", merchantId, "storeId", storeId, "expectedVersion", "2"),
            bearer(token));
    assertEquals(200, enabledAgain.status(), enabledAgain.redacted());
    assertEquals("3", enabledAgain.data().get("version"));
    assertEquals(4L, count("SELECT COUNT(*) FROM merchant_staff_audit WHERE staff_id=?", Long.parseLong(staffId)));
    Reply second =
        send(
            "POST",
            createPath,
            staffInput(merchantId, storeId, "阿柚", "13800007889", "ACTIVE", false),
            bearer(token));
    assertEquals(201, second.status(), second.redacted());
    Map<String, Object> explicitNull = updateInput(merchantId, storeId, "阿柚二", "0");
    explicitNull.put("phone", null);
    Reply cleared =
        send(
            "PUT",
            "/api/v1/merchant/staff/" + str(second.data(), "staffId"),
            explicitNull,
            bearer(token));
    assertEquals(200, cleared.status(), cleared.redacted());
    assertNull(cleared.data().get("phoneMasked"));
    assertEquals(403, send("POST", "/api/v1/merchant/staff/" + staffId + "/disable", Map.of("merchantId", merchantId, "storeId", storeId, "expectedVersion", "3"), bearer(token)).status());
    assertEquals(1, db.jdbc.queryForObject("SELECT service_enabled FROM merchant_staff WHERE id=?", Integer.class, Long.parseLong(staffId)));

    assertScheduleSeesStaff(token, merchantId, storeId, staffId);

    db.jdbc.update("UPDATE merchant_store SET status='FROZEN',version=version+1 WHERE id=?", Long.parseLong(storeId));
    assertEquals(200, send("GET", detail, null, bearer(token)).status());
    assertError(
        send(
            "POST",
            enablePath,
            Map.of("merchantId", merchantId, "storeId", storeId, "expectedVersion", "3"),
            bearer(token)),
        409,
        "COMMON_CONFLICT");
    db.jdbc.update("UPDATE merchant_store SET status='ACTIVE',version=version+1 WHERE id=?", Long.parseLong(storeId));
    db.jdbc.update(
        "UPDATE merchant SET owner_user_id=?,version=version+1 WHERE id=?",
        Long.parseLong(str(stranger, "userId")),
        Long.parseLong(merchantId));
    assertError(
        send("PUT", "/api/v1/merchant/staff/" + staffId, replacement, bearer(token, updateKey)),
        404,
        "COMMON_NOT_FOUND");
  }

  private void assertScheduleSeesStaff(
      String token, String merchantId, String storeId, String staffId) throws Exception {
    long category = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO service_category(id,category_name,created_at,updated_at)"
            + " VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        category,
        "MER-staff-QA-" + UUID.randomUUID());
    String service = createActiveService(token, merchantId, storeId, String.valueOf(category));
    LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(10);
    String start = utc(day, 9, 0), end = utc(day, 10, 0);
    db.jdbc.update(
        "INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?, ?,2,'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        db.ids.nextId(),
        Long.parseLong(merchantId),
        Long.parseLong(storeId),
        Long.parseLong(service),
        start,
        end);
    String path =
        "/api/v1/c/services/" + service + "/availability?storeId=" + storeId
            + "&startDate=" + day + "&endDate=" + day;
    assertCapacity(send("GET", path, null, bearer(token)), 0);
    db.jdbc.update(
        "INSERT INTO staff_service_capability(id,staff_id,service_id,status,created_at,updated_at)"
            + " VALUES(?,?,?,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        db.ids.nextId(), Long.parseLong(staffId), Long.parseLong(service));
    assertCapacity(send("GET", path, null, bearer(token)), 0);
    db.jdbc.update(
        "INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,'AVAILABLE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        db.ids.nextId(), Long.parseLong(storeId), Long.parseLong(staffId), start, end);
    assertCapacity(send("GET", path, null, bearer(token)), 1);
  }

  private String createActiveService(String token, String merchantId, String storeId, String category)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("merchantId", merchantId);
    body.put("storeId", storeId);
    body.put("serviceName", "员工读侧验收服务");
    body.put("categoryId", category);
    body.put("fulfillmentType", "IN_STORE");
    body.put("price", "128.00");
    body.put("durationMinutes", 60);
    body.put("coverAssetId", COVER_ASSET);
    body.put("applicablePetTypes", List.of("DOG"));
    body.put("description", "员工容量读侧验收");
    Reply created = send("POST", "/api/v1/merchant/services", body, bearer(token));
    assertEquals(201, created.status(), created.redacted());
    String service = str(created.data(), "serviceId");
    Reply submitted =
        send("POST", "/api/v1/merchant/services/" + service + "/online", Map.of("expectedVersion", "0"), bearer(token));
    assertEquals(200, submitted.status(), submitted.redacted());
    Reply approved =
        send(
            "POST",
            "/api/v1/admin/services/" + service + "/decision",
            Map.of("decisionType", "APPROVE", "expectedVersion", "1"),
            bearer(adminLogin()));
    assertEquals(200, approved.status(), approved.redacted());
    assertEquals("ACTIVE", approved.data().get("status"));
    return service;
  }

  private void assertCapacity(Reply response, int expected) {
    assertEquals(200, response.status(), response.redacted());
    Map<String, Object> window = map(listItems(response).getFirst());
    assertEquals(expected, ((Number) window.get("effectiveCapacity")).intValue());
    assertEquals(expected > 0, window.get("available"));
  }

  private static String utc(LocalDate day, int hour, int minute) {
    return day.atTime(hour, minute)
        .atZone(ZoneId.of("Asia/Shanghai"))
        .withZoneSameInstant(ZoneOffset.UTC)
        .toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
  }

  private static Map<String, Object> staffInput(
      String merchantId, String storeId, String name, String phone, String status, boolean enabled) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("merchantId", merchantId);
    body.put("storeId", storeId);
    body.put("staffName", name);
    if (phone != null) body.put("phone", phone);
    body.put("employmentStatus", status);
    body.put("serviceEnabled", enabled);
    return body;
  }

  private static Map<String, Object> updateInput(
      String merchantId, String storeId, String name, String version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("merchantId", merchantId);
    body.put("storeId", storeId);
    body.put("staffName", name);
    body.put("expectedVersion", version);
    return body;
  }

  private static void assertStaffStrings(Map<String, Object> staff, String merchantId, String storeId) {
    assertEquals(merchantId, staff.get("merchantId"));
    assertEquals(storeId, staff.get("storeId"));
    assertTrue(str(staff, "staffId").matches("[1-9][0-9]*"));
    assertTrue(str(staff, "version").matches("(0|[1-9][0-9]*)"));
    assertFalse(staff.containsKey("phone"));
  }

  private static void assertError(Reply reply, int status, String code) {
    assertEquals(status, reply.status(), reply.redacted());
    assertEquals(code, reply.envelope().get("code"));
    assertNull(reply.envelope().get("data"));
  }

  private long count(String sql, Object arg) {
    return db.jdbc.queryForObject(sql, Long.class, arg);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> listItems(Reply reply) {
    return (List<Object>) reply.data().get("items");
  }

  private Reply admission(String merchantId, String storeId, String token) throws Exception {
    return send(
        "GET",
        "/api/v1/merchant/auth/admission?merchantId=" + merchantId + "&storeId=" + storeId,
        null,
        bearer(token));
  }

  private void addPrivateAssets(String ownerId, long start) throws Exception {
    for (long id = start; id < start + 4; id++)
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("staff-asset-" + id), "image/jpeg", 512, "READY"));
  }

  private String approveApplication(String token, long firstAsset, String merchantName, int variant)
      throws Exception {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("merchantName", merchantName);
    draft.put("contactName", "张三");
    draft.put("contactPhone", variant == 0 ? "13800138000" : "13800138001");
    draft.put("email", variant == 0 ? "staff-owner@example.test" : "staff-other@example.test");
    draft.put("merchantTypeCode", "PET_LIFE_STORE");
    draft.put("cityCode", "chengdu");
    draft.put("address", "测试服务地址");
    draft.put("longitude", "121.4");
    draft.put("latitude", "31.2");
    draft.put("storePhotoAssetIds", List.of(String.valueOf(firstAsset)));
    draft.put("businessLicenseAssetId", String.valueOf(firstAsset + 1));
    draft.put("idCardFrontAssetId", String.valueOf(firstAsset + 2));
    draft.put("idCardBackAssetId", String.valueOf(firstAsset + 3));
    Reply created = send("POST", "/api/v1/c/merchant-applications", draft, bearer(token));
    assertEquals(201, created.status(), created.redacted());
    String applicationId = str(created.data(), "applicationId");
    Reply submitted =
        send(
            "POST",
            "/api/v1/c/merchant-applications/" + applicationId + "/submit",
            Map.of(
                "expectedVersion", str(created.data(), "version"),
                "revisionId", str(created.data(), "currentRevisionId")),
            bearer(token));
    assertEquals(200, submitted.status(), submitted.redacted());
    String admin = adminLogin();
    String reviewPath = "/api/v1/admin/merchant-applications/" + applicationId;
    Reply review = send("GET", reviewPath, null, bearer(admin));
    assertEquals(200, review.status(), review.redacted());
    Reply claimed =
        send(
            "POST",
            reviewPath + "/claim",
            Map.of("expectedTaskVersion", str(map(review.data().get("task")), "version")),
            bearer(admin));
    assertEquals(200, claimed.status(), claimed.redacted());
    Map<String, Object> revision = map(review.data().get("submittedRevision"));
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (Object raw : (List<?>) revision.get("materialReferences")) {
      Map<String, Object> ref = map(raw);
      String type = str(ref, "materialType");
      boolean license = "BUSINESS_LICENSE".equals(type);
      if (!license && !"ID_CARD_BACK".equals(type)) continue;
      evidence.add(
          Map.of(
              "materialId", str(ref, "materialId"),
              "materialSha256", str(ref, "materialSha256"),
              "credentialType", license ? "CREDIT_CODE" : "IDENTITY_NUMBER",
              "subjectName", license ? merchantName : "张三",
              "identifier", license ? testCreditCode(variant) : testIdentity(variant),
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
    return str(approved.data(), "reservedMerchantId");
  }

  private void sign(String merchantId, String token) throws Exception {
    String version = "staff-acceptance-v1", content = "测试环境商家员工协议", hash = sha(content);
    if (db.jdbc.queryForObject("SELECT COUNT(*) FROM merchant_agreement_current", Long.class) == 0) {
      long id = db.ids.nextId();
      db.jdbc.update(
          "INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
              + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
          id,
          version,
          content,
          hash,
          db.jdbc.queryForObject(
              "SELECT id FROM admin_account WHERE account_display='qa-reviewer'", Long.class));
      db.jdbc.update(
          "INSERT INTO merchant_agreement_current(agreement_key,agreement_version_id,version,updated_at)"
              + " VALUES('MERCHANT',?,0,UTC_TIMESTAMP(3))",
          id);
    }
    Reply signed =
        send(
            "POST",
            "/api/v1/merchant/agreement/consent",
            Map.of(
                "merchantId", merchantId,
                "agreementVersion", version,
                "contentSha256", hash,
                "accepted", true),
            bearer(token));
    assertEquals(201, signed.status(), signed.redacted());
  }

  private Map<String, Object> consumerLogin(String openid, String phone) throws Exception {
    Reply attempt =
        send("POST", "/api/v1/c/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"), Map.of());
    assertEquals(201, attempt.status(), attempt.redacted());
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
                "Cookie", attempt.raw().headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                "X-Auth-Attempt", str(attempt.data(), "attemptToken"),
                "X-Request-Id", UUID.randomUUID().toString()));
    Reply challenge =
        send("POST", "/api/v1/admin/auth/captcha/challenges", Map.of("attemptId", attemptId), headers);
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
            Map.of("attemptId", attemptId, "captchaId", str(challenge.data(), "captchaId"), "answer", "ABC234"),
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
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    @SuppressWarnings("unchecked")
    Map<String, Object> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String);
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), envelope, response);
  }

  private Reply sendRaw(String method, String path, String body, Map<String, String> headers)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    builder.header("Content-Type", "application/json");
    if (!headers.containsKey("X-Request-Id"))
      builder.header("X-Request-Id", UUID.randomUUID().toString());
    HttpResponse<String> response =
        http.send(
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString());
    @SuppressWarnings("unchecked")
    Map<String, Object> envelope = json.readValue(response.body(), Map.class);
    assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), envelope, response);
  }

  private static Map<String, String> bearer(String token) {
    return Map.of("Authorization", "Bearer " + token);
  }

  private static Map<String, String> bearer(String token, String requestId) {
    return Map.of("Authorization", "Bearer " + token, "X-Request-Id", requestId);
  }

  private static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> result = new LinkedHashMap<>();
      m.forEach((k, v) -> result.put(String.valueOf(k), v));
      return result;
    }
    throw new IllegalArgumentException("Expected object");
  }

  private static String str(Map<String, Object> value, String field) {
    Object found = value.get(field);
    if (found == null) throw new IllegalArgumentException("Missing " + field);
    return String.valueOf(found);
  }

  private static byte[] key(int seed) {
    byte[] result = new byte[32];
    new SecureRandom(String.valueOf(seed).getBytes(StandardCharsets.UTF_8)).nextBytes(result);
    return result;
  }

  private static String sha(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder();
    for (byte b : digest) out.append(String.format("%02x", b));
    return out.toString();
  }

  private static String testIdentity(int variant) {
    String body = variant == 0 ? "51010419900101001" : "51010419900101002";
    int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += (body.charAt(i) - '0') * weights[i];
    return body + "10X98765432".charAt(sum % 11);
  }

  private static String testCreditCode(int variant) {
    String body = variant == 0 ? "91510100MA0000000" : "91510100MA0000001";
    String alphabet = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += alphabet.indexOf(body.charAt(i)) * weights[i];
    return body + alphabet.charAt((31 - sum % 31) % 31);
  }

  private record Reply(int status, Map<String, Object> envelope, HttpResponse<String> raw) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    String redacted() {
      return "Reply[status=" + status + ",code=" + envelope.get("code") + "]";
    }
  }
}
