package com.petplatform.task.core;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import com.petplatform.task.core.mapper.AsyncTaskMapper;

/** Read-only status lookup for an owner reconciling its durable tasks. */
public final class JdbcAsyncTaskStatusReader {
    private final AsyncTaskMapper mapper;

    public JdbcAsyncTaskStatusReader(DataSource source) {
        mapper = TaskMybatis.template(Objects.requireNonNull(source)).getMapper(AsyncTaskMapper.class);
    }

    /** Returns null when the deterministic task key has no row. */
    public String status(String taskKey) {
        if (taskKey == null || taskKey.isBlank() || taskKey.length() > 191)
            throw new IllegalArgumentException("invalid task key");
        List<String> rows = mapper.selectStatusByExactKey(taskKey);
        if (rows.size() > 1) throw new IllegalStateException("duplicate async task key");
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
