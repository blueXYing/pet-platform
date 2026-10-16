package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.merchant.biz.infrastructure.persistence.mapper.AdminMerchantDispositionMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** MERCHANT-owned SQL access for the contract-63 v0.2 disable action (joins the caller's tx). */
public final class AdminMerchantDispositionStore {
    private final AdminMerchantDispositionMapper mapper;

    public AdminMerchantDispositionStore(DataSource source) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(source, "source is required"));
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/AdminMerchantDispositionMapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            mapper = new SqlSessionTemplate(factory).getMapper(AdminMerchantDispositionMapper.class);
        } catch (Exception failure) {
            throw new IllegalStateException("MERCHANT admin disposition MyBatis initialization failed", failure);
        }
    }

    public AdminMerchantDispositionMapper mapper() { return mapper; }
}
