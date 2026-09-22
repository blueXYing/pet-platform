package com.petplatform.merchant.biz.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public final class MerchantMybatis {
    private MerchantMybatis() {}

    static SqlSessionTemplate template(DataSource dataSource) {
        return build(dataSource);
    }

    /** Joining template for callers that must read within their active Spring transaction. */
    public static SqlSessionTemplate joiningTemplate(DataSource dataSource) {
        return build(dataSource);
    }

    private static SqlSessionTemplate build(DataSource dataSource) {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(Objects.requireNonNull(dataSource, "dataSource is required"));
        try {
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/Merchant*Mapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(factoryBean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("merchant domain SqlSessionFactory build failed", failure);
        }
    }
}
