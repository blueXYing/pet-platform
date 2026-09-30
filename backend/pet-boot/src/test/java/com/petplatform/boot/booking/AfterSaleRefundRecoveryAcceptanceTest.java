package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.LateRefundConfiguration;
import com.petplatform.common.ApiException;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import com.petplatform.verification.api.command.VerificationCompletionApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Real durable AFS task registrations and worker claims; funding and channel remain QA-only. */
class AfterSaleRefundRecoveryAcceptanceTest {
    private static final String SUBMIT = "AFTERSALE_REFUND_SUBMIT";
    private static final String QUERY = "AFTERSALE_REFUND_CHANNEL_QUERY";

    @Test void missingSubmitTaskRecoversAndUnknownQueriesOriginalNumberWithFundingDisabled() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.rejectedOrder();
            var verification = f.verificationCommand(order);
            var done = f.decide(f.accept(f.create(order)), "PARTIAL_REFUND", new BigDecimal("32.00"));
            String key = taskKey(SUBMIT, done.refundOrderId());
            var jdbc = f.ordinary.t.r.f.f.db.jdbc;
            String originalPayload = jdbc.queryForObject(
                    "SELECT CAST(payload_json AS CHAR) FROM async_task WHERE task_key=?", String.class, key);
            // Deliberate task loss after the real business transaction, never a fabricated refund.
            assertEquals(1, jdbc.update("DELETE FROM async_task WHERE task_key=?", key));
            var runtime = new AfterSaleChannelFixture(f, true);
            assertEquals(1, runtime.execution.reconcileDeadTasks());
            assertTaskBinding(f, SUBMIT, done.refundOrderId());
            assertEquals(originalPayload, jdbc.queryForObject(
                    "SELECT CAST(payload_json AS CHAR) FROM async_task WHERE task_key=?", String.class, key));
            assertEquals("READY", taskStatus(f, key));
            assertEquals(0, runtime.sends.get());

