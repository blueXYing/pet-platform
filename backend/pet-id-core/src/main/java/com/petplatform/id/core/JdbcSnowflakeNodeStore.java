package com.petplatform.id.core;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.petplatform.id.core.SnowflakeProviderSettings.*;

/** Own short UTC transactions, never runtime-initialize rows or reclaim a previously granted range. */
public final class JdbcSnowflakeNodeStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    private record Row(int node, String format, boolean enabled, String initialization,
                       UUID owner, long fence, long high, Long start, Long through, Long lease) {}

    public JdbcSnowflakeNodeStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(1);
    }

    public SnowflakeNodeGrant acquire(SnowflakeProviderSettings settings, UUID incarnation,
                                      PreviousJvmExitVerifier verifier) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(incarnation);
        Objects.requireNonNull(verifier);
        long started = System.nanoTime();
        Row observed = inTransaction(() -> read(settings.nodeId(), false));
        requireUsable(observed);
        // Host I/O must not hold the node row lock. Recheck the entire observation after locking.
        verifier.verify(new PreviousJvmExitVerifier.PreviousJvm(observed.node, observed.owner,
                observed.fence, observed.initialization, observed.high));
        requireBudget(started);
        SnowflakeNodeGrant grant = inTransaction(() -> {
            Row current = read(settings.nodeId(), true);
            requireUsable(current);
            if (!current.equals(observed)) throw unavailable("Node changed during host verification");
            if (incarnation.equals(current.owner)) throw unavailable("New incarnation is required");
            long absoluteNow = sampleDbTime(); // NEW statement after obtaining the row lock.
            if (current.owner != null && current.lease > absoluteNow) throw unavailable("Node is still leased");
            long now = relativeMillis(absoluteNow);
            long start = Math.max(Math.addExact(current.high, 1), now);
            long through = Math.addExact(start, WINDOW_MILLIS - 1);
            requireRange(through, now);
            long fence = Math.addExact(current.fence, 1);
            long lease = Math.addExact(absoluteNow, LEASE_MILLIS);
            requireBudget(started);
            int changed = jdbc.update("""
                    UPDATE snowflake_worker_state
                    SET owner_incarnation=?, fence=?, grant_start=?, grant_through=?, reserved_through=?,
                        lease_until=?, updated_at=NOW(3)
                    WHERE node_id=? AND fence=? AND reserved_through=?
                    """, bytes(incarnation), fence, start, through, through, dateTime(lease),
                    settings.nodeId(), current.fence, current.high);
            requireOne(changed);
            requireBudget(started);
            return new SnowflakeNodeGrant(settings.nodeId(), incarnation, fence, start, through,
                    lease, started, absoluteNow);
        }); // Return only after commit ACK. Unknown commit propagates failure to the provider.
        requireBudget(started);
        return grant;
    }

    public SnowflakeNodeGrant renew(SnowflakeProviderSettings settings, SnowflakeNodeGrant expected) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(expected);
        if (settings.nodeId() != expected.nodeId()) throw unavailable("Node mismatch");
        long started = System.nanoTime();
        expected.requireLocallyValid(started);
        SnowflakeNodeGrant grant = inTransaction(() -> {
            Row current = read(settings.nodeId(), true);
            requireUsable(current);
            if (!Objects.equals(current.owner, expected.incarnation()) || current.fence != expected.fence()
                    || !Objects.equals(current.start, expected.startMillis())
                    || !Objects.equals(current.through, expected.throughMillis())
                    || current.high != expected.throughMillis()
                    || !Objects.equals(current.lease, expected.dbLeaseUntilMillis())) {
                throw unavailable("Node permission no longer matches");
            }
            long absoluteNow = sampleDbTime(); // Do not use the timestamp of the waiting SELECT.
            if (current.lease <= absoluteNow) throw unavailable("Expired node permission cannot renew");
            expected.requireLocallyValid(System.nanoTime());
            long now = relativeMillis(absoluteNow);
            long through = current.high;
            if (through - now <= REFILL_MILLIS) {
                through = Math.max(Math.addExact(through, 1), Math.addExact(now, WINDOW_MILLIS - 1));
            }
            requireRange(through, now);
            long lease = Math.addExact(absoluteNow, LEASE_MILLIS);
            requireBudget(started);
            requireOne(jdbc.update("""
                    UPDATE snowflake_worker_state
                    SET grant_through=?, reserved_through=?, lease_until=?, updated_at=NOW(3)
                    WHERE node_id=? AND owner_incarnation=? AND fence=? AND reserved_through=?
                    """, through, through, dateTime(lease), settings.nodeId(), bytes(expected.incarnation()),
                    expected.fence(), expected.throughMillis()));
            requireBudget(started);
            return new SnowflakeNodeGrant(settings.nodeId(), expected.incarnation(), expected.fence(),
                    expected.startMillis(), through, lease, started, absoluteNow);
        });
        requireBudget(started);
        return grant;
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> {
            jdbc.execute("SET SESSION time_zone = '+00:00'");
            return action.get();
        });
    }

    private Row read(int node, boolean lock) {
        var rows = jdbc.query("SELECT * FROM snowflake_worker_state WHERE node_id=?"
                + (lock ? " FOR UPDATE" : ""), ROW_MAPPER, node);
        if (rows.size() != 1) throw unavailable("Audited node row is missing");
        return rows.getFirst();
    }

    private long sampleDbTime() {
        long before = System.currentTimeMillis();
        LocalDateTime sampled = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
        long after = System.currentTimeMillis();
        long millis = Objects.requireNonNull(sampled).toInstant(ZoneOffset.UTC).toEpochMilli();
        relativeMillis(before);
        relativeMillis(after);
        relativeMillis(millis);
        if (after < before || millis < before - SKEW_MILLIS || millis > after + SKEW_MILLIS) {
            throw unavailable("Database and OS UTC sample mismatch");
        }
        return millis;
    }

    private static void requireUsable(Row row) {
        if (!row.enabled || !FORMAT_IDENTITY.equals(row.format)
                || row.initialization == null || row.initialization.isBlank()
                || row.high < -1 || row.high > MAX_RELATIVE_MILLIS || row.fence < 0
                || (row.start == null) != (row.through == null)
                || (row.start != null && (row.start < 0 || row.start > row.through || row.through != row.high))
                || (row.owner == null) != (row.lease == null)
                || (row.owner != null && (row.start == null || row.fence == 0))) {
            throw unavailable("Disabled, uninitialized or invalid node record");
        }
    }

    private static void requireRange(long through, long now) {
        if (through < 0 || through > MAX_RELATIVE_MILLIS || through - now > MAX_AHEAD_MILLIS) {
            throw unavailable("Reservation outside time range or maximum ahead limit");
        }
    }

    private static void requireBudget(long started) {
        long elapsed = System.nanoTime() - started;
        if (elapsed < 0 || elapsed >= BUDGET_MILLIS * 1_000_000L) throw unavailable("Node operation budget exceeded");
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw unavailable("Node CAS failed");
    }

    private static IllegalStateException unavailable(String reason) { return new IllegalStateException(reason); }
    private static LocalDateTime dateTime(long millis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length != 16) throw unavailable("Invalid owner incarnation");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static Long millis(ResultSet rs, String name) throws SQLException {
        LocalDateTime value = rs.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
    private static final RowMapper<Row> ROW_MAPPER = (rs, index) -> new Row(rs.getInt("node_id"),
            rs.getString("format_identity"), rs.getBoolean("enabled"), rs.getString("initialization_ref"),
            uuid(rs.getBytes("owner_incarnation")), rs.getLong("fence"), rs.getLong("reserved_through"),
            rs.getObject("grant_start", Long.class), rs.getObject("grant_through", Long.class), millis(rs, "lease_until"));
}
