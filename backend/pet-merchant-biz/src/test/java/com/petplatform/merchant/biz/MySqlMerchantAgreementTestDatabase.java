package com.petplatform.merchant.biz;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Test-only fixture: Schema06 followed by the approved agreement DDL in a fresh MySQL database. */
final class MySqlMerchantAgreementTestDatabase implements AutoCloseable {
    private final String name = "mer001_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private boolean created;

    MySqlMerchantAgreementTestDatabase() throws Exception {
        String url = System.getenv().getOrDefault(
                "MER001_MYSQL_URL", "jdbc:mysql://127.0.0.1:33451/");
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
            Path schema06 = root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql");
            Path agreement = root.resolve("docs/03-database/28-Merchant-Agreement-Schema-v0.1.sql");
            if (!Files.exists(agreement)) {
                throw new IllegalStateException("Approved agreement schema was not found");
            }
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(schema06), StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(agreement), StandardCharsets.UTF_8));
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

    DataSource dataSource() { return dataSource; }
    JdbcTemplate jdbc() { return jdbc; }

    JdbcTemplate independentJdbc() {
        String url = System.getenv().getOrDefault(
                "MER001_MYSQL_URL", "jdbc:mysql://127.0.0.1:33451/");
        String user = System.getenv().getOrDefault("MER001_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("MER001_MYSQL_PASSWORD", "");
        return new JdbcTemplate(source(url + name, user, password));
    }

    void seedMerchant(long merchantId, long ownerUserId, String status) {
        jdbc.update("""
                INSERT INTO merchant
                (id,owner_user_id,merchant_name,status,provider_merchant_no,version,created_at,updated_at)
                VALUES (?,?,?,?,NULL,0,NOW(3),NOW(3))
                """, merchantId, ownerUserId, "协议商家" + merchantId, status);
    }

    String seedAgreementVersion(long id, String version, String content, long publishedBy) {
        String hash = sha256(content);
        jdbc.update("""
                INSERT INTO merchant_agreement_version
                (id,agreement_version,content,content_sha256,published_at,published_by_operator_id)
                VALUES (?,?,?,?,?,?)
                """, id, version, content, hash, LocalDateTime.of(2026, 9, 17, 8, 0), publishedBy);
        return hash;
    }

    void seedCurrent(long versionId, long version) {
        jdbc.update("""
                INSERT INTO merchant_agreement_current
                (agreement_key,agreement_version_id,version,updated_at)
                VALUES ('MERCHANT',?,?,?)
                """, versionId, version, LocalDateTime.of(2026, 9, 17, 8, 0));
    }

    void moveCurrent(long versionId, long version) {
        jdbc.update("""
                UPDATE merchant_agreement_current SET agreement_version_id=?,version=?,updated_at=?
                WHERE agreement_key='MERCHANT'
                """, versionId, version, LocalDateTime.of(2026, 9, 17, 9, 0));
    }

    void seedAcceptance(long id, long merchantId, long versionId, long userId,
                        LocalDateTime acceptedAt, String hash) {
        jdbc.update("""
                INSERT INTO merchant_agreement_acceptance
                (id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256)
                VALUES (?,?,?,?,?,?)
                """, id, merchantId, versionId, userId, acceptedAt, hash);
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public void close() {
        if (created) {
            admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }
}