            assertEquals(AsyncTaskWorker.Outcome.COMPLETED, runWorker(f, runtime));
            assertEquals("SUCCEEDED", taskStatus(f, key));
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task_attempt WHERE result='SUCCESS'"));
            assertUnknownBlocksVerification(f, runtime, verification, done.refundOrderId());
            assertEquals(AsyncTaskWorker.Outcome.EMPTY, runWorker(f, runtime), "query respects its persisted deadline");
            finishOriginalQueryWithoutFunding(f, runtime, done.refundOrderId(), false);
        }
    }

    @Test void deadSubmitRecoveryPreservesAttemptAndMissingQueryRecoversItsOriginalSchedule() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.rejectedOrder();
            var verification = f.verificationCommand(order);
            var done = f.decide(f.accept(f.create(order)), "PARTIAL_REFUND", new BigDecimal("32.00"));
            String key = taskKey(SUBMIT, done.refundOrderId());
            var jdbc = f.ordinary.t.r.f.f.db.jdbc;
            long taskId = jdbc.queryForObject("SELECT id FROM async_task WHERE task_key=?", Long.class, key);
            var runtime = new AfterSaleChannelFixture(f, true);
            f.fundingAvailable.set(false);
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED, runWorker(f, runtime));
            assertEquals("RETRY_WAIT", taskStatus(f, key));
            assertEquals(0, runtime.sends.get());
            assertEquals(0, f.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
            long firstAttempt = jdbc.queryForObject(
                    "SELECT id FROM async_task_attempt WHERE task_id=? AND attempt_no=1 AND result='RETRY'",
                    Long.class, taskId);
            long priorFence = jdbc.queryForObject("SELECT version FROM async_task WHERE id=?", Long.class, taskId);
            // Exhaust the original durable delivery without replacing its real attempt history.
            assertEquals(1, jdbc.update(
                    "UPDATE async_task SET status='DEAD',retry_count=8,finished_at=UTC_TIMESTAMP(3) WHERE id=?",
                    taskId));
            f.fundingAvailable.set(true);
            assertEquals(1, runtime.execution.reconcileDeadTasks());
            assertTaskBinding(f, SUBMIT, done.refundOrderId());
            assertEquals(taskId, jdbc.queryForObject("SELECT id FROM async_task WHERE task_key=?", Long.class, key));
            assertEquals(priorFence + 1, jdbc.queryForObject("SELECT version FROM async_task WHERE id=?", Long.class, taskId));
            assertEquals("READY", taskStatus(f, key));
            assertEquals(firstAttempt, jdbc.queryForObject(
                    "SELECT id FROM async_task_attempt WHERE task_id=? AND attempt_no=1 AND result='RETRY'",
                    Long.class, taskId));

            assertEquals(AsyncTaskWorker.Outcome.COMPLETED, runWorker(f, runtime));
            assertEquals("SUCCEEDED", taskStatus(f, key));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM async_task_attempt WHERE task_id=?", Integer.class, taskId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM async_task_attempt WHERE task_id=? AND attempt_no=2 AND result='SUCCESS'",
                    Integer.class, taskId));
            assertUnknownBlocksVerification(f, runtime, verification, done.refundOrderId());
            finishOriginalQueryWithoutFunding(f, runtime, done.refundOrderId(), true);
        }
    }

    private static void assertUnknownBlocksVerification(AfterSaleFixture f, AfterSaleChannelFixture runtime,
            VerificationCompletionApi.Command verification, String refundId) {
        assertEquals(1, runtime.sends.get());
        assertEquals(0, runtime.queries.get());
        assertEquals("UNKNOWN", f.text("SELECT status FROM refund_order"));
        assertEquals("QUERY_PENDING", f.text("SELECT state FROM payment_refund_dispatch"));
        assertEquals("CONFIRMED", f.text("SELECT status FROM schedule_reservation"));
        assertEquals(new BigDecimal("0.00"), f.decimal("SELECT refunded_amount FROM pet_order"));
        assertThrows(ApiException.class, () -> f.verify(verification));
        assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
        assertEquals("UNVERIFIED", f.text("SELECT verification_status FROM pet_order"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
        assertTaskBinding(f, QUERY, refundId);
    }

    private static void finishOriginalQueryWithoutFunding(AfterSaleFixture f, AfterSaleChannelFixture original,
            String refundId, boolean loseQueryTask) {
        String number = original.lastRequest.get().refundNo();
        String key = taskKey(QUERY, refundId);
        var jdbc = f.ordinary.t.r.f.f.db.jdbc;
        var firstQueryAt = jdbc.queryForObject("SELECT first_query_at FROM refund_execution", java.time.LocalDateTime.class);
        assertNotNull(firstQueryAt);
        if (loseQueryTask) assertEquals(1, jdbc.update("DELETE FROM async_task WHERE task_key=?", key));
        f.fundingAvailable.set(false);
        f.at(f.now().plusMinutes(2).toInstant());
        // New runtime and worker use the durable dispatch and no funding Provider, as the off switch does.
        var recovered = new AfterSaleChannelFixture(f, false, false);
        if (loseQueryTask) assertEquals(1, recovered.execution.reconcileDeadTasks());
        assertTaskBinding(f, QUERY, refundId);
        assertEquals(firstQueryAt, jdbc.queryForObject(
                "SELECT submitted_execute_at FROM async_task WHERE task_key=?", java.time.LocalDateTime.class, key));
        assertEquals(firstQueryAt, jdbc.queryForObject(
                "SELECT execute_at FROM async_task WHERE task_key=?", java.time.LocalDateTime.class, key));
        assertEquals(AsyncTaskWorker.Outcome.COMPLETED, runWorker(f, recovered));
        assertEquals("SUCCEEDED", taskStatus(f, key));
        assertEquals(1, original.sends.get());
        assertEquals(0, original.queries.get());
        assertEquals(0, recovered.sends.get());
        assertEquals(1, recovered.queries.get());
        assertEquals(number, recovered.lastRequest.get().refundNo());
        assertEquals(new BigDecimal("32.00"), recovered.lastRequest.get().amount());
        assertEquals("SUCCESS", f.text("SELECT status FROM refund_order"));
        assertEquals("PARTIAL", f.text("SELECT refund_type FROM refund_order"));
        assertEquals(refundId, f.text("SELECT CAST(id AS CHAR) FROM refund_order"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM payment_refund_funding_proof"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='RefundSucceededEvent.v1'"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM refund_reconciliation_issue WHERE status='OPEN'"));
        assertEquals(AsyncTaskWorker.Outcome.EMPTY, runWorker(f, recovered));
    }

    private static AsyncTaskWorker.Outcome runWorker(AfterSaleFixture f, AfterSaleChannelFixture runtime) {
        try (var worker = AsyncTaskWorker.create(f.ordinary.source, AfterSaleFixture.IDS::incrementAndGet,
                "afs-refund-recovery-qa", f.clock, TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of("REFUND_CHANNEL", List.of(Duration.ofSeconds(30)))),
                List.of(LateRefundConfiguration.registration(SUBMIT, runtime.execution, f.ordinary.source),
                        LateRefundConfiguration.registration(QUERY, runtime.execution, f.ordinary.source)))) {
            return worker.runOne();
        }
    }

    private static void assertTaskBinding(AfterSaleFixture f, String type, String refundId) {
        var jdbc = f.ordinary.t.r.f.f.db.jdbc;
        String key = taskKey(type, refundId);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM async_task WHERE task_key=? AND owner_module='REFUND'"
                + " AND task_type=? AND biz_type='REFUND' AND biz_id=? AND expected_version=0"
                + " AND max_retry_count=8 AND retry_policy='REFUND_CHANNEL'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.refundOrderId'))=?"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.storeId'))=?"
                + " AND JSON_EXTRACT(payload_json,'$.bindingVersion')=0",
                Integer.class, key, type, Long.parseLong(refundId), refundId, AfterSaleFixture.STORE));
    }

    private static String taskStatus(AfterSaleFixture f, String key) {
        return f.ordinary.t.r.f.f.db.jdbc.queryForObject("SELECT status FROM async_task WHERE task_key=?", String.class, key);
    }

    private static String taskKey(String type, String refundId) { return type + ":" + refundId + ":0"; }
}
