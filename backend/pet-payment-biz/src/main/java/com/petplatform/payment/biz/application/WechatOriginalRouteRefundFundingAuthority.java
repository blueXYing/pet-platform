package com.petplatform.payment.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.PaymentSuccessFact;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi;
import com.petplatform.payment.api.query.RefundFundingEvidenceChecks;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Ruling 32 (2026-10-13) production funding authority: after-sale refunds ride the original
 * WeChat Pay route of the succeeded payment; V1 keeps no freeze/split/withdrawal lifecycle, so
 * the channel-held paid amount is the refundable balance. Eligibility is derived only from the
 * guarded PAYMENT success fact inside the caller's transaction; no network I/O happens here.
 */
public final class WechatOriginalRouteRefundFundingAuthority implements RefundFundingEligibilityFactsApi {
    /** Bound to the submit task family's bounded retry horizon; refreshed at every first send. */
    static final Duration EVIDENCE_VALIDITY = Duration.ofHours(24);
    static final String AUTHORITY_ID = "WECHAT_PAY_ORIGINAL_ROUTE";
    static final String CONTRACT_VERSION = "wechat-original-route-v1";
    static final String POLICY_VERSION = "ruling32-original-route-v1";
    private static final String EVIDENCE_PREFIX = "WECHAT_ORIGINAL_ROUTE:";
    private final PaymentSuccessFactsApi payments;
    private final DatabaseClock clock;

    /** Production clock: PAYMENT database time, so kernel rechecks never see future evidence. */
    public WechatOriginalRouteRefundFundingAuthority(DataSource source, PaymentSuccessFactsApi payments) {
        this(payments, new PaymentFoundationStore(Objects.requireNonNull(source))::now);
    }

    WechatOriginalRouteRefundFundingAuthority(PaymentSuccessFactsApi payments, DatabaseClock clock) {
        this.payments = Objects.requireNonNull(payments);
        this.clock = Objects.requireNonNull(clock);
    }

    @FunctionalInterface interface DatabaseClock { OffsetDateTime now(); }

    @Override public FundingEvidence requireForDecision(FundingCheck check, QueryContext context) {
        requireSystem(context);
        requireShape(check, "DECISION_COMMIT");
        var paid = requireSucceededPayment(check, context);
        RefundFundingEvidenceChecks.amount(check.refundType(), check.requestedRefundAmount(), paid.paidAmount());
        return evidence(check, paid, clock.now());
    }

    @Override public FundingEvidence requireForFirstSend(FundingCheck check, String committedEvidenceId, QueryContext context) {
        requireSystem(context);
        requireShape(check, "FIRST_SEND");
        // The dispatch may only extend the exact evidence the decision committed.
        if (committedEvidenceId == null || !committedEvidenceId.equals(EVIDENCE_PREFIX
                + RefundFundingEvidenceChecks.hash(decisionPhase(check))))
            throw unavailable();
        var paid = requireSucceededPayment(check, context);
        RefundFundingEvidenceChecks.amount(check.refundType(), check.requestedRefundAmount(), paid.paidAmount());
        return evidence(check, paid, clock.now());
    }

    private FundingEvidence evidence(FundingCheck check, PaymentSuccessFact paid, OffsetDateTime at) {
        String binding = RefundFundingEvidenceChecks.hash(check);
        return new FundingEvidence(EVIDENCE_PREFIX + binding, AUTHORITY_ID, CONTRACT_VERSION,
                paid.paymentId(), Long.toString(paid.paidAt().toInstant().toEpochMilli()),
                "trade:" + paid.channelTradeNo(), binding, "UNSETTLED", "ALLOWED", POLICY_VERSION,
                check.requestedRefundAmount(), "CNY", at, at, at.plus(EVIDENCE_VALIDITY),
                "pay:" + paid.paymentId() + ":" + paid.paidAt().toInstant().toEpochMilli());
    }

    private PaymentSuccessFact requireSucceededPayment(FundingCheck check, QueryContext context) {
        var paid = payments.requireSucceeded(check.paymentId(), check.orderId(), check.storeId(), context);
        if (paid == null || !"CNY".equals(paid.currency()) || !paid.currency().equals(check.currency())
                || !paid.paymentNo().equals(check.paymentNo())
                || !paid.successEventId().equals(check.paymentSuccessEventId())
                || !paid.channelTradeNo().equals(check.channelTradeNo())
                || !paid.userId().equals(check.userId()) || !paid.merchantId().equals(check.merchantId())
                || !paid.storeId().equals(check.storeId()) || !paid.orderId().equals(check.orderId())
                || !paid.paymentId().equals(check.paymentId())
                || paid.paidAmount().compareTo(originalPaid(check)) != 0)
            throw unavailable();
        return paid;
    }

    private static void requireShape(FundingCheck check, String phase) {
        if (check == null || !phase.equals(check.phase()) || !"CNY".equals(check.currency())) throw unavailable();
        try { RefundFundingEvidenceChecks.hash(check); }
        catch (RuntimeException invalid) { throw unavailable(); }
        if ("FIRST_SEND".equals(phase)) {
            if (check.refundOrderId() == null || check.refundNo() == null || !"0".equals(check.bindingVersion()))
                throw unavailable();
        } else if (check.refundOrderId() != null || check.refundNo() != null || check.bindingVersion() != null)
            throw unavailable();
    }

    /** The decision-phase twin of a first-send check: same business facts, no refund order identity. */
    private static FundingCheck decisionPhase(FundingCheck check) {
        return new FundingCheck(check.orderId(), check.paymentId(), check.paymentNo(),
                check.paymentSuccessEventId(), check.channelTradeNo(), check.userId(), check.merchantId(),
                check.storeId(), check.caseId(), check.decisionId(), check.commandId(), check.refundType(),
                check.requestedRefundAmount(), check.originalPaidAmount(), check.currency(),
                "DECISION_COMMIT", null, null, null);
    }

    private static BigDecimal originalPaid(FundingCheck check) {
        return check.originalPaidAmount() == null ? BigDecimal.ZERO.negate() : check.originalPaidAmount();
    }

    private static void requireSystem(QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.SYSTEM) throw unavailable();
    }

    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "WeChat original-route refund funding evidence unavailable");
    }
}
