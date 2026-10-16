package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.aftersale.api.command.AfterSaleCommandApi.Decide;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.verification.api.command.VerificationCompletionApi.Command;
import com.petplatform.verification.api.command.VerificationCompletionApi.Receipt;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * QA-004 specialised CREATE_REFUND × VERIFY concurrency matrix on real MySQL, in the same
 * barrier-synchronised per-round style as {@link PayTimeoutCallbackConcurrencyMySqlTest}.
 *
 * <p>Every racing pair is serialised by the one stable SCH store-guard row lock, so each round
 * must land in exactly one legal serialization order, and whichever side wins is decided by
 * real scheduling, never by the test. The matrix pins the product hard rules on that race
 * surface: a created refund_order blocks later verification (VERIFICATION_BLOCKED_BY_REFUND)
 * while verification that committed first never vetoes an already approved refund; a current
 * unfulfilled after-sale case is invalidated by the winning verification, and the losing
 * refund decision or case creation then fails closed; identical requestIds replay to the same
 * result with no second durable effect on either side. Pure test increment: no production
 * code, no new route, no contract change.
 */
class RefundVerifyGuardConcurrencyMySqlTest {
    private static final String STORE = "710302";
    private static final String OWNER = "710300";
    private static final BigDecimal PAID = new BigDecimal("128.00");
    private static final int CREATE_RACE_ROUNDS = 6;
    private static final int AFS_RACE_ROUNDS = 4;

