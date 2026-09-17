package com.petplatform.event.core;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * MyBatis wiring for the outbox tables (PLAT-006 / 22号裁决; no boot starter here, the
 * SqlSessionTemplate joins the surrounding Spring transaction on the shared DataSource).
 * Statements live in resources/mapper/*.xml.
 */
final class EventMybatis {
    private EventMybatis() {}

    static SqlSessionTemplate template(DataSource dataSource) {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(Objects.requireNonNull(dataSource));
        try {
            factoryBean.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*.xml"));
            SqlSessionFactory factory = factoryBean.getObject();
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("event outbox SqlSessionFactory build failed", failure);
        }
    }
}
