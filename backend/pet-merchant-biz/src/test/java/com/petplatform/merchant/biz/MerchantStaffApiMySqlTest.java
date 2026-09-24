package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.*;
import com.petplatform.merchant.api.command.*;
import com.petplatform.merchant.api.dto.*;
import com.petplatform.merchant.api.query.*;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Isolated real MySQL verifies staff CAS, binding, audit and read projection. */
class MerchantStaffApiMySqlTest {
    private static final long MERCHANT = 9_007_199_254_740_993L;
    private static final long STORE = 9_007_199_254_740_994L;
    private static final long OWNER = 9_007_199_254_740_995L;
    private static final long OTHER = 9_007_199_254_740_996L;
    private final AtomicLong ids = new AtomicLong(9_200_000_000_000_000L);

    private static String id(long value) { return Long.toString(value); }
    private static QueryContext query(long owner) {
        return new QueryContext("test", OperatorType.USER, id(owner));
    }
    private static CommandContext command(String requestId, long owner) {
        return new CommandContext(requestId, "test", OperatorType.USER, id(owner), "MINIAPP");
    }
    private MerchantStaffApiImpl api(MySqlMerchantApplicationSchemaTestDatabase db,
            ApplicationReviewFactsReader facts) {
        return new MerchantStaffApiImpl(db.dataSource(), ids::incrementAndGet, facts,
                new AesGcmProtectedValueProvider("staff-test-v1", key((byte) 1), key((byte) 2)),
                Clock.fixed(Instant.parse("2026-09-24T08:00:00Z"), ZoneOffset.UTC));
    }
    private static byte[] key(byte value) { byte[] bytes = new byte[32]; Arrays.fill(bytes, value); return bytes; }
    private static void seed(MySqlMerchantApplicationSchemaTestDatabase db) throws Exception {
        JdbcTemplate jdbc = db.jdbc();
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                MERCHANT, OWNER, "员工测试商家");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STORE, MERCHANT, "门店", "地址");
        jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,subject_verification_status,version,created_at,updated_at) VALUES(?,?,?,'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                1001L, OWNER, MERCHANT);
        String content = "员工测试协议";
        String hash = MySqlMerchantAgreementTestDatabase.sha256(content);
        jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                1002L, "staff-test", content, hash, OWNER);
        jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                1003L, MERCHANT, 1002L, OWNER, hash);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/03-database/35-Merchant-Staff-Audit-Schema-v0.1.sql")))
            root = root.getParent();
        assertNotNull(root);
        try (Connection connection = db.dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                    root.resolve("docs/03-database/35-Merchant-Staff-Audit-Schema-v0.1.sql")), StandardCharsets.UTF_8));
        }
    }

    @Test void createUpdateEnableReplayAndAudit() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            // The application approval provider is a test seam; HTTP QA exercises the real SQL29 chain.
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String createKey = UUID.randomUUID().toString();
            var create = new CreateMerchantStaffCommand(id(MERCHANT), id(STORE), "王小明", "13800138000",
                    "ACTIVE", false, command(createKey, OWNER));
            MerchantStaffCommandResult first = api.createStaff(create);
            assertTrue(first.created());
            assertFalse(first.replayed());
            assertEquals("138****8000", first.staff().phoneMasked());
            assertEquals("0", first.staff().version());
            assertEquals(first.staff(), api.createStaff(create).staff());
            assertTrue(api.createStaff(create).replayed());
            assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_staff_audit", Integer.class));
            byte[] canonical = db.jdbc().queryForObject("SELECT params_canonical FROM merchant_command_idempotency", byte[].class);
            assertNotNull(canonical);
            assertFalse(new String(canonical, StandardCharsets.UTF_8).contains("13800138000"));
            assertFalse(new String(canonical, StandardCharsets.UTF_8).contains("王小明"));

            String staffId = first.staff().staffId();
            String updateKey = UUID.randomUUID().toString();
            var update = new UpdateMerchantStaffCommand(id(MERCHANT), id(STORE), staffId,
                    "王小明二", null, "0", command(updateKey, OWNER));
            assertEquals("1", api.updateStaff(update).staff().version());
            assertNull(api.updateStaff(update).staff().phoneMasked());
            assertEquals("2", api.enableStaff(new EnableMerchantStaffCommand(id(MERCHANT), id(STORE),
                    staffId, "1", command(UUID.randomUUID().toString(), OWNER))).staff().version());
            assertTrue(api.getStaff(new MerchantStaffQuery(id(MERCHANT), id(STORE), staffId,
                    query(OWNER))).serviceEnabled());
            assertEquals(1, api.listStaff(new MerchantStaffListQuery(id(MERCHANT), id(STORE),
                    1, 20, "ACTIVE", true, query(OWNER))).total());
            assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_staff_audit", Integer.class));
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class, () -> api.updateStaff(new UpdateMerchantStaffCommand(
                    id(MERCHANT), id(STORE), staffId, "旧版本", null, "0",
                    command(UUID.randomUUID().toString(), OWNER)))).code());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> api.createStaff(new CreateMerchantStaffCommand(id(MERCHANT), id(STORE), "另一名员工",
                            null, "ACTIVE", false, command(createKey, OWNER)))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> api.getStaff(new MerchantStaffQuery(id(MERCHANT), id(STORE), staffId,
                            query(OTHER)))).code());
        }
    }

    @Test void writeFailsClosedWhenApprovalOrSignatureIsMissingButReadSurvivesOffline() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("DRAFT"));
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.createStaff(new CreateMerchantStaffCommand(id(MERCHANT), id(STORE),
                            "待审员工", null, "ACTIVE", false,
                            command(UUID.randomUUID().toString(), OWNER)))).code());
            db.jdbc().update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,employment_status,service_enabled,version,created_at,updated_at) VALUES(?,?,?,'历史员工','ACTIVE',1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    1004L, MERCHANT, STORE);
            db.jdbc().update("UPDATE merchant SET status='OFFLINE' WHERE id=?", MERCHANT);
            assertEquals("历史员工", api.getStaff(new MerchantStaffQuery(id(MERCHANT), id(STORE), "1004",
                    query(OWNER))).staffName());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.enableStaff(new EnableMerchantStaffCommand(id(MERCHANT), id(STORE), "1004",
                            "0", command(UUID.randomUUID().toString(), OWNER)))).code());
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_staff_audit", Integer.class));
        }
    }

    @Test void unavailableCanonicalProtectionRejectsWriteBeforeBinding() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            var api = new MerchantStaffApiImpl(db.dataSource(), ids::incrementAndGet,
                    id -> new ApplicationReviewFactsReader.Facts("APPROVED"),
                    (purpose, value) -> { throw new IllegalStateException("secret unavailable"); },
                    Clock.systemUTC());
            db.jdbc().update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,employment_status,service_enabled,version,created_at,updated_at) VALUES(?,?,?,'只读员工','ACTIVE',1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    1005L, MERCHANT, STORE);
            assertEquals("只读员工", api.getStaff(new MerchantStaffQuery(id(MERCHANT), id(STORE),
                    "1005", query(OWNER))).staffName());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> api.createStaff(new CreateMerchantStaffCommand(id(MERCHANT), id(STORE),
                            "安全员工", "13800138000", "ACTIVE", false,
                            command(UUID.randomUUID().toString(), OWNER)))).code());
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency", Integer.class));
        }
    }

    @Test void internalSystemContextCannotBorrowOwnerAuthority() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            var system = new CommandContext(UUID.randomUUID().toString(), "trace", OperatorType.SYSTEM,
                    id(OWNER), "INTERNAL");
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> api.createStaff(new CreateMerchantStaffCommand(id(MERCHANT), id(STORE),
                            "越权员工", null, "ACTIVE", false, system))).code());
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> api.listStaff(new MerchantStaffListQuery(id(MERCHANT), id(STORE), 1, 20,
                            null, null, new QueryContext("trace", OperatorType.SYSTEM, id(OWNER))))).code());
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_staff", Integer.class));
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_command_idempotency", Integer.class));
        }
    }

    @Test void executionRechecksOwnerAfterAdmissionAndLeavesReservedBindingOnRevocation() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase();
             var pool = Executors.newSingleThreadExecutor();
             Connection revoke = db.dataSource().getConnection()) {
            seed(db);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            revoke.setAutoCommit(false);
            try (var statement = revoke.prepareStatement("UPDATE merchant SET owner_user_id=? WHERE id=?")) {
                statement.setLong(1, OTHER);
                statement.setLong(2, MERCHANT);
                assertEquals(1, statement.executeUpdate());
            }
            String requestId = UUID.randomUUID().toString();
            var command = new CreateMerchantStaffCommand(id(MERCHANT), id(STORE), "并发员工", null,
                    "ACTIVE", false, command(requestId, OWNER));
            Future<String> result = pool.submit(() -> {
                try { api.createStaff(command); return "unexpected success"; }
                catch (ApiException failure) { return failure.code(); }
            });
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_command_idempotency",
                    Integer.class) == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_command_idempotency", Integer.class));
            revoke.commit();
            assertEquals(CommonApiCodes.NOT_FOUND, result.get(5, TimeUnit.SECONDS));
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_staff", Integer.class));
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_staff_audit", Integer.class));
            assertEquals("RESERVED", db.jdbc().queryForObject(
                    "SELECT status FROM merchant_command_idempotency", String.class));
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> api.createStaff(command)).code());
        }
    }

    @Test void inconsistentStaffMerchantInOwnedStoreIsDependencyFailure() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            db.jdbc().update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,employment_status,service_enabled,version,created_at,updated_at) VALUES(?,?,?,'损坏员工','ACTIVE',1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    1006L, MERCHANT + 100, STORE);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> api.getStaff(new MerchantStaffQuery(id(MERCHANT), id(STORE), "1006",
                            query(OWNER)))).code());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> api.listStaff(new MerchantStaffListQuery(id(MERCHANT), id(STORE), 1, 20,
                            null, null, query(OWNER)))).code());
        }
    }
}
