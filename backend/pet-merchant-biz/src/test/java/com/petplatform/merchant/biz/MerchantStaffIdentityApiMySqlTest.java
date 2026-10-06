package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.*;
import com.petplatform.merchant.api.dto.MerchantStaffIdentityFactsDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMembershipDTO;
import com.petplatform.merchant.api.query.MerchantStaffActionQuery;
import com.petplatform.merchant.api.query.MerchantStaffIdentityFactsQuery;
import com.petplatform.merchant.api.query.MerchantStaffMembershipQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffIdentityApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Isolated real MySQL verifies the contract-52 read kernel: relation facts, filtered
 * memberships, fail-closed action gate and damaged-fact handling. Seeds are fixtures only and
 * never binding evidence (STA-01); writers arrive with the approved binding CCR.
 */
class MerchantStaffIdentityApiMySqlTest {
    private static final long MERCHANT = 9_100_000_000_000_001L;
    private static final long STORE = 9_100_000_000_000_002L;
    private static final long STORE2 = 9_100_000_000_000_003L;
    private static final long OTHER_MERCHANT = 9_100_000_000_000_004L;
    private static final long OTHER_STORE = 9_100_000_000_000_005L;
    private static final long OWNER = 9_100_000_000_000_010L;
    private static final long USER = 9_100_000_000_000_011L;
    private static final long STAFF = 9_100_000_000_000_012L;
    private static final long OTHER_OWNER = 9_100_000_000_000_013L;
    private static final long MEMBER = 9_100_000_000_000_020L;
    private static final long GRANT = 9_100_000_000_000_021L;
    private static final long GRANT2 = 9_100_000_000_000_022L;

    private final AtomicLong ids = new AtomicLong(9_300_000_000_000_000L);

    private static String id(long value) { return Long.toString(value); }
    private static QueryContext query(long user) {
        return new QueryContext("test", OperatorType.USER, id(user));
    }
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T08:00:00Z"), ZoneOffset.UTC);

    private MerchantStaffIdentityApiImpl api(MySqlMerchantApplicationSchemaTestDatabase db,
            ApplicationReviewFactsReader facts) {
        ScheduleCapacityGuardApi guard = Mockito.mock(ScheduleCapacityGuardApi.class);
        return new MerchantStaffIdentityApiImpl(db.dataSource(), guard, facts, CLOCK);
    }

