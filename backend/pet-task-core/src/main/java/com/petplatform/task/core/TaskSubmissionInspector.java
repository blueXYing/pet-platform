package com.petplatform.task.core;

import com.petplatform.task.core.mapper.TaskInspectionMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;

/** TASK owns the SQL. Callers may compare immutable submissions without accessing task tables. */
public final class TaskSubmissionInspector {
    private final TaskInspectionMapper mapper;

    public TaskSubmissionInspector(DataSource source) {
        mapper = TaskMybatis.template(Objects.requireNonNull(source)).getMapper(TaskInspectionMapper.class);
    }

    /** Includes a collation-equivalent key so the caller can report a case-sensitive binding conflict. */
    public TaskSubmissionSnapshot find(String taskKey) {
        if (taskKey == null || taskKey.isBlank() || taskKey.length() > 191
                || taskKey.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid task key");
        var rows = mapper.findSubmission(taskKey);
        if (rows.size() > 1) throw new IllegalStateException("Ambiguous task submission");
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        return new TaskSubmissionSnapshot(Long.toString(row.id), row.taskKey, row.ownerModule,
                row.taskType, row.bizType, Long.toString(row.bizId), row.expectedVersion,
                row.payloadJson, offset(row.submittedExecuteAt), offset(row.executeAt), row.status,
                row.retryCount, row.maxRetryCount, row.retryPolicy);
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
