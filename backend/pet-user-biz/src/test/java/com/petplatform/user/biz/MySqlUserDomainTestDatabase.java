package com.petplatform.user.biz;

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

/** Test-only, real MySQL fixture over Schema 06 + SQL 14. Never adopts or drops a pre-existing database. */
final class MySqlUserDomainTestDatabase implements AutoCloseable {
    private final String name = "usr001_test_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    MySqlUserDomainTestDatabase() throws Exception {
        String url = System.getenv().getOrDefault("USR001_MYSQL_URL", "jdbc:mysql://127.0.0.1:33440/");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException("USR001_MYSQL_URL must name a local isolated server, with no database or parameters");
        }
        String user = System.getenv().getOrDefault("USR001_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("USR001_MYSQL_PASSWORD", "");
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
                        new FileSystemResource(root.resolve("docs/03-database/14-Command-Idempotency-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
            }
            System.out.println("USR-001 real MySQL " + version + "; new database " + name);
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

    void seedUser(long id, String phone, String status) {
        jdbc.update("""
                INSERT INTO user_account (id,phone,nickname,status,created_at,updated_at)
                VALUES (?,?,?, ?,NOW(3),NOW(3))
                """, id, phone, "测试用户" + id, status);
    }

    void seedOrderPetSnapshot(long snapshotId, long orderId, long petId, String name, String weightKg) {
        jdbc.update("""
                INSERT INTO order_pet_snapshot
                (id,order_id,pet_id,pet_name,pet_type,breed_name,sex,weight_kg,health_note,created_at)
                VALUES (?,?,?,?,'DOG','柯基','MALE',?,'seed note',NOW(3))
                """, snapshotId, orderId, petId, name, weightKg);
    }

    @Override public void close() {
        if (created) {
            admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }
}
