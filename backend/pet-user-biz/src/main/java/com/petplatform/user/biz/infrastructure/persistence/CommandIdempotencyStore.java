package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.biz.application.CanonicalParams;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Supplement 23 §5 binding record over SQL14: a short independent admission
 * transaction reserves (requestKey, protected params); execution locks the row,
 * re-verifies byte equality and commits the first success receipt with the
 * business writes. Bindings are never deleted; same key with different bytes is
 * IDEMPOTENCY_KEY_CONFLICT even when the first execution failed.
 */
public final class CommandIdempotencyStore {
    public record Binding(long id, String requestKey, String canonicalVersion, String paramsSha256,
                          byte[] paramsCanonical, String status, String receiptJson) {}

    public static final class ParamsConflict extends RuntimeException {
        public ParamsConflict() { super("same requestId with different parameters"); }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate admission;
    private final TransactionTemplate execution;
    private final SnowflakeIdGenerator ids;

    public CommandIdempotencyStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
        var manager = new DataSourceTransactionManager(dataSource);
        this.admission = new TransactionTemplate(manager);
        admission.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        admission.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        admission.setTimeout(10);
        this.execution = new TransactionTemplate(manager);
        execution.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        execution.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        execution.setTimeout(10);
    }

    /**
     * Returns empty for a fresh reservation (caller executes then complete()s inside the
     * execution transaction), a stored receipt for a same-params success replay, and throws
     * ParamsConflict for same-key different-params.
     */
    public Optional<String> admit(String requestKey, CanonicalParams.Canonical canonical) {
        return admission.execute(status -> {
            jdbc.execute("SET SESSION time_zone = '+00:00'");
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 2");
            long id = ids.nextId();
            if (id <= 0) throw new IllegalStateException("Invalid ID from provider");
            try {
                jdbc.update("""
                        INSERT INTO command_idempotency
                        (id,request_key,canonical_version,params_sha256,params_canonical,status,created_at)
                        VALUES (?,?,?,?,?,'RESERVED',NOW(3))
                        """, id, requestKey, canonical.version, canonical.sha256, canonical.bytes);
                return Optional.<String>empty();
            } catch (DuplicateKeyException bound) {
                Binding existing = requireBinding(requestKey);
                if (!existing.paramsSha256.equals(canonical.sha256)
                        || !java.util.Arrays.equals(existing.paramsCanonical, canonical.bytes)) {
                    throw new ParamsConflict();
                }
                return "SUCCEEDED".equals(existing.status) ? Optional.ofNullable(existing.receiptJson) : Optional.empty();
            }
        });
    }

    /** Locks the binding row for the execution transaction; caller completes business + receipt atomically. */
    public Binding lockForExecution(String requestKey) {
        Binding binding = requireBinding(requestKey);
        if (!"RESERVED".equals(binding.status)) {
            throw new IllegalStateException("Binding is not RESERVED: " + binding.status);
        }
        return binding;
    }

    public void succeed(String requestKey, String receiptJson) {
        int changed = jdbc.update("""
                UPDATE command_idempotency SET status='SUCCEEDED',receipt_json=?,succeeded_at=NOW(3)
                WHERE request_key=? AND status='RESERVED'
                """, receiptJson, requestKey);
        if (changed != 1) throw new IllegalStateException("Binding completion pairing violated; transaction rolled back");
    }

    private Binding requireBinding(String requestKey) {
        // The admission/execution transaction holds the row lock from this SELECT.
        return jdbc.query("""
                SELECT id,request_key,canonical_version,params_sha256,params_canonical,status,receipt_json
                FROM command_idempotency WHERE request_key=? FOR UPDATE
                """, (rs, row) -> new Binding(rs.getLong("id"), rs.getString("request_key"),
                rs.getString("canonical_version"), rs.getString("params_sha256"),
                rs.getBytes("params_canonical"), rs.getString("status"), rs.getString("receipt_json")),
                requestKey).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Idempotency binding disappeared; transaction rolled back"));
    }
}
