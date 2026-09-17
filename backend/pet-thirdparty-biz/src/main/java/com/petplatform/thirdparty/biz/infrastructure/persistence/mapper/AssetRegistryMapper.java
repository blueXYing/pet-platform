package com.petplatform.thirdparty.biz.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * asset_registry access; SQL lives verbatim in resources/mapper/AssetRegistryMapper.xml
 * (SQL 15, PLAT-006 / 22号裁决). Rows are retired, never deleted (CCR-OSS-001).
 */
public interface AssetRegistryMapper {

    void setSessionTimeZoneUtc();

    int upsertActive(@Param("id") long id, @Param("assetKey") String assetKey,
                     @Param("s3ObjectKey") String s3ObjectKey, @Param("publicUrl") String publicUrl,
                     @Param("sha256") String sha256, @Param("bytes") long bytes,
                     @Param("category") String category);

    List<String> selectActiveAssetKeys(@Param("category") String category);

    int retireActive(@Param("assetKey") String assetKey);

    String selectActiveObjectKey(@Param("assetKey") String assetKey);

    int countActive(@Param("category") String category);
}
