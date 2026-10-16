package com.petplatform.points.biz.infrastructure.persistence;

import com.petplatform.points.biz.infrastructure.persistence.mapper.AdminPointsQueryMapper;
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

/** Read-only store over points_ledger for the contract-65 operator face (SQL06 §9). */
public final class AdminPointsQueryStore {
    private final SqlSessionTemplate sql;
    private final TransactionTemplate transaction;

    public AdminPointsQueryStore(DataSource source) {
        Objects.requireNonNull(source, "dataSource is required");
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
                    .getResources("classpath*:mapper/AdminPointsQueryMapper.xml"));
            sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        } catch (Exception failure) {
            throw new IllegalStateException("points admin mapper initialization failed", failure);
        }
    }

    public <T> T read(Function<AdminPointsQueryMapper, T> work) {
        return transaction.execute(status -> work.apply(sql.getMapper(AdminPointsQueryMapper.class)));
    }
}
