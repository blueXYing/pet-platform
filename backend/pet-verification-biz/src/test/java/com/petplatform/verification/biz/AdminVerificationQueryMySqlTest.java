package com.petplatform.verification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminAuthorization;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationListQuery;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationQuery;
import com.petplatform.verification.biz.apiimpl.AdminVerificationQueryApiImpl;
import com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.VerificationAuthorizationPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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

/**
 * Contract-63 platform verification read face over a real MySQL (schemas 06 + 47 + 48): list
 * filters with the fixed verified_at DESC sort, the dispute-tracing detail (attempt chain plus
 * risk attempts) and the kernel-side authorization discipline. Credential cipher material is
 * never selected. The schema-47 credential rows stay absent: this face never reads them.
 */
class AdminVerificationQueryMySqlTest {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-01T00:00:00.000Z");
    private static final AdminAuthorization AUTH = new AdminAuthorization("9007199254740993", 1);

    @Test void listFiltersSortAndDetailTraceTheAttemptChain() throws Exception {
        try (Database d = new Database()) {
            d.seedRecord(1, 8101L, 101L, 8L, "SCAN", "2026-09-01 08:00:00");
            d.seedRecord(2, 8102L, 102L, 9L, "MANUAL", "2026-09-01 09:00:00");
            d.seedAttempt(301, 101L, "FAILED", "CODE_EXPIRED", "2026-09-01 07:55:00");
            d.seedAttempt(302, 101L, "SUCCESS", null, "2026-09-01 08:00:00");
            d.seedAttempt(303, 102L, "LOCKED", "RISK_LOCKED", "2026-09-01 08:50:00");
            d.seedRiskAttempt(401, 101L, "FAIL_COUNT_3", true, "2026-09-01 07:56:00");

            var page = d.api().listVerifications(list(null, null, null));
            assertEquals(2, page.total());
            // Fixed sort verified_at DESC: the later record first.
            assertEquals(2L, Long.parseLong(page.items().get(0).verificationId()));

            assertEquals(1, d.api().listVerifications(list("101", null, null)).total());
            assertEquals(1, d.api().listVerifications(list(null, "9", null)).total());
            assertEquals(1, d.api().listVerifications(list(null, null, "MANUAL")).total());
            assertEquals(1, d.api().listVerifications(list(null, null, "SCAN")).total());

            var detail = d.api().getVerification(new AdminVerificationQuery("1", operator(), AUTH));
            assertEquals("8101", detail.verificationNo());
            assertEquals("101", detail.orderId());
            assertEquals("201", detail.operatorStaffId());
            assertEquals(2, detail.attempts().size());
            assertEquals("FAILED", detail.attempts().get(0).result());
            assertEquals("CODE_EXPIRED", detail.attempts().get(0).failReason());
            assertEquals(1, detail.riskAttempts().size());
            assertEquals("FAIL_COUNT_3", detail.riskAttempts().get(0).resultCode());
            assertTrue(detail.riskAttempts().get(0).countsFailure());

            ApiException unknown = assertThrows(ApiException.class,
                () -> d.api().getVerification(new AdminVerificationQuery("999", operator(), AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, unknown.code());
            ApiException invalid = assertThrows(ApiException.class,
                () -> d.api().getVerification(new AdminVerificationQuery("abc", operator(), AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, invalid.code());
        }
    }

    @Test void authorizationIsRecheckedInsideTheKernelAndFailsClosed() throws Exception {
        try (Database d = new Database()) {
            d.seedRecord(1, 8101L, 101L, 8L, "SCAN", "2026-09-01 08:00:00");
            ApiException denied = assertThrows(ApiException.class,
                () -> d.api(false, true, true).listVerifications(list(null, null, null)));
            assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
            ApiException scope = assertThrows(ApiException.class,
                () -> d.api(true, false, true).getVerification(new AdminVerificationQuery("1", operator(), AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, scope.code());
            ApiException unavailable = assertThrows(ApiException.class,
                () -> d.api(true, true, false).getVerification(new AdminVerificationQuery("1", operator(), AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
            ApiException identity = assertThrows(ApiException.class,
                () -> d.api().getVerification(new AdminVerificationQuery("1",
                    new QueryContext("trace", OperatorType.MERCHANT_STAFF, "7"), AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, identity.code());
        }
    }

    private static QueryContext operator() {
        return new QueryContext("trace", OperatorType.PLATFORM_OPERATOR, "42");
    }

    private static AdminVerificationListQuery list(String orderId, String storeId, String method) {
        return new AdminVerificationListQuery(orderId, storeId, method, null, null, 1, 20,
            operator(), AUTH);
    }

    private static final class Database implements AutoCloseable {
        final String name = "admverify_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate sql;

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ADM_VERIFY_MYSQL_URL") ? "ADM_VERIFY"
                : System.getenv().containsKey("ORDER_CREATE_MYSQL_URL") ? "ORDER_CREATE"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ADM_VERIFY";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL", "jdbc:mysql://127.0.0.1:33459/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("ADM_VERIFY_MYSQL_URL must target local server root");
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

        AdminVerificationQueryApiImpl api() { return api(true, true, true); }

        /** Collection entry / resource scope / availability switches for the authorization port. */
        AdminVerificationQueryApiImpl api(boolean collectionAllowed, boolean scopeAllowed, boolean available) {
            return new AdminVerificationQueryApiImpl(source, new VerificationAuthorizationPort() {
                @Override
                public com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision check(
                        com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Check check) {
                    if (!available) return null;
                    if (!scopeAllowed) return new com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision(false, CHECKED, "1:1", "RESOURCE_SCOPE_DENIED");
                    return new com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                }

                @Override
                public com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision checkCollection(
                        com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.CollectionCheck check) {
                    if (!available) return null;
                    if (!collectionAllowed) return new com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision(false, CHECKED, "1:1", "ACTION_NOT_GRANTED");
                    return new com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                }
            });
        }

        void seedRecord(int id, long verificationNo, long orderId, long storeId, String method, String verifiedAt) {
            sql.update("""
                INSERT INTO verification_record(id, verification_no, order_id, store_id, operator_staff_id,
                    verify_method, request_id, verified_at, created_at, operator_type, operator_id,
                    membership_kind, command_id, credential_id, attempt_id, order_version)
                VALUES(?, ?, ?, ?, 201, ?, ?, ?, ?, 'MERCHANT_STAFF', 201, 'STAFF', ?, 601, ?, 7)
                """, (long) id, verificationNo, orderId, storeId, method,
                "verify-req-" + id, verifiedAt, verifiedAt, (long) (500 + id), (long) (300 + id));
        }

        void seedAttempt(int id, long orderId, String result, String failReason, String attemptedAt) {
            sql.update("""
                INSERT INTO verification_attempt(id, order_id, store_id, operator_staff_id, request_id,
                    result, fail_reason, created_at, operator_type, operator_id, membership_kind, command_id)
                VALUES(?, ?, 8, 201, ?, ?, ?, ?, 'MERCHANT_STAFF', 201, 'STAFF', ?)
                """, (long) id, orderId, "attempt-req-" + id, result, failReason, attemptedAt, (long) (600 + id));
        }

        void seedRiskAttempt(int id, long orderId, String resultCode, boolean countsFailure, String attemptedAt) {
            sql.update("""
                INSERT INTO verification_credential_risk_attempt(id, order_id, store_id, credential_id,
                    command_id, actor_type, actor_id, result_code, counts_failure, attempted_at)
                VALUES(?, ?, 8, NULL, 610, 'USER', 42, ?, ?, ?)
                """, (long) id, orderId, resultCode, countsFailure, attemptedAt);
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
