package com.petplatform.thirdparty.biz;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Test-only, real MySQL fixture over SQL 15. Never adopts or drops a pre-existing database. */
final class MySqlAssetRegistryTestDatabase implements AutoCloseable {
    private final String name = "osstest_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private boolean created;

    MySqlAssetRegistryTestDatabase() throws Exception {
        String url = System.getenv().getOrDefault("OSSTEST_MYSQL_URL", "jdbc:mysql://127.0.0.1:33440/");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException("OSSTEST_MYSQL_URL must name a local isolated server");
        }
        String user = System.getenv().getOrDefault("OSSTEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("OSSTEST_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(source(url, user, password));
        dataSource = source(url + name, user, password);
        admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
        created = true;
        try {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(root.resolve("docs/03-database/15-Asset-Registry-Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) throw new IllegalStateException("SQL15 was not found");
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                        root.resolve("docs/03-database/15-Asset-Registry-Schema-v0.1.sql")), StandardCharsets.UTF_8));
            }
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static DataSource source(String url, String user, String password) {
        return new DriverManagerDataSource(
                url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password);
    }

    DataSource dataSource() { return dataSource; }

    @Override public void close() {
        if (created) {
            admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }
}
