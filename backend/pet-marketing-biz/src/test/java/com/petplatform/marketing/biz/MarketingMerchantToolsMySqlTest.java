package com.petplatform.marketing.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.petplatform.marketing.api.MarketingAdminApi.AssetUploadCommand;
import com.petplatform.marketing.api.MarketingAdminApi.CampaignInput;
import com.petplatform.marketing.api.MarketingAdminApi.CampaignStatusCommand;
import com.petplatform.marketing.api.MarketingAdminApi.CreateCampaignCommand;
import com.petplatform.marketing.api.MarketingMerchantApi;
import com.petplatform.marketing.api.MarketingMerchantApi.MerchantCreateCampaignCommand;
import com.petplatform.marketing.api.MarketingMerchantApi.MerchantScope;
import com.petplatform.marketing.api.MarketingMerchantApi.MerchantUpdateCampaignCommand;
import com.petplatform.marketing.api.MarketingMerchantApi.UploadMerchantAssetCommand;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.apiimpl.MarketingAdminApiImpl;
import com.petplatform.marketing.biz.apiimpl.MarketingMerchantApiImpl;
import com.petplatform.marketing.biz.application.MarketingAdminAuthorizationPort;
import com.petplatform.marketing.biz.application.MarketingCityCatalogPort;
import com.petplatform.marketing.biz.application.MarketingCouponFactsPort;
import com.petplatform.marketing.biz.application.MarketingServiceCategoryPort;
import com.petplatform.marketing.biz.application.MarketingStoreFactsPort;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * CCR-MKT-TOOLS-001 P1 acceptance over isolated real MySQL running marketing migrations
 * V33→V37 (contract 76): merchant campaign fields (description/themeCode/highlightText/
 * playType/coverAssetId), derived coverUrl, merchant-owned cover assets (upload validation,
 * ownership scoping, requestId idempotency), the pre-submit cover gate, the four-counter hub
 * overview, and the untouched platform-enrollment regression surface.
 */
class MarketingMerchantToolsMySqlTest {

    private static final long MERCHANT = 7_600_000_000_000_001L;
    private static final long OTHER_MERCHANT = 7_600_000_000_000_091L;
    private static final long STORE_A = 7_600_000_000_000_002L;
    private static final long STORE_B = 7_600_000_000_000_003L;
    private static final long USER = 8_600_000_000_000_101L;
    private static final String ASSET_BASE = "https://cdn.example.com";

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime WIN_START = OffsetDateTime.parse("2026-11-10T00:00:00Z");
    private static final OffsetDateTime WIN_END = OffsetDateTime.parse("2026-12-10T00:00:00Z");
    private static final OffsetDateTime PAST_END = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private static final OffsetDateTime PAST_START = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    private static final OffsetDateTime ENROLL_START = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private static final OffsetDateTime ENROLL_END = OffsetDateTime.parse("2026-12-01T00:00:00Z");

    private static final AdminAuthorization AUTH = new AdminAuthorization("sess-1", 1, "1001");
    private static final MerchantScope SCOPE_A = new MerchantScope(USER, MERCHANT, STORE_A);
    private static final MerchantScope SCOPE_B =
            new MerchantScope(USER + 1, MERCHANT, STORE_B);
    private static final MerchantScope SCOPE_OTHER =
            new MerchantScope(USER + 2, OTHER_MERCHANT, STORE_B);

    // ------------------------------------------------------------------ campaign fields

    @Test
    void createEditAndListMerchantCampaignWithP1Fields() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            String assetId = upload(merchant, SCOPE_A, merchantContext(), png(800, 600));

            MarketingViews.Receipt created = merchant.createMyCampaign(
                    new MerchantCreateCampaignCommand(SCOPE_A, "中秋宠物月饼礼盒", "中秋宠物月饼礼盒",
                            "节日限定礼盒", null, null, null, "活动规则与参与方式", "MID_AUTUMN",
                            "宠物月饼礼盒", "THEME", assetId, WIN_START, WIN_END, merchantContext()));
            assertEquals("DRAFT", created.status());
            assertEquals(1, created.version());

