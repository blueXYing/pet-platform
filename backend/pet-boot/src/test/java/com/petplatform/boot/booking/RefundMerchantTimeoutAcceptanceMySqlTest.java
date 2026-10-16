package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.boot.config.RefundApplicationConfiguration;
import com.petplatform.event.core.OutboxDispatcher;
import com.petplatform.refund.api.command.RefundApplicationCommandApi.Receipt;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REF-004 closing verification on real MySQL: the 24-hour merchant refund timeout fires through
 * the real durable worker and auto-approves in full (source MERCHANT_TIMEOUT_AUTO, 32nd ruling
 * full original-route refund), a merchant who decided inside the window makes the timeout task a
 * NOOP, and every replay of the task or the create command stays idempotent.
 *
 * <p>Reuses the ordinary-refund acceptance fixture (real ORDER/PAYMENT/REFUND/SCHEDULE rows);
 * only the external refund channel is scripted. Clock movement is MySQL session time, so the
 * durable task due-check (execute_at &lt;= NOW(3)) is exercised for real, never a seeded decision.
 */
class RefundMerchantTimeoutAcceptanceMySqlTest {

    /** Deadline passed with the merchant silent: worker fires, auto-approves, full original-route refund. */
    @Test
    void dueTimeoutTaskAutoApprovesAndCreatesOneFullOriginalRouteRefund() throws Exception {
        try (var f = new RefundApplicationAcceptanceTest.F();
             var worker = timeoutWorker(f)) {
            String order = f.ready(true);
            Receipt r = f.apps.apply(f.applyCommand(order));
            assertEquals("PENDING_MERCHANT", r.applicationStatus());
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task "
                    + "WHERE task_type='REFUND_MERCHANT_TIMEOUT' AND status='READY'"));
            String applicationId = r.applicationId();

            // Before the deadline the merchant still owns the window: the handler only re-arms.
            var early = f.apps.handle(f.timeout(r));
            assertFalse(early.done());
            assertEquals(OffsetDateTime.parse(r.merchantDeadline()), early.nextAt());
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application_decision"));
            // The durable worker agrees: the task is not claimable before its due time.
            assertEquals(AsyncTaskWorker.Outcome.EMPTY, worker.runOne());
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application_decision"));

