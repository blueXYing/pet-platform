package com.petplatform.order.biz.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Builds ORDER mappers against the caller's transaction DataSource. */
final class OrderMybatis {
    private OrderMybatis() {}

    static SqlSessionTemplate template(DataSource source) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(source, "source is required"));
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/Order*Mapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("ORDER MyBatis initialization failed", failure);
        }
    }
}
