package com.petplatform.boot.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.petplatform.marketing.api.MarketingPublicApi.PublicPlacementQuery;
import com.petplatform.marketing.biz.apiimpl.MarketingPublicApiImpl;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 首页默认营销数据（42 号 §十、§十二）的落库验证，跑在隔离的真 MySQL 上：
 *
 * <ul>
 *   <li>76 号种子 SQL 与 {@link E2eCoreLoopServer#seedMarketing} 写出同一套数据——三张活动卡是平台活动
 *       （不关联商家 / 门店、全部城市、长期有效、插画取固定 bucket），加三块首页内容位；两处重复执行
 *       都不再新增行。</li>
 *   <li>已执行过 v0.1 的库跑一遍 76 号文件即升级：归属改为平台、删除 v0.1 的门店关联、放开城市、
 *       延长有效期；运营在后台改过的字段保持原样。</li>
 * </ul>
 *
 * <p>需要 {@code AUTH_MYSQL_URL/USER/PASSWORD}（本机隔离实例、不带库名），与其余 MySQL 测试一致。
 */
class DefaultMarketingSeedTest {

    private static final String IMAGE_BASE =
        "https://mtxoss2.oss-cn-hangzhou.aliyuncs.com/marketing/fullcard/";
    private static final MarketingAssetObjectStore NO_ASSETS = new MarketingAssetObjectStore() {
        @Override public boolean enabled() { return false; }
        @Override public void put(String objectKey, byte[] content, String contentType) {}
        @Override public byte[] get(String objectKey) { return null; }
    };

    @Test
    void sqlSeedAndE2eSeedWriteTheSameDefaultDataAndStayIdempotent() throws Exception {
        try (Database sql = new Database(); Database e2e = new Database()) {
            for (int round = 0; round < 2; round++) {
                sql.runSeedFile();
                E2eCoreLoopServer.seedMarketing(e2e.jdbc);
            }
            assertDefaultShape(sql.jdbc);
            assertDefaultShape(e2e.jdbc);
            assertEquals(businessRows(sql.jdbc), businessRows(e2e.jdbc),
                "76 号 SQL 与 E2eCoreLoopServer#seedMarketing 必须写入同一套数据（改一处要同步改另一处）");

            // C 端读取不依赖门店，也不限定城市：任何城市（含未选城市）都能拿到三张卡。
            AtomicLong sequence = new AtomicLong(9_700_000_000_000_000L);
            var api = new MarketingPublicApiImpl(sql.source, sequence::incrementAndGet,
                Clock.systemUTC(), NO_ASSETS);
            assertEquals(3, api.listPlacements(new PublicPlacementQuery("HOME_ACTIVITY", "shanghai")).size());
            assertEquals(3, api.listPlacements(new PublicPlacementQuery("HOME_ACTIVITY", null)).size());
        }
    }

    @Test
    void versionOneRowsAreUpgradedWithoutTouchingOperatorEdits() throws Exception {
        try (Database db = new Database()) {
            insertVersionOneRows(db.jdbc);
            db.runSeedFile();
            JdbcTemplate jdbc = db.jdbc;

            // 720100：原样的 v0.1 行被完整升级为平台活动。
            assertEquals(1L, count(jdbc, """
                SELECT COUNT(*) FROM marketing_campaign WHERE id=720100 AND campaign_code='MKT-NEW-50'
                  AND owner_type='PLATFORM' AND owner_merchant_id IS NULL AND owner_store_id IS NULL
                  AND campaign_type='DISPLAY' AND participation_mode='PLATFORM_SELECTED'
                  AND JSON_LENGTH(city_codes)=0 AND cover_url=? AND end_at >= '2099-12-31'
                """, IMAGE_BASE + "new-user-clean.png"));
            // 720101：没挂券的"券活动"改为纯展示；运营换过的插画和结束时间保持原样。
            assertEquals(1L, count(jdbc, """
                SELECT COUNT(*) FROM marketing_campaign WHERE id=720101 AND campaign_code='MKT-FIRST-30'
                  AND owner_type='PLATFORM' AND campaign_type='DISPLAY'
                  AND participation_mode='PLATFORM_SELECTED'
                  AND cover_url='https://cdn.example.com/op-edited.png'
                  AND end_at = DATE_ADD(created_at, INTERVAL 100 DAY)
                """));
            // 720102：运营配置过报名期，已是真正的招商活动，归属不动。
            assertEquals(1L, count(jdbc, """
                SELECT COUNT(*) FROM marketing_campaign WHERE id=720102 AND owner_type='MERCHANT'
                  AND owner_merchant_id=710301 AND owner_store_id=710302
                  AND participation_mode='MERCHANT_ENROLL' AND enroll_start_at IS NOT NULL
                """));

            // v0.1 写的三行门店关联被删除；运营另选的门店保留。
            assertEquals(0L, count(jdbc, """
                SELECT COUNT(*) FROM marketing_campaign_store WHERE id IN (720110,720111,720112)
                """));
            assertEquals(1L, count(jdbc,
                "SELECT COUNT(*) FROM marketing_campaign_store WHERE id=880001"));

            // 投放位放开城市，投放位与内容位的默认有效期一并延长。
            assertEquals(3L, count(jdbc, """
                SELECT COUNT(*) FROM marketing_placement
                WHERE id IN (720200,720201,720202) AND city_code IS NULL AND end_at >= '2099-12-31'
                """));
            assertEquals(3L, count(jdbc, """
                SELECT COUNT(*) FROM marketing_home_block
                WHERE id IN (720300,720301,720302) AND end_at >= '2099-12-31'
                """));
        }
    }

    // ------------------------------------------------------------------ assertions

    private static void assertDefaultShape(JdbcTemplate jdbc) {
        assertEquals(3L, count(jdbc, """
            SELECT COUNT(*) FROM marketing_campaign
            WHERE id IN (720100,720101,720102)
              AND campaign_code IN ('MKT-NEW-50','MKT-FIRST-30','MKT-GROOM-50')
              AND owner_type='PLATFORM' AND owner_merchant_id IS NULL AND owner_store_id IS NULL
              AND campaign_type='DISPLAY' AND participation_mode='PLATFORM_SELECTED' AND status='ONLINE'
              AND coupon_campaign_id IS NULL AND enroll_start_at IS NULL AND enroll_end_at IS NULL
              AND JSON_LENGTH(city_codes)=0 AND cover_url LIKE 'https://mtxoss2.oss-cn-hangzhou.aliyuncs.com/marketing/fullcard/%.png'
              AND start_at < UTC_TIMESTAMP() AND start_at >= DATE_SUB(UTC_TIMESTAMP(), INTERVAL 31 DAY)
              AND end_at >= '2099-12-31'
            """));
        assertEquals(3L, count(jdbc, "SELECT COUNT(*) FROM marketing_campaign"));
        assertEquals(0L, count(jdbc, "SELECT COUNT(*) FROM marketing_campaign_store"));
        assertEquals(3L, count(jdbc, """
            SELECT COUNT(*) FROM marketing_placement
            WHERE id IN (720200,720201,720202) AND position_code='HOME_ACTIVITY'
              AND campaign_id IN (720100,720101,720102) AND city_code IS NULL AND status='ENABLED'
              AND start_at < UTC_TIMESTAMP() AND end_at >= '2099-12-31'
            """));
        assertEquals(3L, count(jdbc, "SELECT COUNT(*) FROM marketing_placement"));
        assertEquals(3L, count(jdbc, """
            SELECT COUNT(*) FROM marketing_home_block
            WHERE id IN (720300,720301,720302) AND status='ENABLED' AND city_code IS NULL
              AND image_url IS NULL AND sponsor_merchant_id IS NULL AND sponsor_store_id IS NULL
              AND start_at < UTC_TIMESTAMP() AND end_at >= '2099-12-31'
            """));
        assertEquals(3L, count(jdbc, "SELECT COUNT(*) FROM marketing_home_block"));
    }

    /** 两处种子的业务字段（不含创建 / 更新时间与开始日，它们随执行时刻变化）。 */
    private static List<Map<String, Object>> businessRows(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("""
            SELECT 'campaign' AS kind, id, campaign_code AS code, name, owner_type, owner_merchant_id,
                   owner_store_id, campaign_type, participation_mode, status, title, subtitle, tag_code,
                   tag_name, cover_url, CAST(city_codes AS CHAR) AS city_codes,
                   CAST(end_at AS CHAR) AS end_at, sort_weight, version, created_by_type, created_by_id
            FROM marketing_campaign ORDER BY id
            """));
        rows.addAll(jdbc.queryForList("""
            SELECT 'placement' AS kind, id, position_code AS code, campaign_id, city_code, status,
                   sort_weight, version, created_by_id, CAST(end_at AS CHAR) AS end_at
            FROM marketing_placement ORDER BY id
            """));
        rows.addAll(jdbc.queryForList("""
            SELECT 'home_block' AS kind, id, block_code AS code, position_code, template_code, title,
                   subtitle, tag_name, image_url, target_type, target_value, city_code, sponsor_merchant_id,
                   sponsor_store_id, sort_weight, status, version, created_by_id, CAST(end_at AS CHAR) AS end_at
            FROM marketing_home_block ORDER BY id
            """));
        return rows;
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ------------------------------------------------------------------ v0.1 fixture

    /** v0.1 的活动行：归商户 710301 / 门店 710302 的招商活动、限成都、cover 存 Key。 */
    private record VersionOneCampaign(long id, String code, String name, String type, String cover,
        LocalDateTime endAt, LocalDateTime enrollStartAt, LocalDateTime enrollEndAt) {}

    /** 按 v0.1（2026-11-23）的形态落库，并在 720101 / 720102 上叠加运营在后台做过的修改。 */
    private static void insertVersionOneRows(JdbcTemplate jdbc) {
        LocalDateTime created = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        Timestamp at = Timestamp.valueOf(created);
        List<VersionOneCampaign> campaigns = List.of(
            new VersionOneCampaign(720100L, "MKT-NEW-50", "新客专享·限时5折", "DISPLAY",
                "marketing/fullcard/new-user-clean.png", created.plusDays(400), null, null),
            // 运营换过插画，并把结束时间提前到 100 天后。
            new VersionOneCampaign(720101L, "MKT-FIRST-30", "首单立减¥30", "COUPON",
                "https://cdn.example.com/op-edited.png", created.plusDays(100), null, null),
            // 运营配置了报名期。
            new VersionOneCampaign(720102L, "MKT-GROOM-50", "宠物美容狂欢", "DISPLAY",
                "marketing/fullcard/grooming-clean.png", created.plusDays(400), created, created.plusDays(30)));
        for (VersionOneCampaign c : campaigns) {
            jdbc.update("""
                INSERT INTO marketing_campaign(id,campaign_code,name,owner_type,owner_merchant_id,owner_store_id,
                    campaign_type,participation_mode,status,title,subtitle,tag_code,tag_name,cover_url,city_codes,
                    start_at,end_at,enroll_start_at,enroll_end_at,sort_weight,version,created_by_type,created_by_id,
                    created_at,updated_at)
                VALUES(?,?,?,'MERCHANT',710301,710302,?,'MERCHANT_ENROLL','ONLINE',?,NULL,'full_card',NULL,?,
                    '["chengdu"]',?,?,?,?,1,1,'ADMIN',9001,?,?)
                """,
                c.id(), c.code(), c.name(), c.type(), c.name(), c.cover(),
                Timestamp.valueOf(created.minusDays(30)), Timestamp.valueOf(c.endAt()),
                c.enrollStartAt() == null ? null : Timestamp.valueOf(c.enrollStartAt()),
                c.enrollEndAt() == null ? null : Timestamp.valueOf(c.enrollEndAt()), at, at);
            jdbc.update("""
                INSERT INTO marketing_placement(id,position_code,campaign_id,city_code,sort_weight,start_at,end_at,
                    status,version,created_by_id,created_at,updated_at)
                VALUES(?,'HOME_ACTIVITY',?,'chengdu',1,?,?,'ENABLED',1,9001,?,?)
                """, c.id() + 100, c.id(), Timestamp.valueOf(created.minusDays(30)),
                Timestamp.valueOf(created.plusDays(400)), at, at);
        }
        for (long[] link : new long[][] {{720110, 720100}, {720111, 720101}, {720112, 720102}}) {
            jdbc.update("""
                INSERT INTO marketing_campaign_store(id,campaign_id,merchant_id,store_id,source,status,
                    sort_weight,version,created_at,updated_at)
                VALUES(?,?,710301,710302,'MERCHANT_OWN','ACTIVE',1,1,?,?)
                """, link[0], link[1], at, at);
        }
        // 运营另选的门店：不是本种子写的行，升级时必须保留。
        jdbc.update("""
            INSERT INTO marketing_campaign_store(id,campaign_id,merchant_id,store_id,source,status,
                sort_weight,version,created_at,updated_at)
            VALUES(880001,720100,880002,880003,'PLATFORM_SELECTED','ACTIVE',1,1,?,?)
            """, at, at);
        // target_value：COMMUNITY（宠友圈）按 71 号 CHECK 必须有值，NONE 不需要。
        String[][] blocks = {
            {"720300", "HB-HOT-SERVICE", "HOME_SERVICE", "HOT_SERVICE_FEATURE", "HOT_SERVICE", null},
            {"720301", "HB-COMMUNITY-HOT", "HOME_COMMUNITY", "COMMUNITY_HOT", "COMMUNITY", "HOT"},
            {"720302", "HB-EXOTIC-PLANT", "HOME_EXOTIC_PLANT", "EXOTIC_PLANT", "NONE", null}};
        for (String[] b : blocks) {
            jdbc.update("""
                INSERT INTO marketing_home_block(id,block_code,position_code,template_code,title,tag_name,
                    target_type,target_value,sort_weight,start_at,end_at,status,version,created_by_id,
                    created_at,updated_at)
                VALUES(?,?,?,?,?, ?,?,?,1,?,?,'ENABLED',1,9001,?,?)
                """, Long.parseLong(b[0]), b[1], b[2], b[3], b[1], b[1], b[4], b[5],
                Timestamp.valueOf(created.minusDays(30)), Timestamp.valueOf(created.plusDays(400)), at, at);
        }
    }

    // ------------------------------------------------------------------ isolated MySQL

    private static Path seedFile() throws Exception {
        Path root = root();
        try (Stream<Path> files = Files.list(root.resolve("docs/03-database"))) {
            return files.filter(path -> path.getFileName().toString().startsWith("76-")
                    && path.toString().endsWith(".sql"))
                .findFirst().orElseThrow(() -> new IllegalStateException("76 号种子 SQL 未找到"));
        }
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

    /** 隔离的真 MySQL 库（营销迁移 V33→V37），关闭时删除。 */
    private static final class Database implements AutoCloseable {
        private final String name = "auth001c_mktseed76_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

        Database() throws Exception {
            String server = System.getenv().getOrDefault("AUTH_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:33440/");
            if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                    "AUTH_MYSQL_URL must target a local isolated server, database-less");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(server, user, password));
            source = source(server + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try (Connection connection = source.getConnection()) {
                for (String version : List.of("V33__marketing_center.sql", "V34__marketing_assets.sql",
                        "V35__marketing_home_blocks.sql", "V36__hot_service_placement.sql",
                        "V37__merchant_marketing_tools.sql")) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new ClassPathResource("db/marketing-migration/" + version),
                            StandardCharsets.UTF_8));
                }
            } catch (Exception failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /** 整份 76 号文件在同一个连接里执行（含会话变量），等价于 {@code mysql < 文件}。 */
        void runSeedFile() throws Exception {
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new FileSystemResource(seedFile()), StandardCharsets.UTF_8));
            }
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
