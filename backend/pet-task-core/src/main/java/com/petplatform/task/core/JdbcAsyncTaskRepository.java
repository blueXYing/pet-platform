package com.petplatform.task.core;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.task.core.mapper.AsyncTaskMapper;
import com.petplatform.task.core.mapper.AsyncTaskRowEntity;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL 8 repository over SQL13. It owns short transactions, never a handler transaction.
 * Supply a dedicated infrastructure DataSource; its sessions use UTC DATETIME(3).
 * No ID bean is created here: production requires the PLAT-002 provider.
 */
public final class JdbcAsyncTaskRepository {
    private final SqlSessionTemplate template;
    private final TransactionTemplate transaction;
    private final SnowflakeIdGenerator ids;

    public JdbcAsyncTaskRepository(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.template = TaskMybatis.template(dataSource);
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // The task row serializes attempts; gap locks on different tasks are unnecessary
        // and can deadlock concurrent attempt inserts under MySQL REPEATABLE READ.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> {
            tasks().setTimeZoneUtc();
            return action.get();
        });
    }

    private AsyncTaskMapper tasks() {
        return template.getMapper(AsyncTaskMapper.class);
    }

    public Optional<TaskLease> claim(String owner, Duration leaseDuration) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("owner must contain 1..128 characters");
        }
        long micros = positiveMillis(leaseDuration) * 1000;
        long attemptId = ids.nextId(); // Do not hold claim locks while waiting for an ID.
        if (attemptId <= 0) throw new IllegalStateException("Invalid ID from provider");
        return inTransaction(() -> {
            AsyncTaskRowEntity candidate = tasks().selectClaimCandidate();
            if (candidate == null) return Optional.empty();
            int attemptNo = Math.addExact(tasks().selectMaxAttemptNo(candidate.getId()), 1);
            // An abandoned attempt is an interrupted delivery, not a business failure.
            tasks().expireAbandonedAttempts(candidate.getId());
            long nextVersion = Math.addExact(candidate.getVersion(), 1);
            requireOne(tasks().updateClaim(owner, micros, nextVersion, candidate.getId()));
            requireOne(tasks().insertAttempt(attemptId, candidate.getId(), attemptNo, owner));
            return Optional.of(new TaskLease(candidate.getId(), candidate.getTaskKey(),
                    candidate.getTaskType(), candidate.getBizId(), candidate.getExpectedVersion(),
                    candidate.getPayloadJson(), owner, nextVersion, attemptId, attemptNo,
                    candidate.getRetryCount(), candidate.getMaxRetryCount(), candidate.getRetryPolicy()));
        });
    }

    public boolean heartbeat(TaskLease lease, Duration duration) {
        long micros = positiveMillis(duration) * 1000;
        return inTransaction(() -> {
            lockTask(lease.taskId());
            return tasks().updateHeartbeat(micros, lease.taskId(), lease.owner(), lease.version()) == 1;
        });
    }

    /** False means lease lost. Task and its exact attempt are committed or rolled back together. */
    public boolean complete(TaskLease lease, TaskExecutionResult result) {
        Objects.requireNonNull(result);
        String state;
        String attemptResult;
        String resultCode = null;
        String errorCode = null;
        long delayMicros = 0;
        int retries = lease.retryCount();
        switch (result) {
            case TaskExecutionResult.Success success -> {
                state = "SUCCEEDED"; resultCode = code(success.resultCode());
                attemptResult = "NOOP".equals(resultCode) ? "NOOP" : "SUCCESS";
            }
            case TaskExecutionResult.Cancelled cancelled -> {
                state = "CANCELED"; attemptResult = "NOOP"; resultCode = code(cancelled.reasonCode());
            }
            case TaskExecutionResult.Dead dead -> {
                state = "DEAD"; attemptResult = "DEAD"; errorCode = code(dead.errorCode());
            }
            case TaskExecutionResult.Retry retry -> {
                errorCode = code(retry.errorCode());
                retries = Math.addExact(retries, 1);
                state = retries > lease.maxRetryCount() ? "DEAD" : "RETRY_WAIT";
                attemptResult = "DEAD".equals(state) ? "DEAD" : "RETRY";
                delayMicros = positiveMillis(retry.nextDelay()) * 1000;
            }
        }
        String finalState = state;
        String finalAttempt = attemptResult;
        String finalResultCode = resultCode;
        String finalErrorCode = errorCode;
        int finalRetries = retries;
        long finalDelay = delayMicros;
        return inTransaction(() -> {
            lockTask(lease.taskId());
            int changed = tasks().updateComplete(finalState, finalRetries, finalResultCode, finalErrorCode,
                    finalDelay, lease.taskId(), lease.owner(), lease.version());
            if (changed == 0) return false;
            requireOne(tasks().updateAttemptResult(finalAttempt, finalErrorCode, lease.attemptId(),
                    lease.taskId(), lease.attemptNo(), lease.owner()));
            return true;
        });
    }

    private void lockTask(long taskId) {
        // NOW is fixed at statement start in MySQL. Acquire a contended lock first,
        // then evaluate lease validity in a fresh UPDATE statement after any wait.
        tasks().selectIdForUpdate(taskId);
    }

    static long positiveMillis(Duration value) {
        Objects.requireNonNull(value);
        long millis = value.toMillis();
        if (millis <= 0 || millis > Duration.ofDays(365).toMillis()
                || !value.equals(Duration.ofMillis(millis))) {
            throw new IllegalArgumentException("Duration must be whole milliseconds in (0,365 days]");
        }
        return millis;
    }

    private static String code(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("Result/error code must contain 1..64 characters");
        }
        return value;
    }

    private static void requireOne(int affected) {
        if (affected != 1) throw new IllegalStateException("Task/attempt pairing violated; transaction rolled back");
    }
}
