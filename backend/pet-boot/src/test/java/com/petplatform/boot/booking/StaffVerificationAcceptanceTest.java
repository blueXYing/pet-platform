package com.petplatform.boot.booking;
import static com.petplatform.boot.booking.VerificationCredentialAcceptanceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.VerificationCredentialConfiguration;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffIdentityApiImpl;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.order.api.command.OrderVerificationCommitApi.OperatorIdentity;
import com.petplatform.verification.api.command.VerificationCompletionApi.Receipt;
import com.petplatform.verification.biz.application.CredentialPorts;
import com.petplatform.verification.biz.application.VerificationCompletionService;
import com.petplatform.verification.biz.application.VerificationCredentialService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 48 K1 v0.2 acceptance over real MySQL: STAFF completion through the contract-52
 * member action gate with a traceable operator identity, OWNER regression, fail-closed
 * revocation/offline boundaries and the STA-05 revocation/verification competition (D2/D4/D5).
 * Seeded member/grant rows are fixtures only and never binding evidence (STA-01 stays with the
 * binding slice); the invite flow itself is delivered by the parallel binding work.
 */
class StaffVerificationAcceptanceTest {
    private static final long MERCHANT = 710301L;
    private static final long STORE = 710302L;
    private static final long OWNER = 710300L;
    private static final long STAFF_PROFILE = 710303L;
    private static final long STAFF_USER = 710310L;
    private static final long MEMBER = 710311L;
    private static final long GRANT = 710312L;
    private static final String VERIFY_ACTION = VerificationCredentialConfiguration.VERIFY_ACTION;

    private record Env(T t, VerificationCompletionService service, JdbcTemplate jdbc) implements AutoCloseable {
        @Override public void close() { t.close(); }
    }

    private static void applyIdentitySchema(T t) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/03-database/52-Merchant-Staff-Identity-Schema-v0.1.sql")))
            root = root.getParent();
        assertNotNull(root, "contract-52 schema not found");
        try (Connection connection = t.r.f.f.db.source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                    root.resolve("docs/03-database/52-Merchant-Staff-Identity-Schema-v0.1.sql")), StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void seedStaff(JdbcTemplate jdbc, Long staffId, String memberStatus, String grantStatus, String action) {
        jdbc.update("INSERT INTO merchant_member(id,merchant_id,user_id,status,version,created_at,updated_at) VALUES(?,?,?,'ENABLED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", MEMBER, MERCHANT, STAFF_USER);
        jdbc.update("UPDATE merchant_member SET status=? WHERE id=?", memberStatus, MEMBER);
        jdbc.update("INSERT INTO merchant_member_store_grant(id,member_id,store_id,staff_id,status,version,created_at,updated_at) VALUES(?,?,?,?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", GRANT, MEMBER, STORE, staffId, grantStatus);
        if (action != null) jdbc.update("INSERT INTO merchant_member_store_action(id,member_id,store_id,action_code,created_at,updated_at) VALUES(9001,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", MEMBER, STORE, action);
    }

    private static Env staffEnv(String memberStatus, String grantStatus, Long staffId, String action) {
        T t = new T();
        try {
            applyIdentitySchema(t);
            seedStaff(t.r.f.f.db.jdbc, staffId, memberStatus, grantStatus, action);
            return new Env(t, staffService(t), t.r.f.f.db.jdbc);
        } catch (RuntimeException failure) {
            t.close();
            throw failure;
        }
    }

