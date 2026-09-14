package com.petplatform.task.core;

import com.petplatform.common.SnowflakeIdGenerator;
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

/** Test-only, real MySQL fixture. Never adopts or drops a pre-existing database. */
final class MySqlTestDatabase implements AutoCloseable {
    private final String name = "plat004_test_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    MySqlTestDatabase() throws Exception {
        String url = System.getenv().getOrDefault("PLAT004_MYSQL_URL", "jdbc:mysql://127.0.0.1:33440/");
        // The fixture accepts only a dedicated local service with no existing database in its URL.
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException("PLAT004_MYSQL_URL must name a local isolated server, with no database or parameters");
        }
        String user = System.getenv().getOrDefault("PLAT004_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("PLAT004_MYSQL_PASSWORD", "");
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
            while (root != null && !Files.exists(root.resolve("docs/03-database/13-Async-Infra-Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) throw new IllegalStateException("Authoritative SQL13 schema was not found");
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                        root.resolve("docs/03-database/13-Async-Infra-Schema-v0.1.sql")));
            }
            System.out.println("PLAT-004 real MySQL " + version + "; new database " + name);
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
    JdbcAsyncTaskRepository repository(SnowflakeIdGenerator ids) {
        return new JdbcAsyncTaskRepository(dataSource, ids);
    }

    void seed(long id, String key, String type, int maxRetries) {
        jdbc.update("""
                INSERT INTO async_task
                (id,task_no,task_key,owner_module,task_type,biz_type,biz_id,status,
                 execute_at,max_retry_count,created_at,updated_at)
                VALUES (?,?,?,'task',?,'TEST',?,'READY',TIMESTAMPADD(SECOND,-1,NOW(3)),?,NOW(3),NOW(3))
                """, id, id, key, type, id, maxRetries);
    }

    @Override
    public void close() {
        if (created) {
            admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }
}