    /** Race 1: refund_order creation versus verification on one approved application. */
    @Test void createRefundAndVerifyRaceLandsInExactlyOneLegalOutcomeEveryRound() throws Exception {
        int resolved = 0;
        for (int round = 0; round < CREATE_RACE_ROUNDS; round++) {
            try (RefundApplicationAcceptanceTest.F f = new RefundApplicationAcceptanceTest.F()) {
                String order = f.ready(false);
                var applied = f.apps.apply(f.applyCommand(order));
                var approved = f.apps.decide(f.decision(applied, "APPROVE"));
                var credential = f.t.issue(order, "INITIAL");
                var verification = VerificationCompletionAcceptanceTest.build(f.t);
                // Terminal verify requestIds are UUID-shaped by contract (48 K1); the fixed
                // instance below is what makes the replay assertions idempotency evidence.
                var command = new Command(new CommandContext(UUID.randomUUID().toString(),
                        "qa-rv-concurrent", OperatorType.USER, OWNER, "MINIAPP"),
                        order, STORE, credential.code(), credential.credentialVersion(), true);
                var create = f.create(approved);
                CountDownLatch ready = new CountDownLatch(2);
                try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                    Future<Object> verify = pool.submit(() -> {
                        ready.countDown();
                        assertTrue(ready.await(10, TimeUnit.SECONDS));
                        try { return (Object) verification.verify(command); }
                        catch (ApiException refused) { return refused.code(); }
                    });
                    Future<String> refund = pool.submit(() -> {
                        ready.countDown();
                        assertTrue(ready.await(10, TimeUnit.SECONDS));
                        return f.apps.createApproved(create);
                    });
                    ready.await(10, TimeUnit.SECONDS);
                    String refundId = refund.get(60, TimeUnit.SECONDS);
                    Object verifyResult = verify.get(60, TimeUnit.SECONDS);
                    pool.shutdownNow();
                    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

                    // The refund side commits exactly once, whichever serialization won.
                    assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
                    assertEquals(1, f.count("SELECT COUNT(*) FROM order_refund_application_commit"));
                    assertEquals(refundId, f.text(
                            "SELECT CAST(refund_order_id AS CHAR) FROM refund_application"));
                    if (verifyResult instanceof Receipt receipt) {
                        // Verification won: the order is completed and the approved refund is
                        // still created exactly once; a first verification never vetoes it.
                        assertEquals("VERIFIED", receipt.resultCode());
                        assertEquals("COMPLETED", f.text("SELECT order_stage FROM pet_order"));
                        assertEquals("VERIFIED", f.text("SELECT verification_status FROM pet_order"));
                        assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record"));
                        assertEquals(receipt, verification.verify(command));
                        assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record"));
                    } else {
                        // The refund won: the same requestId is refused again with the same
                        // code and still no attempt side effect ever reaches the credential.
                        assertEquals("VERIFICATION_BLOCKED_BY_REFUND", verifyResult);
                        assertEquals("UNVERIFIED", f.text(
                                "SELECT verification_status FROM pet_order"));
                        assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
                        long attempts = f.count("SELECT COUNT(*) FROM verification_attempt");
                        assertEquals("VERIFICATION_BLOCKED_BY_REFUND", assertThrows(
                                ApiException.class, () -> verification.verify(command)).code());
                        assertEquals(attempts, f.count("SELECT COUNT(*) FROM verification_attempt"));
                    }
                    // Replay the creation task with the same requestId: same refund, never two.
                    assertEquals(refundId, f.apps.createApproved(create));
                    assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
                }
                resolved++;
            }
        }
        assertEquals(CREATE_RACE_ROUNDS, resolved, "every round must resolve to one legal winner");
    }

    /**
     * Race 2: verification versus the operator after-sale decision that creates the refund
     * order from the current unfulfilled case. The decision wins only while the case is still
     * pending, and then blocks verification; the verification wins by invalidating the case,
     * and then the decision must fail closed with no money moved.
     */
    @Test void verifyRacingAftersaleRefundDecisionHasExactlyOneWinnerEveryRound() throws Exception {
        int resolved = 0;
        for (int round = 0; round < AFS_RACE_ROUNDS; round++) {
            try (AfterSaleFixture f = new AfterSaleFixture()) {
                String order = f.rejectedOrder();
                var created = f.create(order);
                var accepted = f.accept(created);
                var verifyCommand = f.verificationCommand(order);
                var refundDecision = new Decide(f.admin(), accepted.afterSaleId(),
                        accepted.version(), "FULL_REFUND", PAID,
                        "Independent final decision based on reviewed evidence");
                CountDownLatch ready = new CountDownLatch(2);
                try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                    Future<Object> verify = pool.submit(() -> {
                        ready.countDown();
                        assertTrue(ready.await(10, TimeUnit.SECONDS));
                        try { return (Object) f.verify(verifyCommand); }
                        catch (ApiException refused) { return refused.code(); }
                    });
                    Future<Object> decide = pool.submit(() -> {
                        ready.countDown();
                        assertTrue(ready.await(10, TimeUnit.SECONDS));
                        f.identity.asAdmin();
                        try { return (Object) f.aftersales.decide(refundDecision); }
                        catch (ApiException refused) { return refused.code(); }
                    });
                    ready.await(10, TimeUnit.SECONDS);
                    Object verifyResult = verify.get(90, TimeUnit.SECONDS);
                    Object decideResult = decide.get(90, TimeUnit.SECONDS);
                    pool.shutdownNow();
                    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

                    assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_case"));
                    if (verifyResult instanceof Receipt receipt) {
                        // Verification won: the current unfulfilled case is invalidated and the
                        // refund decision is refused with no decision and no money moved.
                        assertEquals("VERIFIED", receipt.resultCode());
                        assertEquals("INVALIDATED", f.text("SELECT status FROM aftersale_case"));
                        assertEquals(0, f.count("SELECT active_flag FROM aftersale_case"));
                        assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_verification_proof"
                                + " WHERE invalidated=1 AND aftersale_id=" + created.afterSaleId()));
                        assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_decision"));
                        assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
                        assertEquals("AFTERSALE_ALREADY_INVALIDATED", decideResult);
                        assertEquals(receipt, f.verify(verifyCommand));
                        f.identity.asAdmin();
                        assertEquals("AFTERSALE_ALREADY_INVALIDATED", assertThrows(ApiException.class,
                                () -> f.aftersales.decide(refundDecision)).code());
                    } else {
                        // The decision won: refund_order exists and now blocks verification.
                        assertEquals("AFTERSALE_DECISION", f.text("SELECT source_type FROM refund_order"));
                        assertEquals("RESOLVED", f.text("SELECT status FROM aftersale_case"));
                        assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_decision"));
                        assertEquals(1, f.count("SELECT COUNT(*) FROM refund_aftersale_proof"));
                        assertEquals(1, f.count("SELECT COUNT(*) FROM order_aftersale_refund_commit"));
                        assertEquals("VERIFICATION_BLOCKED_BY_REFUND", verifyResult);
                        assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
                        assertEquals("UNVERIFIED", f.text("SELECT verification_status FROM pet_order"));
                        f.identity.asAdmin();
                        assertEquals(decideResult, f.aftersales.decide(refundDecision));
                        assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
                        assertEquals("VERIFICATION_BLOCKED_BY_REFUND", assertThrows(ApiException.class,
                                () -> f.verify(verifyCommand)).code());
                        assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
                    }
                    // The scripted channel never ran in this matrix: the reservation stays held.
                    assertEquals("CONFIRMED", f.text("SELECT status FROM schedule_reservation"));
                }
                resolved++;
            }
        }
        assertEquals(AFS_RACE_ROUNDS, resolved, "every round must resolve to one legal winner");
    }

    /**
     * Race 3: verification versus after-sale case creation on the unfulfilled window. Both
     * actions are individually legal, so the store guard decides: a case that committed first
     * is invalidated by the winning verification, while a case created after it reads the
     * VERIFIED source stage and stays a lawful post-service case. Either way exactly one case
     * and one verification record exist, and replays of both requestIds stay silent.
     */
    @Test void createCaseRacingVerificationLandsInOneLegalBindingEveryRound() throws Exception {
        int resolved = 0;
        for (int round = 0; round < AFS_RACE_ROUNDS; round++) {
            try (AfterSaleFixture f = new AfterSaleFixture()) {
                String order = f.rejectedOrder();
                var verifyCommand = f.verificationCommand(order);
                var createCommand = f.createCommand(order, List.of(), null);
                CountDownLatch ready = new CountDownLatch(2);
                try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                    Future<Object> verify = pool.submit(() -> {
                        ready.countDown();
                        assertTrue(ready.await(10, TimeUnit.SECONDS));
                        try { return (Object) f.verify(verifyCommand); }
                        catch (ApiException refused) { return refused.code(); }
                    });
                    Future<com.petplatform.aftersale.api.command.AfterSaleCommandApi.Receipt> create =
                            pool.submit(() -> {
                                ready.countDown();
                                assertTrue(ready.await(10, TimeUnit.SECONDS));
                                f.identity.asUser(AfterSaleFixture.BUYER);
                                return f.aftersales.create(createCommand);
                            });
                    ready.await(10, TimeUnit.SECONDS);
                    Object verifyResult = verify.get(90, TimeUnit.SECONDS);
                    var created = create.get(90, TimeUnit.SECONDS);
                    pool.shutdownNow();
                    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

                    // Verification is lawful next to a current unfulfilled case: it always wins
                    // its own commit and the case can never duplicate.
                    assertTrue(verifyResult instanceof Receipt, "verification always commits");
                    assertEquals("VERIFIED", ((Receipt) verifyResult).resultCode());
                    assertEquals("COMPLETED", f.text("SELECT order_stage FROM pet_order"));
                    assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record"));
                    assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_case"));
                    assertEquals(1, f.count("SELECT COUNT(*) FROM order_aftersale_source_proof"));
                    assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
                    var view = f.caseView(created);
                    if ("UNVERIFIED_POST_START".equals(view.sourceStage())) {
                        // The case committed before verification: it is the invalidated one.
                        assertEquals("INVALIDATED", view.status());
                        assertEquals(0, f.count("SELECT active_flag FROM aftersale_case"));
                        assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_verification_proof"
                                + " WHERE invalidated=1 AND aftersale_id=" + created.afterSaleId()));
                    } else {
                        // The case read the committed verification: a post-service case.
                        assertEquals("VERIFIED", view.sourceStage());
                        assertEquals("PENDING", view.status());
                        assertEquals(1, f.count("SELECT active_flag FROM aftersale_case"));
                        assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_verification_proof"
                                + " WHERE invalidated=1"));
                    }
                    // Both requestIds replay to their original receipts with no second effect.
                    assertEquals(created, f.aftersales.create(createCommand));
                    assertEquals(((Receipt) verifyResult), f.verify(verifyCommand));
                    assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_case"));
                    assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record"));
                    assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox"
                            + " WHERE event_type='AfterSaleCreatedEvent'"));
                }
                resolved++;
            }
        }
        assertEquals(AFS_RACE_ROUNDS, resolved, "every round must resolve to one legal binding");
    }

    /** Deterministic anchors for both serial orders, independent of race scheduling luck. */
    @Test void bothSerialOrdersOfCreationAndVerificationStayLegalWithStableReplays() {
        // Creation first: the existing refund_order blocks verification with the exact code.
        try (RefundApplicationAcceptanceTest.F f = new RefundApplicationAcceptanceTest.F()) {
            String order = f.ready(false);
            var applied = f.apps.apply(f.applyCommand(order));
            var approved = f.apps.decide(f.decision(applied, "APPROVE"));
            // The credential must exist before the refund: reading and issuing are blocked by
            // the same refund fence afterwards, which this anchor also pins explicitly.
            var credential = f.t.issue(order, "INITIAL");
            var verification = VerificationCompletionAcceptanceTest.build(f.t);
            String refund = f.apps.createApproved(f.create(approved));
            assertEquals("VERIFICATION_BLOCKED_BY_REFUND", assertThrows(ApiException.class,
                    () -> f.t.read(order)).code());
            var command = new Command(new CommandContext(UUID.randomUUID().toString(),
                    "qa-rv-concurrent", OperatorType.USER, OWNER, "MINIAPP"),
                    order, STORE, credential.code(), credential.credentialVersion(), true);
            assertEquals("VERIFICATION_BLOCKED_BY_REFUND", assertThrows(ApiException.class,
                    () -> verification.verify(command)).code());
            assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals("VERIFICATION_BLOCKED_BY_REFUND", assertThrows(ApiException.class,
                    () -> verification.verify(command)).code());
            assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals(refund, f.apps.createApproved(f.create(approved)));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
        }
        // Verification first: the completed order never vetoes the already approved refund.
        try (RefundApplicationAcceptanceTest.F f = new RefundApplicationAcceptanceTest.F()) {
            String order = f.ready(false);
            var applied = f.apps.apply(f.applyCommand(order));
            var credential = f.t.issue(order, "INITIAL");
            var verification = VerificationCompletionAcceptanceTest.build(f.t);
            var command = new Command(new CommandContext(UUID.randomUUID().toString(),
                    "qa-rv-concurrent", OperatorType.USER, OWNER, "MINIAPP"),
                    order, STORE, credential.code(), credential.credentialVersion(), true);
            assertEquals("VERIFIED", verification.verify(command).resultCode());
            var approved = f.apps.decide(f.decision(applied, "APPROVE"));
            String refund = f.apps.createApproved(f.create(approved));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals("COMPLETED", f.text("SELECT order_stage FROM pet_order"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals(refund, f.apps.createApproved(f.create(approved)));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }
}
