package com.petplatform.event.core;

import com.petplatform.event.core.mapper.ConsumeLogMapper;
import com.petplatform.event.core.mapper.OutboxMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * MyBatis wiring for the outbox tables (PLAT-006 / 22号裁决; no boot starter here, the
 * SqlSessionTemplate joins the surrounding Spring transaction on the shared DataSource).
 */
final class EventMybatis {
    private EventMybatis() {}

    static SqlSessionTemplate template(DataSource dataSource) {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(Objects.requireNonNull(dataSource));
        try {
            SqlSessionFactory factory = factoryBean.getObject();
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().addMapper(OutboxMapper.class);
            factory.getConfiguration().addMapper(ConsumeLogMapper.class);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("event outbox SqlSessionFactory build failed", failure);
        }
    }
}
