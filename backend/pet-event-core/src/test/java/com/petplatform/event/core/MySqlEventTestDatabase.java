package com.petplatform.event.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Test-only, real MySQL fixture over the authoritative Schema 06. Never adopts or drops a pre-existing database. */
final class MySqlEventTestDatabase implements AutoCloseable {
    private final String name = "plat003_test_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    MySqlEventTestDatabase() throws Exception {
        String url = System.getenv().getOrDefault("PLAT003_MYSQL_URL", "jdbc:mysql://127.0.0.1:33440/");
        // The fixture accepts only a dedicated local service with no existing database in its URL.
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException("PLAT003_MYSQL_URL must name a local isolated server, with no database or parameters");
        }
        String user = System.getenv().getOrDefault("PLAT003_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("PLAT003_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(source(url, user, password));
        dataSource = source(url + name, user, password);
        jdbc = new JdbcTemplate(dataSource);
        admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
        created = true;
        try {
            String version = admin.queryForObject("SELECT VERSION()", String.class);
            if (version == null || !version.startsWith("8.")) {
                throw new IllegalStateException("Real MySQL 8 is required");
            }
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) throw new IllegalStateException("Authoritative SQL06 schema was not found");
            try (Connection connection = dataSource.getConnection()) {
                // The authoritative schema carries Chinese comments: read it as UTF-8 on every platform.
                ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.support.EncodedResource(
                        new FileSystemResource(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        java.nio.charset.StandardCharsets.UTF_8));
                // Test-scoped business fact table; lives only inside this throwaway database.
                jdbc.execute("""
                        CREATE TABLE outbox_test_fact (
                            id           BIGINT      NOT NULL,
                            consumer     VARCHAR(128) NOT NULL,
                            event_id     VARCHAR(64) NOT NULL,
                            created_at   DATETIME(3) NOT NULL,
                            PRIMARY KEY (id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            }
            System.out.println("PLAT-003 real MySQL " + version + "; new database " + name);
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static DataSource source(String url, String user, String password) {
        return new DriverManagerDataSource(url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (var statement = connection.createStatement()) {
                    statement.execute("SET SESSION time_zone = '+00:00'");
                    return connection;
                } catch (SQLException failure) {
                    connection.close();
                    throw failure;
                }
            }
        };
    }

    DataSource dataSource() { return dataSource; }
    JdbcTemplate jdbc() { return jdbc; }

    @Override public void close() {
        if (created) {
            admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }
}
