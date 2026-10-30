package com.petplatform.marketing.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.marketing.api.MarketingAdminApi;
import com.petplatform.marketing.api.MarketingAdminApi.AdminAuthorization;
import com.petplatform.marketing.api.MarketingAdminApi.CampaignInput;
import com.petplatform.marketing.api.MarketingAdminApi.CampaignStatusCommand;
import com.petplatform.marketing.api.MarketingAdminApi.CreateCampaignCommand;
import com.petplatform.marketing.api.MarketingAdminApi.CreateHomeBlockCommand;
import com.petplatform.marketing.api.MarketingAdminApi.CreatePlacementCommand;
import com.petplatform.marketing.api.MarketingAdminApi.EnrollmentListQuery;
import com.petplatform.marketing.api.MarketingAdminApi.HomeBlockInput;
import com.petplatform.marketing.api.MarketingAdminApi.HomeBlockStatusCommand;
import com.petplatform.marketing.api.MarketingAdminApi.PlacementInput;
import com.petplatform.marketing.api.MarketingAdminApi.PlacementStatusCommand;
import com.petplatform.marketing.api.MarketingAdminApi.ReviewEnrollmentCommand;
import com.petplatform.marketing.api.MarketingAdminApi.UpdateCampaignCommand;
import com.petplatform.marketing.api.MarketingMerchantApi;
import com.petplatform.marketing.api.MarketingMerchantApi.EnrollCommand;
import com.petplatform.marketing.api.MarketingMerchantApi.MerchantScope;
import com.petplatform.marketing.api.MarketingPublicApi;
import com.petplatform.marketing.api.MarketingPublicApi.PublicHomeBlockQuery;
import com.petplatform.marketing.api.MarketingPublicApi.PublicPlacementQuery;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.apiimpl.MarketingAdminApiImpl;
import com.petplatform.marketing.biz.apiimpl.MarketingMerchantApiImpl;
import com.petplatform.marketing.biz.apiimpl.MarketingPublicApiImpl;
import com.petplatform.marketing.biz.application.MarketingAdminAuthorizationPort;
import com.petplatform.marketing.biz.application.MarketingCityCatalogPort;
import com.petplatform.marketing.biz.application.MarketingCouponFactsPort;
import com.petplatform.marketing.biz.application.MarketingServiceCategoryPort;
import com.petplatform.marketing.biz.application.MarketingStoreFactsPort;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * B-MKT-01/02/04/05 acceptance over isolated real MySQL running the marketing migrations
 * V33→V36 (contract 71 v0.3 hot-service patch): the platform-wide single ENABLED
 * {@code HOT_SERVICE_TOP} placement (409 PLACEMENT_LIMIT_EXCEEDED on a second enable, C-side
 * answers 0/1 after the city-scope filter), the {@code HOT_SERVICE} home-block landing with a
 * nullable target value, the campaign {@code serviceCategoryCodes} dictionary-validation
 * switch (default off), the placement/home-block CHECK whitelists, and the existing
 * HOME_BANNER max-3 / campaign publish-enroll-review / requestId idempotency / version-conflict
 * regression surface.
 */
class MarketingHotServiceMySqlTest {

    private static final long MERCHANT = 7_500_000_000_000_001L;
    private static final long STORE_A = 7_500_000_000_000_002L;
    private static final long USER = 8_500_000_000_000_101L;
    private static final String MERCHANT_ID = Long.toUnsignedString(MERCHANT);

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime WIN_START = OffsetDateTime.parse("2026-11-10T00:00:00Z");
    private static final OffsetDateTime WIN_END = OffsetDateTime.parse("2026-12-10T00:00:00Z");
    private static final OffsetDateTime ENROLL_START = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private static final OffsetDateTime ENROLL_END = OffsetDateTime.parse("2026-12-01T00:00:00Z");
    private static final OffsetDateTime PLACEMENT_START = OffsetDateTime.parse("2026-11-12T00:00:00Z");
    private static final OffsetDateTime PLACEMENT_END = OffsetDateTime.parse("2026-11-20T00:00:00Z");

