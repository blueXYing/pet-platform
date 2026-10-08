package com.petplatform.review.biz.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Builds the REVIEW mappers against the caller's transaction DataSource (MyBatis only). */
final class ReviewMybatis {
    private ReviewMybatis() {}

    static SqlSessionTemplate template(DataSource dataSource) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(dataSource, "dataSource is required"));
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/Review*Mapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            // Sibling modules write through their own factory in the same transaction; every
            // statement re-reads from MySQL instead of a stale local cache.
            factory.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("REVIEW MyBatis initialization failed", failure);
        }
    }
}
