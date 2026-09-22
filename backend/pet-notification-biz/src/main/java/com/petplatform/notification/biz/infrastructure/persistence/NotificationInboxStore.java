package com.petplatform.notification.biz.infrastructure.persistence;

import com.petplatform.notification.biz.infrastructure.persistence.entity.NotificationInboxEntity;
import com.petplatform.notification.biz.infrastructure.persistence.mapper.NotificationInboxMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Read/CAS store over the shared notification inbox table (schema 06 section 11). */
public final class NotificationInboxStore {
  private final SqlSessionTemplate sql;
  private final TransactionTemplate transaction;

  public NotificationInboxStore(DataSource source) {
    Objects.requireNonNull(source, "dataSource is required");
    this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      var factory = new SqlSessionFactoryBean();
      factory.setDataSource(source);
      org.apache.ibatis.session.Configuration configuration =
          new org.apache.ibatis.session.Configuration();
      configuration.setMapUnderscoreToCamelCase(true);
      factory.setConfiguration(configuration);
      factory.setMapperLocations(
          new PathMatchingResourcePatternResolver()
              .getResources("classpath*:mapper/NotificationInboxMapper.xml"));
      sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
    } catch (Exception failure) {
      throw new IllegalStateException("inbox mapper initialization failed");
    }
  }

  public <T> T read(Function<NotificationInboxMapper, T> work) {
    return transaction.execute(status -> work.apply(sql.getMapper(NotificationInboxMapper.class)));
  }

  public <T> T write(Function<NotificationInboxMapper, T> work) {
    return transaction.execute(status -> work.apply(sql.getMapper(NotificationInboxMapper.class)));
  }

}
