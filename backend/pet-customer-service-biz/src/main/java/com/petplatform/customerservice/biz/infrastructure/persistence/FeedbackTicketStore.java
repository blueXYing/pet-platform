package com.petplatform.customerservice.biz.infrastructure.persistence;

import com.petplatform.customerservice.biz.infrastructure.persistence.mapper.FeedbackTicketMapper;
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

/**
 * MyBatis wiring for this module's tables (67号 schema; 22号裁决 discipline): one factory per
 * DataSource over this module's own mapper XML only, so every mapper call joins the surrounding
 * DataSourceTransactionManager transaction on the same connection.
 */
public final class FeedbackTicketStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate execution;

    public FeedbackTicketStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        this.execution = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        execution.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        execution.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        execution.setTimeout(10);
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/FeedbackTicketMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("feedback ticket mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<FeedbackTicketMapper, T> work) {
        return execution.execute(status -> work.apply(sql.getMapper(FeedbackTicketMapper.class)));
    }

    /** Inside an existing transaction (idempotent admission/execution frame). */
    public FeedbackTicketMapper mapper() {
        return sql.getMapper(FeedbackTicketMapper.class);
    }

    public TransactionTemplate execution() {
        return execution;
    }
}
