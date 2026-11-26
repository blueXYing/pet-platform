package com.petplatform.payment.biz.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskLease;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for Stage 5 {@link WalletQueryTask} key/payload/backoff/mapping invariants. */
class WalletTaskTest {

    @Test
    void keyPayloadDecodeAndBackoffMapping() {
        String key = WalletQueryTask.taskKey(9001L);
        String payload = WalletQueryTask.payload(9001L);
        assertEquals("WALLET_WITHDRAW_QUERY:9001", key);
        assertEquals("{\"withdrawalId\":\"9001\"}", payload);

        TaskLease lease = new TaskLease(1L, key, WalletQueryTask.TASK_TYPE, 9001L,
                null, payload, "owner", 0L, 10L, 1, 2, WalletQueryTask.MAX_RETRY_COUNT,
                WalletQueryTask.RETRY_POLICY);
        WalletQueryTask.Payload decoded = WalletQueryTask.decode(lease);
        assertEquals(9001L, decoded.withdrawalId());
        assertEquals(2, decoded.retryCount());

        assertEquals(Duration.ofMinutes(1), WalletQueryTask.delay(0));
        assertEquals(Duration.ofMinutes(5), WalletQueryTask.delay(1));
        assertEquals(Duration.ofMinutes(15), WalletQueryTask.delay(2));
        assertEquals(Duration.ofHours(1), WalletQueryTask.delay(3));
        assertEquals(Duration.ofHours(6), WalletQueryTask.delay(4));
        assertEquals(Duration.ofHours(6), WalletQueryTask.delay(10));

        assertInstanceOf(TaskExecutionResult.Success.class,
                WalletQueryTask.map(WalletQueryTask.Outcome.DONE, 0));
        assertInstanceOf(TaskExecutionResult.Retry.class,
                WalletQueryTask.map(WalletQueryTask.Outcome.PENDING, 0));
        assertInstanceOf(TaskExecutionResult.Dead.class,
                WalletQueryTask.map(WalletQueryTask.Outcome.PENDING, WalletQueryTask.MAX_RETRY_COUNT - 1));
        assertInstanceOf(TaskExecutionResult.Cancelled.class,
                WalletQueryTask.map(WalletQueryTask.Outcome.NOT_FOUND, 0));

        assertThrows(IllegalArgumentException.class, () -> WalletQueryTask.taskKey(0L));
    }
}
