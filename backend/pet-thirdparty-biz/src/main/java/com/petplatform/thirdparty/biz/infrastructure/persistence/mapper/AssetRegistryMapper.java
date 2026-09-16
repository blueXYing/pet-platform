package com.petplatform.thirdparty.biz.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * asset_registry statements, SQL 15 kept verbatim (PLAT-006 / 22号裁决). Rows are
 * retired, never deleted (CCR-OSS-001).
 */
public interface AssetRegistryMapper {

    @Update("SET SESSION time_zone = '+00:00'")
    void setSessionTimeZoneUtc();

    @Insert("""
            INSERT INTO asset_registry
            (id,asset_key,s3_object_key,public_url,sha256,bytes,category,status,version,created_at,updated_at)
            VALUES (#{id},#{assetKey},#{s3ObjectKey},#{publicUrl},#{sha256},#{bytes},#{category},'ACTIVE',0,NOW(3),NOW(3))
            ON DUPLICATE KEY UPDATE s3_object_key=VALUES(s3_object_key),
              public_url=VALUES(public_url),sha256=VALUES(sha256),bytes=VALUES(bytes),
              category=VALUES(category),status='ACTIVE',version=version+1,updated_at=NOW(3)
            """)
    int upsertActive(@Param("id") long id, @Param("assetKey") String assetKey,
                     @Param("s3ObjectKey") String s3ObjectKey, @Param("publicUrl") String publicUrl,
                     @Param("sha256") String sha256, @Param("bytes") long bytes,
                     @Param("category") String category);

    @Select("SELECT asset_key FROM asset_registry WHERE category=#{category} AND status='ACTIVE'")
    List<String> selectActiveAssetKeys(@Param("category") String category);

    @Update("""
            UPDATE asset_registry SET status='RETIRED',updated_at=NOW(3)
            WHERE asset_key=#{assetKey} AND status='ACTIVE'
            """)
    int retireActive(@Param("assetKey") String assetKey);

    @Select("SELECT s3_object_key FROM asset_registry WHERE asset_key=#{assetKey} AND status='ACTIVE'")
    String selectActiveObjectKey(@Param("assetKey") String assetKey);

    @Select("SELECT COUNT(*) FROM asset_registry WHERE category=#{category} AND status='ACTIVE'")
    int countActive(@Param("category") String category);
}
