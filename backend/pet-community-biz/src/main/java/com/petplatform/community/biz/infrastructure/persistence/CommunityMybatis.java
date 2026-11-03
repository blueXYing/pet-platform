package com.petplatform.community.biz.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Community-domain mappers (encyclopedia read + CCR-COM-001 community tables). */
public final class CommunityMybatis {
    private CommunityMybatis() {}

    public static SqlSessionTemplate template(DataSource source) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(source, "source is required"));
        try {
            bean.setMapperLocations(concat(
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:mapper/Encyclopedia*Mapper.xml"),
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:mapper/Community*Mapper.xml")));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("community SqlSessionFactory build failed", failure);
        }
    }

    private static org.springframework.core.io.Resource[] concat(
            org.springframework.core.io.Resource[] first,
            org.springframework.core.io.Resource[] second) {
        org.springframework.core.io.Resource[] all =
                new org.springframework.core.io.Resource[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
