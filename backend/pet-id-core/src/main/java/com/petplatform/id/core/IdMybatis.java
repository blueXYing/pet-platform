package com.petplatform.id.core;

import com.petplatform.id.core.mapper.SnowflakeNodeMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * MyBatis wiring for snowflake_worker_state (PLAT-006 / 22号裁决; no boot starter here, the
 * SqlSessionTemplate joins the surrounding Spring transaction on the shared DataSource).
 */
final class IdMybatis {
    private IdMybatis() {}

    static SqlSessionTemplate template(DataSource dataSource) {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(Objects.requireNonNull(dataSource));
        try {
            SqlSessionFactory factory = factoryBean.getObject();
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().addMapper(SnowflakeNodeMapper.class);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("snowflake node SqlSessionFactory build failed", failure);
        }
    }
}
