package com.petplatform.notification.biz.infrastructure.persistence;

import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.notification.biz.infrastructure.persistence.mapper.NotificationMerchantReviewMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.BiPredicate;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Notification-owned transaction: the platform consume guard and inbox insert commit together. */
public final class MerchantReviewNotificationStore {
  private final SqlSessionTemplate sql;
  private final TransactionTemplate transaction;
  private final BiPredicate<String, DispatchedEvent> consumeGuard;

  public MerchantReviewNotificationStore(
      DataSource source, BiPredicate<String, DispatchedEvent> consumeGuard) {
    Objects.requireNonNull(source);
    this.consumeGuard = Objects.requireNonNull(consumeGuard);
    this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      var factory = new SqlSessionFactoryBean();
      factory.setDataSource(source);
      factory.setMapperLocations(
          new PathMatchingResourcePatternResolver()
              .getResources("classpath*:mapper/NotificationMerchantReviewMapper.xml"));
      sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
    } catch (Exception failure) {
      throw new IllegalStateException("notification mapper initialization failed");
    }
  }

  public void recordOnce(
      String consumer,
      DispatchedEvent event,
      long id,
      long ownerId,
      long applicationId,
      String title,
      String content,
      OffsetDateTime createdAt) {
    transaction.executeWithoutResult(
        status -> {
          var mapper = sql.getMapper(NotificationMerchantReviewMapper.class);
          mapper.setTimeZoneUtc();
          if (!consumeGuard.test(consumer, event)) return;
          if (mapper.insert(
                  id,
                  ownerId,
                  applicationId,
                  title,
                  content,
                  createdAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())
              != 1) {
            throw new IllegalStateException("notification was not persisted");
          }
        });
  }
}
