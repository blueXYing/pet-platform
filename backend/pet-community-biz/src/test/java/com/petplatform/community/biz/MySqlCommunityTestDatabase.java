package com.petplatform.community.biz;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Test-only, real MySQL fixture over Schema 06 + SQL 62 (baike content). Never adopts or
 * drops a pre-existing database; every test gets its own throwaway schema.
 */
final class MySqlCommunityTestDatabase implements AutoCloseable {
    private final String name = "cmn001_test_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    MySqlCommunityTestDatabase() throws Exception {
        // Same env wiring as the CI-green fixtures (e.g. schedule ReservationConfirmMySqlTest):
        // the globally provided AUTH_MYSQL_URL/USER/PASSWORD win, the defaults only name a
        // local isolated developer server. CI overrides all three via the workflow env block.
        String url = System.getenv().getOrDefault("AUTH_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:33440/");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException("AUTH_MYSQL_URL must name a local isolated server, with no database or parameters");
        }
        String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
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
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root.resolve("docs/03-database/62-Baike-Content-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root.resolve("docs/03-database/64-Community-Storage-v0.1.sql")),
                        StandardCharsets.UTF_8));
            }
            System.out.println("CMN-001 real MySQL " + version + "; new database " + name);
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
