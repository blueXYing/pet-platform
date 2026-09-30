package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.aftersale.api.command.AfterSaleCommandApi.*;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Independent business oracles; positive AFS/ordinary rejection/verification facts always use real APIs. */
class AfterSaleWorkflowAcceptanceTest {
    @Test void realRejectionCreatesOnePrivateCaseWithoutRefundOrReservationRelease() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.rejectedOrder();
            String asset = f.assets.upload(AfterSaleFixture.BUYER);
            var command = f.createCommand(order, List.of(asset), null);
            f.identity.asUser(AfterSaleFixture.BUYER);
            var receipt = f.aftersales.create(command);
            assertEquals("PENDING", receipt.status());
            assertEquals(receipt, f.aftersales.create(command));
            var view = f.aftersales.getCase(command.context(), receipt.afterSaleId());
            assertEquals("UNVERIFIED_POST_START", view.sourceStage());
            assertEquals(List.of(asset), view.evidence().getFirst().assetIds());
            assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_case"));
            assertEquals(receipt.afterSaleId(), f.text("SELECT CAST(current_aftersale_id AS CHAR) FROM pet_order"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals("CONFIRMED", f.text("SELECT status FROM schedule_reservation"));
            assertFalse(f.text("SELECT GROUP_CONCAT(payload) FROM integration_event_outbox").contains(command.description()));
            var changed = new Create(command.context(), order, command.typeCode(), command.demandCode(),
                    command.description() + " changed", null, List.of(asset), null);
            AfterSaleFixture.code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, () -> f.aftersales.create(changed));
        }
    }

    @Test void verifiedOrderNeedsNoPriorMerchantRejection() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.verifiedOrder();
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application"));
            var receipt = f.create(order);
            var view = f.caseView(receipt);
            assertEquals("VERIFIED", view.sourceStage());
            assertEquals("PENDING", view.status());
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }

    @Test void unverifiedWithoutRealRejectionAndForeignBuyerCannotCreate() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.unverifiedOrder();
            assertThrows(ApiException.class, () -> f.create(order));
            f.identity.asUser(AfterSaleFixture.OTHER);
            var ownContext = f.user(AfterSaleFixture.OTHER);
            var wrong = new Create(ownContext, order, "QA_QUALITY", "QA_REFUND", "Different buyer cannot submit this case", null, List.of(), null);
            assertThrows(ApiException.class, () -> f.aftersales.create(wrong));
            assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_case"));
        }
    }

    @Test void bothSevenDayWindowsIncludeDeadlineAndExcludeNextMillisecond() throws Exception {
        for (boolean verified : List.of(false, true)) {
            for (long delta : new long[] {-1, 0, 1}) {
                try (var f = new AfterSaleFixture()) {
                    String order = verified ? f.verifiedOrder() : f.rejectedOrder();
                    OffsetDateTime anchor = f.anchor(order, verified);
                    if (verified) assertTrue(anchor.isAfter(f.anchor(order, false)), "verification must differ from appointment start");
                    else assertTrue(f.now().isAfter(anchor), "merchant rejection must differ from appointment start");
                    f.at(anchor.plusDays(7).plusNanos(delta * 1_000_000).toInstant());
                    f.refreshBuyer();
                    if (delta <= 0) {
                        var created = f.create(order);
                        assertEquals("PENDING", created.status());
                        assertEquals(anchor.plusDays(7).toInstant(), OffsetDateTime.parse(f.caseView(created).deadline()).toInstant());
                    }
                    else {
                        assertThrows(ApiException.class, () -> f.create(order));
                        assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_case"));
                    }
                }
            }
        }
    }

    @Test void oneActiveCaseAndOrdinaryApplicationAreMutuallyExclusiveInBothDirections() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.verifiedOrder();
            var first = f.create(order);
            assertThrows(ApiException.class, () -> f.create(order));
            f.identity.asUser(AfterSaleFixture.BUYER);
            assertThrows(ApiException.class, () -> f.ordinary.apps.apply(f.ordinary.applyCommand(order)));
            var withdrawn = f.aftersales.withdraw(new Withdraw(f.user(AfterSaleFixture.BUYER), first.afterSaleId(), first.version()));
            assertEquals("WITHDRAWN", withdrawn.status());
            var ordinary = f.ordinary.apps.apply(f.ordinary.applyCommand(order));
            assertThrows(ApiException.class, () -> f.create(order));
            f.identity.asUser(AfterSaleFixture.OWNER);
            f.ordinary.apps.decide(f.ordinary.decision(ordinary, "REJECT"));
            assertEquals("PENDING", f.create(order).status());
            assertEquals(2, f.count("SELECT COUNT(*) FROM aftersale_case"));
        }
    }

    @Test void withdrawalFromEachActiveStatePreservesOriginalWindowAndCreatesNewId() throws Exception {
        for (String state : List.of("PENDING", "PROCESSING", "WAITING_SUPPLEMENT")) {
            try (var f = new AfterSaleFixture()) {
                String order = f.rejectedOrder();
                var original = f.create(order);
                String deadline = f.caseView(original).deadline();
                var current = original;
                if (!state.equals("PENDING")) current = f.accept(current);
                if (state.equals("WAITING_SUPPLEMENT")) current = f.requestEvidence(current, "USER", f.now().plusHours(1));
                f.at(f.now().plusMinutes(1).toInstant());
                f.identity.asUser(AfterSaleFixture.BUYER);
                var withdrawn = f.aftersales.withdraw(new Withdraw(f.user(AfterSaleFixture.BUYER), current.afterSaleId(), current.version()));
                assertEquals("WITHDRAWN", withdrawn.status());
                f.at(f.now().plusMinutes(1).toInstant());
                var next = f.create(order);
                assertNotEquals(original.afterSaleId(), next.afterSaleId());
                assertEquals(deadline, f.caseView(next).deadline());
                assertEquals(f.anchor(order, false).plusDays(7).toInstant(), OffsetDateTime.parse(deadline).toInstant());
                assertNotEquals(f.caseView(original).createdAt(), f.caseView(next).createdAt());
                assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_case WHERE active_flag=1"));
            }
        }
    }

    @Test void finalHistoryRequiresAssessmentAndDuplicateClosureDoesNotCreateAnotherDecision() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.verifiedOrder();
            var original = f.decide(f.accept(f.create(order)), "REJECT", null);
            assertThrows(ApiException.class, () -> f.create(order));
            var next = f.create(order, "The same category has a separate newly observed problem");
            f.identity.asAdmin();
            assertThrows(ApiException.class, () -> f.aftersales.accept(new Accept(f.admin(), next.afterSaleId(), next.version(), null, null)));
            long decisions = f.decisionCount();
            var close = new CloseDuplicate(f.admin(), next.afterSaleId(), next.version(), original.afterSaleId(), "The submitted facts were already finally decided");
            var closed = f.aftersales.closeDuplicate(close);
            assertEquals("CLOSED", closed.status());
            assertNull(closed.decisionId());
            assertNull(closed.refundOrderId());
            assertEquals(decisions, f.decisionCount());
            assertEquals(closed, f.aftersales.closeDuplicate(close));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
            var reopened = f.create(order, "Independent issue after a duplicate closure, still within the original window");
            f.identity.asAdmin();
            assertThrows(ApiException.class, () -> f.aftersales.closeDuplicate(new CloseDuplicate(
                    f.admin(), reopened.afterSaleId(), reopened.version(), closed.afterSaleId(), "A closure without a decision cannot become a final reference")));
            assertEquals(Set.of(original.afterSaleId()), Set.copyOf(f.caseView(reopened).priorFinalCaseIds()));
            assertEquals(decisions, f.decisionCount());
        }
    }

    @Test void newProblemWithSameCategoryCanBeAcceptedAgainstServerHistory() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.verifiedOrder();
            var old = f.decide(f.accept(f.create(order)), "OTHER", null);
            var secondPending = f.create(order, "A distinct new fact in the same issue category was discovered");
            String oneFinalVersion = f.caseView(secondPending).finalSetVersion();
            var second = f.decide(f.accept(secondPending), "RESERVICE", null);
            var next = f.create(order, "A third independently observed fact requires its own assessment");
            var view = f.caseView(next);
            assertEquals(Set.of(old.afterSaleId(), second.afterSaleId()), Set.copyOf(view.priorFinalCaseIds()));
            assertNotEquals(oneFinalVersion, view.finalSetVersion());
            f.identity.asAdmin();
            AfterSaleFixture.code(CommonApiCodes.CONFLICT, () -> f.aftersales.accept(new Accept(f.admin(), next.afterSaleId(), next.version(),
                    "This assessment was based on an obsolete single-final history", oneFinalVersion)));
            assertEquals("PENDING", f.caseView(next).status());
            f.identity.asAdmin();
            var command = new Accept(f.admin(), next.afterSaleId(), next.version(),
                    "Reviewed prior decision and independently distinct new evidence", view.finalSetVersion());
            assertEquals("PROCESSING", f.aftersales.accept(command).status());
            assertEquals(2, f.decisionCount());
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }

    @Test void withdrawnOrInvalidatedHistoryCannotBeForgedAsFinalDecision() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.rejectedOrder();
            var old = f.create(order);
            f.identity.asUser(AfterSaleFixture.BUYER);
            f.aftersales.withdraw(new Withdraw(f.user(AfterSaleFixture.BUYER), old.afterSaleId(), old.version()));
            var next = f.create(order);
            f.identity.asAdmin();
            assertThrows(ApiException.class, () -> f.aftersales.closeDuplicate(new CloseDuplicate(
                    f.admin(), next.afterSaleId(), next.version(), old.afterSaleId(), "Invalid attempt to treat withdrawal as a final decision")));
            assertEquals("PENDING", f.caseView(next).status());
            f.at(f.now().plusSeconds(20).toInstant());
            assertEquals("VERIFIED", f.verify(f.verificationCommand(order)).resultCode());
            assertEquals("INVALIDATED", f.caseView(next).status());
            var verifiedCase = f.create(order);
            assertEquals("VERIFIED", f.caseView(verifiedCase).sourceStage());
            assertTrue(f.caseView(verifiedCase).priorFinalCaseIds().isEmpty());
            f.identity.asAdmin();
            assertThrows(ApiException.class, () -> f.aftersales.closeDuplicate(new CloseDuplicate(
                    f.admin(), verifiedCase.afterSaleId(), verifiedCase.version(), next.afterSaleId(), "Real invalidation is not a nonrefund final decision")));
            assertEquals("PENDING", f.caseView(verifiedCase).status());
            assertEquals(0, f.decisionCount());
        }
    }

    @Test void duplicateReferenceMustBelongToTheSameRealOrder() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String firstOrder = f.verifiedOrder();
            var firstFinal = f.decide(f.accept(f.create(firstOrder)), "OTHER", null);
            String secondOrder = f.secondVerifiedOrder();
            assertNotEquals(firstOrder, secondOrder);
            var secondCase = f.create(secondOrder);
            assertTrue(f.caseView(secondCase).priorFinalCaseIds().isEmpty());
            f.identity.asAdmin();
            assertThrows(ApiException.class, () -> f.aftersales.closeDuplicate(new CloseDuplicate(
                    f.admin(), secondCase.afterSaleId(), secondCase.version(), firstFinal.afterSaleId(), "A genuine final decision from another order is not a valid duplicate reference")));
            assertEquals("PENDING", f.caseView(secondCase).status());
            assertEquals(1, f.decisionCount());
            assertEquals(2, f.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }

    @Test void supplementDeadlineDoesNotDependOnWhetherWorkerAlreadyRan() throws Exception {
        for (long delta : new long[] {-1, 0, 1}) {
            try (var f = new AfterSaleFixture()) {
                var current = f.accept(f.create(f.verifiedOrder()));
                var deadline = f.now().plusMinutes(5);
                var waiting = f.requestEvidence(current, "USER", deadline);
                f.at(deadline.plusNanos(delta * 1_000_000).toInstant());
                f.identity.asUser(AfterSaleFixture.BUYER);
                var command = new SubmitEvidence(f.user(AfterSaleFixture.BUYER), waiting.afterSaleId(),
                        waiting.version(), waiting.supplementRequestId(), "Additional evidence within the requested supplement round", List.of());
                if (delta < 0) assertEquals("PROCESSING", f.aftersales.submitEvidence(command).status());
                else {
                    assertThrows(ApiException.class, () -> f.aftersales.submitEvidence(command));
                    assertEquals("WAITING_SUPPLEMENT", f.caseView(waiting).status());
                    var bypass = new SubmitEvidence(f.user(AfterSaleFixture.BUYER), waiting.afterSaleId(), waiting.version(), null, command.text(), List.of());
                    assertThrows(ApiException.class, () -> f.aftersales.submitEvidence(bypass));
                }
            }
        }
    }

    @Test void evidenceMustBeOwnedReadyAndAftersalePurpose() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.verifiedOrder();
            String other = f.assets.upload(AfterSaleFixture.OTHER);
            String wrongPurpose = f.assets.upload(AfterSaleFixture.BUYER, "MERCHANT_APPLICATION_MATERIAL", UUID.randomUUID().toString());
            for (String asset : List.of(other, wrongPurpose)) {
                f.identity.asUser(AfterSaleFixture.BUYER);
                assertThrows(ApiException.class, () -> f.aftersales.create(f.createCommand(order, List.of(asset), null)));
                assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_case"));
            }
            f.assets.scannerAvailable.set(false);
            assertThrows(ApiException.class, () -> f.assets.upload(AfterSaleFixture.BUYER));
            assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_case"));
        }
    }

    @Test void realUserAndAdminRevocationBlocksOldReceiptReplay() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.verifiedOrder();
            var command = f.createCommand(order, List.of(), null);
            f.identity.asUser(AfterSaleFixture.BUYER);
            var created = f.aftersales.create(command);
            var accept = new Accept(f.admin(), created.afterSaleId(), created.version(), null, null);
            f.identity.asAdmin();
            var accepted = f.aftersales.accept(accept);
            f.identity.revokeAdmin();
            assertThrows(ApiException.class, () -> f.aftersales.accept(accept));
            f.identity.asUser(AfterSaleFixture.BUYER);
            f.identity.revokeUser(AfterSaleFixture.BUYER);
            assertThrows(ApiException.class, () -> f.aftersales.create(command));
            assertEquals("PROCESSING", accepted.status());
            assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_case"));
        }
    }

    @Test void allNonRefundFinalDecisionsAreIrreversibleAndNeverCreateMoney() throws Exception {
        for (String decision : List.of("REJECT", "RESERVICE", "OTHER")) {
            try (var f = new AfterSaleFixture()) {
                var current = f.accept(f.create(f.verifiedOrder()));
                var done = f.decide(current, decision, null);
                assertEquals("RESOLVED", done.status());
                assertNull(done.refundOrderId());
                assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
                assertThrows(ApiException.class, () -> f.decide(done, "FULL_REFUND", new BigDecimal("128.00")));
                assertEquals(1, f.decisionCount());
            }
        }
    }

    @Test void retainingReadPermissionDoesNotAuthorizeOldWriteReceiptReplay() throws Exception {
        for (boolean terminal : List.of(false, true)) try (var f = new AfterSaleFixture()) {
            var created = f.create(f.verifiedOrder());
            var command = new Accept(f.admin(), created.afterSaleId(), created.version(), null, null);
            f.identity.asAdmin();
            var accepted = f.aftersales.accept(command);
            var decision = new Decide(f.admin(), accepted.afterSaleId(), accepted.version(), "OTHER", null,
                    "Independent final resolution before operator permission revocation");
            if (terminal) f.aftersales.decide(decision);
            f.identity.revokeAdminWritesKeepRead();
            f.identity.asAdmin();
            assertNotNull(f.aftersales.getCase(f.admin(), accepted.afterSaleId()), "read remains available");
            if (terminal) assertThrows(ApiException.class, () -> f.aftersales.decide(decision));
            else assertThrows(ApiException.class, () -> f.aftersales.accept(command));
        }
    }

    @Test void differentCreateKeysCannotCommitTwoActiveCases() throws Exception {
        try(var f=new AfterSaleFixture();var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            String order=f.verifiedOrder();var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();try{return (Object)f.create(order);}catch(ApiException e){return e;}});
            var b=pool.submit(()->{start.await();try{return (Object)f.create(order);}catch(ApiException e){return e;}});
            start.countDown();Object first=a.get(30,java.util.concurrent.TimeUnit.SECONDS),second=b.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(first instanceof ApiException,second instanceof ApiException);
            assertEquals(1,f.count("SELECT COUNT(*) FROM aftersale_case WHERE active_flag=1"));
            assertEquals(1,f.count("SELECT COUNT(*) FROM order_aftersale_source_proof"));
            assertEquals(1,f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='AfterSaleCreatedEvent'"));
        }
    }

    @Test void createRacingRealVerificationPreservesTheWinningSourceStage() throws Exception {
        try (var f = new AfterSaleFixture(); var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            String order = f.rejectedOrder();
            var verifyCommand = f.verificationCommand(order);
            var createCommand = f.createCommand(order, List.of(), null);
            var start = new java.util.concurrent.CountDownLatch(1);
            var create = pool.submit(() -> { start.await(); f.identity.asUser(AfterSaleFixture.BUYER); return f.aftersales.create(createCommand); });
            var verify = pool.submit(() -> { start.await(); return f.verify(verifyCommand); });
            start.countDown();
            var created = create.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var verified = verify.get(30, java.util.concurrent.TimeUnit.SECONDS);
            // Both operations are legal: the store guard decides whether the case precedes
            // verification (and is invalidated) or originates from the completed service.
            assertEquals("VERIFIED", verified.resultCode());
            var view = f.caseView(created);
            if (view.sourceStage().equals("UNVERIFIED_POST_START")) {
                assertEquals("INVALIDATED", view.status());
                assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_verification_proof WHERE invalidated=1 AND aftersale_id="+created.afterSaleId()));
                assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_case WHERE active_flag=1"));
            } else {
                assertEquals("VERIFIED", view.sourceStage());
                assertEquals("PENDING", view.status());
                assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_verification_proof WHERE invalidated=0 AND aftersale_id IS NULL"));
                assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_case WHERE active_flag=1"));
            }
            assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_case"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM order_aftersale_source_proof"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM order_verification_commit"));
            assertEquals(created.afterSaleId(), f.text("SELECT CAST(current_aftersale_id AS CHAR) FROM pet_order"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='AfterSaleCreatedEvent'"));
            f.identity.asUser(AfterSaleFixture.BUYER);
            assertEquals(created, f.aftersales.create(createCommand), "historical creation receipt survives later verification");
            assertEquals(verified, f.verify(verifyCommand), "historical verification receipt survives later AFS binding");
        }
    }

    @Test void competingTerminalDecisionsAtTheSameVersionCommitOnlyOneOutcome() throws Exception {
        try (var f = new AfterSaleFixture(); var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var accepted = f.accept(f.create(f.verifiedOrder()));
            var start = new java.util.concurrent.CountDownLatch(1);
            var reject = pool.submit(() -> { start.await(); try { return (Object) f.decide(accepted, "REJECT", null); } catch (ApiException failure) { return failure; } });
            var refund = pool.submit(() -> { start.await(); try { return (Object) f.decide(accepted, "PARTIAL_REFUND", new BigDecimal("32.00")); } catch (ApiException failure) { return failure; } });
            start.countDown();
            Object rejected = reject.get(30, java.util.concurrent.TimeUnit.SECONDS), refunded = refund.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(rejected instanceof ApiException, refunded instanceof ApiException);
            var view = f.caseView(accepted);
            assertEquals("RESOLVED", view.status());
            assertEquals(1, f.decisionCount());
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='AfterSaleResolvedEvent'"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_case WHERE active_flag=1"));
            if (refunded instanceof Receipt receipt) {
                assertEquals("PARTIAL_REFUND", view.decisionType());
                assertNotNull(receipt.refundOrderId());
                assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
                assertEquals(1, f.count("SELECT COUNT(*) FROM refund_aftersale_proof"));
                assertEquals(1, f.count("SELECT COUNT(*) FROM order_aftersale_refund_commit"));
                assertEquals(0, new BigDecimal("32.00").compareTo(f.decimal("SELECT refund_amount FROM refund_order")));
            } else {
                assertEquals("REJECT", view.decisionType());
                assertNull(((Receipt) rejected).refundOrderId());
                assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
                assertEquals(0, f.count("SELECT COUNT(*) FROM refund_aftersale_proof"));
                assertEquals(0, f.count("SELECT COUNT(*) FROM order_aftersale_refund_commit"));
            }
            assertEquals("CONFIRMED", f.text("SELECT status FROM schedule_reservation"));
        }
    }

    @Test void frozenBuyerRetainsCaseReadingButCannotWriteOrReplayCreate() throws Exception {
        try(var f=new AfterSaleFixture()) {
            String order=f.verifiedOrder();var command=f.createCommand(order,List.of(),null);
            f.identity.asUser(AfterSaleFixture.BUYER);var created=f.aftersales.create(command);
            f.ordinary.sql("UPDATE user_account SET status='FROZEN' WHERE id="+AfterSaleFixture.BUYER);
            assertEquals(created.afterSaleId(),f.caseView(created).afterSaleId());
            assertThrows(ApiException.class,()->f.aftersales.create(command));
            assertThrows(ApiException.class,()->f.aftersales.submitEvidence(new SubmitEvidence(f.user(AfterSaleFixture.BUYER),
                    created.afterSaleId(),created.version(),null,"Frozen accounts cannot add more evidence",List.of())));
            assertEquals(1,f.count("SELECT COUNT(*) FROM aftersale_evidence_batch"));
        }
    }

    @Test void internalRequestIdsPreserve512Utf8BytesCaseAndTrailingSpace() throws Exception {
        try(var f=new AfterSaleFixture()) {
            String order=f.verifiedOrder();String prefix="界".repeat(170),key=prefix+"A ";
            assertEquals(512,key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            var context=new com.petplatform.common.CommandContext(key,"afs-byte-key",com.petplatform.common.OperatorType.USER,AfterSaleFixture.BUYER,"MINIAPP");
            var original=f.createCommand(order,List.of(),null);
            var command=new Create(context,order,original.typeCode(),original.demandCode(),original.description(),null,List.of(),null);
            f.identity.asUser(AfterSaleFixture.BUYER);var created=f.aftersales.create(command);
            assertEquals(created,f.aftersales.create(command));
            var evidence=new SubmitEvidence(context,created.afterSaleId(),created.version(),null,"First independent evidence for binary request-key checking",List.of());
            var first=f.aftersales.submitEvidence(evidence);
            assertEquals(first,f.aftersales.submitEvidence(evidence));
            var secondContext=new com.petplatform.common.CommandContext(prefix+"a ",context.traceId(),context.operatorType(),context.operatorId(),context.source());
            var second=f.aftersales.submitEvidence(new SubmitEvidence(secondContext,created.afterSaleId(),first.version(),null,
                    "Second evidence has only a differently cased request key",List.of()));
            var thirdContext=new com.petplatform.common.CommandContext(prefix+"A",context.traceId(),context.operatorType(),context.operatorId(),context.source());
            var third=f.aftersales.submitEvidence(new SubmitEvidence(thirdContext,created.afterSaleId(),second.version(),null,
                    "Third evidence removes only the trailing request-key space",List.of()));
            assertEquals("3",third.version());
            assertEquals(4,f.count("SELECT COUNT(*) FROM aftersale_evidence_batch"));
            assertEquals(3,f.count("SELECT COUNT(*) FROM aftersale_command WHERE command_namespace=CAST('aftersale.evidence.submit' AS BINARY)"));
            long before=f.count("SELECT COUNT(*) FROM aftersale_command");
            var tooLong=new com.petplatform.common.CommandContext("界".repeat(171),context.traceId(),context.operatorType(),context.operatorId(),context.source());
            AfterSaleFixture.code(CommonApiCodes.INVALID_ARGUMENT,()->f.aftersales.submitEvidence(new SubmitEvidence(tooLong,
                    created.afterSaleId(),third.version(),null,"Invalid request length must be rejected before durable admission",List.of())));
            assertEquals(before,f.count("SELECT COUNT(*) FROM aftersale_command"));
        }
    }

    @Test void successfulReplaySurvivesProviderOutageButRechecksPayloadAndCurrentAuthority() throws Exception {
        try(var f=new AfterSaleFixture()) {
            String order=f.verifiedOrder();var create=f.createCommand(order,List.of(),null);
            f.identity.asUser(AfterSaleFixture.BUYER);var created=f.aftersales.create(create);
            f.reasonPolicyAvailable.set(false);f.moderationAvailable.set(false);
            int reasonCalls=f.reasonPolicyCalls.get(),moderationCalls=f.moderationCalls.get();
            assertEquals(created,f.aftersales.create(create));
            var changed=new Create(create.context(),order,create.typeCode(),create.demandCode(),
                    create.description()+" changed",null,List.of(),null);
            AfterSaleFixture.code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->f.aftersales.create(changed));
            assertEquals(reasonCalls,f.reasonPolicyCalls.get());assertEquals(moderationCalls,f.moderationCalls.get());
            f.reasonPolicyAvailable.set(true);f.moderationAvailable.set(true);
            var accepted=f.accept(created);
            String creationScope=f.text("SELECT scope_version FROM aftersale_case");
            // Controlled change to the mutable MER resource, not a seeded AFS decision or authority fact.
            f.ordinary.sql("UPDATE merchant_store SET version=version+1 WHERE id="+AfterSaleFixture.STORE);
            String decisionScope=f.currentMerchantScope();assertNotEquals(creationScope,decisionScope);
            var decide=new Decide(f.admin(),accepted.afterSaleId(),accepted.version(),"PARTIAL_REFUND",
                    new BigDecimal("32.00"),"Reviewed partial refund after the merchant resource scope changed");
            f.identity.asAdmin();var decided=f.aftersales.decide(decide);
            assertEquals(decisionScope,f.text("SELECT scope_version FROM aftersale_decision"));
            assertEquals(creationScope,f.text("SELECT scope_version FROM aftersale_case"));
            f.reasonPolicyAvailable.set(false);f.moderationAvailable.set(false);f.fundingAvailable.set(false);
            moderationCalls=f.moderationCalls.get();
            assertEquals(decided,f.aftersales.decide(decide));
            var changedDecision=new Decide(decide.context(),decide.afterSaleId(),decide.expectedVersion(),decide.decisionType(),
                    decide.refundAmount(),decide.reason()+" changed");
            AfterSaleFixture.code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->f.aftersales.decide(changedDecision));
            assertEquals(moderationCalls,f.moderationCalls.get());
            assertEquals(1,f.decisionCount());assertEquals(1,f.count("SELECT COUNT(*) FROM refund_order"));
            f.identity.revokeAdminWritesKeepRead();f.identity.asAdmin();
            AfterSaleFixture.code(CommonApiCodes.FORBIDDEN,()->f.aftersales.decide(decide));
            f.identity.asUser(AfterSaleFixture.BUYER);f.identity.revokeUser(AfterSaleFixture.BUYER);
            AfterSaleFixture.code(CommonApiCodes.UNAUTHORIZED,()->f.aftersales.create(create));
            assertEquals(moderationCalls,f.moderationCalls.get());
        }
    }
}
