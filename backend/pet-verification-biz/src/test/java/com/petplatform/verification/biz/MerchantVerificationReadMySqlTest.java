package com.petplatform.verification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.merchant.api.query.MerchantStaffNamingApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.verification.api.query.MerchantVerificationReadApi.StoreVerificationListQuery;
import com.petplatform.verification.api.query.MerchantVerificationReadApi.VerificationRow;
import com.petplatform.verification.biz.apiimpl.MerchantVerificationReadApiImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * CCR-M-VERIFY-RECORD-001 merchant verification-record read face over a real MySQL (schemas
 * 06 + 47 + 48): store-scoped filters with the fixed verified_at DESC/id DESC sort, pagination,
 * the CCR §9 display pair (STAFF real name via the naming face, OWNER 「店长」 label, missing
 * profile degrading to null) and the guard-chain discipline — the store guard acquire, the
 * OWNER re-proof passthrough and the 63号 range rules. The raw operator_id is never selected.
 */
class MerchantVerificationReadMySqlTest {
    private static final String MERCHANT = "910301";
    private static final String STORE = "910302";
    private static final String OTHER_STORE = "910312";

    @Test
    void listFiltersSortPagingAndTheDisplayPair() throws Exception {
        try (Database d = new Database()) {
            d.seedOwnerRecord(1, 101L, "2026-09-01 08:00:00");
            d.seedStaffRecord(2, 102L, 201L, "2026-09-01 09:00:00");
            d.seedStaffRecord(3, 103L, 202L, "2026-09-01 10:00:00");   // staff profile absent → null
            d.seedOwnerRecord(4, 104L, OTHER_STORE, "2026-09-01 11:00:00"); // foreign store row

            var page = d.api().listStoreVerifications(query(null, null, null));
            assertEquals(3, page.total());
            // Fixed sort verified_at DESC, id DESC: newest first; the foreign store row is absent.
            assertEquals(List.of("3", "2", "1"),
                    page.items().stream().map(VerificationRow::verificationId).toList());
            // CCR §9 display pair: STAFF real name, OWNER label, absent profile null.
            VerificationRow staffNamed = page.items().get(1);
            assertEquals("STAFF", staffNamed.membershipKind());
            assertEquals("201", staffNamed.operatorStaffId());
            assertEquals("张三", staffNamed.operatorName());
            VerificationRow staffMissing = page.items().get(0);
            assertEquals("STAFF", staffMissing.membershipKind());
            assertEquals("202", staffMissing.operatorStaffId());
            assertNull(staffMissing.operatorName());
            VerificationRow owner = page.items().get(2);
            assertEquals("OWNER", owner.membershipKind());
            assertNull(owner.operatorStaffId());
            assertEquals("店长", owner.operatorName());
            assertEquals("USER", owner.operatorType());
            assertEquals(OffsetDateTime.parse("2026-09-01T08:00:00.000Z"), owner.verifiedAt());

            // orderId exact filter.
            assertEquals(1, d.api().listStoreVerifications(query("102", null, null)).total());
            // Paired range isolates the 10:00 row.
            assertEquals(1, d.api().listStoreVerifications(query(null,
                    OffsetDateTime.parse("2026-09-01T09:30:00.000Z"),
                    OffsetDateTime.parse("2026-09-01T10:30:00.000Z"))).total());
            // Pagination: one per page keeps the stable order.
            PageResult<VerificationRow> first = d.api().listStoreVerifications(page(1, 1));
            assertEquals(3, first.total());
            assertEquals(List.of("3"), first.items().stream().map(VerificationRow::verificationId).toList());
            PageResult<VerificationRow> second = d.api().listStoreVerifications(page(2, 1));
            assertEquals(List.of("2"), second.items().stream().map(VerificationRow::verificationId).toList());
        }
    }

