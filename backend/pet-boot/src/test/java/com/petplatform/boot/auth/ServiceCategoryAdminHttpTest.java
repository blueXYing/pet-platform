package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.service.api.command.ServiceCategoryCommandApi;
import com.petplatform.service.api.dto.ServiceCategoryTypes.CreateServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminListQuery;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceAdminAuthorization;
import com.petplatform.service.api.query.ServiceCategoryAdminQueryApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * CCR-SVC-CAT-001 B-SVC-02 admin dictionary HTTP acceptance (contract 74 §4): real Boot,
 * ADMIN_WEB Bearer, real MySQL/Redis. Covers CRUD, partial update, enable/disable, optimistic
 * version CAS, requestId replay/conflict, append-only audit, the read/manage permission split,
 * unknown categories, icon URL scheme A and the remaining field validation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceCategoryAdminHttpTest {
  private static final String ORIGIN = "https://svccat.example.invalid";
  private static final String ACCOUNT = "qa-category";
  private static final String PASSWORD = "Example_ONLY_92!";
  private static final String BASE_PATH = "/api/v1/admin/service-categories";

  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final byte[] adminMac = key(3), adminAes = key(7);

  private CAuthHttpTest.HttpFixture db;
  private ConfigurableApplicationContext context;
  private String base;
  private String token;
  private String sessionId;
  private long sessionGeneration;
  private long actor;

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
    return Objects.requireNonNull(p);
  }

  private static byte[] key(int fill) {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) fill);
    return bytes;
  }

  @BeforeAll
  void start() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    stage(
        "schema",
        () -> {
          try (var connection = db.source.getConnection()) {
            ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(
                    new FileSystemResource(
                        root().resolve("docs/03-database/26-Admin-Auth-Schema-v0.1.sql")),
                    StandardCharsets.UTF_8));
            // CCR-SVC-CAT-001 B-SVC-01: apply the approved opt-in dictionary delta (audit
            // table and category_code/version/icon/description columns) on the empty
            // SQL06 service_category.
            ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(
                    new FileSystemResource(
                        root().resolve(
                            "backend/pet-boot/src/main/resources/db/service-migration/"
                                + "V29__service_category_dictionary.sql")),
                    StandardCharsets.UTF_8));
          }
          return null;
        });
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", 0);
    props.put("spring.flyway.enabled", false);
    props.put("spring.main.banner-mode", "off");
    props.put("spring.jmx.enabled", false);
    props.put("pet.auth.admin.enabled", true);
    props.put("pet.auth.admin.migration-enabled", false);
    props.put("pet.auth.admin.origin", ORIGIN);
    props.put("pet.auth.admin.redis-host", db.redisHost);
    props.put("pet.auth.admin.redis-port", db.redisPort);
    props.put("pet.auth.admin.cache-prefix", db.prefix);
    props.put("pet.auth.admin.key-id", "qa-key");
    props.put("pet.auth.admin.mac-key-base64", Base64.getEncoder().encodeToString(adminMac));
    props.put(
        "pet.auth.admin.encryption-key-base64", Base64.getEncoder().encodeToString(adminAes));
    props.put("pet.auth.admin.audit-path", db.directory.resolve("svccat-audit.bin").toString());
    props.put("pet.service.command.enabled", true);
    try {
      context =
          stage(
              "boot",
              () ->
                  new SpringApplicationBuilder(PetPlatformApplication.class)
              .properties(props)
              .initializers(
                  ctx -> {
                    var beans = (GenericApplicationContext) ctx;
                    beans.registerBean(
                        "svccatDataSource", DataSource.class, () -> db.source);
                    beans.registerBean(
                        "svccatIds", SnowflakeIdGenerator.class, () -> db.ids);
                    // The dictionary face never consults merchant admission or the outbox;
                    // these seams satisfy the service-write assembly without another module.
                    beans.registerBean(
                        "svccatAdmissions",
                        MerchantAdmissionQueryApi.class,
                        () ->
                            new MerchantAdmissionQueryApi() {
                              @Override
                              public MerchantMembershipPageDTO listMemberships(
                                  MerchantMembershipQuery query) {
                                throw new UnsupportedOperationException();
                              }

                              @Override
                              public MerchantAdmissionDTO getAdmission(
                                  MerchantAdmissionQuery query) {
                                throw new UnsupportedOperationException();
                              }
                            });
                  })
              .run());
      base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      AdminAuthService auth = context.getBean(AdminAuthService.class);
      stage(
          "bootstrap",
          () -> {
            auth.bootstrap(ACCOUNT, "QA Category Operator", PASSWORD.toCharArray(),
                "Isolated service category acceptance");
            return null;
          });
      var attempt =
          stage("attempt", () -> auth.createAttempt(UUID.randomUUID().toString(), "127.0.0.1"));
      var granted =
          stage(
              "login",
              () ->
                  auth.login(
                      UUID.randomUUID().toString(),
                      Long.parseLong(str(attempt.data(), "attemptId")),
                      str(attempt.data(), "attemptToken"),
                      attempt.bindingCookie(),
                      ACCOUNT,
                      PASSWORD.toCharArray(),
                      null));
      token = str(granted.data(), "accessToken");
      sessionId = str(granted.data(), "sessionId");
      actor = Long.parseLong(str(granted.data(), "operatorId"));
      sessionGeneration =
          db.jdbc.queryForObject(
              "SELECT generation FROM admin_web_session WHERE id=?",
              Long.class,
              Long.parseLong(sessionId));
    } catch (Exception failure) {
      if (context != null) context.close();
      db.close();
      db = null;
      throw failure;
    }
  }

  @AfterAll
  void stop() throws Exception {
    try {
      if (context != null) context.close();
    } finally {
      if (db != null) db.close();
    }
  }

  // ---------------------------------------------------------------- acceptance

  @Test
  void crudReplayVersionCasAndAuditAreEnforced() throws Exception {
    String suffix = suffix();
    String code = "QA_" + suffix;
    String name = "QA分类" + suffix;
    Map<String, Object> create = createBody(code, name);
    create.put("iconUrl", "https://cdn.example.invalid/category/" + suffix.toLowerCase() + ".png");
    create.put("description", "QA 自动化分类描述");
    create.put("sortNo", 7);
    String createKey = UUID.randomUUID().toString();

    Reply created = send("POST", BASE_PATH, create, createKey);
    assertEquals(200, created.status(), created.redacted());
    String categoryId = str(created.data(), "categoryId");
    assertTrue(categoryId.matches("[1-9][0-9]*"), categoryId);
    assertEquals(code, created.data().get("categoryCode"));
    assertEquals("ENABLED", created.data().get("status"));
    assertEquals(0L, number(created.data(), "version"));
    long rowId = Long.parseLong(categoryId);

    // Same requestId + same parameters: first receipt, no second row and no second audit row.
    Reply replay = send("POST", BASE_PATH, create, createKey);
    assertEquals(200, replay.status(), replay.redacted());
    assertEquals(categoryId, str(replay.data(), "categoryId"));
    assertEquals(0L, number(replay.data(), "version"));
    assertEquals(
        1L,
        count("SELECT COUNT(*) FROM service_category WHERE category_code=?", code));
    assertEquals(
        1L,
        count("SELECT COUNT(*) FROM service_category_audit WHERE category_id=?", rowId));

    // Same requestId + different parameters: 409 before any write.
    Map<String, Object> different = new LinkedHashMap<>(create);
    different.put("sortNo", 8);
    Reply keyConflict = send("POST", BASE_PATH, different, createKey);
    assertEquals(409, keyConflict.status(), keyConflict.redacted());
    assertEquals("IDEMPOTENCY_KEY_CONFLICT", keyConflict.envelope.get("code"));
    assertEquals(1L, count("SELECT COUNT(*) FROM service_category WHERE category_code=?", code));

    // A fresh requestId with an already used code: 409 CATEGORY_CODE_CONFLICT.
    Map<String, Object> duplicateCode = new LinkedHashMap<>(create);
    duplicateCode.put("categoryName", name + "B");
    Reply codeConflict =
        send("POST", BASE_PATH, duplicateCode, UUID.randomUUID().toString());
    assertEquals(409, codeConflict.status(), codeConflict.redacted());
    assertEquals("CATEGORY_CODE_CONFLICT", codeConflict.envelope.get("code"));

    // Partial update with version CAS.
    String updateKey = UUID.randomUUID().toString();
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("categoryName", name + "改");
    update.put("iconUrl", "https://cdn.example.invalid/category/" + suffix.toLowerCase() + "-v2.png");
    update.put("description", "更新后的分类描述");
    update.put("sortNo", 9);
    update.put("expectedVersion", 0);
    String updatePath = BASE_PATH + "/" + code;
    Reply saved = send("PUT", updatePath, update, updateKey);
    assertEquals(200, saved.status(), saved.redacted());
    assertEquals(categoryId, str(saved.data(), "categoryId"));
    assertEquals(1L, number(saved.data(), "version"));
    assertEquals(
        2L, count("SELECT COUNT(*) FROM service_category_audit WHERE category_id=?", rowId));

    Reply savedReplay = send("PUT", updatePath, update, updateKey);
    assertEquals(200, savedReplay.status(), savedReplay.redacted());
    assertEquals(1L, number(savedReplay.data(), "version"));
    assertEquals(
        2L, count("SELECT COUNT(*) FROM service_category_audit WHERE category_id=?", rowId));

    Map<String, Object> stale = new LinkedHashMap<>(update);
    stale.put("expectedVersion", 0);
    Reply staleUpdate = send("PUT", updatePath, stale, UUID.randomUUID().toString());
    assertEquals(409, staleUpdate.status(), staleUpdate.redacted());
    assertEquals("CATEGORY_VERSION_CONFLICT", staleUpdate.envelope.get("code"));
    assertEquals(
        2L, count("SELECT COUNT(*) FROM service_category_audit WHERE category_id=?", rowId));
    assertEquals(
        1,
        db.jdbc.queryForObject(
            "SELECT version FROM service_category WHERE category_code=?", Integer.class, code));

    // List projection: all dictionary fields, no-store, and the updated facts.
    Reply list =
        send("GET", BASE_PATH + "?status=ENABLED&keyword=" + code + "&page=1&pageSize=20",
            null, null);
    assertEquals(200, list.status(), list.redacted());
    assertEquals(1L, number(list.data(), "total"));
    assertEquals(1, ((List<?>) list.data().get("items")).size());
    @SuppressWarnings("unchecked")
    Map<String, Object> item = (Map<String, Object>) ((List<?>) list.data().get("items")).get(0);
    assertEquals(categoryId, item.get("categoryId"));
    assertEquals(code, item.get("categoryCode"));
    assertEquals(name + "改", item.get("categoryName"));
    assertNull(item.get("parentCode"));
    assertEquals(
        "https://cdn.example.invalid/category/" + suffix.toLowerCase() + "-v2.png",
        item.get("iconUrl"));
    assertEquals("更新后的分类描述", item.get("description"));
    assertEquals(9, ((Number) item.get("sortNo")).intValue());
    assertEquals("ENABLED", item.get("status"));
    assertEquals(1L, ((Number) item.get("version")).longValue());
    assertNotNull(item.get("createdAt"));
    assertNotNull(item.get("updatedAt"));

    // Disable then enable, each with its own version CAS and idempotent replay.
    String disableKey = UUID.randomUUID().toString();
    Map<String, Object> disable = Map.of("expectedVersion", 1);
    Reply disabled = send("POST", updatePath + "/disable", disable, disableKey);
    assertEquals(200, disabled.status(), disabled.redacted());
    assertEquals("DISABLED", disabled.data().get("status"));
    assertEquals(2L, number(disabled.data(), "version"));
    assertFalse(disabled.data().containsKey("categoryId"));
    Reply disabledReplay = send("POST", updatePath + "/disable", disable, disableKey);
    assertEquals(200, disabledReplay.status(), disabledReplay.redacted());
    assertEquals(2L, number(disabledReplay.data(), "version"));
    assertEquals(
        3L, count("SELECT COUNT(*) FROM service_category_audit WHERE category_id=?", rowId));
    Reply staleDisable =
        send("POST", updatePath + "/disable", disable, UUID.randomUUID().toString());
    assertEquals(409, staleDisable.status(), staleDisable.redacted());
    assertEquals("CATEGORY_VERSION_CONFLICT", staleDisable.envelope.get("code"));

    Reply enabled =
        send("POST", updatePath + "/enable", Map.of("expectedVersion", 2),
            UUID.randomUUID().toString());
    assertEquals(200, enabled.status(), enabled.redacted());
    assertEquals("ENABLED", enabled.data().get("status"));
    assertEquals(3L, number(enabled.data(), "version"));
    Reply enabledReplay =
        send("POST", updatePath + "/enable", Map.of("expectedVersion", 2),
            UUID.randomUUID().toString());
    assertEquals(409, enabledReplay.status(), enabledReplay.redacted());
    assertEquals("CATEGORY_VERSION_CONFLICT", enabledReplay.envelope.get("code"));

    // Append-only audit: one row per persisted write, actor + requestId + transition captured.
    List<Map<String, Object>> audits =
        db.jdbc.queryForList(
            "SELECT action_type,from_status,to_status,request_id,operator_id"
                + " FROM service_category_audit WHERE category_id=? ORDER BY id",
            rowId);
    assertEquals(List.of("CREATE", "UPDATE", "DISABLE", "ENABLE"), column(audits, "action_type"));
    assertNull(audits.get(0).get("from_status"));
    assertEquals("ENABLED", audits.get(0).get("to_status"));
    assertEquals(createKey, audits.get(0).get("request_id"));
    assertNull(audits.get(1).get("from_status"));
    assertNull(audits.get(1).get("to_status"));
    assertEquals(updateKey, audits.get(1).get("request_id"));
    assertEquals("ENABLED", audits.get(2).get("from_status"));
    assertEquals("DISABLED", audits.get(2).get("to_status"));
    assertEquals(disableKey, audits.get(2).get("request_id"));
    assertEquals("DISABLED", audits.get(3).get("from_status"));
    assertEquals("ENABLED", audits.get(3).get("to_status"));
    for (Map<String, Object> audit : audits) {
      assertEquals(actor, ((Number) audit.get("operator_id")).longValue());
    }

    // Unknown but well-formed codes are 404 on every write face.
    String missing = "QA_MISSING_" + suffix;
    Reply missingUpdate =
        send("PUT", BASE_PATH + "/" + missing, Map.of("expectedVersion", 0),
            UUID.randomUUID().toString());
    assertEquals(404, missingUpdate.status(), missingUpdate.redacted());
    assertEquals("CATEGORY_NOT_FOUND", missingUpdate.envelope.get("code"));
    Reply missingDisable =
        send("POST", BASE_PATH + "/" + missing + "/disable", Map.of("expectedVersion", 0),
            UUID.randomUUID().toString());
    assertEquals(404, missingDisable.status(), missingDisable.redacted());
    assertEquals("CATEGORY_NOT_FOUND", missingDisable.envelope.get("code"));
  }

  @Test
  void iconSchemeAValidationParentsAndUnknownQueriesAreRejected() throws Exception {
    String suffix = suffix();
    Map<String, Object> httpIcon =
        createBody("QA_HTTP_" + suffix, "QA协议" + suffix);
    httpIcon.put("iconUrl", "http://cdn.example.invalid/icon.png");
    Reply httpReply = send("POST", BASE_PATH, httpIcon, UUID.randomUUID().toString());
    assertEquals(400, httpReply.status(), httpReply.redacted());
    assertEquals("COMMON_INVALID_ARGUMENT", httpReply.envelope.get("code"));
    assertEquals(
        0L,
        count("SELECT COUNT(*) FROM service_category WHERE category_code=?", "QA_HTTP_" + suffix));

    Map<String, Object> ftpIcon = createBody("QA_FTP_" + suffix, "QA协议F" + suffix);
    ftpIcon.put("iconUrl", "ftp://cdn.example.invalid/icon.png");
    assertEquals(
        400,
        send("POST", BASE_PATH, ftpIcon, UUID.randomUUID().toString()).status());

    Map<String, Object> longIcon = createBody("QA_LONG_" + suffix, "QA协议L" + suffix);
    longIcon.put("iconUrl", "https://cdn.example.invalid/" + "a".repeat(500));
    assertEquals(
        400,
        send("POST", BASE_PATH, longIcon, UUID.randomUUID().toString()).status());

    Map<String, Object> badCode = createBody("qa_lower_" + suffix, "QA小写" + suffix);
    assertEquals(400, send("POST", BASE_PATH, badCode, UUID.randomUUID().toString()).status());

    Map<String, Object> badSort = createBody("QA_SORT_" + suffix, "QA排序" + suffix);
    badSort.put("sortNo", 10_000);
    assertEquals(400, send("POST", BASE_PATH, badSort, UUID.randomUUID().toString()).status());

    Map<String, Object> missingVersion =
        new LinkedHashMap<>();
    missingVersion.put("categoryName", "QA无版本" + suffix);
    assertEquals(
        400,
        send("PUT", BASE_PATH + "/QA_ABSENT_" + suffix, missingVersion,
            UUID.randomUUID().toString()).status());

    assertEquals(
        400,
        send("GET", BASE_PATH + "?status=ARCHIVED", null, null).status());
    assertEquals(
        400,
        send("GET", BASE_PATH + "?bogus=1", null, null).status());

    // Duplicate display name with a different code stays a field-level 400.
    String parentCode = "QA_PARENT_" + suffix;
    String parentName = "QA父类" + suffix;
    Reply parent = send("POST", BASE_PATH, createBody(parentCode, parentName),
        UUID.randomUUID().toString());
    assertEquals(200, parent.status(), parent.redacted());
    Map<String, Object> sameName = createBody("QA_DUPNAME_" + suffix, parentName);
    Reply sameNameReply = send("POST", BASE_PATH, sameName, UUID.randomUUID().toString());
    assertEquals(400, sameNameReply.status(), sameNameReply.redacted());
    assertEquals("COMMON_INVALID_ARGUMENT", sameNameReply.envelope.get("code"));
    assertEquals(
        0L,
        count(
            "SELECT COUNT(*) FROM service_category WHERE category_code=?",
            "QA_DUPNAME_" + suffix));

    // Parent resolution: existing parent is projected, self-parent and unknown parent are 400.
    String childCode = "QA_CHILD_" + suffix;
    Map<String, Object> child = createBody(childCode, "QA子类" + suffix);
    child.put("parentCode", parentCode);
    Reply childCreated = send("POST", BASE_PATH, child, UUID.randomUUID().toString());
    assertEquals(200, childCreated.status(), childCreated.redacted());
    Reply childList =
        send("GET", BASE_PATH + "?keyword=" + childCode, null, null);
    assertEquals(200, childList.status(), childList.redacted());
    @SuppressWarnings("unchecked")
    Map<String, Object> childItem =
        (Map<String, Object>) ((List<?>) childList.data().get("items")).get(0);
    assertEquals(parentCode, childItem.get("parentCode"));

    Map<String, Object> selfParent = Map.of("parentCode", childCode, "expectedVersion", 0);
    Reply selfParentReply =
        send("PUT", BASE_PATH + "/" + childCode, selfParent, UUID.randomUUID().toString());
    assertEquals(400, selfParentReply.status(), selfParentReply.redacted());
    Map<String, Object> unknownParent =
        Map.of("parentCode", "QA_NOPARENT_" + suffix, "expectedVersion", 0);
    assertEquals(
        400,
        send("PUT", BASE_PATH + "/" + childCode, unknownParent,
            UUID.randomUUID().toString()).status());
  }

  @Test
  void manageActionIsRequiredForWritesWhileReadGrantStillLists() throws Exception {
    long roleId =
        db.jdbc.queryForObject(
            "SELECT ar.role_id FROM admin_account_role ar JOIN admin_role r ON r.id=ar.role_id"
                + " WHERE ar.account_id=? AND r.role_code='PLATFORM_SUPER_ADMIN'",
            Long.class,
            actor);
    String suffix = suffix();
    try {
      db.jdbc.update("DELETE FROM admin_account_role WHERE account_id=?", actor);
      db.jdbc.update(
          "INSERT INTO admin_extra_grant(account_id,action_code,granted_by,granted_at)"
              + " VALUES(?,'service.category.read',?,UTC_TIMESTAMP(3))",
          actor,
          actor);
      db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");

      Reply list = send("GET", BASE_PATH + "?page=1&pageSize=5", null, null);
      assertEquals(200, list.status(), list.redacted());

      String code = "QA_DENIED_" + suffix;
      String deniedKey = UUID.randomUUID().toString();
      Reply deniedCreate =
          send("POST", BASE_PATH, createBody(code, "QA无权限" + suffix), deniedKey);
      assertEquals(403, deniedCreate.status(), deniedCreate.redacted());
      assertEquals("COMMON_FORBIDDEN", deniedCreate.envelope.get("code"));
      Reply deniedUpdate =
          send("PUT", BASE_PATH + "/" + code, Map.of("expectedVersion", 0),
              UUID.randomUUID().toString());
      assertEquals(403, deniedUpdate.status(), deniedUpdate.redacted());
      Reply deniedDisable =
          send("POST", BASE_PATH + "/" + code + "/disable", Map.of("expectedVersion", 0),
              UUID.randomUUID().toString());
      assertEquals(403, deniedDisable.status(), deniedDisable.redacted());
      assertEquals(
          0L,
          count("SELECT COUNT(*) FROM service_category WHERE category_code=?", code));
      assertEquals(
          0L,
          count("SELECT COUNT(*) FROM service_category_audit WHERE request_id=?", deniedKey));

      // Bypass the controller snapshot entirely: the service layer must re-prove the live
      // session and action against the admin kernel. A read grant still admits the collection
      // check, while the missing manage grant denies a write before any category row exists.
      ServiceCategoryCommandApi kernelCommands = context.getBean(ServiceCategoryCommandApi.class);
      ServiceCategoryAdminQueryApi kernelQueries =
          context.getBean(ServiceCategoryAdminQueryApi.class);
      ServiceAdminAuthorization kernelAuth =
          new ServiceAdminAuthorization(sessionId, sessionGeneration);
      QueryContext kernelReadContext =
          new QueryContext("qa-category-kernel", OperatorType.PLATFORM_OPERATOR, Long.toString(actor));
      assertNotNull(
          kernelQueries.list(
              new ServiceCategoryAdminListQuery(
                  1, 5, null, null, kernelAuth, kernelReadContext)));

      String kernelCode = "QA_KERNEL_" + suffix;
      CommandContext kernelWriteContext =
          new CommandContext(
              UUID.randomUUID().toString(),
              "qa-category-kernel",
              OperatorType.PLATFORM_OPERATOR,
              Long.toString(actor),
              "ADMIN_WEB");
      ApiException kernelDenied =
          assertThrows(
              ApiException.class,
              () ->
                  kernelCommands.create(
                      new CreateServiceCategoryCommand(
                          kernelCode,
                          "QA内核" + suffix,
                          null,
                          null,
                          null,
                          0,
                          kernelAuth,
                          kernelWriteContext)));
      assertEquals(CommonApiCodes.FORBIDDEN, kernelDenied.code());
      assertEquals(
          0L, count("SELECT COUNT(*) FROM service_category WHERE category_code=?", kernelCode));

      ApiException invalidSession =
          assertThrows(
              ApiException.class,
              () ->
                  kernelCommands.create(
                      new CreateServiceCategoryCommand(
                          "QA_BADSESSION_" + suffix,
                          "QA坏会话" + suffix,
                          null,
                          null,
                          null,
                          0,
                          new ServiceAdminAuthorization("999999999999", sessionGeneration),
                          new CommandContext(
                              UUID.randomUUID().toString(),
                              "qa-category-kernel",
                              OperatorType.PLATFORM_OPERATOR,
                              Long.toString(actor),
                              "ADMIN_WEB"))));
      assertEquals(CommonApiCodes.UNAUTHORIZED, invalidSession.code());

      db.jdbc.update("DELETE FROM admin_extra_grant WHERE account_id=?", actor);
      db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
      ApiException kernelReadDenied =
          assertThrows(
              ApiException.class,
              () ->
                  kernelQueries.list(
                      new ServiceCategoryAdminListQuery(
                          1, 5, null, null, kernelAuth, kernelReadContext)));
      assertEquals(CommonApiCodes.FORBIDDEN, kernelReadDenied.code());
    } finally {
      db.jdbc.update("DELETE FROM admin_extra_grant WHERE account_id=?", actor);
      db.jdbc.update(
          "INSERT INTO admin_account_role(account_id,role_id,granted_by,granted_at)"
              + " VALUES(?,?,?,UTC_TIMESTAMP(3))",
          actor,
          roleId,
          actor);
      db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
    }

    // The restored super-admin grant still permits writes.
    String restored = "QA_RESTORED_" + suffix;
    Reply created =
        send("POST", BASE_PATH, createBody(restored, "QA恢复" + suffix),
            UUID.randomUUID().toString());
    assertEquals(200, created.status(), created.redacted());
  }

  @Test
  void missingOrInvalidAdminBearerIsRejectedBeforeAnyCategoryAccess() throws Exception {
    Reply missing = send("GET", BASE_PATH + "?page=1&pageSize=5", null, null, null);
    assertEquals(401, missing.status(), missing.redacted());
    assertEquals("COMMON_UNAUTHORIZED", missing.envelope.get("code"));

    Reply invalid =
        send("GET", BASE_PATH + "?page=1&pageSize=5", null, null, "not-a-real-token");
    assertEquals(401, invalid.status(), invalid.redacted());
    assertEquals("COMMON_UNAUTHORIZED", invalid.envelope.get("code"));
  }

  @Test
  void keywordWildcardsAreEscapedAsLiterals() throws Exception {
    String suffix = suffix();
    String percentCode = "QA_PCT_" + suffix;
    String percentName = "PCT%" + suffix;
    assertEquals(
        200,
        send("POST", BASE_PATH, createBody(percentCode, percentName),
            UUID.randomUUID().toString()).status());
    // Decoy matches the unescaped '%' wildcard but not the literal "PCT%".
    String percentDecoyCode = "QA_PCTZ_" + suffix;
    assertEquals(
        200,
        send("POST", BASE_PATH, createBody(percentDecoyCode, "PCTZZ" + suffix),
            UUID.randomUUID().toString()).status());

    Reply percentList =
        send("GET", BASE_PATH + "?keyword=PCT%25&page=1&pageSize=20", null, null);
    assertEquals(200, percentList.status(), percentList.redacted());
    assertEquals(1L, number(percentList.data(), "total"));
    assertEquals(percentCode, firstItem(percentList).get("categoryCode"));

    String underscoreCode = "QA_UND_" + suffix;
    String underscoreName = "UND_" + suffix;
    assertEquals(
        200,
        send("POST", BASE_PATH, createBody(underscoreCode, underscoreName),
            UUID.randomUUID().toString()).status());
    // Decoy matches the unescaped '_' wildcard but not the literal "UND_".
    String underscoreDecoyCode = "QA_UNDZ_" + suffix;
    assertEquals(
        200,
        send("POST", BASE_PATH, createBody(underscoreDecoyCode, "UNDZ" + suffix),
            UUID.randomUUID().toString()).status());

    Reply underscoreList =
        send("GET", BASE_PATH + "?keyword=UND_&page=1&pageSize=20", null, null);
    assertEquals(200, underscoreList.status(), underscoreList.redacted());
    assertEquals(1L, number(underscoreList.data(), "total"));
    assertEquals(underscoreCode, firstItem(underscoreList).get("categoryCode"));
  }

  @Test
  void updatePresenceKeepsAbsentFieldsAndClearsExplicitNullOrDefault() throws Exception {
    String suffix = suffix();
    String parentCode = "QA_MP_" + suffix;
    Reply parent =
        send("POST", BASE_PATH, createBody(parentCode, "QA母" + suffix),
            UUID.randomUUID().toString());
    assertEquals(200, parent.status(), parent.redacted());

    String code = "QA_MC_" + suffix;
    Map<String, Object> create = createBody(code, "QA可清" + suffix);
    create.put("parentCode", parentCode);
    create.put("iconUrl", "https://cdn.example.invalid/category/presence.png");
    create.put("description", "保留描述");
    Reply created = send("POST", BASE_PATH, create, UUID.randomUUID().toString());
    assertEquals(200, created.status(), created.redacted());
    long childId = Long.parseLong(str(created.data(), "categoryId"));
    String path = BASE_PATH + "/" + code;

    // Absent nullable fields keep their stored values and omit parentId from the audit diff.
    Map<String, Object> keep = new LinkedHashMap<>();
    keep.put("categoryName", "QA保留" + suffix);
    keep.put("expectedVersion", 0);
    Reply kept = send("PUT", path, keep, UUID.randomUUID().toString());
    assertEquals(200, kept.status(), kept.redacted());
    assertEquals(1L, number(kept.data(), "version"));
    Map<String, Object> keptRow =
        db.jdbc.queryForMap(
            "SELECT parent_id,icon_url,description,version FROM service_category"
                + " WHERE category_code=?",
            code);
    assertNotNull(keptRow.get("parent_id"));
    assertEquals(
        "https://cdn.example.invalid/category/presence.png", keptRow.get("icon_url"));
    assertEquals("保留描述", keptRow.get("description"));
    assertEquals(1, ((Number) keptRow.get("version")).intValue());
    String keepDetail =
        db.jdbc.queryForObject(
            "SELECT detail_json FROM service_category_audit"
                + " WHERE category_id=? AND action_type='UPDATE' ORDER BY id DESC LIMIT 1",
            String.class,
            childId);
    assertFalse(keepDetail.contains("\"parentId\""), keepDetail);

    // Explicit null / empty string clears the nullable fields and records the parent diff.
    Map<String, Object> clear = new LinkedHashMap<>();
    clear.put("parentCode", null);
    clear.put("iconUrl", "");
    clear.put("description", "");
    clear.put("expectedVersion", 1);
    Reply cleared = send("PUT", path, clear, UUID.randomUUID().toString());
    assertEquals(200, cleared.status(), cleared.redacted());
    assertEquals(2L, number(cleared.data(), "version"));
    Map<String, Object> clearedRow =
        db.jdbc.queryForMap(
            "SELECT parent_id,icon_url,description,version FROM service_category"
                + " WHERE category_code=?",
            code);
    assertNull(clearedRow.get("parent_id"));
    assertNull(clearedRow.get("icon_url"));
    assertNull(clearedRow.get("description"));
    assertEquals(2, ((Number) clearedRow.get("version")).intValue());
    String clearDetail =
        db.jdbc.queryForObject(
            "SELECT detail_json FROM service_category_audit"
                + " WHERE category_id=? AND action_type='UPDATE' ORDER BY id DESC LIMIT 1",
            String.class,
            childId);
    assertTrue(clearDetail.contains("\"parentId\""), clearDetail);
    assertTrue(clearDetail.contains("\"to\": null"), clearDetail);
  }

  // ---------------------------------------------------------------- helpers

  /** Adds the failing startup stage to the sanitized AdminAuthFailure cause chain. */
  private static <T> T stage(String name, CheckedSupplier<T> work) {
    try {
      return work.get();
    } catch (RuntimeException | Error failure) {
      throw new IllegalStateException("startup stage '" + name + "' failed", failure);
    } catch (Exception failure) {
      throw new IllegalStateException("startup stage '" + name + "' failed", failure);
    }
  }

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }

  private static String suffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }

  private static Map<String, Object> createBody(String code, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("categoryCode", code);
    body.put("categoryName", name);
    body.put("parentCode", null);
    body.put("iconUrl", null);
    body.put("description", null);
    body.put("sortNo", 0);
    return body;
  }

  private Reply send(String method, String path, Object body, String requestId)
      throws Exception {
    return send(method, path, body, requestId, token);
  }

  private Reply send(
      String method, String path, Object body, String requestId, String bearer)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
    builder.header("X-Request-Id", requestId == null ? UUID.randomUUID().toString() : requestId);
    if (body != null) builder.header("Content-Type", "application/json");
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
    @SuppressWarnings("unchecked")
    Map<String, Object> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String, response.body());
    assertTrue(
        response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
        response.headers().firstValue("Cache-Control").orElse(""));
    assertFalse(response.body().contains(PASSWORD));
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), envelope);
  }

  private static Map<String, Object> firstItem(Reply reply) {
    @SuppressWarnings("unchecked")
    Map<String, Object> item =
        (Map<String, Object>) ((List<?>) reply.data().get("items")).get(0);
    return item;
  }

  private long count(String sql, Object... args) {
    return db.jdbc.queryForObject(sql, Long.class, args);
  }

  private static List<Object> column(List<Map<String, Object>> rows, String name) {
    List<Object> values = new ArrayList<>();
    for (Map<String, Object> row : rows) values.add(row.get(name));
    return values;
  }

  private static String str(Map<String, Object> data, String field) {
    Object value = data.get(field);
    return value == null ? null : value.toString();
  }

  private static long number(Map<String, Object> data, String field) {
    Object value = data.get(field);
    if (!(value instanceof Number number)) throw new AssertionError(field + " is not numeric");
    return number.longValue();
  }

  private record Reply(int status, Map<String, Object> envelope) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    String redacted() {
      return status + " " + envelope.get("code") + " " + envelope.get("message");
    }
  }
}
