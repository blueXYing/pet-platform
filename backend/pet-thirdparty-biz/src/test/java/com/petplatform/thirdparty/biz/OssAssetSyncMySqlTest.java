package com.petplatform.thirdparty.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.thirdparty.biz.application.LocalSequenceIdGenerator;
import com.petplatform.thirdparty.biz.application.OssAssetSyncService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** One-click re-import behaviour over a real MySQL registry: idempotent, content-updated, retired-not-deleted. */
class OssAssetSyncMySqlTest {
    @TempDir
    Path tempDir;

    private static void write(Path file, byte[] content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private static byte[] bytes(int size, byte fill) {
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, fill);
        return data;
    }

    @Test void rerunIsIdempotentAndRetiresVanishedSources() throws Exception {
        try (var db = new MySqlAssetRegistryTestDatabase()) {
            FakeOssAssetClient client = new FakeOssAssetClient();
            var service = new OssAssetSyncService(client, db.dataSource(), new LocalSequenceIdGenerator());
            Path root = tempDir.resolve("assets");
            write(root.resolve("banner.png"), bytes(60_000, (byte) 1));   // above threshold
            write(root.resolve("icon.png"), bytes(10, (byte) 2));          // below threshold: stays in package

            var first = service.sync(Map.of("c002-assets", root), 51_200);
            assertEquals(1, first.uploaded());
            assertEquals(0, first.unchanged());
            assertEquals(1, client.store.size(), "only the large asset reached storage");
            JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM asset_registry WHERE status='ACTIVE'", Integer.class));

            // Re-run with no changes: pure no-op.
            var second = service.sync(Map.of("c002-assets", root), 51_200);
            assertEquals(0, second.uploaded());
            assertEquals(1, second.unchanged());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM asset_registry WHERE status='ACTIVE'", Integer.class));

            // Content changed: same asset_key, new object key and URL, registry updated in place.
            write(root.resolve("banner.png"), bytes(60_000, (byte) 9));
            var third = service.sync(Map.of("c002-assets", root), 51_200);
            assertEquals(1, third.uploaded());
            assertEquals(2, client.store.size(), "content-addressed keys keep both versions");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM asset_registry WHERE status='ACTIVE'", Integer.class));
            String url = jdbc.queryForObject(
                    "SELECT public_url FROM asset_registry WHERE asset_key='c002-assets/banner.png'",
                    String.class);
            assertTrue(url.startsWith("https://test-bucket.oss-cn-test.aliyuncs.com/assets/"));

            // Source file removed: row is retired, never deleted (CCR-OSS-001).
            Files.delete(root.resolve("banner.png"));
            var fourth = service.sync(Map.of("c002-assets", root), 51_200);
            assertEquals(1, fourth.retired());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM asset_registry WHERE status='ACTIVE'", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM asset_registry WHERE status='RETIRED'", Integer.class));
        }
    }
}
