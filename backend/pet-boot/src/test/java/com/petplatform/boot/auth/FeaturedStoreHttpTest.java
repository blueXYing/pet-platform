package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.math.BigDecimal;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract 74 §3.4/§6 featured-card acceptance (B-CARD-01/02/03) over a real approved-and-signed
 * merchant with two stores, the V29 dictionary, seeded review and effective-order facts: the
 * eligibility conjunction (C-visible + ACTIVE service + city/category), the confirmed card order,
 * the sold-ranked top-2 lines, the primary-category and tags projection, the &lt;5 rating display
 * rule, the merchant promotion projection, honest paging, the strict no-PII projection, the
 * anonymous/invalid-bearer discipline, whole-page 503 on damaged facts and the fail-closed avatar
 * signer rule; the second scenario proves the rating facts degrade to {@code available=false}
 * while an available sales aggregation answers honest zeros.
 */
class FeaturedStoreHttpTest {
  private static final String ORIGIN = "https://svc.example.invalid";
  private static final String PASSWORD = "Example_ONLY_92!";
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<Long, PrivateAssetQueryPort.PrivateAssetRef> privateAssets = new HashMap<>();
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

  private void start(boolean reviewEnabled) throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    try (var connection = db.source.getConnection()) {
      for (String file :
          List.of(
              "26-Admin-Auth-Schema-v0.1.sql",
              "28-Merchant-Agreement-Schema-v0.1.sql",
              "29-Merchant-Application-Schema-v0.1.sql",
              // The featured services read projects list_price (SQL33 write-slice delta) and the
              // card facts join merchant_store_profile (27), so both deltas are applied too.
              "33-Service-Write-Schema-v0.1.sql",
              "27-Merchant-Store-Profile-Schema-v0.1.sql")) {
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
    props.put("pet.auth.admin.audit-path", db.directory.resolve("featured-audit.bin"));
    props.put("pet.merchant.application.open-cities[0].code", "chengdu");
    props.put("pet.merchant.application.open-cities[0].name", "成都");
    props.put("pet.merchant.application.open-cities[1].code", "shanghai");
    props.put("pet.merchant.application.open-cities[1].name", "上海");
    props.put("pet.merchant.application.enabled", true);
    props.put("pet.outbox.enabled", true);
    props.put("pet.service.query.enabled", true);
    if (reviewEnabled) {
      props.put("pet.review.enabled", true);
    }
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  ctx -> {
                    var beans = (GenericApplicationContext) ctx;
                    beans.registerBean(
                        "featuredDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("featuredIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "featuredWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "featuredProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "featuredPrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(privateAssets::get)
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "featuredMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () ->
                            (city, address, lng, lat) ->
                                city.equals("chengdu") && address.equals("测试服务地址"));
                    beans.registerBean(
                        "featuredSubjects",
                        SubjectCredentialPort.class,
                        () ->
                            new MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63)));
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
              "Isolated featured-card acceptance");
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

  private static final Set<String> CARD_FIELDS =
      Set.of(
          "storeId", "merchantId", "storeName", "merchantName", "cityCode", "address",
          "avatarUrl", "avatarUrlExpiresAt", "intro", "categoryCode", "categoryName",
          "rating", "monthlySold", "tags", "services", "promotion");
  private static final Set<String> SERVICE_FIELDS =
      Set.of(
          "serviceId", "serviceName", "categoryCode", "categoryName", "salePrice", "listPrice",
          "durationMinutes", "fulfillmentType", "soldCount");
  private static final Set<String> RATING_FIELDS = Set.of("score", "count", "available");

