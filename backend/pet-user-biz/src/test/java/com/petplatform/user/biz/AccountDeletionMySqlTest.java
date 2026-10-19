package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.merchant.api.dto.MerchantMembershipDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.user.biz.application.AccountDeletionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Contract-66 deletion kernel over isolated real MySQL (PRD 5.1.29; CCR-USR-DELETION-001):
 * the precheck composes the four PRD blockers in catalog order, the write is the
 * supplement-23 idempotent command appending the immutable audit row and flipping
 * user_account.status to CANCELED in one transaction, blocked attempts write nothing, FROZEN
 * cannot delete, a concurrent second requestId finds the account already CANCELED, and the
 * audit table never grows a second row for the same account.
 */
class AccountDeletionMySqlTest {

    private static final long USER = 620_001L;
    private static final AtomicLong IDS = new AtomicLong(700_000);

    /** Deterministic fake fact ports (the real ones have their own module MySQL tests). */
    private static final class FakeFacts implements OrderDeletionFactsApi, AfterSaleDeletionFactsApi {
        boolean userOrdersBlocking;
        boolean userCasesOpen;
        boolean storeOrdersBlocking;
        boolean storeCasesOpen;
        final List<String> ownerStores;

        FakeFacts(List<String> ownerStores) { this.ownerStores = ownerStores; }

        @Override public boolean userHasBlockingOrder(
                OrderDeletionFactsApi.UserDeletionFactQuery query) {
            return userOrdersBlocking;
        }

        @Override public boolean storesHaveBlockingOrder(
                OrderDeletionFactsApi.StoreDeletionFactQuery query) {
            return storeOrdersBlocking;
        }

        @Override public boolean userHasOpenCase(
                AfterSaleDeletionFactsApi.UserDeletionFactQuery query) {
            return userCasesOpen;
        }

        @Override public boolean storesHaveOpenCase(
                AfterSaleDeletionFactsApi.StoreDeletionFactQuery query) {
            return storeCasesOpen;
        }
    }

    private static final MerchantAdmissionQueryApi OWNER_MEMBERSHIPS = new MerchantAdmissionQueryApi() {
        @Override
        public MerchantMembershipPageDTO listMemberships(MerchantMembershipQuery query) {
            return new MerchantMembershipPageDTO(
                    List.of(new MerchantMembershipDTO("7100", "测试商家", "7001", "测试门店", "OWNER")),
                    1, 50, 1);
        }

        @Override
        public com.petplatform.merchant.api.dto.MerchantAdmissionDTO getAdmission(
                com.petplatform.merchant.api.query.MerchantAdmissionQuery query) {
            throw new UnsupportedOperationException("not used by the deletion precheck");
        }
    };

    @Test
    void precheckComposesCatalogOrderBlockers() throws Exception {
        try (Database db = new Database()) {
            db.seedUser(USER, "13800002001", "ACTIVE");
            FakeFacts facts = new FakeFacts(List.of("7001"));
            AccountDeletionService service = db.service(facts, OWNER_MEMBERSHIPS);

            var clean = service.precheck(Long.toString(USER));
            assertFalse(clean.blocked());
            assertTrue(clean.blockers().isEmpty());

            facts.userOrdersBlocking = true;
            var one = service.precheck(Long.toString(USER));
            assertTrue(one.blocked());
            assertEquals("USER_ORDERS_UNSETTLED", one.blockers().get(0).code());

            facts.userCasesOpen = true;
            assertEquals("USER_AFTERSALES_OPEN", service.precheck(Long.toString(USER)).blockers().get(1).code());
            facts.storeOrdersBlocking = true;
            assertEquals("OWNER_STORE_ORDERS_UNSETTLED",
                    service.precheck(Long.toString(USER)).blockers().get(2).code());
            facts.storeCasesOpen = true;
            var all = service.precheck(Long.toString(USER));
            assertEquals(4, all.blockers().size());
            assertEquals("OWNER_STORE_AFTERSALES_OPEN", all.blockers().get(3).code());

            // No OWNER stores: store blockers stay out even when the flags say otherwise.
            FakeFacts noStores = new FakeFacts(List.of());
            noStores.storeOrdersBlocking = true;
            noStores.storeCasesOpen = true;
            assertEquals(2, db.service(noStores, OWNER_MEMBERSHIPS)
                    .precheck(Long.toString(USER)).blockers().size());
        }
    }

    @Test
    void deletionWritesAuditRowAndFlipsStatusThenReplaysReceipt() throws Exception {
        try (Database db = new Database()) {
            db.seedUser(USER, "13800002002", "ACTIVE");
            FakeFacts facts = new FakeFacts(List.of());
            AccountDeletionService service = db.service(facts, OWNER_MEMBERSHIPS);
            String requestId = UUID.randomUUID().toString();

            var outcome = service.deleteAccount(command(USER, requestId));
            assertFalse(outcome.replayed());
            assertEquals("CANCELED", outcome.receipt().status());
            assertEquals(Long.toString(USER), outcome.receipt().userId());
            assertEquals("CANCELED", db.jdbc.queryForObject(
                    "SELECT status FROM user_account WHERE id=?", String.class, USER));
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_deletion_request WHERE user_id=?", Integer.class, USER));
            assertEquals("ACCEPTED", db.jdbc.queryForObject(
                    "SELECT outcome FROM user_deletion_request WHERE user_id=?", String.class, USER));
            assertEquals(requestId, db.jdbc.queryForObject(
                    "SELECT request_id FROM user_deletion_request WHERE user_id=?", String.class, USER));

            // Same-key replay returns the first receipt (no second audit row, idempotent 200).
            var replay = service.deleteAccount(command(USER, requestId));
            assertTrue(replay.replayed());
            assertEquals(outcome.receipt().deletedAt(), replay.receipt().deletedAt());
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_deletion_request", Integer.class));

            // A different requestId on the canceled account conflicts (no second row).
            ApiException conflict = assertThrows(ApiException.class,
                    () -> service.deleteAccount(command(USER, UUID.randomUUID().toString())));
            assertEquals("COMMON_CONFLICT", conflict.code());
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_deletion_request", Integer.class));
        }
    }

