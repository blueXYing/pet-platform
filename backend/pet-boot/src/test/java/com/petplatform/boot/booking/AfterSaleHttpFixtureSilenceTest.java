package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.task.core.AsyncTaskWorker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the "worker stays off" contract the HTTP slices are written against (AfterSaleHttpFixture).
 * #234 removed the switches the fixture used to pin background work off, and the live workers then
 * raced the tests: a past-deadline refund application was auto-decided before the merchant's
 * request, and an unpaid booking was cancelled between booking and payment. A closed worker
 * refuses {@code start()}; one that is running treats it as a no-op, which is the probe used here.
 */
class AfterSaleHttpFixtureSilenceTest {

    private static final List<String> OPTED_IN = List.of("orderAutoConfirmWorker", "merchantRefundWorker");
    /** Closed by name, not by type: a rename must fail here instead of silently keeping it alive. */
    private static final List<String> SILENT_TIMERS = List.of("orderAutoConfirmReconciler", "merchantRefundReconciler",
            "afterSaleReconciler", "lateRefundDeadTaskReconciler", "adminTradeRefundReconciler", "refundApplicationReconciler",
            "outboxDispatcher", "dailyReconcileScheduler", "settlementCompensationScanner");

    @Test
    void everyBackgroundWorkerStaysClosedByDefault() throws Exception {
        try (var f = new AfterSaleHttpFixture()) {
            var workers = f.context.getBeansOfType(AsyncTaskWorker.class);
            assertTrue(workers.keySet().containsAll(OPTED_IN), "assembled workers: " + workers.keySet());
            workers.forEach((name, worker) ->
                assertThrows(IllegalStateException.class, worker::start, name + " must stay closed"));
            for (String name : SILENT_TIMERS) assertTrue(f.context.containsBean(name), "missing " + name);
        }
    }

    /** The historic opt-in keys keep exactly their own worker alive; everything else stays closed. */
    @Test
    void historicOptInKeysKeepOnlyTheirOwnWorkersAlive() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.order.auto-confirm.worker.enabled", true,
                "pet.order.merchant.worker.enabled", true))) {
            f.context.getBeansOfType(AsyncTaskWorker.class).forEach((name, worker) -> {
                if (OPTED_IN.contains(name)) assertDoesNotThrow(worker::start, name + " is opted in");
                else assertThrows(IllegalStateException.class, worker::start, name + " must stay closed");
            });
        }
    }
}