    @Test
    void queryDisciplineAndGuardChainFailClosed() throws Exception {
        try (Database d = new Database()) {
            d.seedOwnerRecord(1, 101L, "2026-09-01 08:00:00");
            // 63号 range rules: single-sided, inverted, span over 366 days.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> d.api().listStoreVerifications(
                query(null, OffsetDateTime.parse("2026-09-01T00:00:00.000Z"), null))));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> d.api().listStoreVerifications(
                query(null, OffsetDateTime.parse("2026-09-02T00:00:00.000Z"),
                    OffsetDateTime.parse("2026-09-01T00:00:00.000Z")))));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> d.api().listStoreVerifications(
                query(null, OffsetDateTime.parse("2025-01-01T00:00:00.000Z"),
                    OffsetDateTime.parse("2026-09-01T00:00:00.000Z")))));
            // Malformed coordinates / orderId and a non-USER subject fail before any read.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> d.api().listStoreVerifications(
                new StoreVerificationListQuery("x1", STORE, null, null, null, 1, 20, operator()))));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> d.api().listStoreVerifications(
                query("bad", null, null))));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> d.api().listStoreVerifications(
                new StoreVerificationListQuery(MERCHANT, STORE, null, null, null, 1, 20,
                    new QueryContext("trace", OperatorType.MERCHANT_STAFF, "7")))));
            // OWNER re-proof denial passes through as the family 403 (anti-enumeration).
            assertEquals(CommonApiCodes.FORBIDDEN, code(() -> d.api(false).listStoreVerifications(query(null, null, null))));
        }
    }

    private interface Statement { Object run(); }

    private static String code(Statement statement) {
        ApiException error = assertThrows(ApiException.class, statement::run);
        return error.code();
    }

    private static QueryContext operator() {
        return new QueryContext("trace", OperatorType.USER, "910300");
    }

    private static StoreVerificationListQuery query(String orderId, OffsetDateTime from, OffsetDateTime to) {
        return new StoreVerificationListQuery(MERCHANT, STORE, orderId, from, to, 1, 20, operator());
    }

    private static StoreVerificationListQuery page(int page, int pageSize) {
        return new StoreVerificationListQuery(MERCHANT, STORE, null, null, null, page, pageSize, operator());
    }

    private static final class Database implements AutoCloseable {
        final String name = "merverify_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate sql;

        Database() throws Exception {
            String prefix = System.getenv().containsKey("MER_VERIFY_MYSQL_URL") ? "MER_VERIFY"
                : System.getenv().containsKey("ADM_VERIFY_MYSQL_URL") ? "ADM_VERIFY"
                : System.getenv().containsKey("ORDER_CREATE_MYSQL_URL") ? "ORDER_CREATE"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "MER_VERIFY";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL", "jdbc:mysql://127.0.0.1:33459/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("MER_VERIFY_MYSQL_URL must target local server root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(dataSource(url, user, password));
            source = dataSource(url + name, user, password);
            sql = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                    root = root.getParent();
                }
                if (root == null) throw new IllegalStateException("Schema06 not found");
                try (Connection connection = source.getConnection()) {
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql", "47-Verification-Credential-Schema-v0.1.sql",
                        "48-Verification-Completion-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(root.resolve("docs/03-database/" + file)),
                            StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) { close(); throw failure; }
        }

        MerchantVerificationReadApiImpl api() { return api(true); }

        /** allow switch for the OWNER re-proof port. */
        MerchantVerificationReadApiImpl api(boolean ownerAllowed) {
            ScheduleCapacityGuardApi guard = new ScheduleCapacityGuardApi() {
                @Override public void acquire(List<String> storeIds, QueryContext context) {}
                @Override public void requireHeld(String storeId, DataSource callerSource) {}
            };
            MerchantOrderAuthorityApi authority = new MerchantOrderAuthorityApi() {
                @Override public void requireOwner(String merchantId, String storeId, QueryContext context) {}
                @Override public void requireOwnerRead(String merchantId, String storeId, QueryContext context) {
                    if (!ownerAllowed) throw new ApiException(CommonApiCodes.FORBIDDEN, "Existing order authority unavailable");
                }
            };
            MerchantStaffNamingApi naming = new MerchantStaffNamingApi() {
                @Override public Map<String, String> staffNames(String storeId, java.util.Collection<String> staffIds,
                        QueryContext context) {
                    // Only this store's own profile rows resolve; 202 has no merchant_staff row.
                    Map<String, String> names = new LinkedHashMap<>();
                    if (STORE.equals(storeId)) names.put("201", "张三");
                    return names;
                }
            };
            return new MerchantVerificationReadApiImpl(source, guard, authority, naming);
        }

        void seedOwnerRecord(int id, long orderId, String verifiedAt) {
            seedOwnerRecord(id, orderId, STORE, verifiedAt);
        }

        void seedOwnerRecord(int id, long orderId, String storeId, String verifiedAt) {
            sql.update("""
                INSERT INTO verification_record(id, verification_no, order_id, store_id, operator_staff_id,
                    verify_method, request_id, verified_at, created_at, operator_type, operator_id,
                    membership_kind, command_id, credential_id, attempt_id, order_version)
                VALUES(?, ?, ?, ?, NULL, 'MANUAL', ?, ?, ?, 'USER', 910300, 'OWNER', ?, 601, ?, 7)
                """, (long) id, (long) id, orderId, Long.parseLong(storeId),
                "owner-req-" + id, verifiedAt, verifiedAt, (long) (500 + id), (long) (300 + id));
        }

        void seedStaffRecord(int id, long orderId, long staffId, String verifiedAt) {
            sql.update("""
                INSERT INTO verification_record(id, verification_no, order_id, store_id, operator_staff_id,
                    verify_method, request_id, verified_at, created_at, operator_type, operator_id,
                    membership_kind, command_id, credential_id, attempt_id, order_version)
                VALUES(?, ?, ?, ?, ?, 'SCAN', ?, ?, ?, 'MERCHANT_STAFF', ?, 'STAFF', ?, 601, ?, 7)
                """, (long) id, (long) id, orderId, Long.parseLong(STORE), staffId,
                "staff-req-" + id, verifiedAt, verifiedAt, staffId, (long) (500 + id), (long) (300 + id));
        }

        private static DataSource dataSource(String url, String user, String password) {
            DriverManagerDataSource source = new DriverManagerDataSource(url, user, password);
            source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            return source;
        }

        @Override public void close() {
            try { admin.execute("DROP DATABASE IF EXISTS `" + name + "`"); } catch (RuntimeException ignored) { }
        }
    }
}