    @Test
    void blockedDeletionWritesNothingAndRecoversWhenBlockerClears() throws Exception {
        try (Database db = new Database()) {
            db.seedUser(USER, "13800002003", "ACTIVE");
            FakeFacts facts = new FakeFacts(List.of("7001"));
            facts.userOrdersBlocking = true;
            AccountDeletionService service = db.service(facts, OWNER_MEMBERSHIPS);

            ApiException blocked = assertThrows(ApiException.class,
                    () -> service.deleteAccount(command(USER, UUID.randomUUID().toString())));
            assertEquals("DELETION_BLOCKED", blocked.code());
            assertEquals("ACTIVE", db.jdbc.queryForObject(
                    "SELECT status FROM user_account WHERE id=?", String.class, USER));
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_deletion_request", Integer.class));

            // The blocker clears; the same account can still delete under a fresh requestId.
            facts.userOrdersBlocking = false;
            facts.storeOrdersBlocking = false;
            var outcome = service.deleteAccount(command(USER, UUID.randomUUID().toString()));
            assertFalse(outcome.replayed());
            assertEquals("CANCELED", db.jdbc.queryForObject(
                    "SELECT status FROM user_account WHERE id=?", String.class, USER));
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_deletion_request", Integer.class));
        }
    }

    @Test
    void frozenUsersAreRefusedAndGuardsHold() throws Exception {
        try (Database db = new Database()) {
            db.seedUser(USER, "13800002004", "FROZEN");
            AccountDeletionService service = db.service(new FakeFacts(List.of()), OWNER_MEMBERSHIPS);
            ApiException frozen = assertThrows(ApiException.class,
                    () -> service.deleteAccount(command(USER, UUID.randomUUID().toString())));
            assertEquals("USER_FROZEN", frozen.code());

            // Non-USER operator contexts never reach the kernel body.
            CommandContext staffContext = new CommandContext(UUID.randomUUID().toString(),
                    "trace", OperatorType.MERCHANT_STAFF, "7100", "M_WEB");
            assertThrows(IllegalArgumentException.class,
                    () -> service.deleteAccount(new AccountDeletionService.DeleteAccountCommand(
                            staffContext, true)));

            // acknowledged=false is a plain 400 before any binding is created.
            ApiException notAcknowledged = assertThrows(ApiException.class,
                    () -> service.deleteAccount(new AccountDeletionService.DeleteAccountCommand(
                            userContext(USER, UUID.randomUUID().toString()), false)));
            assertEquals("COMMON_INVALID_ARGUMENT", notAcknowledged.code());
        }
    }

    private static CommandContext userContext(long userId, String requestId) {
        return new CommandContext(requestId, "trace-" + requestId, OperatorType.USER,
                Long.toString(userId), "C_MINIAPP");
    }

    private static AccountDeletionService.DeleteAccountCommand command(long userId, String requestId) {
        return new AccountDeletionService.DeleteAccountCommand(userContext(userId, requestId), true);
    }

    /** Isolated real-MySQL fixture over schemas 06 + 14 + 66; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "usrdel001_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource dataSource;
        private final JdbcTemplate jdbc;

        Database() throws Exception {
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL", "jdbc:mysql://127.0.0.1:33440/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("AUTH_MYSQL_URL must name a local isolated server");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            dataSource = source(url + name, user, password);
            jdbc = new JdbcTemplate(dataSource);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                try (Connection connection = dataSource.getConnection()) {
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql",
                            "14-Command-Idempotency-Schema-v0.1.sql",
                            "66-User-Settings-Deletion-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root().resolve("docs/03-database/" + file)),
                                StandardCharsets.UTF_8));
                    }
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

        AccountDeletionService service(OrderDeletionFactsApi orders,
                                       AfterSaleDeletionFactsApi aftersales,
                                       MerchantAdmissionQueryApi memberships) {
            return new AccountDeletionService(dataSource, IDS::incrementAndGet, orders, aftersales,
                    memberships);
        }

        AccountDeletionService service(FakeFacts facts, MerchantAdmissionQueryApi memberships) {
            return service(facts, facts, memberships);
        }

        void seedUser(long id, String phone, String status) {
            jdbc.update("""
                    INSERT INTO user_account (id,phone,nickname,status,created_at,updated_at)
                    VALUES (?,?,?, ?,NOW(3),NOW(3))
                    """, id, phone, "测试用户" + id, status);
        }

        JdbcTemplate jdbc() {
            return jdbc;
        }

        private static Path root() {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(
                    root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) throw new IllegalStateException("Authoritative SQL06 schema was not found");
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

        @Override
        public void close() {
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }
}
