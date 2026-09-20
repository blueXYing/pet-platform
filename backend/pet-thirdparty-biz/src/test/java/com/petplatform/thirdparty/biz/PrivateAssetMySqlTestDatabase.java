package com.petplatform.thirdparty.biz;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Dedicated disposable real-MySQL fixture over SQL13 and SQL31. */
final class PrivateAssetMySqlTestDatabase implements AutoCloseable {
  private final String name = "privateasset_" + UUID.randomUUID().toString().replace("-", "");
  private final JdbcTemplate admin;
  private final DataSource dataSource;
  private boolean created;

  PrivateAssetMySqlTestDatabase() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "PRIVATE_ASSET_MYSQL_URL",
                System.getenv().getOrDefault("OSSTEST_MYSQL_URL", "jdbc:mysql://127.0.0.1:33452/"));
    if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
      throw new IllegalArgumentException(
          "PRIVATE_ASSET_MYSQL_URL must name an isolated local server");
    }
    String user =
        System.getenv()
            .getOrDefault(
                "PRIVATE_ASSET_MYSQL_USER",
                System.getenv().getOrDefault("OSSTEST_MYSQL_USER", "root"));
    String password =
        System.getenv()
            .getOrDefault(
                "PRIVATE_ASSET_MYSQL_PASSWORD",
                System.getenv().getOrDefault("OSSTEST_MYSQL_PASSWORD", ""));
    admin = new JdbcTemplate(source(url, user, password));
    dataSource = source(url + name, user, password);
    admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
    created = true;
    try {
      Path root = Path.of("").toAbsolutePath();
      while (root != null
          && !Files.exists(root.resolve("docs/03-database/31-Private-Asset-Schema-v0.1.sql"))) {
        root = root.getParent();
      }
      if (root == null) throw new IllegalStateException("SQL31 was not found");
      execute(root.resolve("docs/03-database/13-Async-Infra-Schema-v0.1.sql"));
      execute(root.resolve("docs/03-database/31-Private-Asset-Schema-v0.1.sql"));
    } catch (Exception failure) {
      try {
        close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private void execute(Path script) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
    }
  }

  private static DataSource source(String url, String user, String password) {
    return new DriverManagerDataSource(
        url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password);
  }

  DataSource dataSource() {
    return dataSource;
  }

  JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  @Override
  public void close() {
    if (created) {
      admin.execute("DROP DATABASE `" + name + "`");
      created = false;
    }
  }
}
