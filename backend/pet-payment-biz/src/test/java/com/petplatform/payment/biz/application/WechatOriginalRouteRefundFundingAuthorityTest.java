package com.petplatform.payment.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.PaymentSuccessFact;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingCheck;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingEvidence;
import com.petplatform.payment.api.query.RefundFundingEvidenceChecks;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Ruling 32 authority: evidence derives only from the guarded payment success fact. */
class WechatOriginalRouteRefundFundingAuthorityTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-13T08:00:00.000Z");
    private static final QueryContext SYSTEM = new QueryContext("qa-funding", OperatorType.SYSTEM, null);

    private static FundingCheck check(String phase, String type, String amount) {
        return new FundingCheck("101", "202", "303", "404", "wx-trade-1", "505", "606", "707",
                "808", "909", "111", type, new BigDecimal(amount), new BigDecimal("128.00"), "CNY",
                phase, "FIRST_SEND".equals(phase) ? "121" : null,
                "FIRST_SEND".equals(phase) ? "131" : null, "FIRST_SEND".equals(phase) ? "0" : null);
    }

    private static PaymentSuccessFact fact() {
        return new PaymentSuccessFact("202", "303", "101", "707", "606", "505", "wx-trade-1",
                new BigDecimal("128.00"), OffsetDateTime.parse("2026-10-01T02:03:04.000Z"), "404", "CNY");
    }

    private static WechatOriginalRouteRefundFundingAuthority authority() {
        return new WechatOriginalRouteRefundFundingAuthority((paymentId, orderId, storeId, context) -> fact(), () -> NOW);
    }

    @Test void decisionCommitEvidenceIsAllowedUnsettledAndFullyBoundToTheCheck() {
        var check = check("DECISION_COMMIT", "PARTIAL", "32.00");
        var evidence = authority().requireForDecision(check, SYSTEM);
        assertEquals("ALLOWED", evidence.eligibility());
        assertEquals("UNSETTLED", evidence.settlementState());
        assertEquals(new BigDecimal("32.00"), evidence.authorizedRefundAmount());
        assertEquals(RefundFundingEvidenceChecks.hash(check), evidence.requestBindingSha256());
        assertEquals("WECHAT_ORIGINAL_ROUTE:" + RefundFundingEvidenceChecks.hash(check), evidence.evidenceId());
        assertEquals("trade:wx-trade-1", evidence.authorityEvidenceRef());
        assertDoesNotThrow(() -> RefundFundingEvidenceChecks.requireAllowed(check, evidence, NOW));
        assertDoesNotThrow(() -> RefundFundingEvidenceChecks.requireAllowed(check, evidence, NOW.plusHours(23)));
        assertThrows(ApiException.class, () -> RefundFundingEvidenceChecks.requireAllowed(check, evidence, NOW.plusHours(25)));
    }

    @Test void firstSendExtendsOnlyTheEvidenceTheDecisionCommitted() {
        var decision = check("DECISION_COMMIT", "FULL", "128.00");
        var committed = authority().requireForDecision(decision, SYSTEM).evidenceId();
        var send = check("FIRST_SEND", "FULL", "128.00");
        var evidence = authority().requireForFirstSend(send, committed, SYSTEM);
        assertDoesNotThrow(() -> RefundFundingEvidenceChecks.requireAllowed(send, evidence, NOW));
        // A foreign authority id, a missing commitment or another decision's evidence is never extended.
        assertThrows(ApiException.class, () -> authority().requireForFirstSend(send, "QA_ONLY:other", SYSTEM));
        assertThrows(ApiException.class, () -> authority().requireForFirstSend(send, null, SYSTEM));
        var otherDecision = new FundingCheck("101", "202", "303", "404", "wx-trade-1", "505", "606", "707",
                "818", "919", "111", "FULL", new BigDecimal("128.00"), new BigDecimal("128.00"), "CNY",
                "DECISION_COMMIT", null, null, null);
        var otherCommitted = authority().requireForDecision(otherDecision, SYSTEM).evidenceId();
        assertNotEquals(committed, otherCommitted);
        assertThrows(ApiException.class, () -> authority().requireForFirstSend(send, otherCommitted, SYSTEM));
    }

    @Test void amountBoundsFollowTheSharedKernelChecks() {
        // FULL must equal the paid amount; PARTIAL must stay strictly below it.
        assertThrows(ApiException.class, () -> authority().requireForDecision(check("DECISION_COMMIT", "FULL", "127.99"), SYSTEM));
        assertThrows(ApiException.class, () -> authority().requireForDecision(check("DECISION_COMMIT", "PARTIAL", "128.00"), SYSTEM));
        assertThrows(ApiException.class, () -> authority().requireForDecision(check("DECISION_COMMIT", "PARTIAL", "0.00"), SYSTEM));
        assertDoesNotThrow(() -> authority().requireForDecision(check("DECISION_COMMIT", "FULL", "128.00"), SYSTEM));
    }

    @Test void divergentPaymentFactsOrPhasesOrActorsAreRejected() {
        var authority = new WechatOriginalRouteRefundFundingAuthority((paymentId, orderId, storeId, context) -> {
            if (!"202".equals(paymentId)) throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "gone");
            return fact();
        }, () -> NOW);
        assertDoesNotThrow(() -> authority.requireForDecision(check("DECISION_COMMIT", "PARTIAL", "32.00"), SYSTEM));
        var paidDrift = new WechatOriginalRouteRefundFundingAuthority((paymentId, orderId, storeId, context) ->
                new PaymentSuccessFact("202", "303", "101", "707", "606", "505", "wx-trade-1",
                        new BigDecimal("127.00"), OffsetDateTime.parse("2026-10-01T02:03:04.000Z"), "404", "CNY"), () -> NOW);
        assertThrows(ApiException.class, () -> paidDrift.requireForDecision(check("DECISION_COMMIT", "PARTIAL", "32.00"), SYSTEM));
        var tradeDrift = new WechatOriginalRouteRefundFundingAuthority((paymentId, orderId, storeId, context) ->
                new PaymentSuccessFact("202", "303", "101", "707", "606", "505", "wx-trade-OTHER",
                        new BigDecimal("128.00"), OffsetDateTime.parse("2026-10-01T02:03:04.000Z"), "404", "CNY"), () -> NOW);
        assertThrows(ApiException.class, () -> tradeDrift.requireForDecision(check("DECISION_COMMIT", "PARTIAL", "32.00"), SYSTEM));
        assertThrows(ApiException.class, () -> authority.requireForDecision(check("DECISION_COMMIT", "PARTIAL", "32.00"),
                new QueryContext("qa-funding", OperatorType.USER, "505")));
        // DECISION_COMMIT checks never carry refund order identity; FIRST_SEND always must.
        var withOrder = new FundingCheck("101", "202", "303", "404", "wx-trade-1", "505", "606", "707",
                "808", "909", "111", "PARTIAL", new BigDecimal("32.00"), new BigDecimal("128.00"), "CNY",
                "DECISION_COMMIT", "121", "131", "0");
        assertThrows(ApiException.class, () -> authority.requireForDecision(withOrder, SYSTEM));
        var withoutOrder = new FundingCheck("101", "202", "303", "404", "wx-trade-1", "505", "606", "707",
                "808", "909", "111", "PARTIAL", new BigDecimal("32.00"), new BigDecimal("128.00"), "CNY",
                "FIRST_SEND", null, null, null);
        assertThrows(ApiException.class, () -> authority.requireForFirstSend(withoutOrder, "WECHAT_ORIGINAL_ROUTE:x", SYSTEM));
    }
}