    /** The completion fixture stack with the kernel composed through the real staff-aware authority. */
    private static VerificationCompletionService staffService(T t) {
        var source = t.r.f.f.db.source;
        var guard = t.r.f.f.guard;
        var facts = new com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl(source, guard);
        var confirmed = new com.petplatform.schedule.biz.apiimpl.ReservationConfirmApiImpl(source, IDS::incrementAndGet, guard, facts, new com.petplatform.order.biz.apiimpl.OrderPaymentFactsApiImpl(source, guard));
        var owner = new MerchantOrderAuthorityApiImpl(source, guard);
        var orders = new com.petplatform.order.biz.apiimpl.OrderVerificationCredentialFactsApiImpl(source, guard,
                new com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl(source, guard),
                new com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl(source, guard), confirmed, facts, owner);
        var staffIdentity = new MerchantStaffIdentityApiImpl(source, guard,
                new PersistentApplicationReviewFactsReader(source, IDS::incrementAndGet), Clock.systemUTC());
        CredentialPorts.AttemptAuthority authority = com.petplatform.boot.config.VerificationAuthorityTestSupport
                .staffAwareAuthority(
                        user -> { if (!t.r.f.sessionActive.get()) throw new ApiException(CommonApiCodes.UNAUTHORIZED, "QA revoked"); },
                        owner, staffIdentity);
        var keys = new com.petplatform.verification.biz.application.CredentialProtection("qa-v1",
                java.util.Map.of("qa-v1", new byte[32]), java.util.Map.of("qa-v1", new byte[32]));
        var kernel = new VerificationCredentialService(source, IDS::incrementAndGet, guard, orders, keys,
                user -> { if (!t.r.f.sessionActive.get()) throw new ApiException(CommonApiCodes.UNAUTHORIZED, "QA revoked"); },
                authority,
                new com.petplatform.event.core.TransactionalOutboxPublisher(source, IDS::incrementAndGet, new com.fasterxml.jackson.databind.ObjectMapper()));
        return VerificationCompletionAcceptanceTest.components(t, source, guard, kernel).service();
    }

    private static CommandContext staffContext() { return new CommandContext(UUID.randomUUID().toString(), "staff-verify-qa", OperatorType.USER, Long.toString(STAFF_USER), "MINIAPP"); }
    private static CommandContext ownerContext() { return ctx(Long.toString(OWNER)); }

