package com.petplatform.thirdparty.biz.infrastructure.persistence;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.thirdparty.biz.infrastructure.persistence.mapper.AssetRegistryMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;

/**
 * asset_registry access via the MyBatis mapper, one autocommit session per operation as
 * before the PLAT-006 migration (SQL 15). Rows are retired, never deleted (CCR-OSS-001).
 */
public final class AssetRegistryStore {
    public record AssetRow(String assetKey, String s3ObjectKey, String publicUrl,
                           String sha256, long bytes, String category) {}

    private final SqlSessionFactory sqlSessionFactory;
    private final SnowflakeIdGenerator ids;

    public AssetRegistryStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.sqlSessionFactory = sessionFactory(Objects.requireNonNull(dataSource));
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
    }

    public void upsertActive(AssetRow row) {
        // SET SESSION must land on the same connection as the INSERT so NOW(3) evaluates in UTC.
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AssetRegistryMapper mapper = session.getMapper(AssetRegistryMapper.class);
            mapper.setSessionTimeZoneUtc();
            mapper.upsertActive(ids.nextId(), row.assetKey(), row.s3ObjectKey(), row.publicUrl(),
                    row.sha256(), row.bytes(), row.category());
        }
    }

    /** Marks keys of a category that vanished from the source as RETIRED. */
    public int retireAbsent(String category, Set<String> presentAssetKeys) {
        List<String> active;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            active = session.getMapper(AssetRegistryMapper.class).selectActiveAssetKeys(category);
        }
        Set<String> gone = new HashSet<>(active);
        gone.removeAll(presentAssetKeys);
        for (String key : gone) {
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                session.getMapper(AssetRegistryMapper.class).retireActive(key);
            }
        }
        return gone.size();
    }

    /** Object key of an ACTIVE asset, or null (presign/issuance path). */
    public String activeObjectKey(String assetKey) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(AssetRegistryMapper.class).selectActiveObjectKey(assetKey);
        }
    }

    public int countActive(String category) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(AssetRegistryMapper.class).countActive(category);
        }
    }

    /** Standalone factory over any DataSource; Spring-managed transactions still join when active. */
    private static SqlSessionFactory sessionFactory(DataSource dataSource) {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        try {
            factoryBean.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*.xml"));
            return factoryBean.getObject();
        } catch (Exception failure) {
            throw new IllegalStateException("asset_registry SqlSessionFactory build failed", failure);
        }
    }
}
