package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantStaffNamingMapper;
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

/** Read-only staff display-name store behind the CCR-M-VERIFY-RECORD-001 naming face. */
public final class MerchantStaffNamingStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate transaction;

    public MerchantStaffNamingStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setReadOnly(true);
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/MerchantStaffNamingMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("merchant staff naming mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<MerchantStaffNamingMapper, T> work) {
        return transaction.execute(status -> work.apply(sql.getMapper(MerchantStaffNamingMapper.class)));
    }
}
