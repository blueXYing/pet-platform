package com.petplatform.task.core.mapper;
import org.apache.ibatis.annotations.Param;
public interface TaskCancellationMapper {
    Long lock(@Param("key") String key);
    int cancel(@Param("id") long id);
    int finishAttempts(@Param("id") long id);
}