    private static final AdminAuthorization AUTH = new AdminAuthorization("sess-1", 1, "1001");

    /** Recording dictionary stub: PET_GROOMING/DOG_WALKING enabled, everything else rejected. */
    private static final class DictionaryStub implements MarketingServiceCategoryPort {
        final List<String> asked = new ArrayList<>();

        @Override
        public boolean usable(String categoryCode) {
            asked.add(categoryCode);
            return Set.of("PET_GROOMING", "DOG_WALKING").contains(categoryCode);
        }
    }

    @Test
    void hotServiceTopEnablesExactlyOnePlatformWide() throws Exception {
        try (Database db = new Database()) {
            MarketingAdminApi admin = db.admin(false, new DictionaryStub());
            String campaign = db.publishedEnrollCampaign(admin);
            String first = db.placement(admin, campaign, "HOT_SERVICE_TOP", "shanghai");
            assertEquals("ENABLED", enable(admin, first, 1).status());

            // A second platform-wide ENABLED placement is rejected regardless of city bucket.
            String second = db.placement(admin, campaign, "HOT_SERVICE_TOP", "beijing");
            assertLimitExceeded(() -> enable(admin, second, 1));

            // After the first is disabled the second can take over, and never both.
            assertEquals("DISABLED", disable(admin, first, 2).status());
            assertEquals("ENABLED", enable(admin, second, 1).status());
            assertLimitExceeded(() -> enable(admin, first, 3));

            // The limit counts by status, not by the delivery window: the surviving ENABLED
            // row count stays at exactly one.
            assertEquals(1L, db.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM marketing_placement
                    WHERE position_code='HOT_SERVICE_TOP' AND status='ENABLED'
                    """, Long.class));

            // Enable writes the append-only campaign audit like every other placement action.
            Long audits = db.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM marketing_campaign_audit
                    WHERE action_type='PLACEMENT_ENABLE'
                    """, Long.class);
            assertTrue(audits >= 1, "placement enable must be audited");
        }
    }

    @Test
    void hotServiceTopCAnswersZeroOrOneWithinCityScope() throws Exception {
        try (Database db = new Database()) {
            MarketingAdminApi admin = db.admin(false, new DictionaryStub());
            MarketingPublicApi publicApi = db.publicApi();
            String campaign = db.publishedEnrollCampaign(admin);
            String placement = db.placement(admin, campaign, "HOT_SERVICE_TOP", "shanghai");

            // Nothing enabled yet: the hot-service page hides the banner with an empty list.
            assertEquals(List.of(), publicApi.listPlacements(
                    new PublicPlacementQuery("HOT_SERVICE_TOP", "shanghai")));

            assertEquals("ENABLED", enable(admin, placement, 1).status());
            assertEquals(1, publicApi.listPlacements(
                    new PublicPlacementQuery("HOT_SERVICE_TOP", "shanghai")).size());
            // The unique banner scoped to another city stays invisible there.
            assertEquals(List.of(), publicApi.listPlacements(
                    new PublicPlacementQuery("HOT_SERVICE_TOP", "beijing")));
            // A city-less request answers the single placement (at most one, contract 71 §3.1).
            assertEquals(1, publicApi.listPlacements(
                    new PublicPlacementQuery("HOT_SERVICE_TOP", null)).size());
            assertEquals("HOT_SERVICE_TOP", publicApi.listPlacements(
                    new PublicPlacementQuery("HOT_SERVICE_TOP", null)).get(0).positionCode());

            // Disabling hides the banner immediately; an unknown position stays a 400.
            assertEquals("DISABLED", disable(admin, placement, 2).status());
            assertEquals(List.of(), publicApi.listPlacements(
                    new PublicPlacementQuery("HOT_SERVICE_TOP", "shanghai")));
            assertApi(CommonApiCodes.INVALID_ARGUMENT, () -> publicApi.listPlacements(
                    new PublicPlacementQuery("NOT_A_POSITION", null)));
        }
    }

