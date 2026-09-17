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

/** Test-only fixture using a fresh real MySQL database for every test instance. */
final class MySqlMerchantDomainTestDatabase implements AutoCloseable {
    private final String name = "mer001_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    MySqlMerchantDomainTestDatabase() throws Exception {
        String url = System.getenv().getOrDefault(
                "MER001_MYSQL_URL", "jdbc:mysql://127.0.0.1:33450/");
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
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(
                    root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) {
                throw new IllegalStateException("Authoritative SQL06 schema was not found");
            }
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(
                                root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
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

    DataSource dataSource() {
        return dataSource;
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    /** A separate DataSource pointed at this same random database for snapshot tests. */
    JdbcTemplate independentJdbc() {
        String url = System.getenv().getOrDefault(
                "MER001_MYSQL_URL", "jdbc:mysql://127.0.0.1:33450/");
        String user = System.getenv().getOrDefault("MER001_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("MER001_MYSQL_PASSWORD", "");
        return new JdbcTemplate(source(url + name, user, password));
    }

    void seedMerchant(long merchantId, long ownerUserId, String status) {
        jdbc.update("""
                INSERT INTO merchant
                (id,owner_user_id,merchant_name,status,provider_merchant_no,version,created_at,updated_at)
                VALUES (?,?,?,?,NULL,0,NOW(3),NOW(3))
                """, merchantId, ownerUserId, "商家" + merchantId, status);
    }

    void seedStore(long storeId, long merchantId, String status, String phone) {
        jdbc.update("""
                INSERT INTO merchant_store
                (id,merchant_id,store_name,address,longitude,latitude,phone,status,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,0,NOW(3),NOW(3))
                """, storeId, merchantId, "门店" + storeId, "测试地址" + storeId,
                "121.4737000", "31.2304000", phone, status);
    }

    void seedStaff(long staffId, long merchantId, long storeId, String status,
                   boolean serviceEnabled, String phone) {
        jdbc.update("""
                INSERT INTO merchant_staff
                (id,merchant_id,store_id,staff_name,phone,employment_status,service_enabled,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,0,NOW(3),NOW(3))
                """, staffId, merchantId, storeId, "员工" + staffId, phone, status,
                serviceEnabled);
    }

    @Override
    public void close() {
        if (created) {
            admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }
}
