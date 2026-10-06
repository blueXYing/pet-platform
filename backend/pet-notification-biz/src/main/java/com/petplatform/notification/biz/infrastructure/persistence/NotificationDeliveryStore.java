package com.petplatform.notification.biz.infrastructure.persistence;

import com.petplatform.notification.biz.infrastructure.persistence.mapper.NotificationDeliveryMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Store over the shared notification_delivery table (Schema 06 section 11). PROPAGATION_REQUIRED:
 * the producer join must run inside the caller's inbox transaction on the same DataSource, while
 * handler-side CAS updates execute standalone on the worker thread.
 */
public final class NotificationDeliveryStore {
  private final SqlSessionTemplate sql;
  private final TransactionTemplate transaction;

  public NotificationDeliveryStore(DataSource source) {
    Objects.requireNonNull(source, "dataSource is required");
    this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    try {
      var factory = new SqlSessionFactoryBean();
      factory.setDataSource(source);
      org.apache.ibatis.session.Configuration configuration =
          new org.apache.ibatis.session.Configuration();
      configuration.setMapUnderscoreToCamelCase(true);
      factory.setConfiguration(configuration);
      factory.setMapperLocations(
          new PathMatchingResourcePatternResolver()
              .getResources("classpath*:mapper/NotificationDeliveryMapper.xml"));
      sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
    } catch (Exception failure) {
      throw new IllegalStateException("delivery mapper initialization failed");
    }
  }

  public <T> T read(Function<NotificationDeliveryMapper, T> work) {
    return transaction.execute(status -> work.apply(sql.getMapper(NotificationDeliveryMapper.class)));
  }

  public <T> T write(Function<NotificationDeliveryMapper, T> work) {
    return transaction.execute(status -> work.apply(sql.getMapper(NotificationDeliveryMapper.class)));
  }
}