    @Test
    void homeBannerKeepsMaxThreeAndOtherPositionsUnrestricted() throws Exception {
        try (Database db = new Database()) {
            MarketingAdminApi admin = db.admin(false, new DictionaryStub());
            String campaign = db.publishedEnrollCampaign(admin);

            for (int i = 1; i <= 4; i++) {
                String id = db.placement(admin, campaign, "HOME_BANNER", null);
                if (i < 4) {
                    assertEquals("ENABLED", enable(admin, id, 1).status());
                } else {
                    assertLimitExceeded(() -> enable(admin, id, 1));
                }
            }
            MarketingPublicApi publicApi = db.publicApi();
            assertEquals(3, publicApi.listPlacements(
                    new PublicPlacementQuery("HOME_BANNER", null)).size());

            // HOME_ACTIVITY / STORE_LIST_TOP stay unlimited.
            for (int i = 0; i < 4; i++) {
                assertEquals("ENABLED", enable(admin,
                        db.placement(admin, campaign, "HOME_ACTIVITY", null), 1).status());
            }
            assertEquals("ENABLED", enable(admin,
                    db.placement(admin, campaign, "STORE_LIST_TOP", null), 1).status());
            assertEquals(4, publicApi.listPlacements(
                    new PublicPlacementQuery("HOME_ACTIVITY", null)).size());
            assertEquals(1, publicApi.listPlacements(
                    new PublicPlacementQuery("STORE_LIST_TOP", null)).size());
        }
    }