    /** The action gate joins the caller's store-guard transaction; tests simulate exactly that. */
    private TransactionTemplate guardTransaction(MySqlMerchantApplicationSchemaTestDatabase db) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        template.setIsolationLevel(TransactionTemplate.ISOLATION_REPEATABLE_READ);
        return template;
    }

    private static void seed(MySqlMerchantApplicationSchemaTestDatabase db) {
        JdbcTemplate jdbc = db.jdbc();
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',3,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                MERCHANT, OWNER, "身份测试商家");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',2,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STORE, MERCHANT, "一店", "地址一");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STORE2, MERCHANT, "二店", "地址二");
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                OTHER_MERCHANT, OTHER_OWNER, "其他商家");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                OTHER_STORE, OTHER_MERCHANT, "别家店", "地址三");
        jdbc.update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,phone,employment_status,service_enabled,version,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STAFF, MERCHANT, STORE, "王小明", "13800138000");
        String content = "身份测试协议";
        String hash = MySqlMerchantAgreementTestDatabase.sha256(content);
        jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                1102L, "identity-test", content, hash, OWNER);
        jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                1103L, MERCHANT, 1102L, OWNER, hash);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/03-database/52-Merchant-Staff-Identity-Schema-v0.1.sql")))
            root = root.getParent();
        assertNotNull(root);
        try (Connection connection = db.dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                    root.resolve("docs/03-database/52-Merchant-Staff-Identity-Schema-v0.1.sql")), StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void seedMember(JdbcTemplate jdbc, long memberId, long merchantId, long userId, String status) {
        jdbc.update("INSERT INTO merchant_member(id,merchant_id,user_id,status,version,created_at,updated_at) VALUES(?,?,?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                memberId, merchantId, userId, status);
    }

    private static void seedGrant(JdbcTemplate jdbc, long grantId, long memberId, long storeId,
            Long staffId, String status) {
        jdbc.update("INSERT INTO merchant_member_store_grant(id,member_id,store_id,staff_id,status,version,created_at,updated_at) VALUES(?,?,?,?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                grantId, memberId, storeId, staffId, status);
    }

    private static void seedAction(JdbcTemplate jdbc, long rowId, long memberId, long storeId, String code) {
        jdbc.update("INSERT INTO merchant_member_store_action(id,member_id,store_id,action_code,created_at,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                rowId, memberId, storeId, code);
    }

    private static ApplicationReviewFactsReader approved() {
        return merchantId -> new ApplicationReviewFactsReader.Facts("APPROVED");
    }

    @Test
    void factsProjectRelationAndTrackGrantVersion() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            JdbcTemplate jdbc = db.jdbc();
            seedMember(jdbc, MEMBER, MERCHANT, USER, "ENABLED");
            seedGrant(jdbc, GRANT, MEMBER, STORE, STAFF, "ENABLED");
            seedAction(jdbc, 1301L, MEMBER, STORE, "merchant.order.read");
            seedAction(jdbc, 1302L, MEMBER, STORE, "merchant.order.verify");
            var api = api(db, approved());

            var facts = api.getStaffFacts(new MerchantStaffIdentityFactsQuery(id(MERCHANT), id(STORE), query(USER)));
            assertEquals("STAFF", facts.membershipKind());
            assertEquals(Boolean.TRUE, facts.membershipEnabled());
            assertEquals("APPROVED", facts.applicationStatus());
            assertEquals("SIGNED", facts.signingStatus());
            assertEquals("ACTIVE", facts.merchantStatus());
            assertEquals("ACTIVE", facts.storeStatus());
            assertEquals(id(STAFF), facts.staffId());
            assertEquals(List.of("merchant.order.read", "merchant.order.verify"), facts.grantedActions());
            assertEquals("2026-10-02T08:00Z", facts.checkedAt().toString());
            assertTrue(facts.authzVersion().matches("[0-9a-f]{16}"));

            jdbc.update("UPDATE merchant_member_store_grant SET version=2 WHERE id=?", GRANT);
            var changed = api.getStaffFacts(new MerchantStaffIdentityFactsQuery(id(MERCHANT), id(STORE), query(USER)));
            assertNotEquals(facts.authzVersion(), changed.authzVersion());
        }
    }

    @Test
    void membershipsFilterAndPage() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            JdbcTemplate jdbc = db.jdbc();
            seedMember(jdbc, MEMBER, MERCHANT, USER, "ENABLED");
            seedMember(jdbc, 1201L, MERCHANT, 9_100_000_000_000_099L, "ENABLED");
            seedMember(jdbc, 1202L, OTHER_MERCHANT, USER, "ENABLED");
            seedGrant(jdbc, GRANT, MEMBER, STORE, STAFF, "ENABLED");
            seedGrant(jdbc, GRANT2, MEMBER, STORE2, null, "ENABLED");
            seedGrant(jdbc, 1221L, 1201L, STORE, null, "ENABLED");
            seedGrant(jdbc, 1222L, 1202L, OTHER_STORE, null, "ENABLED");
            var api = api(db, approved());

            var page = api.listStaffMemberships(new MerchantStaffMembershipQuery(1, 20, query(USER)));
            // USER holds two enabled grants under MERCHANT and one under OTHER_MERCHANT; the
            // other user's member row never appears.
            assertEquals(3, page.total());
            assertEquals(3, page.items().size());
            MerchantStaffMembershipDTO first = page.items().get(0);
            assertEquals(id(MERCHANT), first.merchantId());
            assertEquals(id(STORE), first.storeId());
            assertEquals("一店", first.storeName());
            assertEquals("STAFF", first.membershipKind());
            assertEquals(id(STAFF), first.staffId());
            assertNull(page.items().get(1).staffId());
            assertEquals(id(STORE2), page.items().get(1).storeId());
            assertEquals(id(OTHER_MERCHANT), page.items().get(2).merchantId());
            assertEquals(id(OTHER_STORE), page.items().get(2).storeId());

            // Unique (merchant_id,user_id): disabled/revoked members are separate users whose
            // relations never surface in a selectable list.
            seedMember(jdbc, 1203L, MERCHANT, 9_100_000_000_000_098L, "DISABLED");
            seedGrant(jdbc, 1231L, 1203L, STORE, null, "ENABLED");
            seedMember(jdbc, 1204L, MERCHANT, 9_100_000_000_000_097L, "REVOKED");
            seedGrant(jdbc, 1232L, 1204L, STORE2, null, "ENABLED");
            var after = api.listStaffMemberships(new MerchantStaffMembershipQuery(1, 20, query(USER)));
            assertEquals(3, after.total(), "disabled and revoked members must not be selectable");
            assertEquals(0, api.listStaffMemberships(
                    new MerchantStaffMembershipQuery(1, 20, query(9_100_000_000_000_098L))).total());
            ApiException revokedFacts = assertThrows(ApiException.class, () -> api.getStaffFacts(
                    new MerchantStaffIdentityFactsQuery(id(MERCHANT), id(STORE2), query(9_100_000_000_000_097L))));
            assertEquals(CommonApiCodes.NOT_FOUND, revokedFacts.code());

            var secondPage = api.listStaffMemberships(new MerchantStaffMembershipQuery(2, 1, query(USER)));
            assertEquals(3, secondPage.total());
            assertEquals(1, secondPage.items().size());
            assertEquals(id(STORE2), secondPage.items().get(0).storeId());
        }
    }

    @Test
    void actionGatePassesGrantAndFailsClosed() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            JdbcTemplate jdbc = db.jdbc();
            seedMember(jdbc, MEMBER, MERCHANT, USER, "ENABLED");
            seedGrant(jdbc, GRANT, MEMBER, STORE, STAFF, "ENABLED");
            seedAction(jdbc, 1301L, MEMBER, STORE, "merchant.order.verify");
            var api = api(db, approved());
            var tx = guardTransaction(db);

            tx.executeWithoutResult(status -> api.requireStaffAction(
                    new MerchantStaffActionQuery(id(MERCHANT), id(STORE), "merchant.order.verify", query(USER))));

            ApiException notGranted = assertThrows(ApiException.class, () -> tx.executeWithoutResult(status ->
                    api.requireStaffAction(new MerchantStaffActionQuery(id(MERCHANT), id(STORE),
                            "merchant.refund.handle", query(USER)))));
            assertEquals(CommonApiCodes.FORBIDDEN, notGranted.code());

            // A grant of another store is no relation to this store: anti-enumeration 404.
            ApiException wrongStore = assertThrows(ApiException.class, () -> tx.executeWithoutResult(status ->
                    api.requireStaffAction(new MerchantStaffActionQuery(id(MERCHANT), id(STORE2),
                            "merchant.order.verify", query(USER)))));
            assertEquals(CommonApiCodes.NOT_FOUND, wrongStore.code());

            // Unrelated targets never leak merchant facts.
            ApiException otherMerchant = assertThrows(ApiException.class, () ->
                    api.getStaffFacts(new MerchantStaffIdentityFactsQuery(id(OTHER_MERCHANT), id(OTHER_STORE), query(USER))));
            assertEquals(CommonApiCodes.NOT_FOUND, otherMerchant.code());
        }
    }

    @Test
    void actionGateDeniesDisabledRevokedFrozenOfflineUnsignedAndForeignSessions() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            JdbcTemplate jdbc = db.jdbc();
            seedMember(jdbc, MEMBER, MERCHANT, USER, "ENABLED");
            seedGrant(jdbc, GRANT, MEMBER, STORE, STAFF, "ENABLED");
            seedAction(jdbc, 1301L, MEMBER, STORE, "merchant.order.verify");
            var api = api(db, approved());
            var tx = guardTransaction(db);
            var gate = new MerchantStaffActionQuery(id(MERCHANT), id(STORE), "merchant.order.verify", query(USER));

            jdbc.update("UPDATE merchant_member SET status='DISABLED' WHERE id=?", MEMBER);
            ApiException disabled = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> api.requireStaffAction(gate)));
            assertEquals(CommonApiCodes.FORBIDDEN, disabled.code());

            jdbc.update("UPDATE merchant_member SET status='REVOKED' WHERE id=?", MEMBER);
            ApiException revoked = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> api.requireStaffAction(gate)));
            assertEquals(CommonApiCodes.NOT_FOUND, revoked.code());

            jdbc.update("UPDATE merchant_member SET status='ENABLED' WHERE id=?", MEMBER);
            jdbc.update("UPDATE merchant_member_store_grant SET status='REVOKED' WHERE id=?", GRANT);
            ApiException revokedGrant = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> api.requireStaffAction(gate)));
            assertEquals(CommonApiCodes.NOT_FOUND, revokedGrant.code());

            jdbc.update("UPDATE merchant_member_store_grant SET status='ENABLED' WHERE id=?", GRANT);
            jdbc.update("UPDATE merchant SET status='FROZEN' WHERE id=?", MERCHANT);
            ApiException frozen = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> api.requireStaffAction(gate)));
            assertEquals(CommonApiCodes.FORBIDDEN, frozen.code());

            jdbc.update("UPDATE merchant SET status='OFFLINE' WHERE id=?", MERCHANT);
            ApiException offline = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> api.requireStaffAction(gate)));
            assertEquals(CommonApiCodes.FORBIDDEN, offline.code());

            jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", MERCHANT);
            var reviewing = api(db, merchantId -> new ApplicationReviewFactsReader.Facts("REVIEWING"));
            ApiException unapproved = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> reviewing.requireStaffAction(gate)));
            assertEquals(CommonApiCodes.FORBIDDEN, unapproved.code());

            jdbc.update("DELETE FROM merchant_agreement_acceptance WHERE id=?", 1103L);
            ApiException unsigned = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> api.requireStaffAction(gate)));
            assertEquals(CommonApiCodes.FORBIDDEN, unsigned.code());
            jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256) VALUES(1104,?,?,?,UTC_TIMESTAMP(3),?)",
                    MERCHANT, 1102L, OWNER, MySqlMerchantAgreementTestDatabase.sha256("身份测试协议"));

            ApiException foreign = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> api.requireStaffAction(
                    new MerchantStaffActionQuery(id(MERCHANT), id(STORE), "merchant.order.verify",
                            new QueryContext("test", OperatorType.PLATFORM_OPERATOR, "1")))));
            assertEquals(CommonApiCodes.FORBIDDEN, foreign.code());
            ApiException anonymous = assertThrows(ApiException.class, () -> api.requireStaffAction(
                    new MerchantStaffActionQuery(id(MERCHANT), id(STORE), "merchant.order.verify", null)));
            assertEquals(CommonApiCodes.UNAUTHORIZED, anonymous.code());

            ApiException badCode = assertThrows(ApiException.class, () -> api.requireStaffAction(
                    new MerchantStaffActionQuery(id(MERCHANT), id(STORE), "MERCHANT.ORDER.VERIFY", query(USER))));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, badCode.code());

            var facts = api.getStaffFacts(new MerchantStaffIdentityFactsQuery(id(MERCHANT), id(STORE), query(USER)));
            assertEquals("SIGNED", facts.signingStatus());
        }
    }

    @Test
    void damagedGrantReferenceFailsClosed() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seed(db);
            JdbcTemplate jdbc = db.jdbc();
            seedMember(jdbc, MEMBER, MERCHANT, USER, "ENABLED");
            seedGrant(jdbc, GRANT, MEMBER, STORE, STAFF, "ENABLED");
            seedAction(jdbc, 1301L, MEMBER, STORE, "merchant.order.verify");
            jdbc.update("UPDATE merchant_staff SET store_id=? WHERE id=?", STORE2, STAFF);
            var api = api(db, approved());
            var tx = guardTransaction(db);
            var gate = new MerchantStaffActionQuery(id(MERCHANT), id(STORE), "merchant.order.verify", query(USER));

            ApiException facts = assertThrows(ApiException.class, () ->
                    api.getStaffFacts(new MerchantStaffIdentityFactsQuery(id(MERCHANT), id(STORE), query(USER))));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, facts.code());
            ApiException gateFailure = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> api.requireStaffAction(gate)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, gateFailure.code());
        }
    }
}
