package com.petplatform.coupon.biz;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Isolated real MySQL 8 database for the merchant coupon tests (contract 79): SQL06 core coupon
 * tables (with the contract-79 columns), SQL63 campaign/claim/budget tables and the shared
 * command_idempotency table (14号) the merchant writes bind to. Same env-prefix convention as
 * the other coupon tests: MCPN first, AUTH as the CI default. The database is dropped on close
 * even when setup fails, so repeated runs cannot leak.
 */
final class MerchantCouponMySqlTestDatabase implements AutoCloseable {
    private static final String PREFIX = "mcpn_merchant_coupon_";
    private static final List<String> SCRIPTS = List.of(
            "06-核心数据库Schema-v0.1.sql",
            "63-Marketing-Storage-v0.1.sql",
            "14-Command-Idempotency-Schema-v0.1.sql");

    private final String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    MerchantCouponMySqlTestDatabase() throws Exception {
        String prefix = System.getenv().containsKey("MCPN_MYSQL_URL") ? "MCPN" : "AUTH";
        String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException(
                    prefix + "_MYSQL_URL must target a local isolated server, database-less");
        }
        String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(source(url, user, password));
        dataSource = source(url + name, user, password);
        jdbc = new JdbcTemplate(dataSource);
        admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
        created = true;
        try {
            Path root = repositoryRoot();
            for (String script : SCRIPTS) {
                execute(root.resolve("docs/03-database/" + script));
            }
        } catch (Exception failure) {
            try {
                close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        if (created) {
            if (!name.startsWith(PREFIX) || !name.matches("[a-z0-9_]+")) {
                throw new IllegalStateException("Refusing to drop unexpected database name: " + name);
            }
            admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }

    private void execute(Path script) throws Exception {
        if (!Files.exists(script)) {
            throw new IllegalStateException("Authoritative schema was not found: " + script);
        }
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
        }
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(
                root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Repository root with SQL06 was not found");
        }
        return root;
    }

    private static DataSource source(String url, String user, String password) {
        return new DriverManagerDataSource(
                url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                user, password) {
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
}
