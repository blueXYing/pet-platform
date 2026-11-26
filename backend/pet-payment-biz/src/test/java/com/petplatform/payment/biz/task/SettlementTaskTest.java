package com.petplatform.payment.biz.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskLease;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Offline verification for SETTLEMENT_DUE and SETTLEMENT_CHANNEL_QUERY task decoders and backoff. */
class SettlementTaskTest {

    @Test
    void dueTaskDecodesAndMapsOutcomes() {
        long orderId = 90001L;
        TaskLease lease = new TaskLease(
                1L, SettlementDueTask.taskKey(orderId, 2), SettlementDueTask.TASK_TYPE,
                orderId, 2L, SettlementDueTask.payload(orderId, 2), "owner",
                0L, 10L, 1, 1, SettlementDueTask.MAX_RETRY_COUNT, SettlementDueTask.RETRY_POLICY);
        var decoded = SettlementDueTask.decode(lease);
        assertEquals(orderId, decoded.settlementOrderId());
        assertEquals(2, decoded.round());
        assertEquals(1, decoded.retryCount());

        assertInstanceOf(TaskExecutionResult.Success.class,
                SettlementDueTask.map(new SettlementDueTask.Outcome.Done(), 0));
        assertInstanceOf(TaskExecutionResult.Success.class,
                SettlementDueTask.map(new SettlementDueTask.Outcome.Held("RECEIVER_NOT_ACTIVE"), 0));
        assertInstanceOf(TaskExecutionResult.Cancelled.class,
                SettlementDueTask.map(new SettlementDueTask.Outcome.Canceled("ORDER_FULLY_REFUNDED"), 0));
        var retry = assertInstanceOf(TaskExecutionResult.Retry.class,
                SettlementDueTask.map(new SettlementDueTask.Outcome.Retry("IO"), 1));
        assertEquals(Duration.ofMinutes(5), retry.nextDelay());
        assertInstanceOf(TaskExecutionResult.Dead.class,
                SettlementDueTask.map(new SettlementDueTask.Outcome.Retry("IO"), SettlementDueTask.MAX_RETRY_COUNT - 1));

        // Wrong expectedVersion rejects lease.
        TaskLease tampered = new TaskLease(
                1L, SettlementDueTask.taskKey(orderId, 2), SettlementDueTask.TASK_TYPE,
                orderId, 1L, SettlementDueTask.payload(orderId, 2), "owner",
                0L, 10L, 1, 0, SettlementDueTask.MAX_RETRY_COUNT, SettlementDueTask.RETRY_POLICY);
        assertThrows(IllegalArgumentException.class, () -> SettlementDueTask.decode(tampered));
    }

    @Test
    void queryTaskEnforcesCcrBackoffScheduleAndDeadExhaustion() {
        assertEquals(Duration.ofSeconds(15), SettlementQueryTask.FIRST_DELAY);
        assertEquals(Duration.ofSeconds(15), SettlementQueryTask.delay(0));
        assertEquals(Duration.ofMinutes(1), SettlementQueryTask.delay(1));
        assertEquals(Duration.ofMinutes(5), SettlementQueryTask.delay(2));
        assertEquals(Duration.ofMinutes(15), SettlementQueryTask.delay(3));
        assertEquals(Duration.ofHours(1), SettlementQueryTask.delay(4));
        assertEquals(Duration.ofHours(6), SettlementQueryTask.delay(5));
        assertEquals(Duration.ofHours(6), SettlementQueryTask.delay(15));

        long instrId = 80001L;
        TaskLease lease = new TaskLease(
                2L, SettlementQueryTask.taskKey(instrId), SettlementQueryTask.TASK_TYPE,
                instrId, null, SettlementQueryTask.payload(instrId), "owner",
                0L, 11L, 1, 3, SettlementQueryTask.MAX_RETRY_COUNT, SettlementQueryTask.RETRY_POLICY);
        var decoded = SettlementQueryTask.decode(lease);
        assertEquals(instrId, decoded.instructionId());
        assertEquals(3, decoded.retryCount());

        var pending = assertInstanceOf(TaskExecutionResult.Retry.class,
                SettlementQueryTask.map(SettlementQueryTask.Outcome.PENDING, 3));
        assertEquals(Duration.ofMinutes(15), pending.nextDelay());
        var dead = assertInstanceOf(TaskExecutionResult.Dead.class,
                SettlementQueryTask.map(SettlementQueryTask.Outcome.PENDING, SettlementQueryTask.MAX_RETRY_COUNT - 1));
        assertEquals(SettlementQueryTask.EXHAUSTED, dead.errorCode());
        assertInstanceOf(TaskExecutionResult.Success.class,
                SettlementQueryTask.map(SettlementQueryTask.Outcome.DONE, 0));
        assertInstanceOf(TaskExecutionResult.Cancelled.class,
                SettlementQueryTask.map(SettlementQueryTask.Outcome.NOT_FOUND, 0));
    }
}
