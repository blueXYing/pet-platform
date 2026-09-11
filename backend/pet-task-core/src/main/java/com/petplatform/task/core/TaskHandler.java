package com.petplatform.task.core;

public interface TaskHandler<T> {
    String taskType();
    TaskExecutionResult execute(TaskExecutionContext context, T payload);
}
