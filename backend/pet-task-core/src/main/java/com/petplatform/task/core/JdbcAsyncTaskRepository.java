package com.petplatform.task.core;

import com.petplatform.common.SnowflakeIdGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL 8 repository over SQL13. It owns short transactions, never a handler transaction.
 * Supply a dedicated infrastructure DataSource; its sessions use UTC DATETIME(3).
 * No ID bean is created here: production requires the PLAT-002 provider.
 */
public final class JdbcAsyncTaskRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SnowflakeIdGenerator ids;

    public JdbcAsyncTaskRepository(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
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
            jdbc.execute("SET SESSION time_zone = '+00:00'");
            return action.get();
        });
    }

    public Optional<TaskLease> claim(String owner, Duration leaseDuration) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("owner must contain 1..128 characters");
        }
        long micros = positiveMillis(leaseDuration) * 1000;
        long attemptId = ids.nextId(); // Do not hold claim locks while waiting for an ID.
        if (attemptId <= 0) throw new IllegalStateException("Invalid ID from provider");
        return inTransaction(() -> {
            List<TaskLease> candidates = jdbc.query("""
                    SELECT * FROM async_task
                    WHERE (status IN ('READY','RETRY_WAIT') AND execute_at <= NOW(3))
                       OR (status = 'RUNNING' AND lease_until < NOW(3))
                    ORDER BY priority DESC, execute_at ASC, id ASC
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new TaskLease(rs.getLong("id"), rs.getString("task_key"),
                    rs.getString("task_type"), rs.getLong("biz_id"),
                    rs.getObject("expected_version", Long.class), rs.getString("payload_json"),
                    owner, Math.addExact(rs.getLong("version"), 1), attemptId, 0,
                    rs.getInt("retry_count"), rs.getInt("max_retry_count"), rs.getString("retry_policy")));
            if (candidates.isEmpty()) return Optional.empty();
            TaskLease candidate = candidates.getFirst();
            Integer prior = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(attempt_no),0) FROM async_task_attempt WHERE task_id=?",
                    Integer.class, candidate.taskId());
            int attemptNo = Math.addExact(Objects.requireNonNull(prior), 1);
            // An abandoned attempt is an interrupted delivery, not a business failure.
            jdbc.update("""
                    UPDATE async_task_attempt SET result='RETRY', error_code='LEASE_EXPIRED',
                    finished_at=NOW(3), duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,NOW(3)) DIV 1000)
                    WHERE task_id=? AND finished_at IS NULL
                    """, candidate.taskId());
            requireOne(jdbc.update("""
                    UPDATE async_task SET status='RUNNING',lease_owner=?,
                    lease_until=TIMESTAMPADD(MICROSECOND,?,NOW(3)), version=?, updated_at=NOW(3)
                    WHERE id=?
                    """, owner, micros, candidate.version(), candidate.taskId()));
            requireOne(jdbc.update("""
                    INSERT INTO async_task_attempt
                    (id,task_id,attempt_no,instance_id,started_at,created_at)
                    VALUES (?,?,?,?,NOW(3),NOW(3))
                    """, attemptId, candidate.taskId(), attemptNo, owner));
            return Optional.of(new TaskLease(candidate.taskId(), candidate.taskKey(), candidate.taskType(),
                    candidate.bizId(), candidate.expectedVersion(), candidate.payloadJson(), owner,
                    candidate.version(), attemptId, attemptNo, candidate.retryCount(),
                    candidate.maxRetryCount(), candidate.retryPolicy()));
        });
    }

    public boolean heartbeat(TaskLease lease, Duration duration) {
        long micros = positiveMillis(duration) * 1000;
        return inTransaction(() -> {
            lockTask(lease.taskId());
            return jdbc.update("""
                UPDATE async_task SET lease_until=TIMESTAMPADD(MICROSECOND,?,NOW(3)), updated_at=NOW(3)
                WHERE id=? AND status='RUNNING' AND lease_owner=? AND version=? AND lease_until>=NOW(3)
                """, micros, lease.taskId(), lease.owner(), lease.version()) == 1;
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
            int changed = jdbc.update("""
                    UPDATE async_task SET status=?,retry_count=?,last_result_code=?,last_error_code=?,
                    last_error_message=NULL,lease_owner=NULL,lease_until=NULL,updated_at=NOW(3),
                    execute_at=IF(?='RETRY_WAIT',TIMESTAMPADD(MICROSECOND,?,NOW(3)),execute_at),
                    finished_at=IF(?='RETRY_WAIT',NULL,NOW(3))
                    WHERE id=? AND status='RUNNING' AND lease_owner=? AND version=? AND lease_until>=NOW(3)
                    """, finalState, finalRetries, finalResultCode, finalErrorCode, finalState, finalDelay,
                    finalState, lease.taskId(), lease.owner(), lease.version());
            if (changed == 0) return false;
            requireOne(jdbc.update("""
                    UPDATE async_task_attempt SET result=?,error_code=?,finished_at=NOW(3),
                    duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,NOW(3)) DIV 1000)
                    WHERE id=? AND task_id=? AND attempt_no=? AND instance_id=? AND finished_at IS NULL
                    """, finalAttempt, finalErrorCode, lease.attemptId(), lease.taskId(),
                    lease.attemptNo(), lease.owner()));
            return true;
        });
    }

    private void lockTask(long taskId) {
        // NOW is fixed at statement start in MySQL. Acquire a contended lock first,
        // then evaluate lease validity in a fresh UPDATE statement after any wait.
        jdbc.query("SELECT id FROM async_task WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), taskId);
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
