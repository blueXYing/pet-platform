package com.petplatform.task.core;

import com.petplatform.task.core.mapper.AsyncTaskMapper;
import java.time.LocalDateTime;
import javax.sql.DataSource;

/** Database clock used to calculate durable task retry delays. */
public final class TaskDatabaseClock {
    private final AsyncTaskMapper mapper;

    public TaskDatabaseClock(DataSource source) {
        mapper = TaskMybatis.template(source).getMapper(AsyncTaskMapper.class);
    }

    public LocalDateTime now() { return mapper.selectDatabaseNow(); }
}
