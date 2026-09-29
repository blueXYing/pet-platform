package com.petplatform.task.core;

import com.petplatform.task.core.mapper.TaskCancellationMapper;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins the owner's business transaction; a cancellation fences even a currently running lease. */
public final class TaskCancellation {
    public enum Result { MISSING, CANCELED, TERMINAL }
    private final DataSource source;
    private final TaskCancellationMapper mapper;
    private final TaskSubmissionInspector inspector;

    public TaskCancellation(DataSource source) {
        this.source = Objects.requireNonNull(source);
        mapper = TaskMybatis.template(source).getMapper(TaskCancellationMapper.class);
        inspector = new TaskSubmissionInspector(source);
    }

    public Result cancel(String key, Predicate<TaskSubmissionSnapshot> binding) {
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (!(resource instanceof ConnectionHolder holder)
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Cancellation requires the owner's writable transaction");
        try {
            Long id = mapper.lock(key);
            if (id == null) return Result.MISSING;
            var task = inspector.find(key);
            if (task == null || !key.equals(task.taskKey()) || !binding.test(task))
                throw new IllegalStateException("Task cancellation binding mismatch");
            if (Set.of("SUCCEEDED", "DEAD", "CANCELED").contains(task.status())) return Result.TERMINAL;
            if (!Set.of("READY", "RETRY_WAIT", "RUNNING").contains(task.status()))
                throw new IllegalStateException("Unknown task cancellation state");
            if (mapper.cancel(id) != 1) throw new IllegalStateException("Task cancellation failed");
            mapper.finishAttempts(id);
            return Result.CANCELED;
        } catch (RuntimeException failure) {
            holder.setRollbackOnly();
            throw failure;
        }
    }
}
