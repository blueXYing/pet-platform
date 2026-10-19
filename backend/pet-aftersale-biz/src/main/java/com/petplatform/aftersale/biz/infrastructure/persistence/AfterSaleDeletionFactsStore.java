package com.petplatform.aftersale.biz.infrastructure.persistence;

import com.petplatform.aftersale.biz.infrastructure.persistence.mapper.AfterSaleDeletionFactsMapper;
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

/** Read-only store for the contract-66 deletion-blocking fact over aftersale_case. */
public final class AfterSaleDeletionFactsStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate transaction;

    public AfterSaleDeletionFactsStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/AfterSaleDeletionFactsMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("aftersale deletion facts mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<AfterSaleDeletionFactsMapper, T> work) {
        return transaction.execute(status -> work.apply(sql.getMapper(AfterSaleDeletionFactsMapper.class)));
    }
}