            // The deadline passes in database time; the worker fires the timeout for real.
            f.source.fixed.set(OffsetDateTime.parse(r.merchantDeadline()).plusSeconds(1)
                    .toInstant());
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
            assertEquals("AUTO_APPROVED", f.text("SELECT status FROM refund_application"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_application_decision"));
            assertEquals("AUTO_APPROVED", f.text(
                    "SELECT status FROM refund_application_decision"));
            assertEquals("TASK:REFUND_MERCHANT_TIMEOUT:" + Long.parseLong(applicationId),
                    f.text("SELECT request_id FROM refund_application_decision"));
            assertEquals("1", f.text("SELECT d.decided_at >= a.merchant_deadline FROM "
                    + "refund_application_decision d JOIN refund_application a "
                    + "ON a.id=d.application_id"));

            // The auto-approval enqueued the durable creation task; the worker creates the refund.
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task "
                    + "WHERE task_type='REFUND_APPLICATION_CREATE' AND status='READY'"));
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals("MERCHANT_TIMEOUT_AUTO", f.text("SELECT source_type FROM refund_order"));
            assertEquals("MERCHANT_TIMEOUT_AUTO", f.text(
                    "SELECT source_type FROM refund_execution"));
            assertEquals("FULL", f.text("SELECT refund_type FROM refund_order"));
            assertEquals("SYSTEM", f.text("SELECT initiator_type FROM refund_order"));
            assertEquals("LAKALA", f.text("SELECT channel FROM refund_order"));
            assertEquals(0, new java.math.BigDecimal("128.00").compareTo(
                    f.t.r.f.f.db.jdbc.queryForObject("SELECT refund_amount FROM refund_order",
                            java.math.BigDecimal.class)));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox "
                    + "WHERE event_type='RefundOrderCreatedEvent.v1' AND payload "
                    + "LIKE '%MERCHANT_TIMEOUT_AUTO%'"));

            // 32nd ruling: the full amount goes back through the original route exactly once.
            String refund = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
            var runtime = f.runtime(false);
            assertTrue(runtime.execution.execute(refund, "710302", false, "qa-ref004",
                    "APPLICATION").done());
            assertEquals("SUCCESS", f.text("SELECT status FROM refund_order"));
            assertEquals(1, runtime.sends.get());
            assertEquals(1, f.count("SELECT COUNT(*) FROM payment_refund_dispatch "
                    + "WHERE state='VERIFIED_SUCCESS' AND refund_amount=original_paid_amount"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_transaction "
                    + "WHERE refund_id=" + Long.parseLong(refund) + " AND action='REFUND'"));

            // The final channel success releases the reservation and writes the order projection.
            runtime.projection.consume(f.event("RefundSucceededEvent.v1"));
            assertEquals(128, f.count("SELECT refunded_amount FROM pet_order"));
            assertEquals("RELEASED", f.text("SELECT status FROM schedule_reservation"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM schedule_reservation_audit "
                    + "WHERE action='REFUND_RELEASE'"));

            // Every replay is idempotent: no second decision, no second refund, no resubmit.
            assertEquals(AsyncTaskWorker.Outcome.EMPTY, worker.runOne());
            assertTrue(f.apps.handle(f.timeout(r)).done());
            assertEquals(refund, f.apps.createApproved(f.create(f.receipt())));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_application_decision"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals(1, runtime.sends.get());
        }
    }

    /** Merchant manual processing inside the window: the later timeout task is a NOOP. */
    @Test
    void merchantDecisionInsideTheWindowMakesTheTimeoutTaskANoop() throws Exception {
        try (var f = new RefundApplicationAcceptanceTest.F();
             var worker = timeoutWorker(f)) {
            String order = f.ready(true);
            Receipt r = f.apps.apply(f.applyCommand(order));
            f.apps.decide(f.decision(r, "APPROVE"));
            assertEquals("APPROVED", f.text("SELECT status FROM refund_application"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_application_decision"));

            // The deadline passes; the timeout fires but must not touch the merchant decision.
            f.source.fixed.set(OffsetDateTime.parse(r.merchantDeadline()).plusSeconds(1)
                    .toInstant());
            var handled = f.apps.handle(f.timeout(r));
            assertTrue(handled.done());
            assertEquals("APPROVED", f.text("SELECT status FROM refund_application"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_application_decision"));
            assertEquals("APPROVED", f.text(
                    "SELECT status FROM refund_application_decision"));
            assertNotEquals("AUTO_APPROVED", f.text(
                    "SELECT status FROM refund_application"));
            // The worker path reaches the same NOOP through the real durable registration; the
            // two due tasks (timeout NOOP and merchant creation) both complete, nothing remains.
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_application_decision"));
            assertEquals("APPROVED", f.text("SELECT status FROM refund_application"));
            assertEquals(AsyncTaskWorker.Outcome.EMPTY, worker.runOne());

            // The merchant decision — not the timeout — is what created the refund.
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals("MERCHANT_APPROVED", f.text("SELECT source_type FROM refund_order"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order "
                    + "WHERE source_type='MERCHANT_TIMEOUT_AUTO'"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_execution "
                    + "WHERE source_type='MERCHANT_TIMEOUT_AUTO'"));
        }
    }

    /** Worker with the exact production refund-application registrations and database clock. */
    private static AsyncTaskWorker timeoutWorker(RefundApplicationAcceptanceTest.F f) {
        return AsyncTaskWorker.create(f.source, RefundApplicationAcceptanceTest.IDS::incrementAndGet,
                "refund-timeout-qa", Clock.systemUTC(), TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of("REFUND_APPLICATION",
                        List.of(Duration.ofSeconds(1)))),
                List.of(
                        RefundApplicationConfiguration.registration("REFUND_MERCHANT_TIMEOUT",
                                f.apps, f.source),
                        RefundApplicationConfiguration.registration("REFUND_APPLICATION_CREATE",
                                f.apps, f.source)));
    }
}
