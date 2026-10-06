package com.petplatform.merchant.biz;

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
 * Executes the authoritative merchant + staff-binding schemas in a fresh isolated real MySQL
 * database. Connection config follows the module env-prefix fallback chain convention
 * (STAFFB -> MER001 -> AUTH, the last being the CI default), so a hardcoded prefix that CI
 * does not configure fails red locally, never silently skips.
 */
final class StaffBindingMySqlTestDatabase implements AutoCloseable {
    private static final String PREFIX = "staffb_binding_";
    private static final List<String> SCRIPTS = List.of(
            "06-核心数据库Schema-v0.1.sql",
            "28-Merchant-Agreement-Schema-v0.1.sql",
            "29-Merchant-Application-Schema-v0.1.sql",
            "52-Merchant-Staff-Identity-Schema-v0.1.sql",
            "54-Merchant-Staff-Binding-Schema-v0.1.sql");

    private final String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    StaffBindingMySqlTestDatabase() throws Exception {
        String prefix = System.getenv().containsKey("STAFFB_MYSQL_URL") ? "STAFFB"
                : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "STAFFB";
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
            String version = admin.queryForObject("SELECT VERSION()", String.class);
            if (version == null || !version.startsWith("8.")) {
                throw new IllegalStateException("Real MySQL 8 is required");
            }
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
                user,
                password) {
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
}
