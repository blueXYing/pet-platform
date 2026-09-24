package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.dto.StoreStaffFactsDTO;
import com.petplatform.merchant.api.query.MerchantStoreStaffFactsApi;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleReadStore;
import com.petplatform.schedule.biz.infrastructure.provider.ScheduleQualifiedStaffFactsProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ScheduleQualifiedStaffFactsMySqlTest {
    private static final long STORE = 700001L;
    private static final long SERVICE = 700002L;
    private static final long STAFF_A = 700010L;
    private static final long STAFF_B = 700011L;
    private static final String FROM = "2030-01-01T09:00:00+08:00";
    private static final String TO = "2030-01-01T11:00:00+08:00";

    @Test
    void countsOnlyWholeWindowUnionAndFailsClosedOnRelevantUnknownFacts() throws Exception {
        try (Database db = new Database()) {
            var provider = provider(db, List.of(Long.toString(STAFF_A), Long.toString(STAFF_B)));
            assertEquals(0, count(provider));
            capability(db, 1, STAFF_A, SERVICE, "ENABLED");
            capability(db, 2, STAFF_B, SERVICE, "ENABLED");
            availability(db, 3, STORE, STAFF_A, "2030-01-01 01:00:00", "2030-01-01 02:00:00", "AVAILABLE");
            availability(db, 4, STORE, STAFF_A, "2030-01-01 02:00:00", "2030-01-01 03:00:00", "AVAILABLE");
            availability(db, 5, STORE, STAFF_B, "2030-01-01 01:00:00", "2030-01-01 01:59:00", "AVAILABLE");
            availability(db, 6, STORE, STAFF_B, "2030-01-01 02:00:00", "2030-01-01 03:00:00", "AVAILABLE");
            availability(db, 7, STORE + 1, STAFF_B, "2030-01-01 01:59:00", "2030-01-01 02:00:00", "AVAILABLE");
            assertEquals(1, count(provider), "adjacent rows cover A, one-minute gap excludes B");
            db.jdbc.update("UPDATE staff_availability_window SET start_at='2030-01-01 01:59:00' WHERE id=6");
            assertEquals(2, count(provider), "overlap removes B's gap; cross-store row never helped");
            db.jdbc.update("UPDATE staff_service_capability SET status='BROKEN' WHERE id=2");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> count(provider)).code());
            db.jdbc.update("UPDATE staff_service_capability SET status='ENABLED' WHERE id=2");
            db.jdbc.update("UPDATE staff_availability_window SET status='BROKEN' WHERE id=4");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> count(provider)).code());
            db.jdbc.update("UPDATE staff_availability_window SET status='AVAILABLE', end_at=start_at WHERE id=4");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> count(provider)).code());
        }
    }

    @Test
    void joinsTheWindowReadSnapshotRatherThanOpeningAnotherScheduleSnapshot() throws Exception {
        try (Database db = new Database()) {
            capability(db, 1, STAFF_A, SERVICE, "ENABLED");
            availability(db, 2, STORE, STAFF_A, "2030-01-01 01:00:00", "2030-01-01 03:00:00", "AVAILABLE");
            var provider = provider(db, List.of(Long.toString(STAFF_A)));
            var windows = new ScheduleReadStore(db.source);
            int observed = windows.read(mapper -> {
                assertEquals(1, mapper.selectServiceCapabilities(SERVICE).size());
                db.independent.update("UPDATE staff_service_capability SET status='BROKEN' WHERE id=1");
                return count(provider);
            });
            assertEquals(1, observed, "provider must reuse the existing SCH repeatable-read snapshot");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> count(provider)).code(), "standalone call sees the later fact");
        }
    }

    private static ScheduleQualifiedStaffFactsProvider provider(Database db, List<String> staff) {
        MerchantStoreStaffFactsApi merchant = query -> new StoreStaffFactsDTO(query.storeId(), staff);
        return new ScheduleQualifiedStaffFactsProvider(merchant, db.source);
    }

    private static int count(ScheduleQualifiedStaffFactsProvider provider) {
        return provider.countQualifiedAvailableStaff(
                Long.toString(STORE), Long.toString(SERVICE),
                OffsetDateTime.parse(FROM), OffsetDateTime.parse(TO));
    }

    private static ApiException failure(Runnable work) {
        return assertThrows(ApiException.class, work::run);
    }

    private static void capability(Database db, long id, long staff, long service, String status) {
        db.jdbc.update("INSERT INTO staff_service_capability"
                + "(id,staff_id,service_id,status,created_at,updated_at)"
                + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", id, staff, service, status);
    }

    private static void availability(Database db, long id, long store, long staff,
            String start, String end, String status) {
        db.jdbc.update("INSERT INTO staff_availability_window"
                + "(id,store_id,staff_id,start_at,end_at,status,version,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                id, store, staff, start, end, status);
    }

    private static final class Database implements AutoCloseable {
        private final String name = "sch002_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private final JdbcTemplate independent;

        Database() throws Exception {
            String url = System.getenv().getOrDefault("SCH002_MYSQL_URL", "jdbc:mysql://127.0.0.1:33450/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("SCH002_MYSQL_URL must target a local server root");
            }
            String user = System.getenv().getOrDefault("SCH002_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("SCH002_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            independent = new JdbcTemplate(source(url + name, user, password));
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                    root = root.getParent();
                }
                if (root == null) throw new IllegalStateException("Schema06 not found");
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                            StandardCharsets.UTF_8));
                }
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(
                    url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password) {
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
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }
}