  @Test
  void featuredCardsFollowContract74() throws Exception {
    start(true);
    // ---- dictionary: 11 legacy rows + the approved V29 backfill/tightening, one disabled ----
    Map<String, Long> categoryIds = new LinkedHashMap<>();
    String[][] dictionary = {
      {"宠物美容", "10"}, {"遛狗陪护", "20"}, {"宠物寄养", "30"}, {"宠物训练", "40"},
      {"上门喂养", "50"}, {"兽医助理", "60"}, {"小宠寄养", "70"}, {"异宠上门喂养", "80"},
      {"异宠健康检查", "90"}, {"绿植养护", "100"}, {"植物代养", "110"}
    };
    for (String[] entry : dictionary) {
      long id = db.ids.nextId();
      db.jdbc.update(
          "INSERT INTO service_category(id,category_name,status,sort_no,created_at,updated_at)"
              + " VALUES(?,?,'ENABLED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          id, entry[0], Integer.parseInt(entry[1]));
      categoryIds.put(entry[0], id);
    }
    executeScript(
        "backend/pet-boot/src/main/resources/db/service-migration/V29__service_category_dictionary.sql");
    db.jdbc.update("UPDATE service_category SET status='DISABLED' WHERE category_name='兽医助理'");

    // ---- a real approved-and-signed merchant with a second SQL-seeded store ----
    long merchantId = approvedSignedStore("feat-owner", "13800009911", 601);
    long store1 =
        db.jdbc.queryForObject(
            "SELECT id FROM merchant_store WHERE merchant_id=? ORDER BY id LIMIT 1",
            Long.class, merchantId);
    long store2 = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO merchant_store(id,merchant_id,store_name,address,longitude,latitude,phone,"
            + " status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,NULL,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        store2, merchantId, "星河服务生活馆二店", "测试服务地址",
        new BigDecimal("121.4"), new BigDecimal("31.2"));

    // ---- services: ACTIVE supply, one OFFLINE row, one ACTIVE row in a disabled category ----
    long groomingA = seedService(merchantId, store1, categoryIds.get("宠物美容"),
        "专业美容·小型犬", new BigDecimal("80.00"), new BigDecimal("100.00"));
    long groomingB = seedService(merchantId, store1, categoryIds.get("宠物美容"),
        "洗护SPA套餐", new BigDecimal("128.00"), null);
    long walking = seedService(merchantId, store1, categoryIds.get("遛狗陪护"),
        "遛狗-半小时", new BigDecimal("60.00"), null);
    seedService(merchantId, store1, categoryIds.get("兽医助理"), "兽医助理-上门",
        new BigDecimal("200.00"), null);
    seedService(merchantId, store1, categoryIds.get("宠物美容"), "寄养-已下架",
        new BigDecimal("90.00"), null);
    db.jdbc.update("UPDATE service_item SET status='OFFLINE' WHERE service_name=?", "寄养-已下架");
    long groomingC = seedService(merchantId, store2, categoryIds.get("宠物美容"),
        "宠物美容-造型修剪", new BigDecimal("66.00"), new BigDecimal("88.00"));

    // ---- effective-order facts: A=5, B=1, C=2; partial refund and canceled never count ----
    for (int i = 0; i < 5; i++) order(9_100L + i, store1, groomingA, "0.00");
    order(9_200L, store1, groomingB, "0.00");
    for (int i = 0; i < 2; i++) order(9_300L + i, store2, groomingC, "0.00");
    order(9_400L, store1, groomingA, "50.00"); // partial refund: excluded
    order(9_401L, store1, groomingA, "100.00"); // full refund: excluded
    db.jdbc.update(
        "INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,service_id,pet_id,"
            + "reservation_id,order_stage,payment_status,verification_status,fulfillment_type,"
            + "original_amount,discount_amount,pay_amount,refunded_amount,appointment_start_at,"
            + "appointment_end_at,paid_at,created_at,updated_at)"
            + " VALUES(9402,9402,1,?,?,?,?,?,'CANCELED','PAID','UNVERIFIED','IN_STORE',100.00,0.00,"
            + "100.00,0.00,'2030-01-01 09:00:00','2030-01-01 10:00:00','2026-10-05 00:00:00',"
            + "'2026-10-01 08:00:00',UTC_TIMESTAMP(3))",
        merchantId, store1, groomingA, 1, 99402);

    // ---- review facts: store1 six included (avg 4.9), store2 three (<5), one excluded ----
    long[] orderIds = new long[7];
    for (int i = 0; i < 7; i++) orderIds[i] = 95_000L + i;
    String[] includedScores = {"5.0", "4.9", "4.8", "4.9", "5.0", "4.8"};
    for (int i = 0; i < 6; i++) {
      review(orderIds[i], store1, groomingA, includedScores[i], 1);
    }
    review(orderIds[6], store1, groomingA, "1.0", 0); // score_included=0: never counted
    String[] store2Scores = {"4.6", "4.7", "4.5"};
    for (int i = 0; i < 3; i++) review(96_000L + i, store2, groomingC, store2Scores[i], 1);

    // ---- merchant-owned card facts: intro + promotion, avatar unset ----
    db.jdbc.update(
        "INSERT INTO merchant_store_profile(store_id,merchant_id,intro,announcement,business_hours,"
            + "promo_level,promo_title,promo_text,photo_asset_ids,avatar_asset_id,business_status,"
            + "version,created_at,updated_at)"
            + " VALUES(?,?,'一站式宠物生活馆，提供专业美容与家庭寄养。',NULL,NULL,2,'全场美容5折','专业美容师持证上岗',"
            + "NULL,NULL,'OPEN',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        store1, merchantId);

    String path = "/api/v1/c/stores/featured";

    // ---- full page: eligibility, order, projections, no PII, no-store ----
    Reply page = send("GET", path, null, Map.of());
    assertEquals(200, page.status(), page.redacted());
    assertEquals(
        "no-store", page.headers().firstValue("Cache-Control").orElse(""),
        "the featured read must never be cached");
    assertEquals(2L, ((Number) page.data().get("total")).longValue());
    assertEquals(1, ((Number) page.data().get("page")).intValue());
    assertEquals(10, ((Number) page.data().get("pageSize")).intValue());
    List<Map<String, Object>> items = maps(page.data().get("items"));
    assertEquals(List.of(String.valueOf(store1), String.valueOf(store2)),
        storeIds(items),
        "rated store first (4.9), the <5-review store last (score null sorts last)");

    Map<String, Object> card1 = items.get(0);
    assertEquals(CARD_FIELDS, card1.keySet(), "the card projection is exactly the contract fields");
    assertEquals(String.valueOf(store1), card1.get("storeId"));
    assertEquals(String.valueOf(merchantId), card1.get("merchantId"));
    assertEquals("chengdu", card1.get("cityCode"));
    assertEquals("一站式宠物生活馆，提供专业美容与家庭寄养。", card1.get("intro"));
    assertNull(card1.get("avatarUrl"), "no avatar asset keeps both avatar fields null");
    assertNull(card1.get("avatarUrlExpiresAt"));
    assertEquals("PET_GROOMING", card1.get("categoryCode"),
        "the sold leader's category owns the primary category");
    assertEquals("宠物美容", card1.get("categoryName"));
    assertEquals(List.of("宠物美容"), card1.get("tags"));
    assertEquals(rating("4.9", 6, true), card1.get("rating"));
    assertEquals(6, ((Number) card1.get("monthlySold")).intValue(),
        "store monthly sold counts the store's effective orders (exclusions dropped)");
    Map<String, Object> expectedPromotion = new LinkedHashMap<>();
    expectedPromotion.put("source", "MERCHANT_PROMOTION");
    expectedPromotion.put("offerTag", null);
    expectedPromotion.put("offerTitle", "全场美容5折");
    expectedPromotion.put("offerCopy", "专业美容师持证上岗");
    expectedPromotion.put("campaignId", null);
    assertEquals(expectedPromotion, card1.get("promotion"));
    List<Map<String, Object>> services1 = maps(card1.get("services"));
    assertEquals(2, services1.size(), "at most two service lines");
    assertEquals(List.of(String.valueOf(groomingA), String.valueOf(groomingB)),
        serviceIds(services1), "top-2 by sold DESC then serviceId ASC");
    assertEquals(SERVICE_FIELDS, services1.get(0).keySet());
    assertEquals(String.valueOf(groomingA), services1.get(0).get("serviceId"));
    assertEquals("专业美容·小型犬", services1.get(0).get("serviceName"));
    assertEquals("PET_GROOMING", services1.get(0).get("categoryCode"));
    assertEquals("80.00", services1.get(0).get("salePrice"));
    assertEquals("100.00", services1.get(0).get("listPrice"));
    assertEquals(45, ((Number) services1.get(0).get("durationMinutes")).intValue());
    assertEquals("IN_STORE", services1.get(0).get("fulfillmentType"));
    assertEquals(5, ((Number) services1.get(0).get("soldCount")).intValue());
    assertEquals("128.00", services1.get(1).get("salePrice"));
    assertNull(services1.get(1).get("listPrice"), "unset list price stays null");
    assertEquals(1, ((Number) services1.get(1).get("soldCount")).intValue());

    Map<String, Object> card2 = items.get(1);
    assertEquals(rating(null, 3, true), card2.get("rating"),
        "three included reviews: score stays null, count stays honest, block stays available");
    assertEquals(2, ((Number) card2.get("monthlySold")).intValue());
    assertNull(card2.get("promotion"), "no promo fields answer null, never a facade");
    assertNull(card2.get("intro"));
    assertEquals(1, maps(card2.get("services")).size());

    // ---- filters: city range, category eligibility, AND-free composition of the card itself ----
    assertEquals(List.of(String.valueOf(store1), String.valueOf(store2)),
        storeIds(maps(send("GET", path + "?cityCode=chengdu", null, Map.of()).data().get("items"))));
    Reply shanghai = send("GET", path + "?cityCode=shanghai", null, Map.of());
    assertEquals(200, shanghai.status(), shanghai.redacted());
    assertEquals(0L, ((Number) shanghai.data().get("total")).longValue());
    assertTrue(maps(shanghai.data().get("items")).isEmpty(), "open city without supply is empty");

    Reply walkingOnly = send("GET", path + "?categoryCode=DOG_WALKING", null, Map.of());
    assertEquals(200, walkingOnly.status(), walkingOnly.redacted());
    List<Map<String, Object>> walkingItems = maps(walkingOnly.data().get("items"));
    assertEquals(List.of(String.valueOf(store1)), storeIds(walkingItems),
        "only the store with an ACTIVE service in the category survives the filter");
    assertEquals("PET_GROOMING", walkingItems.get(0).get("categoryCode"),
        "the filter gates eligibility only; the card content keeps its own rules");

    Reply emptyCategory = send("GET", path + "?categoryCode=PET_BOARDING", null, Map.of());
    assertEquals(200, emptyCategory.status(), emptyCategory.redacted());
    assertEquals(0L, ((Number) emptyCategory.data().get("total")).longValue());
    assertTrue(maps(emptyCategory.data().get("items")).isEmpty());

    assertEquals(400, send("GET", path + "?categoryCode=VET_ASSISTANT", null, Map.of()).status(),
        "a disabled category is 400 even with a historic ACTIVE service");
    assertEquals(400, send("GET", path + "?categoryCode=UNKNOWN", null, Map.of()).status());
    assertEquals(400, send("GET", path + "?categoryCode=pet_grooming", null, Map.of()).status());

    // ---- paging: honest total, stable order, empty tail ----
    assertEquals(List.of(String.valueOf(store1)),
        storeIds(maps(send("GET", path + "?pageSize=1&page=1", null, Map.of()).data().get("items"))));
    assertEquals(List.of(String.valueOf(store2)),
        storeIds(maps(send("GET", path + "?pageSize=1&page=2", null, Map.of()).data().get("items"))));
    Reply tail = send("GET", path + "?pageSize=1&page=3", null, Map.of());
    assertEquals(200, tail.status(), tail.redacted());
    assertTrue(maps(tail.data().get("items")).isEmpty());
    assertEquals(2L, ((Number) tail.data().get("total")).longValue());

    // ---- argument discipline: page/pageSize bounds, unknown parameters, legacy city rejected ----
    assertEquals(400, send("GET", path + "?page=0", null, Map.of()).status());
    assertEquals(400, send("GET", path + "?page=10001", null, Map.of()).status());
    assertEquals(400, send("GET", path + "?pageSize=0", null, Map.of()).status());
    assertEquals(400, send("GET", path + "?pageSize=21", null, Map.of()).status());
    assertEquals(400, send("GET", path + "?city=chengdu", null, Map.of()).status(),
        "the featured read uses cityCode; the /c/stores city name is not accepted");
    assertEquals(400, send("GET", path + "?extra=1", null, Map.of()).status());
    assertEquals(400, send("GET", path + "?cityCode=chengdu&cityCode=chengdu", null, Map.of()).status());
    assertEquals(400, send("GET", path + "?cityCode=beijing", null, Map.of()).status());
    assertEquals(400, send("GET", path + "?cityCode=CHENGDU", null, Map.of()).status());

    // ---- STR-D8: anonymous passes, a carried invalid bearer still 401s ----
    assertEquals(200, send("GET", path, null, Map.of()).status());
    assertEquals(401, send("GET", path, null, bearer("invalid-token")).status());

    // ---- damaged merchant status fails the whole page closed (503) ----
    db.jdbc.update("UPDATE merchant SET status='CORRUPTED' WHERE id=?", merchantId);
    assertUnavailable(send("GET", path, null, Map.of()));
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);
    assertEquals(200, send("GET", path, null, Map.of()).status());
  }

  /**
   * B-CARD-02/03 second scenario: with the review kernel not assembled the rating degrades to
   * {@code available=false} while the sales facts stay available (honest zeros), and a bound
   * avatar without the private-asset signing pipeline fails the whole page closed (503) — never an
   * unsigned URL.
   */
  @Test
  void degradedRatingAndFailClosedAvatarFollowContract74() throws Exception {
    start(false); // review kernel deliberately not assembled: the rating must degrade
    Map<String, Long> categoryIds = new LinkedHashMap<>();
    for (String[] entry :
        new String[][] {{"宠物美容", "10"}, {"遛狗陪护", "20"}, {"宠物寄养", "30"}, {"宠物训练", "40"},
            {"上门喂养", "50"}, {"兽医助理", "60"}, {"小宠寄养", "70"}, {"异宠上门喂养", "80"},
            {"异宠健康检查", "90"}, {"绿植养护", "100"}, {"植物代养", "110"}}) {
      long id = db.ids.nextId();
      db.jdbc.update(
          "INSERT INTO service_category(id,category_name,status,sort_no,created_at,updated_at)"
              + " VALUES(?,?,'ENABLED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          id, entry[0], Integer.parseInt(entry[1]));
      categoryIds.put(entry[0], id);
    }
    executeScript(
        "backend/pet-boot/src/main/resources/db/service-migration/V29__service_category_dictionary.sql");

    long merchantId = approvedSignedStore("feat-degrade", "13800009921", 651);
    long storeId =
        db.jdbc.queryForObject(
            "SELECT id FROM merchant_store WHERE merchant_id=? ORDER BY id LIMIT 1",
            Long.class, merchantId);
    long service = seedService(merchantId, storeId, categoryIds.get("宠物美容"), "美容-降级",
        new BigDecimal("70.00"), null);
    db.jdbc.update(
        "INSERT INTO merchant_store_profile(store_id,merchant_id,intro,announcement,business_hours,"
            + "promo_level,promo_title,promo_text,photo_asset_ids,avatar_asset_id,business_status,"
            + "version,created_at,updated_at)"
            + " VALUES(?,?,'降级场景门店',NULL,NULL,NULL,NULL,NULL,NULL,650001,'OPEN',1,"
            + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        storeId, merchantId);

    String path = "/api/v1/c/stores/featured";

    // A bound avatar without the signing pipeline fails the whole page closed.
    assertUnavailable(send("GET", path, null, Map.of()));
    db.jdbc.update("UPDATE merchant_store_profile SET avatar_asset_id=NULL WHERE store_id=?", storeId);

    Reply page = send("GET", path, null, Map.of());
    assertEquals(200, page.status(), page.redacted());
    assertEquals(1L, ((Number) page.data().get("total")).longValue());
    Map<String, Object> card = maps(page.data().get("items")).get(0);
    assertEquals(rating(null, 0, false), card.get("rating"),
        "an unassembled review kernel hides the rating, never a fabricated zero");
    // No orders were seeded: the available sales aggregation answers honest zeros.
    assertEquals(0, ((Number) card.get("monthlySold")).intValue());
    assertEquals(0, ((Number) maps(card.get("services")).get(0).get("soldCount")).intValue());
    assertEquals(String.valueOf(service), maps(card.get("services")).get(0).get("serviceId"));
    // STR-D8 discipline still holds in the degraded assembly.
    assertEquals(200, send("GET", path, null, Map.of()).status());
    assertEquals(401, send("GET", path, null, bearer("invalid-token")).status());
  }

  // ---------------------------------------------------------------- fixtures

  private void executeScript(String relativePath) throws Exception {
    try (var connection = db.source.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection,
          new EncodedResource(
              new FileSystemResource(root().resolve(relativePath)), StandardCharsets.UTF_8));
    }
  }

  private long seedService(
      long merchantId, long storeId, long categoryId, String name, BigDecimal price,
      BigDecimal listPrice) {
    long id = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO service_item(id,merchant_id,store_id,category_id,service_name,description,"
            + "price,list_price,duration_minutes,fulfillment_type,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,NULL,?, ?,45,'IN_STORE','ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        id, merchantId, storeId, categoryId, name, price, listPrice);
    return id;
  }

  /** One effective pet_order row (paid, not canceled, refunded_amount as given). */
  private void order(long id, long storeId, long serviceId, String refundedAmount) {
    long merchantId =
        db.jdbc.queryForObject("SELECT merchant_id FROM merchant_store WHERE id=?", Long.class, storeId);
    db.jdbc.update(
        "INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,service_id,pet_id,"
            + "reservation_id,order_stage,payment_status,verification_status,fulfillment_type,"
            + "original_amount,discount_amount,pay_amount,refunded_amount,appointment_start_at,"
            + "appointment_end_at,paid_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?, 'COMPLETED','PAID','UNVERIFIED','IN_STORE',"
            + "100.00,0.00,100.00,?,'2030-01-01 09:00:00','2030-01-01 10:00:00',"
            + "'2026-10-05 00:00:00','2026-10-01 08:00:00',UTC_TIMESTAMP(3))",
        id, id, 1L, merchantId, storeId, serviceId, 1L, 9_000_000L + id, refundedAmount);
  }

  /** One review row; {@code included=0} rows never reach the public rating fact. */
  private void review(long orderId, long storeId, long serviceId, String composite, int included) {
    long merchantId =
        db.jdbc.queryForObject("SELECT merchant_id FROM merchant_store WHERE id=?", Long.class, storeId);
    db.jdbc.update(
        "INSERT INTO review(id,order_id,user_id,merchant_id,store_id,service_id,store_score,"
            + "service_score,staff_score,composite_score,score_included,visibility_status,"
            + "created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?, ?, 'PUBLISHED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
        97_000_000L + orderId, orderId, 1L, merchantId, storeId, serviceId,
        new BigDecimal(composite), new BigDecimal(composite), new BigDecimal(composite),
        new BigDecimal(composite), included);
  }

  /** Real application -> review/verify/approve -> agreement consent; returns the merchant id. */
  private long approvedSignedStore(String openid, String phone, long assetBase) throws Exception {
    Map<String, Object> owner = consumerLogin(openid, phone);
    String token = str(owner, "accessToken");
    String ownerId = str(owner, "userId");
    for (long id = assetBase; id <= assetBase + 3; id++) {
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("feat-asset-" + id), "image/jpeg", 512, "READY"));
    }
    Reply created = send("POST", "/api/v1/c/merchant-applications", draft(assetBase), bearer(token));
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
              "subjectName", license ? "星河服务生活馆" : "张三",
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
    long merchantId = Long.parseLong(String.valueOf(approved.data().get("reservedMerchantId")));
    long agreementId = db.ids.nextId();
    String content = "测试环境商家服务协议", hash = sha(content);
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
        agreementId,
        "feat-http-v1",
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
                "agreementVersion", "feat-http-v1",
                "contentSha256", hash,
                "accepted", true),
            bearer(token));
    assertEquals(201, signed.status(), signed.redacted());
    return merchantId;
  }

  private static Map<String, Object> draft(long assetBase) {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("merchantName", "星河服务生活馆");
    draft.put("contactName", "张三");
    draft.put("contactPhone", "13800009911");
    draft.put("email", "feat-owner@example.test");
    draft.put("merchantTypeCode", "PET_LIFE_STORE");
    draft.put("cityCode", "chengdu");
    draft.put("address", "测试服务地址");
    draft.put("longitude", "121.4");
    draft.put("latitude", "31.2");
    draft.put("storePhotoAssetIds", List.of(String.valueOf(assetBase)));
    draft.put("businessLicenseAssetId", String.valueOf(assetBase + 1));
    draft.put("idCardFrontAssetId", String.valueOf(assetBase + 2));
    draft.put("idCardBackAssetId", String.valueOf(assetBase + 3));
    return draft;
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
    if (!headers.containsKey("X-Request-Id") && !method.equals("GET")) {
      builder.header("X-Request-Id", UUID.randomUUID().toString());
    }
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

  /** Expected rating block; null values are compared with key order preserved. */
  private static Map<String, Object> rating(String score, int count, boolean available) {
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("score", score);
    expected.put("count", count);
    expected.put("available", available);
    return expected;
  }

  private static void assertUnavailable(Reply reply) {
    assertEquals(503, reply.status(), reply.redacted());
    assertEquals(
        CommonApiCodes.DEPENDENCY_UNAVAILABLE,
        reply.envelope().get("code"),
        "facts-source failure must fail closed with the dependency code");
  }

  private static List<Map<String, Object>> maps(Object value) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> result = (List<Map<String, Object>>) value;
    return result;
  }

  private static List<String> storeIds(List<Map<String, Object>> items) {
    return items.stream().map(item -> String.valueOf(item.get("storeId"))).toList();
  }

  private static List<String> serviceIds(List<Map<String, Object>> items) {
    return items.stream().map(item -> String.valueOf(item.get("serviceId"))).toList();
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

    /** Codes and messages are contract text, never credentials — safe for failure reports. */
    String redacted() {
      return "Reply[status=" + status + ", code=" + envelope.get("code")
          + ", message=" + envelope.get("message") + "]";
    }
  }
}
