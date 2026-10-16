package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.merchant.biz.infrastructure.persistence.mapper.AdminMerchantGovernanceMapper;
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

/** Read-only store behind the contract-64 merchant governance face (06/28/29/52/54 tables). */
public final class AdminMerchantGovernanceStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate transaction;

    public AdminMerchantGovernanceStore(DataSource source) {
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
                    .getResources("classpath*:mapper/AdminMerchantGovernanceMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("admin merchant governance mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<AdminMerchantGovernanceMapper, T> work) {
        return transaction.execute(status -> work.apply(sql.getMapper(AdminMerchantGovernanceMapper.class)));
    }
}
