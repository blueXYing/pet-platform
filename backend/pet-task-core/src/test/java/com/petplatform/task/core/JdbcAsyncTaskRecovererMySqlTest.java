package com.petplatform.task.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real SQL13 state-machine history; recovery joins the business owner's exact transaction. */
class JdbcAsyncTaskRecovererMySqlTest {
    private static final String KEY = "REFUND_MERCHANT_TIMEOUT:99001";
    private static final String PAYLOAD = "{\"applicationId\":\"99001\"}";
    private final AtomicLong ids = new AtomicLong(8_000_000);
    private MySqlTestDatabase db;
    private JdbcAsyncTaskRecoverer recoverer;
    private TransactionTemplate tx;
    private OffsetDateTime deadline;

    @BeforeEach void open() throws Exception {
        db = new MySqlTestDatabase();
        // The task-only fixture loads SQL13; this is SQL39's authoritative task column.
        db.jdbc().execute("ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
        recoverer = new JdbcAsyncTaskRecoverer(db.dataSource(), ids::incrementAndGet);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        deadline = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2).truncatedTo(ChronoUnit.MILLIS);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void requiresSameDataSourceWritableReadCommittedTransaction() throws Exception {
        assertThrows(IllegalStateException.class, () -> recover(deadline));
        tx.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> recover(deadline)));
        tx.setReadOnly(false);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> recover(deadline)));
        try (var other = new MySqlTestDatabase()) {
            var foreign = new TransactionTemplate(new DataSourceTransactionManager(other.dataSource()));
            foreign.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThrows(IllegalStateException.class, () -> foreign.execute(s -> recover(deadline)));
        }
        assertEquals(0, count("async_task"));
    }

    @Test void missingTaskCreationIsIdempotentAndRollsBackWithBusinessOwner() {
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> {
            recover(deadline);
            throw new IllegalStateException("owner business rollback");
        }));
        assertEquals(0, count("async_task"));
        long id = tx.execute(s -> recover(deadline));
        assertEquals(id, tx.<Long>execute(s -> recover(deadline)).longValue());
        assertEquals(1, count("async_task"));
        var row = row(id);
        assertEquals("READY", row.get("status"));
        assertEquals(0L, ((Number) row.get("version")).longValue());
        assertEquals(deadline.toLocalDateTime(), db.jdbc().queryForObject(
                "SELECT submitted_execute_at FROM async_task WHERE id=?", LocalDateTime.class, id));
        assertEquals(0, count("async_task_attempt"));
    }

    @Test void immediateTaskRecoveryKeepsItsNullOriginalSchedule() {
        long id = tx.execute(s -> recover(null));
        var repository = db.repository(ids::incrementAndGet);
        var lease = repository.claim("immediate", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(repository.complete(lease, new TaskExecutionResult.Dead("TEST")));
        assertEquals(id, tx.<Long>execute(s -> recover(null)).longValue());
        assertEquals("READY", row(id).get("status"));
        assertNull(row(id).get("submitted_execute_at"));
        assertEquals(1, count("async_task_attempt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEAD", "CANCELED", "SUCCEEDED"})
    void restoresOnlyTerminalStateAndPreservesAttemptHistory(String state) {
        long id = tx.execute(s -> recover(deadline));
        var repository = db.repository(ids::incrementAndGet);
        var first = repository.claim("first", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(repository.complete(first, new TaskExecutionResult.Retry("RETRY", Duration.ofSeconds(1))));
        db.jdbc().update("UPDATE async_task SET execute_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", id);
        var last = repository.claim("terminal", Duration.ofSeconds(30)).orElseThrow();
        TaskExecutionResult result = switch (state) {
            case "DEAD" -> new TaskExecutionResult.Dead("EXHAUSTED");
            case "CANCELED" -> new TaskExecutionResult.Cancelled("STALE_RESULT");
            default -> new TaskExecutionResult.Success("PREMATURE_SUCCESS");
        };
        assertTrue(repository.complete(last, result));
        var prior = row(id);
        assertEquals(1, ((Number) prior.get("retry_count")).intValue());
        var attempts = db.jdbc().queryForList("SELECT * FROM async_task_attempt ORDER BY attempt_no");
        assertEquals(2, attempts.size());
        long version = ((Number) prior.get("version")).longValue();

        assertEquals(id, tx.<Long>execute(s -> recover(deadline)).longValue());
        var recovered = row(id);
        assertEquals("READY", recovered.get("status"));
        assertEquals(version + 1, ((Number) recovered.get("version")).longValue());
        assertEquals(0, ((Number) recovered.get("retry_count")).intValue());
        assertEquals("OWNER_FACT_RECOVERY", recovered.get("last_result_code"));
        for (String field : new String[]{"lease_owner", "lease_until", "finished_at", "last_error_code", "last_error_message"})
            assertNull(recovered.get(field), field);
        assertEquals(deadline.toLocalDateTime(), db.jdbc().queryForObject(
                "SELECT execute_at FROM async_task WHERE id=?", LocalDateTime.class, id));
        assertEquals(attempts, db.jdbc().queryForList("SELECT * FROM async_task_attempt ORDER BY attempt_no"));
        assertEquals(id, tx.<Long>execute(s -> recover(deadline)).longValue());
        assertEquals(recovered, row(id), "replaying recovery must not keep advancing READY's version");

        var next = repository.claim("recovered", Duration.ofSeconds(30)).orElseThrow();
        assertEquals(3, next.attemptNo());
        assertFalse(repository.complete(last, new TaskExecutionResult.Success("STALE")));
        assertTrue(repository.complete(next, new TaskExecutionResult.Success("RECOVERED")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "RUNNING", "RUNNING_EXPIRED", "RETRY_WAIT"})
    void neverChangesLiveOrWaitingTasksOrStealsTheirLease(String state) {
        long id = tx.execute(s -> recover(deadline));
        var repository = db.repository(ids::incrementAndGet);
        if (!state.equals("READY")) {
            var lease = repository.claim("active-owner", Duration.ofSeconds(30)).orElseThrow();
            if (state.equals("RETRY_WAIT"))
                assertTrue(repository.complete(lease, new TaskExecutionResult.Retry("WAIT", Duration.ofHours(1))));
            if (state.equals("RUNNING_EXPIRED"))
                db.jdbc().update("UPDATE async_task SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", id);
        }
        var prior = row(id);
        var attempts = db.jdbc().queryForList("SELECT * FROM async_task_attempt ORDER BY attempt_no");
        assertEquals(id, tx.<Long>execute(s -> recover(deadline)).longValue());
        assertEquals(prior, row(id));
        assertEquals(attempts, db.jdbc().queryForList("SELECT * FROM async_task_attempt ORDER BY attempt_no"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "TYPE", "BIZ_TYPE", "BIZ_ID", "VERSION", "PAYLOAD",
            "MAX_RETRY", "POLICY", "DEADLINE", "NULL_DEADLINE", "KEY_CASE"})
    void rejectsDifferentImmutableMetadataInsteadOfRevivingIt(String changed) {
        long id = tx.execute(s -> recover(deadline));
        var repository = db.repository(ids::incrementAndGet);
        var lease = repository.claim("dead-owner", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(repository.complete(lease, new TaskExecutionResult.Dead("ORIGINAL")));
        var prior = row(id);
        assertThrows(IllegalArgumentException.class, () -> tx.execute(s -> recoverer.recover(
                changed.equals("KEY_CASE") ? KEY.toLowerCase() : KEY,
                changed.equals("OWNER") ? "order" : "refund",
                changed.equals("TYPE") ? "OTHER" : "REFUND_MERCHANT_TIMEOUT",
                changed.equals("BIZ_TYPE") ? "ORDER" : "REFUND_APPLICATION",
                changed.equals("BIZ_ID") ? 99002 : 99001,
                changed.equals("VERSION") ? 1L : 0L,
                changed.equals("PAYLOAD") ? "{\"applicationId\":\"99002\"}" : PAYLOAD,
                changed.equals("MAX_RETRY") ? 9 : 10,
                changed.equals("POLICY") ? "OTHER" : "FAST_INTERNAL",
                changed.equals("DEADLINE") ? deadline.plusSeconds(1)
                        : changed.equals("NULL_DEADLINE") ? null : deadline)), changed);
        assertEquals(prior, row(id));
        assertEquals(1, count("async_task_attempt"));
    }

    @Test void recoveryOfExistingTerminalTaskRollsBackWhenOwnerFails() {
        long id = tx.execute(s -> recover(deadline));
        var repository = db.repository(ids::incrementAndGet);
        var lease = repository.claim("finished", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(repository.complete(lease, new TaskExecutionResult.Success("INCOMPLETE_OWNER_FACT")));
        var prior = row(id);
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> {
            recover(deadline);
            throw new IllegalStateException("business owner failed after recovery");
        }));
        assertEquals(prior, row(id));
    }

    private long recover(OffsetDateTime originalDeadline) {
        return recoverer.recover(KEY, "refund", "REFUND_MERCHANT_TIMEOUT", "REFUND_APPLICATION",
                99001, 0L, PAYLOAD, 10, "FAST_INTERNAL", originalDeadline);
    }
    private int count(String table) {
        return db.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
    private Map<String, Object> row(long id) {
        return db.jdbc().queryForMap("SELECT * FROM async_task WHERE id=?", id);
    }
}
