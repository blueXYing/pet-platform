package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderCustomerNoteMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Mapper access for the merchant customer note write face (contract 69 expansion). ORDER
 * application code outside the persistence package receives this store instead of the
 * package-private MyBatis factory; same statement family loading as {@link OrderMybatis}
 * (classpath mapper/Order*Mapper.xml, statement-scoped local cache).
 */
public final class OrderCustomerNoteStore {
    private final OrderCustomerNoteMapper mapper;

    public OrderCustomerNoteStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
        try {
            org.mybatis.spring.SqlSessionFactoryBean bean =
                    new org.mybatis.spring.SqlSessionFactoryBean();
            bean.setDataSource(source);
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/Order*Mapper.xml"));
            org.apache.ibatis.session.SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
            this.mapper = new SqlSessionTemplate(factory).getMapper(OrderCustomerNoteMapper.class);
        } catch (Exception failure) {
            throw new IllegalStateException("customer note mapper initialization failed", failure);
        }
    }

    public OrderCustomerNoteMapper mapper() {
        return mapper;
    }
}
