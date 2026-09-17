package com.petplatform.merchant.biz;

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

/** Executes the authoritative merchant schemas in a fresh, isolated real MySQL database. */
final class MySqlMerchantApplicationSchemaTestDatabase implements AutoCloseable {
    private static final String PREFIX = "mer001_app_schema_";

    private final String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    MySqlMerchantApplicationSchemaTestDatabase() throws Exception {
        String url = System.getenv().getOrDefault(
                "MER001_MYSQL_URL", "jdbc:mysql://127.0.0.1:33452/");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException(
                    "MER001_MYSQL_URL must name a local isolated server, with no database or parameters");
        }
        String user = System.getenv().getOrDefault("MER001_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("MER001_MYSQL_PASSWORD", "");
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
            execute(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"));
            execute(root.resolve("docs/03-database/28-Merchant-Agreement-Schema-v0.1.sql"));
            execute(root.resolve("docs/03-database/29-Merchant-Application-Schema-v0.1.sql"));
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
