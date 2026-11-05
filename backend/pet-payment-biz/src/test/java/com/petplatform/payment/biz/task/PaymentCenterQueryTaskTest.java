package com.petplatform.payment.biz.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.payment.biz.task.PaymentCenterQueryTask.Outcome;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskLease;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract 75 §5.3 query task: back-off, outcome mapping and generation decoding. Offline. */
class PaymentCenterQueryTaskTest {
    private static TaskLease lease(String key, long bizId, String payload, String type, String policy,
            Long expectedVersion, int retryCount) {
        return new TaskLease(1L, key, type, bizId, expectedVersion, payload, "owner", 1L, 1L, 1, retryCount, 60, policy);
    }

    private static TaskLease valid(long paymentId, int retryCount) {
        return lease(PaymentCenterQueryTask.taskKey(paymentId), paymentId, PaymentCenterQueryTask.payload(paymentId),
                "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, retryCount);
    }

    @Test void backOffIsFiveFifteenThirtySecondsThenOneTwoFiveMinutesThenStaysAtFive() {
        List<Duration> expected = List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
                Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(5),
                Duration.ofMinutes(5));
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), PaymentCenterQueryTask.delay(i), "retry " + i);
        assertEquals(Duration.ofMinutes(5), PaymentCenterQueryTask.delay(59));
        assertThrows(IllegalArgumentException.class, () -> PaymentCenterQueryTask.delay(-1));
        assertEquals(Duration.ofSeconds(5), PaymentCenterQueryTask.FIRST_DELAY);
    }

    @Test void outcomesMapToTaskResultsWithoutInferringFailure() {
        assertInstanceOf(TaskExecutionResult.Success.class, PaymentCenterQueryTask.map(Outcome.DONE, 0));
        var retry = assertInstanceOf(TaskExecutionResult.Retry.class, PaymentCenterQueryTask.map(Outcome.PENDING, 2));
        assertEquals(Duration.ofSeconds(30), retry.nextDelay());
        assertEquals("PAYMENT_QUERY_PENDING", retry.errorCode());
        var dead = assertInstanceOf(TaskExecutionResult.Dead.class, PaymentCenterQueryTask.map(Outcome.EXHAUSTED, 9));
        assertEquals("PAYMENT_QUERY_EXHAUSTED", dead.errorCode());
        assertEquals("PAYMENT_RECONCILIATION_REQUIRED", assertInstanceOf(TaskExecutionResult.Dead.class,
                PaymentCenterQueryTask.map(Outcome.RECONCILIATION_REQUIRED, 0)).errorCode());
        assertEquals("NOT_PAYMENT_CENTER", assertInstanceOf(TaskExecutionResult.Cancelled.class,
                PaymentCenterQueryTask.map(Outcome.NOT_CENTER, 0)).reasonCode());
        assertEquals("PAYMENT_NOT_SENT", assertInstanceOf(TaskExecutionResult.Cancelled.class,
                PaymentCenterQueryTask.map(Outcome.NOT_SENT, 0)).reasonCode());
    }

    @Test void keyAndPayloadFollowScheduler09() {
        assertEquals("PAYCENTER_CHANNEL_QUERY:123", PaymentCenterQueryTask.taskKey(123L));
        assertEquals("{\"paymentId\":\"123\"}", PaymentCenterQueryTask.payload(123L));
        assertThrows(IllegalArgumentException.class, () -> PaymentCenterQueryTask.taskKey(0L));
    }

    @Test void decodeAcceptsOnlyTheExactGeneration() {
        assertEquals(new PaymentCenterQueryTask.Payload(123L, 3), PaymentCenterQueryTask.decode(valid(123L, 3)));
        String key = PaymentCenterQueryTask.taskKey(123L), payload = PaymentCenterQueryTask.payload(123L);
        List<TaskLease> bad = List.of(
                lease(key, 124L, payload, "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, 0),
                lease("PAYCENTER_CHANNEL_QUERY:124", 123L, payload, "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, 0),
                lease(key, 123L, payload, "REFUND_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, 0),
                lease(key, 123L, payload, "PAYCENTER_CHANNEL_QUERY", "REFUND_CHANNEL", null, 0),
                lease(key, 123L, payload, "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", 0L, 0),
                lease(key, 123L, "{\"paymentId\":\"123\",\"x\":1}", "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, 0),
                lease(key, 123L, "{\"paymentId\":123}", "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, 0),
                lease(key, 123L, "{\"paymentId\":\"0123\"}", "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, 0),
                lease(key, 123L, "{\"paymentId\":\"123\"}x", "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, 0),
                lease(key, 123L, "{\"paymentId\":\"123\",\"paymentId\":\"123\"}", "PAYCENTER_CHANNEL_QUERY", "PAYCENTER_CHANNEL_QUERY", null, 0));
        for (TaskLease lease : bad)
            assertThrows(IllegalArgumentException.class, () -> PaymentCenterQueryTask.decode(lease), lease.toString());
    }

    @Test void registrationUsesTheQueryTaskTypeAndDelegatesToTheStep() {
        var registration = PaymentCenterQueryTask.registration(paymentId -> paymentId == 123L ? Outcome.PENDING : Outcome.DONE);
        assertEquals("PAYCENTER_CHANNEL_QUERY", registration.handler().taskType());
        assertEquals("TASK:PAYCENTER_CHANNEL_QUERY:123", registration.requestId().apply(valid(123L, 0)));
        var result = registration.handler().execute(null, new PaymentCenterQueryTask.Payload(123L, 1));
        assertEquals(Duration.ofSeconds(15), assertInstanceOf(TaskExecutionResult.Retry.class, result).nextDelay());
        assertInstanceOf(TaskExecutionResult.Success.class,
                registration.handler().execute(null, new PaymentCenterQueryTask.Payload(7L, 0)));
    }
}
