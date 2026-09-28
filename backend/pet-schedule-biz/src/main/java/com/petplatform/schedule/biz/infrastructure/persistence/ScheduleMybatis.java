package com.petplatform.schedule.biz.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public final class ScheduleMybatis {
    private ScheduleMybatis() {}

    public static SqlSessionTemplate template(DataSource dataSource) {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(Objects.requireNonNull(dataSource, "dataSource is required"));
        try {
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/Schedule*Mapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(factoryBean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            // Other modules may write through a different SqlSessionFactory in this same transaction.
            // Re-read locked rows from MySQL at each statement, as the former JdbcTemplate did.
            factory.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("schedule domain SqlSessionFactory build failed", failure);
        }
    }
}
