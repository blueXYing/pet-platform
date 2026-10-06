package com.petplatform.coupon.biz.infrastructure.persistence;

import com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponQueryMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only store over coupon_instance x coupon_template (schema 06 section 8, CCR-C006 P1). */
public final class CouponQueryStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate transaction;

    public CouponQueryStore(DataSource source) {
        Objects.requireNonNull(source, "dataSource is required");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/CouponQueryMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("coupon query mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<CouponQueryMapper, T> work) {
        return transaction.execute(status -> work.apply(sql.getMapper(CouponQueryMapper.class)));
    }
}
