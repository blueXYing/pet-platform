package com.petplatform.payment.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
import com.petplatform.order.api.query.OrderMerchantRejectFactsApi;
import com.petplatform.order.api.query.OrderLatePaymentFactsApi;
import com.petplatform.order.api.query.OrderRefundApplicationFactsApi;
import com.petplatform.payment.api.command.PaymentRefundApi;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundProgress;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundQuery;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundSubmitCommand;
import com.petplatform.payment.api.dto.PaymentRefundTypes.CoordinationState;
import com.petplatform.payment.api.dto.PaymentSuccessFact;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentRefundStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentRefundStore.Dispatch;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundProtocol;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One durable channel submission per business refund. A MAY_HAVE_SENT row is committed before
 * network I/O; every later invocation queries the original refund number.
 */
public final class PaymentRefundService implements PaymentRefundApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final com.fasterxml.jackson.databind.ObjectMapper FUNDING_JSON=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private final DataSource source;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final OrderLatePaymentFactsApi orders;
    private final OrderMerchantRejectFactsApi merchantOrders;
    private final OrderRefundApplicationFactsApi applicationOrders;
    private final RefundApplicationApprovalFactsApi applicationApprovals;
    private final com.petplatform.order.api.query.OrderAfterSaleRefundFactsApi aftersaleOrders;
    private final com.petplatform.aftersale.api.query.AfterSaleRefundFactsApi aftersaleDecisions;
    private final com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi funding;
    private final PaymentSuccessFactsApi paymentFacts;
    private final RefundExecutionFactsApi refundFacts;
    private final PaymentRefundChannel channel;
    private final Settings settings;
    private final Clock clock;
    private final PaymentFoundationStore payments;
    private final PaymentRefundStore refunds;
    private final TransactionTemplate tx;

    /** Channel time zone must be explicitly confirmed; the protocol's 14-digit time has no zone. */
    public record Settings(String requestIp, String notifyUrl, ZoneId channelTimeZone) {
        public Settings {
            if (requestIp == null || requestIp.isBlank() || channelTimeZone == null)
                throw new IllegalArgumentException("refund channel settings incomplete");
        }
        @Override public String toString() { return "Settings[redacted]"; }
    }

    public PaymentRefundService(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, OrderLatePaymentFactsApi orders,
            PaymentSuccessFactsApi paymentFacts, RefundExecutionFactsApi refundFacts,
            PaymentRefundChannel channel, Settings settings, Clock clock) {
        this(source,ids,guard,orders,paymentFacts,refundFacts,channel,settings,clock,null);
    }
    public PaymentRefundService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            OrderLatePaymentFactsApi orders,PaymentSuccessFactsApi paymentFacts,RefundExecutionFactsApi refundFacts,
            PaymentRefundChannel channel,Settings settings,Clock clock,OrderMerchantRejectFactsApi merchantOrders) {
        this(source, ids, guard, orders, paymentFacts, refundFacts, channel, settings, clock,
                merchantOrders, null, null);
    }
    public PaymentRefundService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            OrderLatePaymentFactsApi orders,PaymentSuccessFactsApi paymentFacts,RefundExecutionFactsApi refundFacts,
            PaymentRefundChannel channel,Settings settings,Clock clock,OrderMerchantRejectFactsApi merchantOrders,
            OrderRefundApplicationFactsApi applicationOrders,
            RefundApplicationApprovalFactsApi applicationApprovals) {
        this(source,ids,guard,orders,paymentFacts,refundFacts,channel,settings,clock,merchantOrders,applicationOrders,applicationApprovals,null,null,null);
    }
    public PaymentRefundService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            OrderLatePaymentFactsApi orders,PaymentSuccessFactsApi paymentFacts,RefundExecutionFactsApi refundFacts,
            PaymentRefundChannel channel,Settings settings,Clock clock,OrderMerchantRejectFactsApi merchantOrders,
            OrderRefundApplicationFactsApi applicationOrders,RefundApplicationApprovalFactsApi applicationApprovals,
            com.petplatform.order.api.query.OrderAfterSaleRefundFactsApi aftersaleOrders,
            com.petplatform.aftersale.api.query.AfterSaleRefundFactsApi aftersaleDecisions,
            com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi funding) {
        if((aftersaleOrders==null)!=(aftersaleDecisions==null)||funding!=null&&aftersaleOrders==null)throw new IllegalArgumentException("After-sale funding dependencies incomplete");
        this.aftersaleOrders=aftersaleOrders;this.aftersaleDecisions=aftersaleDecisions;this.funding=funding;
        this.merchantOrders=merchantOrders;
        this.applicationOrders=applicationOrders;
        this.applicationApprovals=applicationApprovals;
        this.source = Objects.requireNonNull(source);
        this.ids = Objects.requireNonNull(ids);
        this.guard = Objects.requireNonNull(guard);
        this.orders = Objects.requireNonNull(orders);
        this.paymentFacts = Objects.requireNonNull(paymentFacts);
        this.refundFacts = Objects.requireNonNull(refundFacts);
        this.channel = Objects.requireNonNull(channel);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.payments = new PaymentFoundationStore(source);
        this.refunds = new PaymentRefundStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    @Override public ChannelRefundProgress submitRefund(ChannelRefundSubmitCommand command) {
        noOuterTransaction();
        Identity identity = validate(command == null ? null : command.context(),
                command == null ? null : command.refundOrderId(),
                command == null ? null : command.refundNo(),
                command == null ? null : command.paymentId(),
                command == null ? null : command.storeId(),
                command == null ? -1 : command.bindingVersion(), "TASK:REFUND_SUBMIT:");
        try {
            SendDecision decision = tx.execute(status -> prepare(identity));
            if (decision == null) throw unavailable();
            if (!decision.firstSubmission()) {
                if (decision.progress().state() == CoordinationState.QUERY_PENDING)
                    return queryExisting(identity);
                return decision.progress();
            }
            ChannelRefundProgress beforeSend = tx.execute(status -> preflight(identity));
            if (beforeSend == null) throw unavailable();
            if (beforeSend.state() != CoordinationState.QUERY_PENDING) return beforeSend;
            // The immutable MAY_HAVE_SENT fact has already committed. An exception is UNKNOWN.
            PaymentRefundChannel.VerifiedResult verified;
            try {
                verified = channel.submit(decision.request());
            } catch (RuntimeException unknown) {
                return tx.execute(status -> delayUnknown(identity));
            }
            return tx.execute(status -> persist(identity, verified, "SUBMIT"));
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    @Override public ChannelRefundProgress queryRefund(ChannelRefundQuery query) {
        noOuterTransaction();
        Identity identity = validate(query == null ? null : query.context(),
                query == null ? null : query.refundOrderId(),
                query == null ? null : query.refundNo(),
                query == null ? null : query.paymentId(),
                query == null ? null : query.storeId(),
                query == null ? -1 : query.bindingVersion(), "TASK:REFUND_CHANNEL_QUERY:");
        try { return queryExisting(identity); }
        catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    private ChannelRefundProgress queryExisting(Identity identity) {
        QueryDecision decision = tx.execute(status -> loadForQuery(identity));
        if (decision == null) throw unavailable();
        if (decision.request() == null) return decision.progress();
        PaymentRefundChannel.VerifiedResult verified;
        try {
            verified = channel.query(decision.request());
        } catch (RuntimeException unknown) {
            return tx.execute(status -> delayUnknown(identity));
        }
        return tx.execute(status -> persist(identity, verified, "QUERY"));
    }

    private ChannelRefundProgress delayUnknown(Identity input) {
        refunds.session();
        QueryContext context = system(input.context());
        guard.acquire(List.of(input.storeId()), context);
        guard.requireHeld(input.storeId(), source);
        Dispatch row = refunds.byRefund(input.refundOrderIdLong(), true);
        if (row == null) throw unavailable();
        validateIdentity(input, row);
        if (!"MAY_HAVE_SENT".equals(row.state()) && !"QUERY_PENDING".equals(row.state()))
            return progress(row);
        OffsetDateTime now = refunds.now();
        OffsetDateTime next = now.plusSeconds(30);
        if (refunds.markPending(row, next, now) != 1) throw unavailable();
        return new ChannelRefundProgress(input.refundOrderId(), input.refundNo(),
                CoordinationState.QUERY_PENDING, next);
    }

    /** Recheck a just-observed reversal before the sole network submission. */
    private ChannelRefundProgress preflight(Identity input) {
        payments.session();
        refunds.session();
        QueryContext context = system(input.context());
        guard.acquire(List.of(input.storeId()), context);
        guard.requireHeld(input.storeId(), source);
        Dispatch row = refunds.byRefund(input.refundOrderIdLong(), true);
        if (row == null) throw unavailable();
        validateIdentity(input, row);
        if (!"MAY_HAVE_SENT".equals(row.state())) return progress(row);
        var payment = payments.byId(input.paymentId(), true);
        if (payment == null || payment.orderId() != row.orderId()
                || payment.storeId() != row.storeId()) throw unavailable();
        if (!"PAID".equals(payment.status())
                || !"OBSERVED".equals(payment.dispatchState())) {
            OffsetDateTime now = refunds.now();
            if (refunds.markReconciliation(row, now) != 1) throw unavailable();
            return new ChannelRefundProgress(input.refundOrderId(), input.refundNo(),
                    CoordinationState.RECONCILIATION_REQUIRED, null);
        }
        RefundExecutionFact business = refundFacts.requireForChannel(input.refundOrderId(),
                input.storeId(), context);
        validateBusinessAgainstDispatch(row, business);
        var origin = requireOrigin(Long.toString(row.orderId()), input, business, context);
        var paid = paymentFacts.requireSucceeded(Long.toString(input.paymentId()),
                Long.toString(row.orderId()), input.storeId(), context);
        validateBinding(input, payment, origin, paid, business);
        if (!"CREATED".equals(business.status()) && !"PROCESSING".equals(business.status())
                && !"UNKNOWN".equals(business.status())) throw unavailable();
        // Preparing the durable dispatch is not permission to send after its evidence expires.
        // Check the current database time after all other preflight facts have been revalidated.
        validateFunding(row,business,context,true);
        return progress(row);
    }

    private SendDecision prepare(Identity input) {
        payments.session();
        refunds.session();
        QueryContext context = system(input.context());
        guard.acquire(List.of(input.storeId()), context);
        guard.requireHeld(input.storeId(), source);
        var hint = payments.byId(input.paymentId(), false);
        if (hint == null || hint.orderId() <= 0 || hint.storeId() != input.storeIdLong())
            throw unavailable();
        Dispatch previous = refunds.byRefund(input.refundOrderIdLong(), true);
        if (previous != null) {
            // After our own refund, PAYMENT may observe REFUND/REVOKED and cease offering the
            // pre-refund success getter. The immutable dispatch remains the query authority.
            validateIdentity(input, previous);
            return new SendDecision(false, progress(previous), null);
        }
        PaymentSuccessFact paid = paymentFacts.requireSucceeded(Long.toString(input.paymentId()),
                Long.toString(hint.orderId()), input.storeId(), context);
        RefundExecutionFact business = refundFacts.requireForChannel(input.refundOrderId(),
                input.storeId(), context);
        OrderRefundOriginFact order = requireOrigin(Long.toString(hint.orderId()), input,
                business, context);
        validateBinding(input, hint, order, paid, business);
        if (!"CREATED".equals(business.status()) && !"PROCESSING".equals(business.status())
                && !"UNKNOWN".equals(business.status())) throw unavailable();
        com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingCheck fundingCheck=null;
        com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingEvidence fundingEvidence=null;
        String committedEvidence=null;
        if("AFTERSALE_DECISION".equals(business.sourceType())){
            if(funding==null)throw unavailable();
            var decision=aftersaleDecisions.requireCreated(business.sourceBizId(),business.sourceDecisionId(),business.refundOrderId(),business.storeId(),context,source).decision();
            var check=new com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingCheck(business.orderId(),business.paymentId(),business.paymentNo(),business.paymentSuccessEventId(),business.channelTradeNo(),business.userId(),business.merchantId(),business.storeId(),business.sourceBizId(),business.sourceDecisionId(),decision.commandId(),business.refundType(),business.refundAmount(),business.originalPaidAmount(),business.currency(),"FIRST_SEND",business.refundOrderId(),business.refundNo(),Long.toString(business.bindingVersion()));
            var evidence=funding.requireForFirstSend(check,decision.funding().evidenceId(),context);
            fundingCheck=check;fundingEvidence=evidence;committedEvidence=decision.funding().evidenceId();
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void beforeCommit(boolean readOnly){if(readOnly)throw unavailable();com.petplatform.payment.api.query.RefundFundingEvidenceChecks.requireAllowed(check,evidence,refunds.now());}});
        }
        OffsetDateTime now = refunds.now();
        if(fundingCheck!=null)com.petplatform.payment.api.query.RefundFundingEvidenceChecks.requireAllowed(fundingCheck,fundingEvidence,now);
        LocalDateTime channelTime = now.atZoneSameInstant(settings.channelTimeZone())
                .toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
        PaymentRefundChannel.RefundRequest request = new PaymentRefundChannel.RefundRequest(
                hint.merchantNo(), hint.termNo(), input.refundNo(), business.refundAmount(),
                Long.toString(hint.no()), hint.tradeNo(), channelTime,
                settings.requestIp(), settings.notifyUrl(), business.sourceType());
        String sha = requestHash(request);
        if(fundingCheck!=null)refunds.insertFunding(input.refundOrderIdLong(),committedEvidence,fundingCheck,fundingEvidence,sha,now);
        OffsetDateTime queryNotBefore = now.plusSeconds(30);
        Dispatch created = new Dispatch(input.refundOrderIdLong(), input.refundNoLong(),
                input.paymentId(), hint.no(), hint.orderId(), hint.storeId(), hint.merchantNo(),
                hint.termNo(), hint.tradeNo(), id(business.paymentSuccessEventId()),
                business.originalPaidAmount(), business.refundAmount(), business.currency(),
                business.bindingVersion(), input.refundNo(), sha, "MAY_HAVE_SENT",
                PaymentRefundStore.utc(now), PaymentRefundStore.utc(queryNotBefore), null,
                null, null, 0, PaymentRefundStore.utc(now.truncatedTo(ChronoUnit.SECONDS)));
        refunds.insert(created);
        return new SendDecision(true, progress(created), request);
    }

    private QueryDecision loadForQuery(Identity input) {
        payments.session();
        refunds.session();
        QueryContext context = system(input.context());
        guard.acquire(List.of(input.storeId()), context);
        guard.requireHeld(input.storeId(), source);
        Dispatch row = refunds.byRefund(input.refundOrderIdLong(), true);
        if (row == null) throw unavailable();
        validateIdentity(input, row);
        ChannelRefundProgress progress = progress(row);
        if (progress.state() != CoordinationState.QUERY_PENDING
                || refunds.now().isBefore(progress.queryNotBefore()))
            return new QueryDecision(progress, null);
        RefundExecutionFact business = refundFacts.requireForChannel(input.refundOrderId(),
                input.storeId(), context);
        validateBusinessAgainstDispatch(row, business);
        validateFunding(row,business,context,false);
        // A persisted dispatch can still be queried after PAYMENT observed its own REFUND notice.
        // Never re-run first-send admission here; doing so would erase genuine refund success.
        LocalDateTime queryTime = refunds.now().atZoneSameInstant(settings.channelTimeZone())
                .toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
        var request = new PaymentRefundChannel.RefundRequest(row.merchantNo(), row.termNo(),
                input.refundNo(), row.refundAmount(), Long.toString(row.paymentNo()),
                row.originalChannelTradeNo(), queryTime, settings.requestIp(), settings.notifyUrl(),
                business.sourceType());
        return new QueryDecision(progress, request);
    }

    /** First-send evidence is source-specific; event IDs are never substituted for application IDs. */
    private void validateFunding(Dispatch row,RefundExecutionFact f,QueryContext context,boolean firstSend){
        if(!"AFTERSALE_DECISION".equals(f.sourceType()))return;
        if(aftersaleDecisions==null)throw unavailable();var a=aftersaleDecisions.requireCreated(f.sourceBizId(),f.sourceDecisionId(),f.refundOrderId(),f.storeId(),context,source);
        var p=refunds.funding(row.refundOrderId());if(p==null||a==null||!a.decision().funding().evidenceId().equals(p.committedEvidenceId)||!row.requestSha256().equals(p.requestSha256))throw unavailable();
        try{
            var c=FUNDING_JSON.readValue(p.checkJson,com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingCheck.class);
            var e=FUNDING_JSON.readValue(p.evidenceJson,com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingEvidence.class);
            var expected=new com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingCheck(f.orderId(),f.paymentId(),f.paymentNo(),f.paymentSuccessEventId(),f.channelTradeNo(),f.userId(),f.merchantId(),f.storeId(),f.sourceBizId(),f.sourceDecisionId(),a.decision().commandId(),f.refundType(),f.refundAmount(),f.originalPaidAmount(),f.currency(),"FIRST_SEND",f.refundOrderId(),f.refundNo(),Long.toString(f.bindingVersion()));
            if(!com.petplatform.payment.api.query.RefundFundingEvidenceChecks.hash(c).equals(com.petplatform.payment.api.query.RefundFundingEvidenceChecks.hash(expected))||!p.createdAt.equals(row.mayHaveSentAt()))throw unavailable();
            com.petplatform.payment.api.query.RefundFundingEvidenceChecks.requireAllowed(c,e,p.createdAt.atOffset(ZoneOffset.UTC));
            if(firstSend)com.petplatform.payment.api.query.RefundFundingEvidenceChecks.requireAllowed(c,e,refunds.now());
        }catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw unavailable();}
    }
    private OrderRefundOriginFact requireOrigin(String orderId, Identity input,
            RefundExecutionFact business, QueryContext context) {
        validateSourceShape(business);
        OrderRefundOriginFact origin;
        switch (business.sourceType()) {
            case "LATE_PAYMENT_TIMEOUT" -> origin = OrderRefundOriginFact.late(
                    orders.requireLatePayment(orderId, Long.toString(input.paymentId()),
                            input.storeId(), context), business.sourceEventId(), business.refundOrderId());
            case "MERCHANT_REJECT_ORDER" -> {
                if (merchantOrders == null) throw unavailable();
                origin = merchantOrders.requireRejected(orderId, Long.toString(input.paymentId()),
                        input.storeId(), context);
            }
            case "MERCHANT_APPROVED", "MERCHANT_TIMEOUT_AUTO" -> {
                if (applicationOrders == null || applicationApprovals == null) throw unavailable();
                origin = applicationOrders.requireApprovedRefund(orderId,
                        Long.toString(input.paymentId()), input.storeId(), context);
                var approval = applicationApprovals.requireApproved(business.sourceBizId(),
                        business.sourceDecisionId(), input.storeId(), context);
                validateApproval(origin, business, approval);
            }
            case "AFTERSALE_DECISION" -> {
                if(aftersaleOrders==null||aftersaleDecisions==null)throw unavailable();
                var o=aftersaleOrders.requireDecidedRefund(orderId,Long.toString(input.paymentId()),input.storeId(),context);
                var a=aftersaleDecisions.requireCreated(business.sourceBizId(),business.sourceDecisionId(),business.refundOrderId(),input.storeId(),context,source);
                if(o==null||a==null||!business.refundType().equals(o.refundType())||business.refundAmount().compareTo(o.refundAmount())!=0||!business.refundType().equals(a.decision().refundType())||business.refundAmount().compareTo(a.decision().refundAmount())!=0||!a.refundNo().equals(business.refundNo())||!a.createdEventId().equals(business.createdEventId())||!a.createdAt().isEqual(business.createdAt())||!o.fundingEvidenceId().equals(a.decision().funding().evidenceId()))throw unavailable();
                origin=o.origin();
            }
            default -> throw unavailable();
        }
        if (origin == null || !business.sourceType().equals(origin.sourceType())
                || !Objects.equals(business.sourceEventId(), origin.sourceEventId())
                || !Objects.equals(business.sourceBizId(), origin.sourceBizId())
                || !Objects.equals(business.sourceDecisionId(), origin.sourceDecisionId())
                || !business.refundOrderId().equals(origin.refundOrderId())) throw unavailable();
        return origin;
    }

    private static void validateApproval(OrderRefundOriginFact order, RefundExecutionFact business,
            RefundApplicationApprovalFactsApi.ApprovalFact approval) {
        if (order == null || approval == null || approval.application() == null) throw unavailable();
        var application = approval.application();
        boolean manual = "MERCHANT_APPROVED".equals(business.sourceType());
        if (!business.sourceType().equals(approval.sourceType())
                || !(manual ? "APPROVED" : "AUTO_APPROVED").equals(application.status())
                || !(manual ? "USER" : "SYSTEM").equals(approval.operatorType())
                || manual && !validFactId(approval.operatorId())
                || !manual && approval.operatorId() != null
                || !business.sourceBizId().equals(application.applicationId())
                || !business.sourceDecisionId().equals(approval.decisionId())
                || !approval.decisionId().equals(application.decisionId())
                || !business.refundOrderId().equals(application.refundOrderId())
                || !business.orderId().equals(application.orderId())
                || !business.storeId().equals(application.storeId())
                || !business.merchantId().equals(application.merchantId())
                || !business.userId().equals(application.userId())
                || !Objects.equals(order.reservationId(), application.reservationId())
                || !validFactId(application.reservationId())
                || !business.paymentId().equals(application.paymentId())
                || !business.paymentNo().equals(application.paymentNo())
                || !business.paymentSuccessEventId().equals(application.paymentSuccessEventId())
                || !business.channelTradeNo().equals(application.channelTradeNo())
                || application.paidAmount() == null || business.originalPaidAmount() == null
                || business.originalPaidAmount().compareTo(application.paidAmount()) != 0
                || application.paidAt() == null || business.paidAt() == null
                || !business.paidAt().isEqual(application.paidAt())
                || approval.decidedAt() == null || business.createdAt() == null
                || business.createdAt().isBefore(approval.decidedAt())
                || application.createdAt() == null || approval.decidedAt().isBefore(application.createdAt())
                || !validFactId(approval.commandId()) || !validFactId(approval.eventId())) throw unavailable();
    }

    private static void validateSourceShape(RefundExecutionFact business) {
        if (business == null || business.sourceType() == null) throw unavailable();
        switch (business.sourceType()) {
            case "LATE_PAYMENT_TIMEOUT" -> {
                if (!validFactId(business.sourceEventId())
                        || !business.sourceEventId().equals(business.lateEventId())
                        || business.sourceBizId() != null || business.sourceDecisionId() != null)
                    throw unavailable();
            }
            case "MERCHANT_REJECT_ORDER" -> {
                if (!validFactId(business.sourceEventId()) || business.lateEventId() != null
                        || business.sourceBizId() != null || business.sourceDecisionId() != null)
                    throw unavailable();
            }
            case "MERCHANT_APPROVED", "MERCHANT_TIMEOUT_AUTO", "AFTERSALE_DECISION" -> {
                if (business.sourceEventId() != null || business.lateEventId() != null
                        || !validFactId(business.sourceBizId()) || !validFactId(business.sourceDecisionId()))
                    throw unavailable();
            }
            default -> throw unavailable();
        }
    }

    private static boolean validFactId(String value) {
        try { IDS.fromApi(value); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    private ChannelRefundProgress persist(Identity input,
            PaymentRefundChannel.VerifiedResult result, String sourceType) {
        payments.session();
        refunds.session();
        QueryContext context = system(input.context());
        guard.acquire(List.of(input.storeId()), context);
        guard.requireHeld(input.storeId(), this.source);
        Dispatch row = refunds.byRefund(input.refundOrderIdLong(), true);
        if (row == null) throw unavailable();
        validateIdentity(input, row);
        if ("VERIFIED_SUCCESS".equals(row.state())) return progress(row);
        if ("RECONCILIATION_REQUIRED".equals(row.state())) return progress(row);
        if (result == null || result.responseSha256() == null
                || !result.responseSha256().matches("[0-9a-f]{64}")
                || !input.refundNo().equals(result.refundNo())
                || result.requestedCents() != cents(row.refundAmount())) throw unavailable();
        OffsetDateTime now = refunds.now();
        BigDecimal actual = result.actualRefundCents() == null ? null
                : BigDecimal.valueOf(result.actualRefundCents(), 2);
        OffsetDateTime resultAt = result.channelTime() == null ? null
                : channelTime(result.channelTime());
        refunds.recordReceipt(nextId(), row.refundOrderId(), sourceType,
                result.responseSha256(), row.originalChannelTradeNo(), row.channelRequestNo(),
                result.channelRefundNo(), result.state(), actual,
                resultAt == null ? null : PaymentRefundStore.utc(resultAt), now);
        if ("SUCCESS".equals(result.state())) {
            if (result.channelRefundNo() == null || result.channelRefundNo().isBlank()
                    || actual == null || actual.compareTo(row.refundAmount()) != 0
                    || resultAt == null || resultAt.isAfter(now)) {
                if (refunds.markReconciliation(row, now) != 1) throw unavailable();
                return new ChannelRefundProgress(input.refundOrderId(), input.refundNo(),
                        CoordinationState.RECONCILIATION_REQUIRED, null);
            }
            if (refunds.markSuccess(row, result.channelRefundNo(),
                    result.responseSha256(), resultAt, now) != 1) throw unavailable();
            return new ChannelRefundProgress(input.refundOrderId(), input.refundNo(),
                    CoordinationState.VERIFIED_SUCCESS, null);
        }
        // No official signed terminal-failure guarantee is configured in this tranche.
        OffsetDateTime next = now.plusSeconds(30);
        if (refunds.markPending(row, next, now) != 1) throw unavailable();
        return new ChannelRefundProgress(input.refundOrderId(), input.refundNo(),
                CoordinationState.QUERY_PENDING, next);
    }

    private static void validateBinding(Identity input, PaymentFoundationStore.Row row,
            OrderRefundOriginFact order, PaymentSuccessFact paid, RefundExecutionFact business) {
        if(business==null)throw unavailable();
        com.petplatform.payment.api.query.RefundFundingEvidenceChecks.amount(business.refundType(),business.refundAmount(),business.originalPaidAmount());
        if(!"AFTERSALE_DECISION".equals(business.sourceType())&&!"FULL".equals(business.refundType()))throw unavailable();
        String paymentId = Long.toString(row.id());
        String orderId = Long.toString(row.orderId());
        if (order == null || paid == null || business == null || row.no() <= 0
                || !"PAID".equals(row.status()) || !"OBSERVED".equals(row.dispatchState())
                || !"LAKALA_WECHAT".equals(row.channel()) || !"CNY".equals(row.currency())
                || row.merchantNo() == null || row.termNo() == null || row.tradeNo() == null
                || !input.refundOrderId().equals(business.refundOrderId())
                || !input.refundNo().equals(business.refundNo())
                || !paymentId.equals(business.paymentId())
                || !Long.toString(row.no()).equals(business.paymentNo())
                || !orderId.equals(business.orderId())
                || !orderId.equals(order.orderId())
                || !paymentId.equals(order.paymentId())
                || !paymentId.equals(paid.paymentId())
                || !orderId.equals(paid.orderId())
                || !input.storeId().equals(business.storeId())
                || !input.storeId().equals(order.storeId())
                || !input.storeId().equals(paid.storeId())
                || !Long.toString(row.merchantId()).equals(business.merchantId())
                || !business.merchantId().equals(order.merchantId())
                || !business.merchantId().equals(paid.merchantId())
                || !Long.toString(row.userId()).equals(business.userId())
                || !business.userId().equals(order.userId())
                || !business.userId().equals(paid.userId())
                || !business.paymentSuccessEventId().equals(order.paymentSuccessEventId())
                || !business.paymentSuccessEventId().equals(paid.successEventId())
                || !business.channelTradeNo().equals(order.channelTradeNo())
                || !business.channelTradeNo().equals(paid.channelTradeNo())
                || !business.channelTradeNo().equals(row.tradeNo())
                || !"CNY".equals(business.currency())
                || !"CNY".equals(paid.currency())
                || business.refundAmount() == null || business.originalPaidAmount() == null
                || business.refundAmount().signum() <= 0
                || business.originalPaidAmount().compareTo(order.channelPaidAmount()) != 0
                || business.originalPaidAmount().compareTo(paid.paidAmount()) != 0
                || row.paidAmount() == null
                || business.originalPaidAmount().compareTo(row.paidAmount()) != 0
                || business.paidAt() == null || order.channelPaidAt() == null
                || paid.paidAt() == null || !business.paidAt().isEqual(order.channelPaidAt())
                || !business.paidAt().isEqual(paid.paidAt())
                || business.bindingVersion() != input.bindingVersion()) throw unavailable();
    }

    private static void validateIdentity(Identity input, Dispatch row) {
        if (row.refundOrderId() != input.refundOrderIdLong()
                || row.refundNo() != input.refundNoLong()
                || row.paymentId() != input.paymentId()
                || row.storeId() != input.storeIdLong()
                || row.refundBindingVersion() != input.bindingVersion()
                || !row.channelRequestNo().equals(input.refundNo())) throw unavailable();
    }

    private static void validateBusinessAgainstDispatch(Dispatch row, RefundExecutionFact business) {
        validateSourceShape(business);
        if (business == null
                || !Long.toString(row.refundOrderId()).equals(business.refundOrderId())
                || !Long.toString(row.refundNo()).equals(business.refundNo())
                || !Long.toString(row.paymentId()).equals(business.paymentId())
                || !Long.toString(row.paymentNo()).equals(business.paymentNo())
                || !Long.toString(row.orderId()).equals(business.orderId())
                || !Long.toString(row.storeId()).equals(business.storeId())
                || !Long.toString(row.paymentSuccessEventId()).equals(business.paymentSuccessEventId())
                || !row.originalChannelTradeNo().equals(business.channelTradeNo())
                || row.refundAmount().compareTo(business.refundAmount()) != 0
                || row.originalPaidAmount().compareTo(business.originalPaidAmount()) != 0
                || !row.currency().equals(business.currency())
                || row.refundBindingVersion() != business.bindingVersion()) throw unavailable();
    }

    private static ChannelRefundProgress progress(Dispatch row) {
        CoordinationState state = switch (row.state()) {
            case "MAY_HAVE_SENT", "QUERY_PENDING" -> CoordinationState.QUERY_PENDING;
            case "VERIFIED_SUCCESS" -> CoordinationState.VERIFIED_SUCCESS;
            case "VERIFIED_TERMINAL_FAILURE" -> CoordinationState.VERIFIED_TERMINAL_FAILURE;
            case "RECONCILIATION_REQUIRED" -> CoordinationState.RECONCILIATION_REQUIRED;
            default -> throw unavailable();
        };
        return new ChannelRefundProgress(Long.toString(row.refundOrderId()),
                Long.toString(row.refundNo()), state,
                state == CoordinationState.QUERY_PENDING
                        ? row.queryNotBefore().atOffset(ZoneOffset.UTC) : null);
    }

    private String requestHash(PaymentRefundChannel.RefundRequest request) {
        try {
            var input = new LakalaRefundProtocol.RefundInput(request.requestTime(),
                    request.merchantNo(), request.termNo(), request.refundNo(), request.amount(),
                    request.paymentNo(), request.originalChannelTradeNo(), request.requestIp(),
                    request.reason(), request.notifyUrl());
            byte[] body = LakalaRefundProtocol.prepareRefund(input).rawBody();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (RuntimeException | java.security.NoSuchAlgorithmException invalid) {
            throw unavailable();
        }
    }

    private OffsetDateTime channelTime(LocalDateTime value) {
        var offsets = settings.channelTimeZone().getRules().getValidOffsets(value);
        if (offsets.size() != 1) throw unavailable();
        return value.atOffset(offsets.getFirst()).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static Identity validate(CommandContext context, String refundOrderId,
            String refundNo, String paymentId, String storeId, long bindingVersion,
            String keyPrefix) {
        if (context == null || context.operatorType() != OperatorType.SYSTEM
                || bindingVersion != 0) throw new ApiException(CommonApiCodes.FORBIDDEN,
                "refund coordination actor forbidden");
        try { PublicContractChecks.requireCommandRequestId(context); }
        catch (RuntimeException invalid) { throw invalid(); }
        long refundOrder = id(refundOrderId);
        long refund = id(refundNo);
        long payment = id(paymentId);
        long store = id(storeId);
        if (!context.requestId().equals(keyPrefix + refundOrderId + ":0")) throw invalid();
        return new Identity(context, refundOrderId, refundNo, payment, storeId,
                refundOrder, refund, store, bindingVersion);
    }

    private static QueryContext system(CommandContext context) {
        return new QueryContext(context.traceId(), OperatorType.SYSTEM, context.operatorId());
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable();
        return value;
    }

    private static long cents(BigDecimal amount) {
        try { return amount.movePointRight(2).longValueExact(); }
        catch (RuntimeException invalid) { throw unavailable(); }
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
    }

    private static long id(String value) {
        try { return IDS.fromApi(value); }
        catch (RuntimeException invalid) { throw invalid(); }
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid refund coordination command");
    }

    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "refund channel state requires reconciliation");
    }

    private record Identity(CommandContext context, String refundOrderId, String refundNo,
            long paymentId, String storeId, long refundOrderIdLong, long refundNoLong,
            long storeIdLong, long bindingVersion) {}
    private record SendDecision(boolean firstSubmission, ChannelRefundProgress progress,
            PaymentRefundChannel.RefundRequest request) {}
    private record QueryDecision(ChannelRefundProgress progress,
            PaymentRefundChannel.RefundRequest request) {}
}
