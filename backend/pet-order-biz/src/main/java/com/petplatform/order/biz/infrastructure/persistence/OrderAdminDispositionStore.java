package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAdminDispositionMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** ORDER-owned SQL access for the contract-63 v0.2 disposal actions (joins the caller's tx). */
public final class OrderAdminDispositionStore {
    private final OrderAdminDispositionMapper mapper;

    public OrderAdminDispositionStore(DataSource source) {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(Objects.requireNonNull(source, "source is required"));
        try {
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/OrderAdminDispositionMapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            mapper = new SqlSessionTemplate(factory).getMapper(OrderAdminDispositionMapper.class);
        } catch (Exception failure) {
            throw new IllegalStateException("ORDER admin disposition MyBatis initialization failed", failure);
        }
    }

    public OrderAdminDispositionMapper mapper() { return mapper; }
}