            // The stored row carries every P1 field; coverUrl is derived, never echoed input.
            String expectedUrl = ASSET_BASE + "/api/v1/c/marketing/assets/" + assetId;
            var row = db.jdbc.queryForMap(
                    "SELECT description,theme_code,highlight_text,play_type,cover_asset_id,cover_url"
                            + " FROM marketing_campaign WHERE id=?",
                    Long.parseLong(created.id()));
            assertEquals("活动规则与参与方式", row.get("description"));
            assertEquals("MID_AUTUMN", row.get("theme_code"));
            assertEquals("宠物月饼礼盒", row.get("highlight_text"));
            assertEquals("THEME", row.get("play_type"));
            assertEquals(Long.parseLong(assetId), ((Number) row.get("cover_asset_id")).longValue());
            assertEquals(expectedUrl, row.get("cover_url"));

            MarketingViews.Receipt updated = merchant.updateMyCampaign(
                    new MerchantUpdateCampaignCommand(created.id(), "中秋宠物月饼礼盒",
                            "中秋宠物月饼礼盒", "节日限定礼盒", null, null, null,
                            "活动规则更新", "OTHER", "宠物月饼礼盒", null, assetId,
                            WIN_START, WIN_END, created.version(), SCOPE_A, merchantContext()));
            assertTrue(updated.version() > created.version(), "update must advance the version");

