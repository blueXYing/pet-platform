package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.biz.application.CanonicalParams;
import com.petplatform.user.biz.infrastructure.persistence.entity.CommandIdempotencyEntity;
import com.petplatform.user.biz.infrastructure.persistence.mapper.CommandIdempotencyMapper;
import com.petplatform.user.biz.infrastructure.persistence.mapper.SessionControlMapper;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
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

    private final SqlSessionTemplate template;
    private final TransactionTemplate admission;
    private final TransactionTemplate execution;
    private final SnowflakeIdGenerator ids;

    public CommandIdempotencyStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.template = UserMybatis.template(dataSource);
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
            session().setTimeZoneUtc();
            session().setLockWaitTimeout2Seconds();
            long id = ids.nextId();
            if (id <= 0) throw new IllegalStateException("Invalid ID from provider");
            try {
                bindings().insertBinding(id, requestKey, canonical.version, canonical.sha256, canonical.bytes);
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
        int changed = bindings().markSucceeded(requestKey, receiptJson);
        if (changed != 1) throw new IllegalStateException("Binding completion pairing violated; transaction rolled back");
    }

    private Binding requireBinding(String requestKey) {
        // The admission/execution transaction holds the row lock from this SELECT.
        CommandIdempotencyEntity row = bindings().selectBindingForUpdate(requestKey);
        if (row == null) {
            throw new IllegalStateException("Idempotency binding disappeared; transaction rolled back");
        }
        return new Binding(row.getId(), row.getRequestKey(), row.getCanonicalVersion(),
                row.getParamsSha256(), row.getParamsCanonical(), row.getStatus(), row.getReceiptJson());
    }

    private CommandIdempotencyMapper bindings() {
        return template.getMapper(CommandIdempotencyMapper.class);
    }

    private SessionControlMapper session() {
        return template.getMapper(SessionControlMapper.class);
    }
}
