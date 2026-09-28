package com.petplatform.payment.biz.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** PAYMENT mappers join the caller's DataSourceTransactionManager transaction. */
final class PaymentMybatis {
    private PaymentMybatis() {}

    static SqlSessionTemplate template(DataSource source) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(source));
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/Payment*Mapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("PAYMENT MyBatis initialization failed", failure);
        }
    }
}
