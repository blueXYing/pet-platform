package com.petplatform.thirdparty.biz.infrastructure.persistence;

import com.petplatform.thirdparty.biz.infrastructure.persistence.mapper.PrivateAssetMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** MyBatis owner for the private-asset tables. All transactional callbacks use the supplied DS. */
public final class PrivateAssetRepository {
  private final SqlSessionTemplate sessions;
  private final TransactionTemplate transactions;

  public PrivateAssetRepository(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "DataSource is required");
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    var configuration = new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factory.setConfiguration(configuration);
    try {
      factory.setMapperLocations(
          new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
      sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
    } catch (Exception failure) {
      throw new IllegalStateException("Private asset MyBatis initialization failed", failure);
    }
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(15);
  }

  public <T> T transaction(Function<PrivateAssetMapper, T> work) {
    return transactions.execute(
        status -> {
          PrivateAssetMapper mapper = sessions.getMapper(PrivateAssetMapper.class);
          mapper.setSessionTimeZoneUtc();
          return work.apply(mapper);
        });
  }

  public <T> T read(Function<PrivateAssetMapper, T> work) {
    PrivateAssetMapper mapper = sessions.getMapper(PrivateAssetMapper.class);
    mapper.setSessionTimeZoneUtc();
    return work.apply(mapper);
  }
}
