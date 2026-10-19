package com.petplatform.merchant.biz;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
/**
 * Executes the authoritative merchant + staff schemas in a fresh isolated real MySQL database
 * for the contract-64 governance read tests. Connection config follows the module env-prefix
 * fallback chain convention (GOV2 -> MER001 -> AUTH, the last being the CI default), so a
 * hardcoded prefix that CI does not configure fails red locally, never silently skips.
 */
final class AdminMerchantGovernanceMySqlTestDatabase implements AutoCloseable {
    private static final String PREFIX = "gov2_governance_";
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

    AdminMerchantGovernanceMySqlTestDatabase() throws Exception {
        // CI-统一 env 名 AUTH_MYSQL_*（铁律）：优先读 AUTH_MYSQL_*，兼容回退既有
        // GOV2_*/MER001_*（CI 同库同密码，行为不变），本地默认 127.0.0.1:33440。
        String prefix = System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH"
                : System.getenv().containsKey("GOV2_MYSQL_URL") ? "GOV2"
                : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001" : "AUTH";
        String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:33440/");
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

    @Override
    public void close() throws Exception {
        if (created) {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }

    private static DataSource source(String url, String user, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource(url, user, password);
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return source;
    }

    private void execute(Path script) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new FileSystemResource(script), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        while (cwd != null && !cwd.resolve("docs/03-database").toFile().isDirectory()) {
            cwd = cwd.getParent();
        }
        if (cwd == null) {
            throw new IllegalStateException("repository root with docs/03-database not found");
        }
        return cwd;
    }

    static String sha256(String content) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