    @Test
    void staffMemberCompletesWithTraceableIdentity() throws Exception {
        try (var env = staffEnv("ENABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            var staff = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(staffContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            Receipt receipt = assertDoesNotThrow(() -> env.service.verify(staff));
            assertEquals("VERIFIED", receipt.resultCode());
            assertEquals(1, env.t.count("SELECT COUNT(*) FROM verification_record WHERE operator_type='MERCHANT_STAFF' AND operator_id=" + STAFF_PROFILE + " AND membership_kind='STAFF' AND operator_staff_id=" + STAFF_PROFILE));
            assertEquals(1, env.t.count("SELECT COUNT(*) FROM verification_attempt WHERE operator_type='MERCHANT_STAFF' AND membership_kind='STAFF' AND operator_staff_id=" + STAFF_PROFILE + " AND result='SUCCESS'"));
            assertEquals(1, env.t.count("SELECT COUNT(*) FROM verification_credential_command WHERE actor_type='USER' AND actor_id=" + STAFF_USER + " AND state='SUCCEEDED'"));
            // payload is a MySQL JSON column (storage-normalized), so assert parsed fields, not LIKE.
            String payload = env.t.text("SELECT payload FROM integration_event_outbox WHERE event_type='OrderVerifiedEvent.v2'");
            var event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            assertEquals("MERCHANT_STAFF", event.get("operatorType").asText());
            assertEquals("STAFF", event.get("membershipKind").asText());
            assertEquals(Long.toString(STAFF_PROFILE), event.get("operatorStaffId").asText());
            assertEquals(order, event.get("orderId").asText());
            assertEquals(1, env.t.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderVerifiedEvent.v2'"));
            assertEquals(receipt, env.service.verify(staff));
        }
    }

    /**
     * OWNER regression under the identical staff-aware switch and wiring: the OWNER-first
     * resolution of one authority bean stays USER/OWNER/null in a store seeded for staff.
     * (A fresh isolated database per half; one completed fixture order keeps its 2030 claim,
     * and the schedule snapshot invariant forbids a second future booking on that store.)
     */
    @Test
    void ownerPathStaysOwnerUnderTheSameStaffAwareSwitch() {
        try (var env = staffEnv("ENABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            var ownerCommand = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(ownerContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            assertEquals("VERIFIED", env.service.verify(ownerCommand).resultCode());
            assertEquals(1, env.t.count("SELECT COUNT(*) FROM verification_record WHERE membership_kind='OWNER' AND operator_type='USER' AND operator_id=" + OWNER + " AND operator_staff_id IS NULL"));
            assertEquals(1, env.t.count("SELECT COUNT(*) FROM verification_attempt WHERE membership_kind='OWNER' AND operator_type='USER' AND result='SUCCESS'"));
        }
    }

    @Test
    void unconfirmedDisabledUngrantedAndStaffLessGrantsAreRefusedWithoutAnyRecord() {
        try (var env = staffEnv("DISABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            var command = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(staffContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            // Invite in flight / unconfirmed: no confirmed relation for this user at all.
            env.jdbc.update("DELETE FROM merchant_member_store_action WHERE member_id=" + MEMBER);
            env.jdbc.update("DELETE FROM merchant_member_store_grant WHERE id=" + GRANT);
            env.jdbc.update("DELETE FROM merchant_member WHERE id=" + MEMBER);
            code(CommonApiCodes.NOT_FOUND, () -> env.service.verify(command));
            // Confirmed but disabled member: denied without leaking relation facts.
            env.jdbc.update("INSERT INTO merchant_member(id,merchant_id,user_id,status,version,created_at,updated_at) VALUES(" + MEMBER + "," + MERCHANT + "," + STAFF_USER + ",'DISABLED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            env.jdbc.update("INSERT INTO merchant_member_store_grant(id,member_id,store_id,staff_id,status,version,created_at,updated_at) VALUES(" + GRANT + "," + MEMBER + "," + STORE + "," + STAFF_PROFILE + ",'ENABLED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            env.jdbc.update("INSERT INTO merchant_member_store_action(id,member_id,store_id,action_code,created_at,updated_at) VALUES(9001," + MEMBER + "," + STORE + ",'" + VERIFY_ACTION + "',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            code(CommonApiCodes.FORBIDDEN, () -> env.service.verify(command));
            // Revoked member is anti-enumeration 404; action not granted is 403; store without a grant is 404.
            env.jdbc.update("UPDATE merchant_member SET status='REVOKED' WHERE id=" + MEMBER);
            code(CommonApiCodes.NOT_FOUND, () -> env.service.verify(command));
            env.jdbc.update("UPDATE merchant_member SET status='ENABLED' WHERE id=" + MEMBER);
            env.jdbc.update("DELETE FROM merchant_member_store_action WHERE member_id=" + MEMBER);
            code(CommonApiCodes.FORBIDDEN, () -> env.service.verify(command));
            env.jdbc.update("INSERT INTO merchant_member_store_action(id,member_id,store_id,action_code,created_at,updated_at) VALUES(9002," + MEMBER + "," + STORE + ",'" + VERIFY_ACTION + "',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            env.jdbc.update("UPDATE merchant_member_store_grant SET status='REVOKED' WHERE id=" + GRANT);
            code(CommonApiCodes.NOT_FOUND, () -> env.service.verify(command));
            assertEquals(0, env.t.count("SELECT COUNT(*) FROM verification_attempt"));
            assertEquals(0, env.t.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals("PENDING_SERVICE", env.t.text("SELECT order_stage FROM pet_order"));
        }
        // A grant without a staff profile reference can never produce a traceable record.
        try (var env = staffEnv("ENABLED", "ENABLED", null, VERIFY_ACTION)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            var command = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(staffContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            code(CommonApiCodes.FORBIDDEN, () -> env.service.verify(command));
            assertEquals(0, env.t.count("SELECT COUNT(*) FROM verification_record"));
        }
    }

    @Test
    void offlineStoreFailsClosedForStaffWhileOwnerLegacyCompletionContinues() {
        try (var env = staffEnv("ENABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            env.jdbc.update("UPDATE merchant_store SET status='OFFLINE' WHERE id=" + STORE);
            var staff = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(staffContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            code(CommonApiCodes.FORBIDDEN, () -> env.service.verify(staff));
            assertEquals(0, env.t.count("SELECT COUNT(*) FROM verification_record"));
            var ownerCommand = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(ownerContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            assertEquals("VERIFIED", env.service.verify(ownerCommand).resultCode());
        }
        try (var env = staffEnv("ENABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            env.jdbc.update("UPDATE merchant SET status='FROZEN' WHERE id=" + MERCHANT);
            var staff = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(staffContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            code(CommonApiCodes.FORBIDDEN, () -> env.service.verify(staff));
            assertEquals(0, env.t.count("SELECT COUNT(*) FROM verification_record"));
        }
    }

    @Test
    void revocationBeforeVerifyRefusesAndAfterVerifyPreservesHistoryButRefusesReplay() {
        // Revoke committed first: the later command re-verifies inside the lock and must fail.
        try (var env = staffEnv("ENABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            env.jdbc.update("UPDATE merchant_member SET status='REVOKED' WHERE id=" + MEMBER);
            var command = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(staffContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            code(CommonApiCodes.NOT_FOUND, () -> env.service.verify(command));
            assertEquals(0, env.t.count("SELECT COUNT(*) FROM verification_record"));
        }
        // Verify committed first: history keeps the real staff identity; replay after revocation is refused.
        try (var env = staffEnv("ENABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            var command = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(staffContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            assertEquals("VERIFIED", env.service.verify(command).resultCode());
            env.jdbc.update("UPDATE merchant_member_store_grant SET status='REVOKED' WHERE id=" + GRANT);
            assertThrows(ApiException.class, () -> env.service.verify(command));
            assertEquals(1, env.t.count("SELECT COUNT(*) FROM verification_record WHERE membership_kind='STAFF' AND operator_staff_id=" + STAFF_PROFILE));
        }
    }

    /**
     * STA-05: revocation and verification race for the shared store guard; the first committer
     * wins. Either the record exists with the original staff identity, or the verification was
     * refused - a half-applied state is never acceptable.
     */
    @Test
    void revocationAndVerificationRaceAdmitsExactlyOneConsistentOutcome() throws Exception {
        try (var env = staffEnv("ENABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION); var pool = Executors.newFixedThreadPool(2)) {
            String order = env.t.ready();
            var credential = env.t.issue(order, "INITIAL");
            var command = new com.petplatform.verification.api.command.VerificationCompletionApi.Command(staffContext(), order, Long.toString(STORE), credential.code(), credential.credentialVersion(), true);
            var start = new CountDownLatch(1);
            Future<Object> verify = pool.submit(() -> {
                start.await();
                try { return env.service.verify(command); }
                catch (ApiException e) { return e.code(); }
            });
            Future<Object> revoke = pool.submit(() -> {
                start.await();
                var template = new TransactionTemplate(new DataSourceTransactionManager(env.t.r.f.f.db.source));
                template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                template.executeWithoutResult(status -> {
                    env.t.r.f.f.guard.acquire(java.util.List.of(Long.toString(STORE)), new QueryContext("staff-revoke-qa", OperatorType.SYSTEM, null));
                    env.jdbc.update("UPDATE merchant_member SET status='REVOKED' WHERE id=" + MEMBER);
                });
                return "REVOKED";
            });
            start.countDown();
            Object verifyOutcome = verify.get(30, TimeUnit.SECONDS);
            assertEquals("REVOKED", revoke.get(30, TimeUnit.SECONDS));
            boolean verified = verifyOutcome instanceof Receipt;
            if (verified) {
                assertEquals(1, env.t.count("SELECT COUNT(*) FROM verification_record WHERE membership_kind='STAFF' AND operator_staff_id=" + STAFF_PROFILE + " AND order_id=" + order));
                assertThrows(ApiException.class, () -> env.service.verify(command));
            } else {
                assertEquals(CommonApiCodes.NOT_FOUND, verifyOutcome);
                assertEquals(0, env.t.count("SELECT COUNT(*) FROM verification_record"));
            }
            assertEquals("REVOKED", env.t.text("SELECT status FROM merchant_member WHERE id=" + MEMBER));
        }
    }
}
