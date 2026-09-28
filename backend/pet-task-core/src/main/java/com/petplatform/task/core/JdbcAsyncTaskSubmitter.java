package com.petplatform.task.core;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.task.core.mapper.AsyncTaskMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Producer API for SQL13. Enqueue joins the business transaction on the exact same DataSource. */
public final class JdbcAsyncTaskSubmitter {
  private final DataSource dataSource;
  private final SqlSessionTemplate template;
  private final SnowflakeIdGenerator ids;

  public JdbcAsyncTaskSubmitter(DataSource dataSource, SnowflakeIdGenerator ids) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.template = TaskMybatis.template(dataSource);
    this.ids = Objects.requireNonNull(ids);
  }

  public long enqueue(
      String taskKey,
      String ownerModule,
      String taskType,
      String bizType,
      long bizId,
      Long expectedVersion,
      String payloadJson,
      int maxRetryCount,
      String retryPolicy) {
    return submit(taskKey,ownerModule,taskType,bizType,bizId,expectedVersion,payloadJson,maxRetryCount,retryPolicy,null);
  }

  /** Schedules the first attempt without spending retry budget while waiting for a business deadline. */
  public long enqueueAt(String taskKey,String ownerModule,String taskType,String bizType,long bizId,
      Long expectedVersion,String payloadJson,int maxRetryCount,String retryPolicy,
      java.time.OffsetDateTime availableAt) {
    Objects.requireNonNull(availableAt, "availableAt");
    if (availableAt.getNano()%1_000_000!=0) throw new IllegalArgumentException("Deadline must have millisecond precision");
    return submit(taskKey,ownerModule,taskType,bizType,bizId,expectedVersion,payloadJson,maxRetryCount,retryPolicy,
        availableAt.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime());
  }

  private long submit(String taskKey,String ownerModule,String taskType,String bizType,long bizId,
      Long expectedVersion,String payloadJson,int maxRetryCount,String retryPolicy,java.time.LocalDateTime availableAt) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(dataSource)) {
      throw new IllegalStateException(
          "Enqueue requires the caller transaction on the same DataSource");
    }
    text(taskKey, 191);
    text(ownerModule, 32);
    text(taskType, 64);
    text(bizType, 32);
    text(retryPolicy, 32);
    if (bizId <= 0
        || (expectedVersion != null && expectedVersion < 0)
        || maxRetryCount < 0
        || maxRetryCount > 1000
        || payloadJson == null
        || payloadJson.isBlank()
        || payloadJson.length() > 65_536) {
      throw new IllegalArgumentException("Invalid durable task submission");
    }
    long id = ids.nextId();
    if (id <= 0) throw new IllegalStateException("Invalid PLAT-002 task ID");
    AsyncTaskMapper mapper = template.getMapper(AsyncTaskMapper.class);
    mapper.setTimeZoneUtc();
    mapper.insertSubmission(
        id,
        taskKey,
        ownerModule,
        taskType,
        bizType,
        bizId,
        expectedVersion,
        payloadJson,
        maxRetryCount,
        retryPolicy, availableAt);
    Long matched =
        mapper.selectMatchingSubmission(
            taskKey,
            ownerModule,
            taskType,
            bizType,
            bizId,
            expectedVersion,
            payloadJson,
            maxRetryCount,
            retryPolicy, availableAt);
    if (matched == null)
      throw new IllegalArgumentException("Durable task key is bound to different content");
    return matched;
  }

  private static void text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid durable task metadata");
    }
  }
}
