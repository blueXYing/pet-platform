package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.aftersale.api.command.AfterSaleCommandApi.*;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Test-only funding evidence exercises protocol behavior, never claims real settlement availability. */
class AfterSaleMoneyAcceptanceTest {
    @Test void unknownFundingCannotCommitDecisionRefundOrCloseTheCase() throws Exception {
        try (var f = new AfterSaleFixture()) {
            var current = f.accept(f.create(f.verifiedOrder()));
            f.fundingAvailable.set(false);
            assertThrows(ApiException.class, () -> f.decide(current, "FULL_REFUND", new BigDecimal("128.00")));
            assertEquals("PROCESSING", f.caseView(current).status());
            assertEquals(0, f.decisionCount());
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_execution"));
        }
    }

    @Test void partialAndFullBindExactOriginalPaymentAndOnlyOneBusinessRefund() throws Exception {
        for (String kind : List.of("FULL_REFUND", "PARTIAL_REFUND")) {
            try (var f = new AfterSaleFixture()) {
                String order = f.verifiedOrder();
                var current = f.accept(f.create(order));
                BigDecimal amount = new BigDecimal(kind.equals("FULL_REFUND") ? "128.00" : "32.00");
                var result = f.decide(current, kind, amount);
                assertEquals("RESOLVED", result.status());
                assertNotNull(result.refundOrderId());
                assertEquals(amount, f.decimal("SELECT refund_amount FROM refund_order"));
                assertEquals("AFTERSALE_DECISION", f.text("SELECT source_type FROM refund_order"));
                assertEquals(kind.equals("FULL_REFUND") ? "FULL" : "PARTIAL", f.text("SELECT refund_type FROM refund_order"));
                assertEquals(current.afterSaleId(), f.text("SELECT CAST(aftersale_id AS CHAR) FROM refund_order"));
                assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
                assertEquals(1, f.count("SELECT COUNT(*) FROM refund_execution"));
                assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='RefundOrderCreatedEvent.v1'"));
                assertEquals("CONFIRMED", f.text("SELECT status FROM schedule_reservation"));
                assertThrows(ApiException.class, () -> f.create(order));
            }
        }
    }

    @Test void invalidPartialAmountsNeverBecomeARefund() throws Exception {
        for (String amount : List.of("0.00", "-0.01", "128.00", "128.01", "0.001", "10000000000000000.00")) {
            try (var f = new AfterSaleFixture()) {
                var current = f.accept(f.create(f.verifiedOrder()));
                assertThrows(ApiException.class, () -> f.decide(current, "PARTIAL_REFUND", new BigDecimal(amount)));
                assertEquals(0, f.decisionCount());
                assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
                assertEquals("PROCESSING", f.caseView(current).status());
            }
        }
    }

