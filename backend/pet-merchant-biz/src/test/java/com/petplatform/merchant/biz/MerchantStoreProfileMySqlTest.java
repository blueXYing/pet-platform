package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.*;
import com.petplatform.merchant.api.command.UpdateStoreProfileCommand;
import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi.StoreProfileQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantStoreProfileApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.MerchantStoreProfileService.OwnedAsset;
import com.petplatform.merchant.biz.application.MerchantStoreProfileService.PhotoAssetPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Contract 27 §6.4 supplement acceptance over isolated real MySQL: lazily created profile row,
 * full-replace update with the supplement-23 binding (replay + key conflict), the version CAS,
 * the staff-write gate (OWNER/ACTIVE/APPROVED/SIGNED), the changed-field-names-only audit and
 * the anti-enumeration 404 for foreign coordinates.
 */
class MerchantStoreProfileMySqlTest {
    private static final long MERCHANT = 9_100_000_000_000_001L;
    private static final long STORE = 9_100_000_000_000_002L;
    private static final long OWNER = 9_100_000_000_000_003L;
    private static final long OTHER = 9_100_000_000_000_004L;
    private static final AtomicLong ids = new AtomicLong(9_300_000_000_000_000L);

    private static String id(long value) { return Long.toString(value); }
    private static QueryContext query(long owner) {
        return new QueryContext("test", OperatorType.USER, id(owner));
    }
    private static CommandContext command(String requestId, long owner) {
        return new CommandContext(requestId, "test", OperatorType.USER, id(owner), "MINIAPP");
    }
    private static MerchantStoreProfileApiImpl api(MySqlMerchantApplicationSchemaTestDatabase db,
            ApplicationReviewFactsReader facts, PhotoAssetPort photos) {
        return new MerchantStoreProfileApiImpl(db.dataSource(), ids::incrementAndGet, facts, photos,
                Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC));
    }
    private static PhotoAssetPort photos(String... readyIds) {
        Set<String> ready = Set.of(readyIds);
        return (owner, assetIds) -> {
            List<OwnedAsset> resolved = new ArrayList<>();
            for (Long assetId : assetIds) {
                String value = Long.toString(assetId);
                resolved.add(new OwnedAsset(assetId, "image/png",
                        ready.contains(value) ? "READY" : "DELETED"));
            }
            return resolved;
        };
    }

    private static void seed(MySqlMerchantApplicationSchemaTestDatabase db) {
        JdbcTemplate jdbc = db.jdbc();
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                MERCHANT, OWNER, "店铺资料测试商家");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,longitude,latitude,phone,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?, 'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STORE, MERCHANT, "门店", "测试地址 1 号", "121.4737000", "31.2304000", "13800138000");
        jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,subject_verification_status,version,created_at,updated_at) VALUES(?,?,?,'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                2001L, OWNER, MERCHANT);
        String content = "店铺资料测试协议";
        String hash = MySqlMerchantAgreementTestDatabase.sha256(content);
        jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                2002L, "profile-test", content, hash, OWNER);
        jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                2003L, MERCHANT, 2002L, OWNER, hash);
        installProfileSchema(db);
    }

    private static void installProfileSchema(MySqlMerchantApplicationSchemaTestDatabase db) {
        try {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(
                    root.resolve("docs/03-database/27-Merchant-Store-Profile-Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            assertNotNull(root);
            try (var connection = db.dataSource().getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                        root.resolve("docs/03-database/27-Merchant-Store-Profile-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("profile schema install failed", failure);
        }
    }

    private static UpdateStoreProfileCommand update(String requestId, String expectedVersion,
            String intro, Integer promoLevel) {
        return new UpdateStoreProfileCommand(id(MERCHANT), id(STORE), intro, "欢迎光临",
                List.of(), List.of(new MerchantStoreProfileDTO.BusinessHour(List.of(1, 2, 3),
                        "09:00", "18:00")), promoLevel, "月度促销", "全场八折", expectedVersion,
                command(requestId, OWNER));
    }

    @Test
    void readDefaultsThenFullReplaceCasAndAudit() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            var facts = new ApplicationReviewFactsReader.Facts("APPROVED");
            var facade = api(db, id -> facts, photos("88001"));
            // Defaults before the first save: version 0, all editable fields null.
            MerchantStoreProfileDTO first = facade.getStoreProfile(
                    new StoreProfileQuery(id(MERCHANT), id(STORE), query(OWNER)));
            assertEquals("门店", first.readonlyFacts().storeName());
            assertEquals("138****8000", first.readonlyFacts().phoneMasked());
            assertEquals("OPERATING", first.readonlyFacts().operatingStatus());
            assertEquals("APPROVED", first.readonlyFacts().applicationStatus());
            assertEquals("0", first.profile().version());
            assertNull(first.profile().intro());

            MerchantStoreProfileDTO saved = facade.updateStoreProfile(
                    update(UUID.randomUUID().toString(), "0", "专注宠物洗护", 8));
            assertEquals("1", saved.profile().version());
            assertEquals("专注宠物洗护", saved.profile().intro());
            assertEquals(1, saved.profile().businessHours().size());
            assertEquals(8, saved.profile().promoLevel());
            // Second save rides the CAS; the audit keeps field names only.
            MerchantStoreProfileDTO second = facade.updateStoreProfile(
                    update(UUID.randomUUID().toString(), "1", "专注宠物洗护与寄养", 8));
            assertEquals("2", second.profile().version());
            assertEquals(2, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_store_profile_audit", Integer.class));
            String changed = db.jdbc().queryForObject(
                    "SELECT changed_fields FROM merchant_store_profile_audit ORDER BY occurred_at DESC, id DESC LIMIT 1",
                    String.class);
            assertEquals("intro", changed);
            // Version conflict: stale expectedVersion is 409 COMMON_CONFLICT.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> facade.updateStoreProfile(
                            update(UUID.randomUUID().toString(), "1", "过期版本", 8))).code());
        }
    }

    @Test
    void replayReturnsOriginalReceiptWithoutSecondWrite() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            var facade = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"),
                    photos("88001"));
            String key = UUID.randomUUID().toString();
            MerchantStoreProfileDTO first = facade.updateStoreProfile(update(key, "0", "首次保存", 5));
            assertEquals("1", first.profile().version());
            MerchantStoreProfileDTO replay = facade.updateStoreProfile(update(key, "0", "首次保存", 5));
            assertEquals(first.profile(), replay.profile());
            assertFalse(replay.profile().version().equals("2"));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_store_profile", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_store_profile_audit", Integer.class));
            // Same key, different parameters: 409 IDEMPOTENCY_KEY_CONFLICT.
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> facade.updateStoreProfile(update(key, "1", "异参重放", 5))).code());
        }
    }

    @Test
    void gatesForeignOwnerUnapprovedApplicationAndBadAssets() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            var facade = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"),
                    photos("88001"));
            // Foreign owner: anti-enumeration 404 on read, 404 on write authorization.
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> facade.getStoreProfile(
                            new StoreProfileQuery(id(MERCHANT), id(STORE), query(OTHER)))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> facade.updateStoreProfile(new UpdateStoreProfileCommand(id(MERCHANT),
                            id(STORE), "越权", null, List.of(), List.of(), null, null, null, "0",
                            command(UUID.randomUUID().toString(), OTHER)))).code());
            // Unapproved application: the write gate fails with 409 (read stays open).
            var facadeDraft = api(db, id -> new ApplicationReviewFactsReader.Facts("DRAFT"),
                    photos("88001"));
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> facadeDraft.updateStoreProfile(
                            update(UUID.randomUUID().toString(), "0", "未过审", null))).code());
            // An asset that does not resolve READY is 400.
            var facadeAssets = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"),
                    photos()); // nothing READY
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> facadeAssets.updateStoreProfile(new UpdateStoreProfileCommand(id(MERCHANT),
                            id(STORE), "坏素材", null, List.of("88001"), List.of(), null, null, null,
                            "0", command(UUID.randomUUID().toString(), OWNER)))).code());
            // Malformed business hours (end <= start) and duplicate photo ids are 400.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> facadeAssets.updateStoreProfile(new UpdateStoreProfileCommand(id(MERCHANT),
                            id(STORE), null, null, List.of(), List.of(
                                    new MerchantStoreProfileDTO.BusinessHour(List.of(1), "18:00", "09:00")),
                            null, null, null, "0", command(UUID.randomUUID().toString(), OWNER)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> facadeAssets.updateStoreProfile(new UpdateStoreProfileCommand(id(MERCHANT),
                            id(STORE), null, null, List.of("88001", "88001"), List.of(), null, null,
                            null, "0", command(UUID.randomUUID().toString(), OWNER)))).code());
            // The no-photo-facts port fails photo writes closed (503).
            var facadeNoPort = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"), null);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> facadeNoPort.updateStoreProfile(new UpdateStoreProfileCommand(id(MERCHANT),
                            id(STORE), null, null, List.of("88001"), List.of(), null, null, null,
                            "0", command(UUID.randomUUID().toString(), OWNER)))).code());
        }
    }

    @Test
    void concurrentSecondSavesLoseTheVersionRaceExactlyOnce() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            var facade = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"),
                    photos("88001"));
            facade.updateStoreProfile(update(UUID.randomUUID().toString(), "0", "并发基线", null));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                Callable<ApiException> racer = () -> {
                    ready.countDown();
                    ready.await();
                    try {
                        facade.updateStoreProfile(update(UUID.randomUUID().toString(), "1", "并发写", null));
                        return null;
                    } catch (ApiException failure) {
                        return failure;
                    }
                };
                List<Future<ApiException>> results = pool.invokeAll(List.of(racer, racer));
                long wins = 0;
                long conflicts = 0;
                for (Future<ApiException> future : results) {
                    ApiException failure = future.get();
                    if (failure == null) wins++;
                    else if (CommonApiCodes.CONFLICT.equals(failure.code())) conflicts++;
                }
                assertEquals(1, wins);
                assertEquals(1, conflicts);
            } finally {
                pool.shutdownNow();
            }
            assertEquals("2", facade.getStoreProfile(
                    new StoreProfileQuery(id(MERCHANT), id(STORE), query(OWNER))).profile().version());
        }
    }
}
