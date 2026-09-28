package com.petplatform.task.core.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TaskInspectionMapper {
    List<Row> findSubmission(@Param("taskKey") String taskKey);

    final class Row {
        public Long id, bizId, expectedVersion;
        public String taskKey, ownerModule, taskType, bizType, payloadJson, status, retryPolicy;
        public Integer retryCount, maxRetryCount;
        public LocalDateTime submittedExecuteAt, executeAt;
    }
}
