package com.petplatform.thirdparty.biz.infrastructure.persistence;

import com.petplatform.common.SnowflakeIdGenerator;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** asset_registry access (SQL 15). Rows are retired, never deleted (CCR-OSS-001). */
public final class AssetRegistryJdbcStore {
    public record AssetRow(String assetKey, String s3ObjectKey, String publicUrl,
                           String sha256, long bytes, String category) {}

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator ids;

    public AssetRegistryJdbcStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
    }

    public void upsertActive(AssetRow row) {
        jdbc.execute("SET SESSION time_zone = '+00:00'");
        jdbc.update("""
                INSERT INTO asset_registry
                (id,asset_key,s3_object_key,public_url,sha256,bytes,category,status,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'ACTIVE',0,NOW(3),NOW(3))
                ON DUPLICATE KEY UPDATE s3_object_key=VALUES(s3_object_key),
                  public_url=VALUES(public_url),sha256=VALUES(sha256),bytes=VALUES(bytes),
                  category=VALUES(category),status='ACTIVE',version=version+1,updated_at=NOW(3)
                """, ids.nextId(), row.assetKey(), row.s3ObjectKey(), row.publicUrl(),
                row.sha256(), row.bytes(), row.category());
    }

    /** Marks keys of a category that vanished from the source as RETIRED. */
    public int retireAbsent(String category, Set<String> presentAssetKeys) {
        List<String> active = jdbc.queryForList(
                "SELECT asset_key FROM asset_registry WHERE category=? AND status='ACTIVE'",
                String.class, category);
        Set<String> gone = new HashSet<>(active);
        gone.removeAll(presentAssetKeys);
        for (String key : gone) {
            jdbc.update("""
                    UPDATE asset_registry SET status='RETIRED',updated_at=NOW(3)
                    WHERE asset_key=? AND status='ACTIVE'
                    """, key);
        }
        return gone.size();
    }

    public int countActive(String category) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM asset_registry WHERE category=? AND status='ACTIVE'",
                Integer.class, category);
    }
}
