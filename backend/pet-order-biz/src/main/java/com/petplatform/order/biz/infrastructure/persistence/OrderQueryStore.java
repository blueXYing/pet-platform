package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderQueryMapper;
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

/** Read-only store over pet_order for the C-004 query slice (schema 06 + 48/49 projections). */
public final class OrderQueryStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate transaction;

    public OrderQueryStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
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
                    .getResources("classpath*:mapper/OrderQueryMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("order query mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<OrderQueryMapper, T> work) {
        return transaction.execute(status -> work.apply(sql.getMapper(OrderQueryMapper.class)));
    }
}
