package com.petplatform.task.core;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only status lookup for an owner reconciling its durable tasks. */
public final class JdbcAsyncTaskStatusReader {
    private final JdbcTemplate jdbc;

    public JdbcAsyncTaskStatusReader(DataSource source) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(source));
    }

    /** Returns null when the deterministic task key has no row. */
    public String status(String taskKey) {
        if (taskKey == null || taskKey.isBlank() || taskKey.length() > 191)
            throw new IllegalArgumentException("invalid task key");
        List<String> rows = jdbc.query("SELECT status FROM async_task WHERE BINARY task_key=BINARY ?",
                (rs, row) -> rs.getString(1), taskKey);
        if (rows.size() > 1) throw new IllegalStateException("duplicate async task key");
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
