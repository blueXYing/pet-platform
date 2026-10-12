package com.petplatform.id.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real MySQL fixture. Only drops the randomly named database this object created. */
final class MySqlIdTestDatabase implements AutoCloseable {
    private final String name = "plat002_id_test_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String databaseUrl;
    private boolean created;

    MySqlIdTestDatabase() throws Exception {
        String server = System.getenv().getOrDefault("PLAT002_ID_MYSQL_URL", "jdbc:mysql://127.0.0.1:33442/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException("PLAT002_ID_MYSQL_URL must be a local isolated server without database/parameters");
        }
        databaseUrl = server + name;
        admin = new JdbcTemplate(source(server));
        try {
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
        } catch (Exception failure) {
            if (admin.getDataSource() instanceof HikariDataSource pool) pool.close();
            throw failure;
        }
        created = true;
        // Built only after the database exists: the pool opens its connections eagerly.
        dataSource = source(databaseUrl);
        jdbc = new JdbcTemplate(dataSource);
        try {
            String version = admin.queryForObject("SELECT VERSION()", String.class);
            if (version == null || !version.startsWith("8.")) throw new IllegalStateException("Real MySQL 8 required");
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.isDirectory(root.resolve("docs/03-database"))) root = root.getParent();
            if (root == null) throw new IllegalStateException("Authoritative database directory not found");
            Path schema;
            try (var files = Files.list(root.resolve("docs/03-database"))) {
                var matches = files.filter(p -> p.getFileName().toString().startsWith("25-")
                        && p.getFileName().toString().endsWith(".sql")).toList();
                if (matches.size() != 1) throw new IllegalStateException("Exactly one authoritative SQL25 is required");
                schema = matches.getFirst();
            }
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(schema));
            }
            System.out.println("PLAT-002 real MySQL " + version + "; new isolated database " + name);
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static DataSource source(String url) {
        // The store's single-flight lane budget (1s, a production constant without a test seam)
        // covers its REQUIRES_NEW transactions, and the process probe's child runs that budget
        // against a freshly booted JVM. A new TCP+auth handshake per checkout — what
        // DriverManagerDataSource costs — is exactly the fixed overhead a saturated parallel
        // runner squeezes over the budget (PR#139 run 37922615049). A small eagerly-warm pool
        // (same shape as PooledSnowflakeMySqlTest.pool) keeps every budgeted transaction on an
        // established connection. The UTC session the old per-checkout wrapper set is preserved
        // per physical connection via the init SQL.
        var config = new HikariConfig();
        config.setJdbcUrl(url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&connectTimeout=1000&socketTimeout=5000");
        config.setUsername(System.getenv().getOrDefault("PLAT002_ID_MYSQL_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("PLAT002_ID_MYSQL_PASSWORD", ""));
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(3);
        config.setConnectionInitSql("SET SESSION time_zone = '+00:00'");
        return new HikariDataSource(config);
    }

    DataSource dataSource() { return dataSource; }
    JdbcTemplate jdbc() { return jdbc; }
    String databaseUrl() { return databaseUrl; }

    String initializationRef(int node) { return "qa-virgin:" + name + ":" + node; }

    void seedVirgin(int node) {
        jdbc.update("""
                INSERT INTO snowflake_worker_state
                (node_id,format_identity,enabled,initialization_ref,created_at,updated_at)
                VALUES (?,?,TRUE,?,NOW(3),NOW(3))
                """, node, SnowflakeProviderSettings.FORMAT_IDENTITY, initializationRef(node));
    }

    PreviousJvmExitVerifier virginVerifier(int node) {
        return previous -> {
            if (!created || previous.nodeId() != node || previous.incarnation() != null
                    || previous.fence() != 0 || previous.reservedThrough() != -1
                    || !initializationRef(node).equals(previous.initializationRef())) {
                throw new IllegalStateException("Not the specifically initialized virgin row of this fixture");
            }
        };
    }

    /** Explicit synthetic migration/boundary evidence, not a claim that this H is virgin. */
    PreviousJvmExitVerifier seedAuditedBoundary(int node, long expectedHigh) {
        String reference = "qa-boundary:" + name + ":" + node + ":" + expectedHigh;
        jdbc.update("""
                INSERT INTO snowflake_worker_state
                (node_id,format_identity,enabled,initialization_ref,reserved_through,created_at,updated_at)
                VALUES (?,?,TRUE,?,?,NOW(3),NOW(3))
                """, node, SnowflakeProviderSettings.FORMAT_IDENTITY, reference, expectedHigh);
        return previous -> {
            if (!created || previous.nodeId() != node || previous.incarnation() != null
                    || previous.fence() != 0 || previous.reservedThrough() != expectedHigh
                    || !reference.equals(previous.initializationRef())) {
                throw new IllegalStateException("Not the exact audited boundary row created by this fixture");
            }
        };
    }

    long highWater(int node) {
        return jdbc.queryForObject("SELECT reserved_through FROM snowflake_worker_state WHERE node_id=?", Long.class, node);
    }

    @Override public void close() {
        if (!created) return;
        created = false;
        try {
            // Release this fixture's pooled test-database connections before dropping it;
            // the DROP runs on the admin pool, which is closed last.
            if (dataSource instanceof HikariDataSource pool) pool.close();
        } finally {
            try {
                admin.execute("DROP DATABASE `" + name + "`");
            } finally {
                if (admin.getDataSource() instanceof HikariDataSource pool) pool.close();
            }
        }
    }
}