    @Test void realVerificationWinsAndOldUnfulfilledCaseCannotRefund() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.rejectedOrder();
            var current = f.accept(f.create(order));
            var verificationCommand = f.verificationCommand(order);
            var verified = f.verify(verificationCommand);
            assertEquals("VERIFIED", verified.resultCode());
            assertEquals("INVALIDATED", f.caseView(current).status());
            assertThrows(ApiException.class, () -> f.decide(current, "PARTIAL_REFUND", new BigDecimal("32.00")));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
            var next = f.create(order);
            assertEquals("VERIFIED", f.caseView(next).sourceStage());
            assertNotEquals(current.afterSaleId(), next.afterSaleId());
            assertEquals(verified, f.verify(verificationCommand));
            assertEquals(next.afterSaleId(), f.text("SELECT CAST(current_aftersale_id AS CHAR) FROM pet_order"));
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.ordinary.source));
            tx.setIsolationLevel(2);
            tx.executeWithoutResult(ignored -> {
                f.ordinary.guard.acquire(List.of(AfterSaleFixture.STORE),
                        new com.petplatform.common.QueryContext("afs-historical-proof",com.petplatform.common.OperatorType.SYSTEM,null));
                f.verification.orders().requireCommitted(order,AfterSaleFixture.STORE,verified.verificationId(),
                        java.time.OffsetDateTime.parse(verified.verifiedAt()),f.ordinary.source);
                var historical=f.verification.aftersales().requireCommitted(order,AfterSaleFixture.STORE,
                        verified.verificationId(),java.time.OffsetDateTime.parse(verified.verifiedAt()),f.ordinary.source);
                assertEquals(current.afterSaleId(),historical.aftersaleId());
                assertEquals("INVALIDATED",historical.status());
            });
        }
    }

    @Test void realRefundCreationWinsBeforeAnyChannelCallAndBlocksVerification() throws Exception {
        try (var f = new AfterSaleFixture()) {
            String order = f.rejectedOrder();
            var code = f.verificationCommand(order);
            var current = f.accept(f.create(order));
            f.decide(current, "PARTIAL_REFUND", new BigDecimal("32.00"));
            assertThrows(ApiException.class, () -> f.verify(code));
            assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals("CONFIRMED", f.text("SELECT status FROM schedule_reservation"));
        }
    }

    @Test void independentKeysForRefundAndRealVerificationHaveOnlyOneWinner() throws Exception {
        try (var f = new AfterSaleFixture(); var pool = Executors.newFixedThreadPool(2)) {
            String order = f.rejectedOrder();
            var code = f.verificationCommand(order);
            var current = f.accept(f.create(order));
            var start = new CountDownLatch(1);
            Future<Object> verify = pool.submit(() -> { start.await(); try { return f.verify(code); } catch (ApiException failure) { return failure; } });
            Future<Object> refund = pool.submit(() -> { start.await(); try { return f.decide(current, "PARTIAL_REFUND", new BigDecimal("32.00")); } catch (ApiException failure) { return failure; } });
            start.countDown();
            Object a = verify.get(30, TimeUnit.SECONDS), b = refund.get(30, TimeUnit.SECONDS);
            assertNotEquals(a instanceof ApiException, b instanceof ApiException, "exactly one business operation wins");
            assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record") + f.count("SELECT COUNT(*) FROM refund_order"));
            if (a instanceof ApiException) assertEquals("RESOLVED", f.caseView(current).status());
            else assertEquals("INVALIDATED", f.caseView(current).status());
        }
    }

    @Test void everyRefundCommitPointRollsBackAndKeepsRequestParameterBinding() throws Exception {
        try (var f = new AfterSaleFixture()) {
            var current = f.accept(f.create(f.verifiedOrder()));
            var command = new Decide(f.admin(), current.afterSaleId(), current.version(), "PARTIAL_REFUND",
                    new BigDecimal("32.00"), "Independent accepted partial refund decision");
            for (String point : f.refundFailurePoints()) {
                f.fail(point);
                try {
                    f.identity.asAdmin();
                    assertThrows(ApiException.class, () -> f.aftersales.decide(command), point);
                } finally { f.unfail(); }
                assertEquals("PROCESSING", f.caseView(current).status(), point);
                assertEquals(0, f.decisionCount(), point);
                assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"), point);
                assertEquals(0, f.count("SELECT COUNT(*) FROM refund_execution"), point);
            }
            f.identity.asAdmin();
            var changed = new Decide(command.context(), current.afterSaleId(), current.version(), "PARTIAL_REFUND",
                    new BigDecimal("64.00"), command.reason());
            AfterSaleFixture.code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, () -> f.aftersales.decide(changed));
            assertNotNull(f.aftersales.decide(command).refundOrderId());
        }
    }

    @Test void committedAckLossRecoversSameFinalReceiptAndSingleRefund() throws Exception {
        try (var f = new AfterSaleFixture()) {
            var current = f.accept(f.create(f.verifiedOrder()));
            var command = new Decide(f.admin(), current.afterSaleId(), current.version(), "FULL_REFUND",
                    new BigDecimal("128.00"), "Independent accepted full refund decision");
            // Fixture arms the final business commit after all beforeCommit checks,
            // then throws only after the actual JDBC commit returned successfully.
            f.loseBusinessCommitAck();
            f.identity.asAdmin();
            assertThrows(ApiException.class, () -> f.aftersales.decide(command));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            var first = f.aftersales.decide(command);
            assertEquals(first, f.aftersales.decide(command));
            assertEquals(1, f.decisionCount());
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }

    @Test void firstSendRechecksFundingAndNeverDispatchesUnknownAuthority() throws Exception {
        try(var f=new AfterSaleFixture()) {
            var done=f.decide(f.accept(f.create(f.verifiedOrder())),"PARTIAL_REFUND",new BigDecimal("32.00"));
            var channel=new AfterSaleChannelFixture(f,false);f.fundingAvailable.set(false);
            assertThrows(ApiException.class,()->channel.execution.execute(done.refundOrderId(),AfterSaleFixture.STORE,false,"afs-qa","AFTERSALE_DECISION"));
            assertEquals(0,channel.sends.get());
            assertEquals(0,f.count("SELECT COUNT(*) FROM payment_refund_dispatch WHERE state='MAY_HAVE_SENT'"));
            assertEquals("CREATED",f.text("SELECT status FROM refund_order"));
            f.fundingAvailable.set(true);
            assertTrue(channel.execution.execute(done.refundOrderId(),AfterSaleFixture.STORE,false,"afs-qa","AFTERSALE_DECISION").done());
            assertEquals(1,channel.sends.get());
        }
    }

    @Test void partialChannelAckLossQueriesSameNumberWithoutNewFundingOrSecondSend() throws Exception {
        try(var f=new AfterSaleFixture()) {
            var done=f.decide(f.accept(f.create(f.verifiedOrder())),"PARTIAL_REFUND",new BigDecimal("32.00"));
            var channel=new AfterSaleChannelFixture(f,true);
            for(String wrong:List.of("APPLICATION","LATE_PAYMENT_TIMEOUT","MERCHANT_REJECT"))
                assertThrows(ApiException.class,()->channel.execution.execute(done.refundOrderId(),AfterSaleFixture.STORE,false,"afs-qa",wrong));
            assertThrows(ApiException.class,()->channel.execution.execute(done.refundOrderId(),AfterSaleFixture.STORE,false,"afs-qa"));
            assertEquals(0,channel.sends.get());
            assertTrue(channel.execution.execute(done.refundOrderId(),AfterSaleFixture.STORE,false,"afs-qa","AFTERSALE_DECISION").done());
            String number=channel.lastRequest.get().refundNo();
            assertEquals(new BigDecimal("32.00"),channel.lastRequest.get().amount());
            assertEquals("UNKNOWN",f.text("SELECT status FROM refund_order"));
            assertEquals("CONFIRMED",f.text("SELECT status FROM schedule_reservation"));
            f.fundingAvailable.set(false);f.at(f.now().plusMinutes(2).toInstant());
            assertTrue(channel.execution.execute(done.refundOrderId(),AfterSaleFixture.STORE,true,"afs-qa","AFTERSALE_DECISION").done());
            assertEquals(1,channel.sends.get());assertEquals(1,channel.queries.get());
            assertEquals(number,channel.lastRequest.get().refundNo());
            assertEquals("SUCCESS",f.text("SELECT status FROM refund_order"));
            assertEquals("PARTIAL",f.text("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.refundType')) FROM integration_event_outbox WHERE event_type='RefundSucceededEvent.v1'"));
            assertEquals(new BigDecimal("32.00"),f.decimal("SELECT refund_amount FROM refund_order"));
        }
    }

    @Test void fullAndPartialSuccessProjectExactAmountOnceAndOnlyThenReleaseReservation() throws Exception {
        for(boolean verified:List.of(false,true))for(String kind:List.of("FULL_REFUND","PARTIAL_REFUND"))try(var f=new AfterSaleFixture()) {
            String order=verified?f.verifiedOrder():f.rejectedOrder();
            BigDecimal amount=new BigDecimal(kind.equals("FULL_REFUND")?"128.00":"32.00");
            var done=f.decide(f.accept(f.create(order)),kind,amount);
            var channel=new AfterSaleChannelFixture(f,false);
            assertTrue(channel.execution.execute(done.refundOrderId(),AfterSaleFixture.STORE,false,"afs-qa","AFTERSALE_DECISION").done());
            assertEquals("CONFIRMED",f.text("SELECT status FROM schedule_reservation"));
            assertEquals(BigDecimal.ZERO.setScale(2),f.decimal("SELECT refunded_amount FROM pet_order"));
            String originalVerification=f.text("SELECT CAST(verified_at AS CHAR) FROM pet_order");
            var event=f.ordinary.event("RefundSucceededEvent.v1");
            f.fail("schedule_reservation:UPDATE");
            try{assertThrows(ApiException.class,()->channel.projection.consume(event));}finally{f.unfail();}
            assertEquals(BigDecimal.ZERO.setScale(2),f.decimal("SELECT refunded_amount FROM pet_order"));
            assertEquals("CONFIRMED",f.text("SELECT status FROM schedule_reservation"));
            channel.projection.consume(event);channel.projection.consume(event);
            assertEquals(amount,f.decimal("SELECT refunded_amount FROM pet_order"));
            assertEquals("RELEASED",f.text("SELECT status FROM schedule_reservation"));
            assertEquals(1,f.count("SELECT COUNT(*) FROM schedule_reservation_audit WHERE action='REFUND_RELEASE'"));
            assertEquals(originalVerification,f.text("SELECT CAST(verified_at AS CHAR) FROM pet_order"));
            assertEquals(verified?"VERIFIED":"UNVERIFIED",f.text("SELECT verification_status FROM pet_order"));
            assertEquals(1,channel.sends.get());
            // Legacy shared event subscribers must recognize the source and skip it safely.
            new com.petplatform.order.biz.apiimpl.OrderApplicationRefundProjectionConsumer(f.ordinary.source,
                    AfterSaleFixture.IDS::incrementAndGet,f.ordinary.guard,f.ordinaryOrders,f.ordinaryOrders,channel.refunds,channel.release).consume(event);
            new com.petplatform.order.biz.apiimpl.OrderMerchantRefundProjectionConsumer(f.ordinary.source,
                    AfterSaleFixture.IDS::incrementAndGet,f.ordinary.guard,
                    new com.petplatform.order.biz.apiimpl.OrderMerchantRejectFactsApiImpl(f.ordinary.source,f.ordinary.guard,f.ordinary.reservations),
                    channel.refunds,channel.release).consume(event);
            new com.petplatform.order.biz.apiimpl.OrderLateRefundProjectionConsumer(f.ordinary.source,
                    AfterSaleFixture.IDS::incrementAndGet,f.ordinary.guard,f.ordinary.t.r.f.f.reservationExpiry,channel.refunds).consume(event);
            assertEquals(amount,f.decimal("SELECT refunded_amount FROM pet_order"));
        }
    }

    @Test void historicalWithdrawnCaseDoesNotCancelOrdinaryApprovalAfterRealVerification() throws Exception {
        try(var f=new AfterSaleFixture()) {
            String order=f.rejectedOrder();var current=f.create(order);
            f.identity.asUser(AfterSaleFixture.BUYER);
            f.aftersales.withdraw(new Withdraw(f.user(AfterSaleFixture.BUYER),current.afterSaleId(),current.version()));
            var application=f.ordinary.apps.apply(f.ordinary.applyCommand(order));
            f.identity.asUser(AfterSaleFixture.OWNER);
            var approved=f.ordinary.apps.decide(f.ordinary.decision(application,"APPROVE"));
            var verified=f.verify(f.verificationCommand(order));
            assertEquals("VERIFIED",verified.resultCode());
            assertEquals("WITHDRAWN",f.caseView(current).status());
            assertThrows(ApiException.class,()->f.create(order));
            String refund=f.ordinary.apps.createApproved(f.ordinary.create(approved));
            assertNotNull(refund);
            assertEquals("MERCHANT_APPROVED",f.text("SELECT source_type FROM refund_order"));
            assertEquals(new BigDecimal("128.00"),f.decimal("SELECT refund_amount FROM refund_order"));
            assertEquals(0,f.decisionCount());
        }
    }
}