            MarketingViews.Page<MarketingViews.CampaignCard> page = merchant.listMyCampaigns(
                    new MarketingMerchantApi.MerchantMyCampaignListQuery(SCOPE_A, null, 1, 20,
                            merchantQuery()));
            assertEquals(1, page.total());
            MarketingViews.CampaignCard card = page.items().get(0);
            assertEquals("活动规则更新", card.description());
            assertEquals("OTHER", card.themeCode());
            assertEquals("其他", card.themeLabel());
            assertEquals("宠物月饼礼盒", card.highlightText());
            assertEquals("THEME", card.playType());
            assertEquals(assetId, card.coverAssetId());
            assertEquals(expectedUrl, card.coverUrl());
        }
    }

    @Test
    void rejectsIllegalThemePlayTypeAndMerchantCoverUrl() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            // Unknown theme, missing theme, unknown play type: all 400s with their own code.
            assertApi("CAMPAIGN_THEME_INVALID", () -> merchant.createMyCampaign(
                    campaign(SCOPE_A, "LANTERN_FESTIVAL", null, null, merchantContext())));
            assertApi("CAMPAIGN_THEME_INVALID", () -> merchant.createMyCampaign(
                    campaign(SCOPE_A, null, null, null, merchantContext())));
            assertApi("CAMPAIGN_PLAY_TYPE_INVALID", () -> merchant.createMyCampaign(
                    campaign(SCOPE_A, "MID_AUTUMN", "FLASH_PRICE", null, merchantContext())));
            // A merchant may never hand-craft the cover URL.
            assertApi("MARKETING_ASSET_INVALID", () -> merchant.createMyCampaign(
                    new MerchantCreateCampaignCommand(SCOPE_A, "名称两字以上", "标题", null, null,
                            null, "https://cdn.example.com/hand-made.png", null, "OTHER", null,
                            null, null, WIN_START, WIN_END, merchantContext())));
            // Length ceilings: description ≤300, highlightText ≤100 code points.
            assertApi(CommonApiCodes.INVALID_ARGUMENT, () -> merchant.createMyCampaign(
                    new MerchantCreateCampaignCommand(SCOPE_A, "名称两字以上", "标题", null, null,
                            null, null, "字".repeat(301), "OTHER", null, null, null,
                            WIN_START, WIN_END, merchantContext())));
            assertApi(CommonApiCodes.INVALID_ARGUMENT, () -> merchant.createMyCampaign(
                    new MerchantCreateCampaignCommand(SCOPE_A, "名称两字以上", "标题", null, null,
                            null, null, null, "OTHER", "字".repeat(101), null, null,
                            WIN_START, WIN_END, merchantContext())));
        }
    }

    // ------------------------------------------------------------------ submit gate

    @Test
    void submitRequiresOwnedCoverAndFutureEnd() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            String assetId = upload(merchant, SCOPE_A, merchantContext(), png(400, 300));

            // No cover yet: submission is refused.
            MarketingViews.Receipt bare = merchant.createMyCampaign(
                    campaign(SCOPE_A, "MID_AUTUMN", null, null, merchantContext()));
            assertApi("MARKETING_ASSET_INVALID", () -> merchant.submitMyCampaign(
                    new MarketingMerchantApi.MerchantCampaignStatusCommand(bare.id(),
                            bare.version(), SCOPE_A, merchantContext())));

            // With a bound owned cover the submit lands in PENDING_REVIEW.
            MarketingViews.Receipt bound = merchant.updateMyCampaign(
                    new MerchantUpdateCampaignCommand(bare.id(), "名称两字以上", "标题", null, null,
                            null, null, null, "MID_AUTUMN", null, "THEME", assetId,
                            WIN_START, WIN_END, bare.version(), SCOPE_A, merchantContext()));
            MarketingViews.Receipt submitted = merchant.submitMyCampaign(
                    new MarketingMerchantApi.MerchantCampaignStatusCommand(bound.id(),
                            bound.version(), SCOPE_A, merchantContext()));
            assertEquals("PENDING_REVIEW", submitted.status());

            // An already-ended activity can never be submitted even with a cover.
            MarketingViews.Receipt expired = merchant.createMyCampaign(
                    new MerchantCreateCampaignCommand(SCOPE_A, "名称两字以上", "标题", null, null,
                            null, null, null, "OTHER", null, "THEME", assetId,
                            PAST_START, PAST_END, merchantContext()));
            assertApi("CAMPAIGN_STATE_NOT_ALLOWED", () -> merchant.submitMyCampaign(
                    new MarketingMerchantApi.MerchantCampaignStatusCommand(expired.id(),
                            expired.version(), SCOPE_A, merchantContext())));
        }
    }

    @Test
    void updateConflictsOnStaleVersion() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            MarketingViews.Receipt created = merchant.createMyCampaign(
                    campaign(SCOPE_A, "OTHER", null, null, merchantContext()));
            assertApi(CommonApiCodes.CONFLICT, () -> merchant.updateMyCampaign(
                    new MerchantUpdateCampaignCommand(created.id(), "名称两字以上", "标题", null,
                            null, null, null, null, "OTHER", null, null, null,
                            WIN_START, WIN_END, created.version() + 5, SCOPE_A,
                            merchantContext())));
        }
    }

    // ------------------------------------------------------------------ cover assets

    @Test
    void coverAssetIsScopedToTheOwningMerchantAndStore() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            MarketingAdminApi admin = db.admin();
            // A cover uploaded by store A (owner_store_id = STORE_A).
            String ownAsset = upload(merchant, SCOPE_A, merchantContext(), png(300, 200));
            // A platform asset uploaded through the ops face.
            MarketingViews.AssetView platformAsset = admin.uploadAsset(new AssetUploadCommand(
                    png(300, 200), "image/png", AUTH, adminContext()));

            // Another store of the same merchant cannot reuse the store-bound asset.
            assertApi("MARKETING_ASSET_INVALID", () -> merchant.createMyCampaign(
                    campaign(SCOPE_B, "MID_AUTUMN", null, ownAsset,
                            merchantContext())));
            // A different merchant cannot reuse it either.
            assertApi("MARKETING_ASSET_INVALID", () -> merchant.createMyCampaign(
                    campaign(SCOPE_OTHER, "MID_AUTUMN", null, ownAsset, merchantContext())));
            // Platform-owned assets are never valid merchant covers.
            assertApi("MARKETING_ASSET_INVALID", () -> merchant.createMyCampaign(
                    campaign(SCOPE_A, "MID_AUTUMN", null, platformAsset.assetId(),
                            merchantContext())));

            // A store-less merchant asset (owner_store_id NULL) is shared by the merchant's stores.
            db.jdbc.update("UPDATE marketing_asset SET owner_store_id=NULL WHERE id=?",
                    Long.parseLong(ownAsset));
            MarketingViews.Receipt shared = merchant.createMyCampaign(
                    campaign(SCOPE_B, "MID_AUTUMN", null, ownAsset, merchantContext()));
            assertEquals("DRAFT", shared.status());
        }
    }

    @Test
    void uploadSniffSizeAndPixelChecks() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            // Non-image bytes and a lying Content-Type are both rejected after the sniff.
            assertApi("MARKETING_ASSET_INVALID", () -> merchant.uploadAsset(
                    new UploadMerchantAssetCommand(SCOPE_A, "这不是图片".getBytes(StandardCharsets.UTF_8),
                            "image/png", merchantContext())));
            byte[] gif = "GIF89a-not-actually-an-image".getBytes(StandardCharsets.UTF_8);
            assertApi("MARKETING_ASSET_INVALID", () -> merchant.uploadAsset(
                    new UploadMerchantAssetCommand(SCOPE_A, gif, "image/png", merchantContext())));
            // Oversize payload (>2 MiB) hits 413 before any decode.
            byte[] oversize = new byte[2 * 1024 * 1024 + 1];
            System.arraycopy(png(10, 10), 0, oversize, 0, 68);
            assertApi("MARKETING_ASSET_TOO_LARGE", () -> merchant.uploadAsset(
                    new UploadMerchantAssetCommand(SCOPE_A, oversize, "image/png",
                            merchantContext())));
            // Pixel ceiling: 2001px wide is out of the 1..2000 window.
            assertApi("MARKETING_ASSET_INVALID", () -> merchant.uploadAsset(
                    new UploadMerchantAssetCommand(SCOPE_A, png(2001, 10), "image/png",
                            merchantContext())));

            // A legal upload writes the MERCHANT-owned row plus the append-only audit.
            MarketingViews.AssetView ok = merchant.uploadAsset(new UploadMerchantAssetCommand(
                    SCOPE_A, png(640, 480), "image/png", merchantContext()));
            assertEquals("image/png", ok.contentType());
            assertEquals(640, ok.width());
            assertEquals(480, ok.height());
            assertEquals(64, ok.sha256().length());
            var asset = db.jdbc.queryForMap(
                    "SELECT owner_type,owner_merchant_id,owner_store_id,purpose,status"
                            + " FROM marketing_asset WHERE id=?", Long.parseLong(ok.assetId()));
            assertEquals("MERCHANT", asset.get("owner_type"));
            assertEquals(MERCHANT, ((Number) asset.get("owner_merchant_id")).longValue());
            assertEquals(STORE_A, ((Number) asset.get("owner_store_id")).longValue());
            assertEquals("CAMPAIGN_COVER", asset.get("purpose"));
            assertEquals("ACTIVE", asset.get("status"));
            Long audits = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM marketing_asset_audit WHERE asset_id=?",
                    Long.class, Long.parseLong(ok.assetId()));
            assertEquals(1L, audits);
        }
    }

    @Test
    void uploadIdempotencyReplaysFirstReceiptAndConflictsOnDifferentPayload() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            CommandContext context = merchantContext();
            byte[] first = png(200, 100);
            MarketingViews.AssetView one = merchant.uploadAsset(
                    new UploadMerchantAssetCommand(SCOPE_A, first, "image/png", context));
            MarketingViews.AssetView replay = merchant.uploadAsset(
                    new UploadMerchantAssetCommand(SCOPE_A, first, "image/png", context));
            assertEquals(one.assetId(), replay.assetId());
            assertEquals(1L, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM marketing_asset", Long.class));
            // Same requestId, different payload → 409 IDEMPOTENCY_KEY_CONFLICT.
            assertApi(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, () -> merchant.uploadAsset(
                    new UploadMerchantAssetCommand(SCOPE_A, png(201, 100), "image/png", context)));
        }
    }

    @Test
    void unconfiguredPublicBaseFailsClosed() throws Exception {
        try (Database db = new Database()) {
            // Upload and cover-binding both 503 while the public base URL is missing.
            MarketingMerchantApi bare = db.merchant("");
            assertApi(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> bare.uploadAsset(
                    new UploadMerchantAssetCommand(SCOPE_A, png(100, 100), "image/png",
                            merchantContext())));
            MarketingMerchantApi configured = db.merchant(ASSET_BASE);
            String assetId = upload(configured, SCOPE_A, merchantContext(), png(100, 100));
            assertApi(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> bare.createMyCampaign(
                    campaign(SCOPE_A, "OTHER", null, assetId, merchantContext())));
            // Without a cover the create itself stays legal (a coverless draft is allowed).
            assertEquals("DRAFT", bare.createMyCampaign(
                    campaign(SCOPE_A, "OTHER", null, null, merchantContext())).status());
        }
    }

    // ------------------------------------------------------------------ overview

    @Test
    void overviewCountsCampaignsAndEnrollments() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            MarketingAdminApi admin = db.admin();
            String assetId = upload(merchant, SCOPE_A, merchantContext(), png(500, 400));

            // Platform recruitment campaign: the store enrolls (SUBMITTED counts as enrolled).
            String recruit = publishedEnrollCampaign(admin);
            assertEquals("SUBMITTED", merchant.enroll(new MarketingMerchantApi.EnrollCommand(
                    SCOPE_A, recruit, null, null, null, null, merchantContext())).status());

            // Three own campaigns: a plain draft, a pending review, and an ONLINE one in window.
            merchant.createMyCampaign(campaign(SCOPE_A, "OTHER", null, null, merchantContext()));
            MarketingViews.Receipt pending = merchant.createMyCampaign(
                    campaign(SCOPE_A, "OTHER", null, assetId, merchantContext()));
            MarketingViews.Receipt online = merchant.createMyCampaign(
                    campaign(SCOPE_A, "OTHER", null, assetId, merchantContext()));
            merchant.submitMyCampaign(new MarketingMerchantApi.MerchantCampaignStatusCommand(
                    pending.id(), pending.version(), SCOPE_A, merchantContext()));
            MarketingViews.Receipt submittedOnline = merchant.submitMyCampaign(
                    new MarketingMerchantApi.MerchantCampaignStatusCommand(
                            online.id(), online.version(), SCOPE_A, merchantContext()));
            assertEquals("ONLINE", admin.publishCampaign(new CampaignStatusCommand(
                    online.id(), submittedOnline.version(), AUTH, adminContext())).status());

            MarketingViews.MerchantOverview overview = merchant.overview(
                    new MarketingMerchantApi.MerchantOverviewQuery(SCOPE_A, merchantQuery()));
            assertEquals(3L, overview.totalCampaignCount());
            assertEquals(1L, overview.activeCampaignCount());
            assertEquals(1L, overview.pendingReviewCount());
            assertEquals(1L, overview.enrolledCampaignCount());

            // A withdrawn enrollment stops counting.
            var enrollments = merchant.listMyEnrollments(
                    new MarketingMerchantApi.MerchantEnrollmentListQuery(SCOPE_A, null, 1, 20,
                            merchantQuery()));
            MarketingViews.Receipt withdrawn = merchant.withdraw(
                    new MarketingMerchantApi.WithdrawCommand(enrollments.items().get(0).enrollmentId(),
                            enrollments.items().get(0).version(), SCOPE_A, merchantContext()));
            assertEquals("WITHDRAWN", withdrawn.status());
            assertEquals(0L, merchant.overview(new MarketingMerchantApi.MerchantOverviewQuery(
                    SCOPE_A, merchantQuery())).enrolledCampaignCount());
        }
    }

    // ------------------------------------------------------------------ 71 regression

    @Test
    void platformEnrollmentFlowIsUntouched() throws Exception {
        try (Database db = new Database()) {
            MarketingMerchantApi merchant = db.merchant(ASSET_BASE);
            MarketingAdminApi admin = db.admin();
            String recruit = publishedEnrollCampaign(admin);
            MarketingViews.Receipt enrollment = merchant.enroll(
                    new MarketingMerchantApi.EnrollCommand(SCOPE_A, recruit, "5折", "全场五折",
                            "活动价", null, merchantContext()));
            assertEquals("SUBMITTED", enrollment.status());
            assertApi("ENROLLMENT_ALREADY_EXISTS", () -> merchant.enroll(
                    new MarketingMerchantApi.EnrollCommand(SCOPE_A, recruit, "5折", "全场五折",
                            "活动价", null, merchantContext())));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static MerchantCreateCampaignCommand campaign(MerchantScope scope, String themeCode,
            String playType, String coverAssetId, CommandContext context) {
        return new MerchantCreateCampaignCommand(scope, "中秋宠物月饼礼盒", "中秋宠物月饼礼盒",
                "节日限定礼盒", null, null, null, "活动说明", themeCode, "主打项目", playType,
                coverAssetId, WIN_START, WIN_END, context);
    }

    private static String upload(MarketingMerchantApi merchant, MerchantScope scope,
            CommandContext context, byte[] content) {
        return merchant.uploadAsset(
                new UploadMerchantAssetCommand(scope, content, "image/png", context)).assetId();
    }

    private static byte[] png(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("test png generation failed", failure);
        }
    }

    private static CommandContext adminContext() {
        return new CommandContext(UUID.randomUUID().toString(), "trace-admin",
                OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN_WEB");
    }

    private static CommandContext merchantContext() {
        return new CommandContext(UUID.randomUUID().toString(), "trace-merchant",
                OperatorType.USER, Long.toUnsignedString(USER), "MINIAPP");
    }

    private static QueryContext merchantQuery() {
        return new QueryContext("trace-merchant", OperatorType.USER,
                Long.toUnsignedString(USER));
    }

    private static void assertApi(String code, Runnable action) {
        ApiException error = assertThrows(ApiException.class, action::run);
        assertEquals(code, error.code(), error.getMessage());
    }

    private static CampaignInput recruitCampaignInput() {
        return new CampaignInput("招商活动-名称", "MERCHANT_ENROLL", "MERCHANT_ENROLL",
                "招商活动", null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), WIN_START, WIN_END, ENROLL_START, ENROLL_END,
                null, null, 0);
    }

    private static String publishedEnrollCampaign(MarketingAdminApi admin) {
        MarketingViews.Receipt created = admin.createCampaign(new CreateCampaignCommand(
                recruitCampaignInput(), AUTH, adminContext()));
        MarketingViews.Receipt published = admin.publishCampaign(new CampaignStatusCommand(
                created.id(), created.version(), AUTH, adminContext()));
        assertEquals("ONLINE", published.status());
        return created.id();
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

    /** Isolated real-MySQL fixture running marketing migrations V33→V37; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "mkttools_" + UUID.randomUUID().toString().replace("-", "");
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
            long[] seq = {9_700_000_000_000_000L};
            ids = () -> ++seq[0];
            storeFacts = new MarketingStoreFactsPort() {
                @Override
                public StoreFacts store(long storeId) {
                    return new StoreFacts(true, Long.toUnsignedString(MERCHANT), "营销门店",
                            "shanghai", "上海市静安区某路 1 号", null, null, null);
                }

                @Override
                public OwnershipFacts ownership(long userId, long merchantId, long storeId) {
                    return new OwnershipFacts(true, true, true, true);
                }
            };
            try (Connection connection = source.getConnection()) {
                for (String version : List.of("V33__marketing_center.sql",
                        "V34__marketing_assets.sql", "V35__marketing_home_blocks.sql",
                        "V36__hot_service_placement.sql", "V37__merchant_marketing_tools.sql")) {
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

        MarketingAdminApi admin() {
            return new MarketingAdminApiImpl(source, ids, allowAll(), storeFacts,
                    couponFacts(), cityCatalog(), NOW, false, assets, dictionary(), false);
        }

        MarketingMerchantApi merchant(String assetPublicBaseUrl) {
            return new MarketingMerchantApiImpl(source, ids, storeFacts, NOW, assets,
                    assetPublicBaseUrl);
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

        private MarketingServiceCategoryPort dictionary() {
            return categoryCode -> true;
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
