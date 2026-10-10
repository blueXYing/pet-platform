package com.petplatform.refund.biz.infrastructure.persistence;

import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationQueryMapper;
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

/** Read-only store over refund_application for the contract-56 merchant query slice. */
public final class RefundApplicationQueryStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate transaction;

    public RefundApplicationQueryStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // The schedule store guard joining this transaction requires exactly READ_COMMITTED.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/RefundApplicationQueryMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("refund application query mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<RefundApplicationQueryMapper, T> work) {
        return transaction.execute(status -> work.apply(sql.getMapper(RefundApplicationQueryMapper.class)));
    }
}
