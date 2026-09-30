package com.petplatform.task.core;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.task.core.mapper.AsyncTaskMapper;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-driven recovery of an exact durable task after revalidating unfinished business facts. */
public final class JdbcAsyncTaskRecoverer {
    private final DataSource source;
    private final JdbcAsyncTaskSubmitter submitter;
    private final AsyncTaskMapper mapper;

    public JdbcAsyncTaskRecoverer(DataSource source, SnowflakeIdGenerator ids) {
        this.source = Objects.requireNonNull(source);
        submitter = new JdbcAsyncTaskSubmitter(source, ids);
        mapper = TaskMybatis.template(source).getMapper(AsyncTaskMapper.class);
    }

    /** Joins the owner transaction. Never changes payload or takes a RUNNING lease. */
    public long recover(String taskKey, String ownerModule, String taskType, String bizType,
            long bizId, Long expectedVersion, String payloadJson, int maxRetryCount,
            String retryPolicy, OffsetDateTime originalAvailableAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder))
            throw new IllegalStateException("Task recovery requires the owner writable transaction");
        try {
            if (holder.getConnection().getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
                throw new IllegalStateException("Task recovery requires READ_COMMITTED");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Task recovery transaction unavailable", e);
        }
        // This locks the exact row and rejects an existing key with any mismatched immutable field.
        long id = originalAvailableAt == null
                ? submitter.enqueue(taskKey, ownerModule, taskType, bizType, bizId, expectedVersion,
                        payloadJson, maxRetryCount, retryPolicy)
                : submitter.enqueueAt(taskKey, ownerModule, taskType, bizType, bizId, expectedVersion,
                        payloadJson, maxRetryCount, retryPolicy, originalAvailableAt);
        var originalSchedule = originalAvailableAt == null ? null
                : originalAvailableAt.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        // Unlike an old unscheduled enqueue replay, recovery must distinguish NULL from a fixed
        // deadline. Otherwise a caller could revive a scheduled timeout for immediate execution.
        if (mapper.countMatchingRecoverySchedule(id, originalSchedule) != 1)
            throw new IllegalArgumentException("Task recovery is bound to a different original schedule");
        var statuses = mapper.selectStatusByExactKey(taskKey);
        if (statuses.size() != 1 || !Set.of("READY", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "DEAD", "CANCELED").contains(statuses.getFirst()))
            throw new IllegalStateException("Task recovery state is unavailable");
        mapper.recoverTerminal(id, originalSchedule);
        return id;
    }
}
