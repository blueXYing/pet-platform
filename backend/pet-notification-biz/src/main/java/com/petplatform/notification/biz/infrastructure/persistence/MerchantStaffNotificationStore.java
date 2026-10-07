package com.petplatform.notification.biz.infrastructure.persistence;

import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.notification.biz.infrastructure.persistence.mapper.NotificationMerchantStaffMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff-binding (contract 54 NTF slice) notification transaction: the platform consume guard and
 * the inbox insert(s) commit together, mirroring the review-notification store precedents. One
 * event may fan out to two receivers (CONFIRMED notifies the owner and the confirmed employee);
 * both inserts live in the same single transaction, so a partial delivery is impossible. The
 * nullable NTF-002 {@code afterInboxInsert} hook keeps the wechat delivery producer contract-55
 * compatible: it runs inside this transaction right after each authoritative row is persisted.
 */
public final class MerchantStaffNotificationStore {
  private final SqlSessionTemplate sql;
  private final TransactionTemplate transaction;
  private final BiPredicate<String, DispatchedEvent> consumeGuard;

  public MerchantStaffNotificationStore(
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
              .getResources("classpath*:mapper/NotificationMerchantStaffMapper.xml"));
      sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
    } catch (Exception failure) {
      throw new IllegalStateException("notification mapper initialization failed");
    }
  }

  public void recordOnce(String consumer, DispatchedEvent event, Outbox only) {
    recordOnce(consumer, event, new Outbox[] {only}, null);
  }

  public void recordOnce(
      String consumer, DispatchedEvent event, Outbox only, BiConsumer<Long, Long> afterInboxInsert) {
    recordOnce(consumer, event, new Outbox[] {only}, afterInboxInsert);
  }

  public void recordOnce(String consumer, DispatchedEvent event, Outbox first, Outbox second) {
    recordOnce(consumer, event, second == null ? new Outbox[] {first} : new Outbox[] {first, second}, null);
  }

  public void recordOnce(
      String consumer,
      DispatchedEvent event,
      Outbox first,
      Outbox second,
      BiConsumer<Long, Long> afterInboxInsert) {
    recordOnce(consumer, event, second == null ? new Outbox[] {first} : new Outbox[] {first, second},
        afterInboxInsert);
  }

  private void recordOnce(
      String consumer, DispatchedEvent event, Outbox[] rows, BiConsumer<Long, Long> afterInboxInsert) {
    transaction.executeWithoutResult(
        status -> {
          var mapper = sql.getMapper(NotificationMerchantStaffMapper.class);
          mapper.setTimeZoneUtc();
          if (!consumeGuard.test(consumer, event)) return;
          for (Outbox row : rows) {
            if (mapper.insert(
                    row.id(),
                    row.receiverId(),
                    row.messageType(),
                    row.bizType(),
                    row.bizId(),
                    row.title(),
                    row.content(),
                    row.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())
                != 1) {
              throw new IllegalStateException("notification was not persisted");
            }
            if (afterInboxInsert != null) afterInboxInsert.accept(row.id(), row.receiverId());
          }
        });
  }

  /** Immutable insert plan for one authoritative notification row. */
  public record Outbox(
      long id,
      long receiverId,
      String messageType,
      String bizType,
      long bizId,
      String title,
      String content,
      OffsetDateTime createdAt) {

    public Outbox {
      Objects.requireNonNull(createdAt, "createdAt is required");
      createdAt = createdAt.withOffsetSameInstant(ZoneOffset.UTC);
    }
  }
}
