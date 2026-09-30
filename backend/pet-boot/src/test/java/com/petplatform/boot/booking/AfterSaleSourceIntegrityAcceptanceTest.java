package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Corrupts only proofs created by real commands; the channel and funding authority are QA doubles. */
class AfterSaleSourceIntegrityAcceptanceTest {
    @Test void eachIndependentSourceProofMustAgreeBeforeAnyFirstSend() throws Exception {
        try (var f = new AfterSaleFixture()) {
            var done = f.decide(f.accept(f.create(f.verifiedOrder())),
                    "PARTIAL_REFUND", new BigDecimal("32.00"));
            var channel = new AfterSaleChannelFixture(f, false);
            var jdbc = f.ordinary.t.r.f.f.db.jdbc;
            long decisionId = Long.parseLong(done.decisionId());
            long orderId = Long.parseLong(done.orderId());
            long refundId = Long.parseLong(done.refundOrderId());

            byte[] decisionProof = jdbc.queryForObject(
                    "SELECT proof_cipher FROM aftersale_decision WHERE id=?", byte[].class, decisionId);
            assertNotNull(decisionProof);
            assertTrue(decisionProof.length > 0);
            byte[] damagedDecision = decisionProof.clone();
            damagedDecision[damagedDecision.length - 1] ^= 1;
            assertEquals(1, jdbc.update("UPDATE aftersale_decision SET proof_cipher=? WHERE id=?",
                    damagedDecision, decisionId));
            try {
                assertRejectedBeforeDispatch(f, channel, done.refundOrderId(), "AFS encrypted decision");
            } finally {
                assertEquals(1, jdbc.update("UPDATE aftersale_decision SET proof_cipher=? WHERE id=?",
                        decisionProof, decisionId));
            }
            assertArrayEquals(decisionProof, jdbc.queryForObject(
                    "SELECT proof_cipher FROM aftersale_decision WHERE id=?", byte[].class, decisionId));

            BigDecimal committedAmount = jdbc.queryForObject(
                    "SELECT refund_amount FROM order_aftersale_refund_commit WHERE order_id=?",
                    BigDecimal.class, orderId);
            assertEquals(new BigDecimal("32.00"), committedAmount);
            // A different, otherwise legal PARTIAL amount must not authorize this existing refund.
            assertEquals(1, jdbc.update(
                    "UPDATE order_aftersale_refund_commit SET refund_amount=? WHERE order_id=?",
                    new BigDecimal("64.00"), orderId));
            try {
                assertRejectedBeforeDispatch(f, channel, done.refundOrderId(), "ORDER amount misbinding");
            } finally {
                assertEquals(1, jdbc.update(
                        "UPDATE order_aftersale_refund_commit SET refund_amount=? WHERE order_id=?",
                        committedAmount, orderId));
            }

            String refundProof = jdbc.queryForObject(
                    "SELECT CAST(proof_json AS CHAR) FROM refund_aftersale_proof WHERE refund_order_id=?",
                    String.class, refundId);
            assertNotNull(refundProof);
            assertEquals(1, jdbc.update(
                    "UPDATE refund_aftersale_proof SET proof_json=JSON_SET(proof_json,'$.refundAmount',64.00) WHERE refund_order_id=?",
                    refundId));
            try {
                assertRejectedBeforeDispatch(f, channel, done.refundOrderId(), "REFUND proof amount misbinding");
            } finally {
                assertEquals(1, jdbc.update(
                        "UPDATE refund_aftersale_proof SET proof_json=? WHERE refund_order_id=?",
                        refundProof, refundId));
            }
            assertEquals(refundProof, jdbc.queryForObject(
                    "SELECT CAST(proof_json AS CHAR) FROM refund_aftersale_proof WHERE refund_order_id=?",
                    String.class, refundId));

            // Exact restoration of the original committed evidence recovers the same refund.
            assertTrue(channel.execution.execute(done.refundOrderId(), AfterSaleFixture.STORE,
                    false, "afs-source-integrity", "AFTERSALE_DECISION").done());
            assertEquals(1, channel.sends.get());
            assertEquals(0, channel.queries.get());
            assertEquals(done.refundOrderId(), f.text("SELECT CAST(id AS CHAR) FROM refund_order"));
            assertEquals("SUCCESS", f.text("SELECT status FROM refund_order"));
            assertEquals(new BigDecimal("32.00"), channel.lastRequest.get().amount());
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='RefundSucceededEvent.v1'"));
        }
    }

    private static void assertRejectedBeforeDispatch(AfterSaleFixture f, AfterSaleChannelFixture channel,
            String refundId, String damagedOwner) {
        var failure = assertThrows(ApiException.class, () -> channel.execution.execute(refundId,
                AfterSaleFixture.STORE, false, "afs-source-integrity", "AFTERSALE_DECISION"), damagedOwner);
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code(), damagedOwner);
        assertEquals(0, channel.sends.get(), damagedOwner);
        assertEquals(0, channel.queries.get(), damagedOwner);
        assertEquals(0, f.count("SELECT COUNT(*) FROM payment_refund_dispatch"), damagedOwner);
        assertEquals(0, f.count("SELECT COUNT(*) FROM payment_refund_funding_proof"), damagedOwner);
        assertEquals(0, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='RefundSucceededEvent.v1'"), damagedOwner);
        assertEquals("CREATED", f.text("SELECT status FROM refund_order"), damagedOwner);
        assertEquals(new BigDecimal("32.00"), f.decimal("SELECT refund_amount FROM refund_order"), damagedOwner);
        assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"), damagedOwner);
        assertEquals(1, f.count("SELECT COUNT(*) FROM refund_execution"), damagedOwner);
        assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_decision"), damagedOwner);
    }
}
