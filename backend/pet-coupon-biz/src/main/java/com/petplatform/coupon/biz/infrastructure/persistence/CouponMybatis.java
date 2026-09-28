package com.petplatform.coupon.biz.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Coupon mappers join the caller's guarded DataSource transaction. */
public final class CouponMybatis {
    private CouponMybatis() {}

    public static SqlSessionTemplate template(DataSource source) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(source, "source is required"));
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/Coupon*Mapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
            return new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("coupon SqlSessionFactory build failed", failure);
        }
    }
}