    @Test
    void placementRequestIdReplayReturnsTheFirstReceipt() throws Exception {
        try (Database db = new Database()) {
            MarketingAdminApi admin = db.admin(false, new DictionaryStub());
            String campaign = db.publishedEnrollCampaign(admin);
            String placement = db.placement(admin, campaign, "HOT_SERVICE_TOP", null);

            CommandContext context = adminContext();
            MarketingViews.Receipt first = admin.enablePlacement(
                    new PlacementStatusCommand(placement, 1, AUTH, context));
            MarketingViews.Receipt replay = admin.enablePlacement(
                    new PlacementStatusCommand(placement, 1, AUTH, context));
            assertEquals(first.id(), replay.id());
            assertEquals(first.version(), replay.version());
            assertEquals(first.status(), replay.status());

            // Same requestId, different payload → IDEMPOTENCY_KEY_CONFLICT, on the campaign
            // update face where the key space is commandType+operator+requestId+resource.
            // The requestId is fresh: the append-only campaign audit dedupes on request_id, so
            // one requestId drives exactly one audited command.
            String other = db.createdCampaign(admin, "DISPLAY", List.of());
            CommandContext conflicting = new CommandContext(UUID.randomUUID().toString(), "trace-x",
                    OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN_WEB");
            admin.updateCampaign(new UpdateCampaignCommand(other,
                    campaignInput("DISPLAY", List.of(), "改标题一"), 1, AUTH, conflicting));
            assertApi(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, () -> admin.updateCampaign(
                    new UpdateCampaignCommand(other, campaignInput("DISPLAY", List.of(), "改标题二"),
                            2, AUTH, conflicting)));
        }
    }

    @Test
    void homeBlockHotServiceTargetAnswersNullableTargetValue() throws Exception {
        try (Database db = new Database()) {
            MarketingAdminApi admin = db.admin(false, new DictionaryStub());
            MarketingPublicApi publicApi = db.publicApi();

            // HOT_SERVICE needs no target value: the C-side routes on the type alone.
            String block = db.homeBlock(admin, "HOT_SERVICE", null);
            assertEquals("ENABLED", admin.enableHomeBlock(
                    new HomeBlockStatusCommand(block, 1, AUTH, adminContext())).status());
            List<MarketingViews.HomeBlockCard> blocks =
                    publicApi.listHomeBlocks(new PublicHomeBlockQuery("shanghai"));
            assertEquals(1, blocks.size());
            assertEquals("HOT_SERVICE", blocks.get(0).targetType());
            assertNull(blocks.get(0).targetValue());

            // Every other landing type still requires a target value; supplying one stays fine.
            assertApi(CommonApiCodes.INVALID_ARGUMENT, () ->
                    db.homeBlock(admin, "SERVICE_CATEGORY", null));
            assertNotNull(db.homeBlock(admin, "SERVICE_CATEGORY", "PET_GROOMING"));
        }
    }

    @Test
    void checkConstraintsWhitelistNewEnums() throws Exception {
        try (Database db = new Database()) {
            // V36 rewrote both CHECK whitelists.
            List<Map<String, Object>> clauses = db.jdbc.queryForList("""
                    SELECT constraint_name, check_clause FROM information_schema.check_constraints
                    WHERE constraint_schema = ?
                      AND constraint_name IN ('chk_marketing_placement_position',
                                              'chk_marketing_home_block_target')
                    """, db.name);
            assertEquals(2, clauses.size());
            for (Map<String, Object> clause : clauses) {
                String text = String.valueOf(clause.get("check_clause"));
                if ("chk_marketing_placement_position".equals(clause.get("constraint_name"))) {
                    assertTrue(text.contains("HOT_SERVICE_TOP"), text);
                } else {
                    assertTrue(text.contains("HOT_SERVICE"), text);
                }
            }

            // Placement whitelist rejects an unknown position (negative insert). MySQL CHECK
            // violations surface as a generic data-access failure, never a partial write.
            assertThrows(org.springframework.dao.DataAccessException.class, () -> db.jdbc.update("""
                    INSERT INTO marketing_placement
                        (id,position_code,campaign_id,sort_weight,start_at,end_at,status,version,
                         created_by_id,created_at,updated_at)
                    VALUES (9001,'NOT_A_POSITION',1,0,'2026-11-12 00:00:00','2026-11-20 00:00:00',
                            'DRAFT',1,1,'2026-11-10 00:00:00','2026-11-10 00:00:00')
                    """));
            // Home block whitelist: HOT_SERVICE may carry a NULL target, others may not.
            db.jdbc.update("""
                    INSERT INTO marketing_home_block
                        (id,block_code,position_code,template_code,title,target_type,target_value,
                         sort_weight,start_at,end_at,status,version,created_by_id,created_at,updated_at)
                    VALUES (9002,'blk-ok','HOME_SERVICE','HOT_SERVICE_FEATURE','热门服务',
                            'HOT_SERVICE',NULL,0,'2026-11-12 00:00:00','2026-11-20 00:00:00',
                            'DRAFT',1,1,'2026-11-10 00:00:00','2026-11-10 00:00:00')
                    """);
            assertThrows(org.springframework.dao.DataAccessException.class, () -> db.jdbc.update("""
                    INSERT INTO marketing_home_block
                        (id,block_code,position_code,template_code,title,target_type,target_value,
                         sort_weight,start_at,end_at,status,version,created_by_id,created_at,updated_at)
                    VALUES (9003,'blk-bad','HOME_SERVICE','HOT_SERVICE_FEATURE','缺目标',
                            'SERVICE_CATEGORY',NULL,0,'2026-11-12 00:00:00','2026-11-20 00:00:00',
                            'DRAFT',1,1,'2026-11-10 00:00:00','2026-11-10 00:00:00')
                    """));
        }
    }

    @Test
    void campaignCategoryValidationStaysOffByDefault() throws Exception {
        try (Database db = new Database()) {
            DictionaryStub dictionary = new DictionaryStub();
            MarketingAdminApi admin = db.admin(false, dictionary);
            // Switch off: the historical loose lexical check only; the dictionary is untouched.
            MarketingViews.Receipt created = admin.createCampaign(new CreateCampaignCommand(
                    campaignInput("DISPLAY", List.of("LEGACY_CODE_1"), "存量活动"),
                    AUTH, adminContext()));
            assertNotNull(created);
            assertTrue(dictionary.asked.isEmpty(), "switch off must not consult the dictionary");
        }
    }

    @Test
    void campaignCategoryValidationRejectsUnknownLexicalAndDisabledWhenOn() throws Exception {
        try (Database db = new Database()) {
            DictionaryStub dictionary = new DictionaryStub();
            MarketingAdminApi admin = db.admin(true, dictionary);

            // Empty array means "all categories": never validated.
            admin.createCampaign(new CreateCampaignCommand(
                    campaignInput("DISPLAY", List.of(), "全部分类"), AUTH, adminContext()));
            assertTrue(dictionary.asked.isEmpty());

            // Enabled codes pass and hit the dictionary exactly once per element.
            admin.createCampaign(new CreateCampaignCommand(
                    campaignInput("DISPLAY", List.of("PET_GROOMING", "DOG_WALKING"), "合法活动"),
                    AUTH, adminContext()));
            assertEquals(List.of("PET_GROOMING", "DOG_WALKING"), dictionary.asked);

            // Unknown dictionary code → 400 SERVICE_CATEGORY_INVALID (the lexical form is
            // valid, so the dictionary stub is consulted and rejects it).
            assertApi("SERVICE_CATEGORY_INVALID", () -> admin.createCampaign(
                    new CreateCampaignCommand(
                            campaignInput("DISPLAY", List.of("UNKNOWN_CATEGORY"), "未知分类"),
                            AUTH, adminContext())));
            // Lexically invalid (must be ^[A-Z][A-Z0-9_]{1,31}$) never reaches the dictionary.
            assertApi("SERVICE_CATEGORY_INVALID", () -> admin.createCampaign(
                    new CreateCampaignCommand(
                            campaignInput("DISPLAY", List.of("pet_grooming"), "小写分类"),
                            AUTH, adminContext())));
            assertEquals(List.of("PET_GROOMING", "DOG_WALKING", "UNKNOWN_CATEGORY"),
                    dictionary.asked);

            // Editing revalidates with the switch on, while the switch-off face still accepts
            // the legacy code until ops finishes the cleanup (contract 71 v0.3 §5.4).
            MarketingAdminApi offAdmin = db.admin(false, new DictionaryStub());
            String legacy = db.createdCampaign(offAdmin, "DISPLAY", List.of("LEGACY_CODE_1"));
            assertApi("SERVICE_CATEGORY_INVALID", () -> admin.updateCampaign(
                    new UpdateCampaignCommand(legacy,
                            campaignInput("DISPLAY", List.of("LEGACY_CODE_1"), "待清理活动"),
                            1, AUTH, adminContext())));
            assertEquals(2, offAdmin.updateCampaign(new UpdateCampaignCommand(legacy,
                    campaignInput("DISPLAY", List.of("LEGACY_CODE_1"), "待清理活动改"),
                    1, AUTH, adminContext())).version());
        }
    }

    @Test
    void publishEnrollReviewAndVersionConflictRegression() throws Exception {
        try (Database db = new Database()) {
            MarketingAdminApi admin = db.admin(false, new DictionaryStub());
            MarketingMerchantApi merchant = db.merchantApi();
            String campaign = db.publishedEnrollCampaign(admin);

            // Merchant enrollment inside the window; identical replay returns the first receipt.
            MerchantScope scope = new MerchantScope(USER, MERCHANT, STORE_A);
            CommandContext enrollContext = merchantContext();
            MarketingViews.Receipt enrollment = merchant.enroll(new EnrollCommand(
                    scope, campaign, "5折", "全场五折", "活动价", null, enrollContext));
            assertEquals("SUBMITTED", enrollment.status());
            MarketingViews.Receipt replay = merchant.enroll(new EnrollCommand(
                    scope, campaign, "5折", "全场五折", "活动价", null, enrollContext));
            assertEquals(enrollment.id(), replay.id());
            assertEquals(enrollment.version(), replay.version());
            assertApi("ENROLLMENT_ALREADY_EXISTS", () -> merchant.enroll(new EnrollCommand(
                    scope, campaign, "5折", "全场五折", "活动价", null, merchantContext())));

            // Admin review approves and links the store into the campaign aggregate.
            MarketingViews.Page<MarketingViews.EnrollmentView> pending = admin.listEnrollments(
                    new EnrollmentListQuery(campaign, null, 1, 50, AUTH, adminQuery()));
            assertEquals(1, pending.total());
            MarketingViews.EnrollmentView row = pending.items().get(0);
            MarketingViews.Receipt approved = admin.reviewEnrollment(new ReviewEnrollmentCommand(
                    row.enrollmentId(), "APPROVE", null, row.version(), AUTH, adminContext()));
            assertEquals("APPROVED", approved.status());
            assertEquals("ACTIVE", db.jdbc.queryForObject("""
                    SELECT status FROM marketing_campaign_store
                    WHERE campaign_id=? AND store_id=?
                    """, String.class, Long.parseLong(campaign), STORE_A));
            assertEquals(1L, db.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM marketing_enrollment_audit WHERE action_type='APPROVE'
                    """, Long.class));

            // Optimistic version conflicts stay 409 on the status faces.
            assertApi(CommonApiCodes.CONFLICT, () -> admin.publishCampaign(
                    new CampaignStatusCommand(campaign, 99, AUTH, adminContext())));

            // The published campaign is live for the C-side placements read.
            assertEquals(0, db.publicApi().listPlacements(
                    new PublicPlacementQuery("HOME_ACTIVITY", null)).size());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static CampaignInput campaignInput(String type, List<String> categories, String title) {
        boolean recruit = "MERCHANT_ENROLL".equals(type);
        return new CampaignInput(title + "-名称", type,
                recruit ? "MERCHANT_ENROLL" : "PLATFORM_SELECTED",
                title, null, null, null, null,
                List.of(), categories, WIN_START, WIN_END,
                recruit ? ENROLL_START : null, recruit ? ENROLL_END : null,
                null, null, 0);
    }

    private static HomeBlockInput homeBlockInput(String targetType, String targetValue) {
        return new HomeBlockInput("blk-" + UUID.randomUUID().toString().substring(0, 8),
                "HOME_SERVICE", "HOT_SERVICE_FEATURE", "热门服务入口", null, null, null,
                targetType, targetValue, null, null, null, 0, PLACEMENT_START, PLACEMENT_END);
    }

    private static MarketingViews.Receipt enable(
            MarketingAdminApi admin, String placementId, long expectedVersion) {
        return admin.enablePlacement(new PlacementStatusCommand(
                placementId, expectedVersion, AUTH, adminContext()));
    }

    private static MarketingViews.Receipt disable(
            MarketingAdminApi admin, String placementId, long expectedVersion) {
        return admin.disablePlacement(new PlacementStatusCommand(
                placementId, expectedVersion, AUTH, adminContext()));
    }

    private static void assertLimitExceeded(Runnable action) {
        assertApi("PLACEMENT_LIMIT_EXCEEDED", action);
    }

    private static CommandContext adminContext() {
        return new CommandContext(UUID.randomUUID().toString(), "trace-admin",
                OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN_WEB");
    }

    private static CommandContext merchantContext() {
        return new CommandContext(UUID.randomUUID().toString(), "trace-merchant",
                OperatorType.USER, Long.toUnsignedString(USER), "MINIAPP");
    }

    private static QueryContext adminQuery() {
        return new QueryContext("trace-admin", OperatorType.PLATFORM_OPERATOR, "1001");
    }

    private static void assertApi(String code, Runnable action) {
        ApiException error = assertThrows(ApiException.class, action::run);
        assertEquals(code, error.code(), error.getMessage());
    }

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(
                root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Repository root with schema 06 was not found");
        }
        return root;
    }

    /** Isolated real-MySQL fixture running marketing migrations V33→V36; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "mkthotsvc_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private final SnowflakeIdGenerator ids;
        private final MarketingStoreFactsPort storeFacts;
        private final MarketingAssetObjectStore assets = new MarketingAssetObjectStore() {
            @Override public boolean enabled() { return false; }
            @Override public void put(String objectKey, byte[] content, String contentType) {}
            @Override public byte[] get(String objectKey) { return null; }
        };

        Database() throws Exception {
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33440/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                        "AUTH_MYSQL_URL must target a local isolated server, database-less");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            long[] seq = {9_600_000_000_000_000L};
            ids = () -> ++seq[0];
            storeFacts = new MarketingStoreFactsPort() {
                @Override
                public StoreFacts store(long storeId) {
                    return new StoreFacts(true, MERCHANT_ID, "审核门店", "shanghai",
                            "上海市静安区某路 1 号", null, null, null);
                }

                @Override
                public OwnershipFacts ownership(long userId, long merchantId, long storeId) {
                    return new OwnershipFacts(true, true, true, true);
                }
            };
            try (Connection connection = source.getConnection()) {
                for (String version : List.of("V33__marketing_center.sql",
                        "V34__marketing_assets.sql", "V35__marketing_home_blocks.sql",
                        "V36__hot_service_placement.sql")) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(root()
                                    .resolve("backend/pet-boot/src/main/resources/db/marketing-migration/")
                                    .resolve(version)),
                            StandardCharsets.UTF_8));
                }
            } catch (Exception failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        MarketingAdminApi admin(boolean categoryValidation, MarketingServiceCategoryPort dictionary) {
            return new MarketingAdminApiImpl(source, ids, allowAll(), storeFacts,
                    couponFacts(), cityCatalog(), NOW, false, assets, dictionary, categoryValidation);
        }

        MarketingPublicApi publicApi() {
            return new MarketingPublicApiImpl(source, ids, NOW, assets);
        }

        MarketingMerchantApi merchantApi() {
            return new MarketingMerchantApiImpl(source, ids, storeFacts, NOW);
        }

        private MarketingAdminAuthorizationPort allowAll() {
            return check -> new MarketingAdminAuthorizationPort.Decision(
                    true, "authz-v1", OffsetDateTime.now(NOW));
        }

        private MarketingCouponFactsPort couponFacts() {
            return couponCampaignId -> new MarketingCouponFactsPort.CouponFacts(false, false, null);
        }

        private MarketingCityCatalogPort cityCatalog() {
            return cityCode -> Set.of("shanghai", "beijing").contains(cityCode);
        }

        /** Creates and publishes a zero-store MERCHANT_ENROLL campaign (publish-eligible). */
        String publishedEnrollCampaign(MarketingAdminApi admin) {
            MarketingViews.Receipt created = admin.createCampaign(new CreateCampaignCommand(
                    campaignInput("MERCHANT_ENROLL", List.of(), "招商活动"),
                    AUTH, adminContext()));
            MarketingViews.Receipt published = admin.publishCampaign(
                    new CampaignStatusCommand(created.id(), created.version(), AUTH, adminContext()));
            assertEquals("ONLINE", published.status());
            return created.id();
        }

        String createdCampaign(MarketingAdminApi admin, String type, List<String> categories) {
            return admin.createCampaign(new CreateCampaignCommand(
                    campaignInput(type, categories, "草稿活动"), AUTH, adminContext())).id();
        }

        String placement(MarketingAdminApi admin, String campaignId, String position, String city) {
            return admin.createPlacement(new CreatePlacementCommand(
                    new PlacementInput(position, campaignId, city, null, null, null, null, 0,
                            PLACEMENT_START, PLACEMENT_END),
                    AUTH, adminContext())).id();
        }

        String homeBlock(MarketingAdminApi admin, String targetType, String targetValue) {
            return admin.createHomeBlock(new CreateHomeBlockCommand(
                    homeBlockInput(targetType, targetValue), AUTH, adminContext())).id();
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(
                    url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                    user, password) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (SQLException failure) {
                        connection.close();
                        throw failure;
                    }
                }
            };
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }
}
