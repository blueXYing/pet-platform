package com.petplatform.verification.biz.infrastructure.persistence;

import com.petplatform.verification.biz.infrastructure.persistence.mapper.AdminVerificationQueryMapper;
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

/** Read-only store behind the contract-63 platform verification face (schemas 06/47/48). */
public final class AdminVerificationQueryStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate transaction;

    public AdminVerificationQueryStore(DataSource source) {
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
                    .getResources("classpath*:mapper/AdminVerificationQueryMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("admin verification query mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<AdminVerificationQueryMapper, T> work) {
        return transaction.execute(status -> work.apply(sql.getMapper(AdminVerificationQueryMapper.class)));
    }
}
