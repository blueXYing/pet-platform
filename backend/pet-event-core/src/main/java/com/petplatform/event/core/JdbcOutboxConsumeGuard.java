package com.petplatform.event.core;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Consumer-side idempotency claim over integration_event_consume_log
 * (Event Catalog §15/§21). Must run inside the consumer's own transaction:
 * the claim and the business change commit or roll back together. Returns
 * false when this consumer already succeeded for the event — skip idempotently.
 * The unique key (consumer_name, event_id), not the id column, is the dedup fact.
 */
public final class JdbcOutboxConsumeGuard {
    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator ids;

    public JdbcOutboxConsumeGuard(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
    }

    /** True when this is the first delivery to the consumer; false means already consumed. */
    public boolean tryClaim(String consumerName, DispatchedEvent event) {
        String name = consumerName == null ? null : consumerName.strip();
        if (name == null || name.isEmpty() || name.length() > 128) {
            throw new IllegalArgumentException("consumerName must contain 1..128 characters");
        }
        Objects.requireNonNull(event, "event is required");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Consume claim must join the consumer's transaction");
        }
        long claimId = ids.nextId();
        if (claimId <= 0) throw new IllegalStateException("Invalid ID from provider");
        try {
            jdbc.update("""
                    INSERT INTO integration_event_consume_log
                    (id,consumer_name,event_id,event_type,consumed_at)
                    VALUES (?,?,?,?,NOW(3))
                    """, claimId, name, event.eventId(), event.eventType());
            return true;
        } catch (DuplicateKeyException alreadyConsumed) {
            return false;
        }
    }
}
