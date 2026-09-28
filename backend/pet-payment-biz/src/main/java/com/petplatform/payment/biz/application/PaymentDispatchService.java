package com.petplatform.payment.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.dto.OrderPaymentFact;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.payment.api.command.PaymentExpiryCoordinationApi;
import com.petplatform.payment.api.command.PaymentInitiationApi;
import com.petplatform.payment.api.command.PaymentPreparationApi;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.InitiatedPayment;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.WechatPayParameters;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparePaymentCommand;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.PaymentIdentity;
import com.petplatform.user.api.query.PaymentIdentityApi;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable PAYMENT dispatch and expiry coordination. Never performs network I/O in a DB transaction. */
public final class PaymentDispatchService implements PaymentInitiationApi, PaymentExpiryCoordinationApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private final DataSource source;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final OrderPaymentFactsApi orders;
    private final PaymentIdentityApi identities;
    private final PaymentPreparationApi preparation;
    private final PaymentChannel channel;
    private final PaymentNotificationService notifications;
    private final Settings settings;
    private final Clock clock;
    private final PaymentFoundationStore payments;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public record Settings(String outOrgCode, String subject, String requestIp, String notifyUrl,
            ZoneId channelTimeZone, SecretKey parameterKey, boolean terminalCloseCapability) {
        public Settings {
            if (blank(outOrgCode) || blank(subject) || blank(requestIp) || blank(notifyUrl)
                    || channelTimeZone == null || parameterKey == null
                    || !"AES".equalsIgnoreCase(parameterKey.getAlgorithm())
                    || parameterKey.getEncoded() == null || parameterKey.getEncoded().length != 32) {
                throw new IllegalArgumentException("payment dispatch settings are incomplete");
            }
        }
        @Override public String toString() { return "Settings[redacted]"; }
    }

    public PaymentDispatchService(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,
            OrderPaymentFactsApi orders, PaymentIdentityApi identities,
            PaymentPreparationApi preparation, PaymentChannel channel,
            PaymentNotificationService notifications, Settings settings, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.ids = Objects.requireNonNull(ids);
        this.guard = Objects.requireNonNull(guard);
        this.orders = Objects.requireNonNull(orders);
        this.identities = Objects.requireNonNull(identities);
        this.preparation = Objects.requireNonNull(preparation);
        this.channel = Objects.requireNonNull(channel);
        this.notifications = Objects.requireNonNull(notifications);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.payments = new PaymentFoundationStore(source);
        this.jdbc = payments.jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    @Override public InitiatedPayment create(PreparePaymentCommand command) {
        try { return createInternal(command); }
        catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    private InitiatedPayment createInternal(PreparePaymentCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null || command.context().operatorType() != OperatorType.USER)
            throw forbidden();
        try { PublicContractChecks.requireCommandRequestId(command.context()); }
        catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid payment requestId");
        }
        long orderId = id(command.orderId());
        var prepared = preparation.prepare(command);
        QueryContext actor = new QueryContext(command.context().traceId(), OperatorType.USER,
                command.context().operatorId());
        String storeId = orders.locateStore(command.orderId(), actor);
        Start start = tx.execute(status -> start(command, prepared.paymentId(), orderId, storeId, actor));
        if (start instanceof Replay replay) return replay.payment();
        if (start instanceof Recovery recovery) {
            queryForRecovery(recovery);
            throw unavailable();
        }
        Plan plan = (Plan) start;
        PaymentChannel.VerifiedPreorder verified;
        try {
            verified = channel.submitPreorder(new LakalaProtocol.PreorderInput(settings.outOrgCode(),
                    plan.requestTime(), plan.merchantNo(), plan.termNo(), plan.paymentNo(),
                    plan.amount(), settings.subject(), plan.subAppId(), plan.openId(),
                    settings.requestIp(), settings.notifyUrl(), plan.timeoutMinutes()),
                    nonce());
        } catch (RuntimeException uncertain) {
            markUnknown(plan.paymentId(), storeId, actor);
            throw unavailable();
        }
        // Commit the verified channel completion independently of current USER eligibility.
        // A freeze or deadline crossing must not erase proof that the request reached Lakala.
        try {
            tx.execute(status -> { recordPreorder(plan, verified, storeId); return null; });
        } catch (RuntimeException uncertainCommit) {
            // A lost commit ACK may mean parameters were already saved. Read the original
            // payment under its guard once; never submit another preorder.
            Start recovered = tx.execute(status -> start(command, prepared.paymentId(),
                    orderId, storeId, actor));
            if (recovered instanceof Replay replay) return replay.payment();
            if (recovered instanceof Recovery recovery) queryForRecovery(recovery);
            throw unavailable();
        }
        Start current = tx.execute(status -> start(command, prepared.paymentId(), orderId,
                storeId, actor));
        if (current instanceof Replay replay) return replay.payment();
        throw unavailable();
    }

    private Start start(PreparePaymentCommand command, String paymentId, long orderId,
            String storeId, QueryContext actor) {
        payments.session();
        guard.acquire(List.of(storeId), actor);
        guard.requireHeld(storeId, source);
        OrderPaymentFact order = orders.readForPayment(command.orderId(), storeId, actor);
        PaymentFoundationStore.Row payment = payments.byId(id(paymentId), true);
        Dispatch dispatch = dispatch(id(paymentId));
        requireBinding(payment, dispatch, order, orderId, storeId);
        if (!("INIT".equals(payment.status()) || "PAYING".equals(payment.status()))
                || payment.successEventId() != null || payment.paidAt() != null
                || payment.paidAmount() != null) throw unavailable();
        if (dispatch.fencedAt() != null || "RECONCILIATION_REQUIRED".equals(payment.dispatchState()))
            throw unavailable();
        PaymentIdentity identity = identities.requireCurrentPaymentIdentity(order.userId(),
                payment.subAppId(), storeId, actor);
        String identityHmac = identityHmac(identity);
        if (dispatch.identityHmac() == null && !"PREPARED".equals(dispatch.state())) {
            if (dispatch.tradeRequestDate() == null) throw unavailable();
            return new Recovery(payment.id(), payment.no(), payment.merchantNo(),
                    payment.termNo(), payment.amount(), dispatch.tradeRequestDate());
        }
        if (dispatch.identityHmac() != null && !constantEqual(dispatch.identityHmac(), identityHmac))
            throw unavailable();
        OffsetDateTime now = payments.now();
        if ("MAY_HAVE_SENT".equals(dispatch.state()) || "UNKNOWN".equals(dispatch.state())
                || ("PARAMETERS_READY".equals(dispatch.state())
                    && (dispatch.parameterValidUntil() == null
                        || !dispatch.parameterValidUntil().isAfter(PaymentFoundationStore.utc(now))
                        || dispatch.parametersCiphertext() == null
                        || dispatch.parametersIv() == null))) {
            if (dispatch.tradeRequestDate() == null) throw unavailable();
            return new Recovery(payment.id(), payment.no(), payment.merchantNo(),
                    payment.termNo(), payment.amount(), dispatch.tradeRequestDate());
        }
        orders.requirePayableForPreparation(command.orderId(), storeId, actor);
        if ("PARAMETERS_READY".equals(dispatch.state())) {
            if (dispatch.parameterValidUntil() == null
                    || !dispatch.parameterValidUntil().isAfter(PaymentFoundationStore.utc(now))
                    || !payment.expires().isAfter(PaymentFoundationStore.utc(now))
                    || dispatch.parametersCiphertext() == null || dispatch.parametersIv() == null)
                throw unavailable();
            try {
                return new Replay(new InitiatedPayment(paymentId, Long.toString(payment.no()),
                        payment.channel(), decrypt(payment, dispatch),
                        dispatch.parameterValidUntil().atOffset(ZoneOffset.UTC)));
            } catch (ApiException unreadableParameters) {
                if (dispatch.tradeRequestDate() == null) throw unavailable();
                return new Recovery(payment.id(), payment.no(), payment.merchantNo(),
                        payment.termNo(), payment.amount(), dispatch.tradeRequestDate());
            }
        }
        if (!"PREPARED".equals(dispatch.state()) || dispatch.mayHaveSentAt() != null
                || dispatch.identityHmac() != null) throw unavailable();
        long wholeMinutes = Duration.between(now.toInstant(), order.paymentExpireAt().toInstant()).toMinutes();
        if (wholeMinutes < 1) throw new ApiException(CommonApiCodes.CONFLICT,
                "payment deadline has less than one channel minute remaining");
        int timeoutMinutes = (int) Math.min(10, wholeMinutes);
        LocalDateTime requestTime = LocalDateTime.ofInstant(now.toInstant(),
                settings.channelTimeZone()).withNano(0);
        LocalDate tradeDate = requestTime.toLocalDate();
        // Pure protocol validation must finish before the durable MAY_HAVE_SENT fence. In
        // particular USER may have a valid stored openId longer than this channel accepts.
        try {
            LakalaProtocol.preparePreorder(new LakalaProtocol.PreorderInput(settings.outOrgCode(),
                    requestTime, payment.merchantNo(), payment.termNo(), Long.toString(payment.no()),
                    payment.amount(), settings.subject(), payment.subAppId(), identity.openId(),
                    settings.requestIp(), settings.notifyUrl(), timeoutMinutes));
        } catch (RuntimeException malformed) { throw unavailable(); }
        int changed = jdbc.update("UPDATE payment_dispatch SET state='MAY_HAVE_SENT',"
                + "preorder_req_time=?,trade_req_date=?,timeout_express_minutes=?,"
                + "identity_hmac_sha256=?,may_have_sent_at=UTC_TIMESTAMP(3),"
                + "version=version+1,updated_at=UTC_TIMESTAMP(3) "
                + "WHERE payment_id=? AND state='PREPARED' AND fenced_at IS NULL",
                requestTime, tradeDate, timeoutMinutes, identityHmac, payment.id());
        if (changed != 1) throw unavailable();
        return new Plan(paymentId, Long.toString(payment.no()), payment.merchantNo(),
                payment.termNo(), payment.subAppId(), payment.amount(), identity.openId(),
                requestTime, timeoutMinutes);
    }

    private void queryForRecovery(Recovery p) {
        try {
            var input = new LakalaProtocol.QueryInput(settings.outOrgCode(), channelNow(),
                    p.merchantNo(), p.termNo(), Long.toString(p.paymentNo()), p.tradeRequestDate());
            var expected = new LakalaProtocol.ExpectedPayment(p.merchantNo(),
                    Long.toString(p.paymentNo()), p.amount().movePointRight(2).longValueExact());
            PaymentChannel.VerifiedQuery result = channel.lookup(input, expected, nonce());
            notifications.receiveQuery(Long.toString(p.paymentId()), result);
        } catch (RuntimeException uncertain) {
            // The original paymentNo remains the only identifier; no preorder retry is allowed.
        }
    }

    private void recordPreorder(Plan plan, PaymentChannel.VerifiedPreorder verified,
            String storeId) {
        payments.session();
        QueryContext system = new QueryContext(null, OperatorType.SYSTEM, null);
        guard.acquire(List.of(storeId), system);
        guard.requireHeld(storeId, source);
        PaymentFoundationStore.Row payment = payments.byId(id(plan.paymentId()), true);
        Dispatch dispatch = dispatch(id(plan.paymentId()));
        if (payment == null || dispatch == null) throw unavailable();
        validatePreorder(verified, payment);
        if (dispatch.fencedAt() != null || "PAID".equals(payment.status())
                || payment.successEventId() != null
                || !("INIT".equals(payment.status()) || "PAYING".equals(payment.status()))) {
            // Expiry fenced a request already in flight. Preserve its signed completion proof,
            // but never return parameters or reopen dispatch after the fence.
            if (dispatch.mayHaveSentAt() != null && dispatch.preorderResponseSha256() == null)
                jdbc.update("UPDATE payment_dispatch SET preorder_response_sha256=?,"
                        + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE payment_id=?",
                        verified.responseSha256(), payment.id());
            return;
        }
        if (!"MAY_HAVE_SENT".equals(dispatch.state())
                || !("INIT".equals(payment.status()) || "PAYING".equals(payment.status())))
            throw unavailable();
        OffsetDateTime now = payments.now();
        LocalDateTime validUntil = dispatch.mayHaveSentAt().plusMinutes(dispatch.timeoutMinutes());
        if (dispatch.preorderRequestTime() == null) throw unavailable();
        LocalDateTime requestBasedUntil = LocalDateTime.ofInstant(
                dispatch.preorderRequestTime().atZone(settings.channelTimeZone()).toInstant()
                        .plus(Duration.ofMinutes(dispatch.timeoutMinutes())), ZoneOffset.UTC);
        if (requestBasedUntil.isBefore(validUntil)) validUntil = requestBasedUntil;
        if (validUntil.isAfter(payment.expires())) validUntil = payment.expires();
        if (!validUntil.isAfter(PaymentFoundationStore.utc(now))) {
            jdbc.update("UPDATE payment_dispatch SET state='PARAMETERS_READY',"
                    + "preorder_response_sha256=?,parameter_valid_until=?,"
                    + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE payment_id=?",
                    verified.responseSha256(), validUntil, payment.id());
            return;
        }
        WechatPayParameters parameters = parameters(verified.result());
        byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
        byte[] ciphertext = encrypt(payment, parameters, iv);
        jdbc.update("UPDATE payment_dispatch SET state='PARAMETERS_READY',"
                + "preorder_response_sha256=?,parameters_ciphertext=?,parameters_iv=?,"
                + "parameter_valid_until=?,version=version+1,updated_at=UTC_TIMESTAMP(3) "
                + "WHERE payment_id=?", verified.responseSha256(), ciphertext, iv,
                validUntil, payment.id());
        return;
    }

    private void markUnknown(String paymentId, String storeId, QueryContext actor) {
        try {
            tx.execute(status -> {
                payments.session();
                guard.acquire(List.of(storeId), actor);
                guard.requireHeld(storeId, source);
                payments.byId(id(paymentId), true);
                jdbc.update("UPDATE payment_dispatch SET state='UNKNOWN',version=version+1,"
                        + "updated_at=UTC_TIMESTAMP(3) WHERE payment_id=? AND state='MAY_HAVE_SENT'",
                        id(paymentId));
                return null;
            });
        } catch (RuntimeException ignored) {
            // MAY_HAVE_SENT was committed before network I/O and already prevents another send.
        }
    }

    @Override public ExpiryEvidence reconcileForExpiry(ReconcilePaymentExpiryCommand command) {
        try { return reconcileInternal(command); }
        catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    private ExpiryEvidence reconcileInternal(ReconcilePaymentExpiryCommand command) {
        noOuterTransaction();
        validateExpiryCommand(command);
        QueryContext system = new QueryContext(command.context().traceId(), OperatorType.SYSTEM,
                command.context().operatorId());
        if (!command.storeId().equals(orders.locateStore(command.orderId(), system)))
            throw unavailable();
        ExpiryStart start = tx.execute(status -> fenceForExpiry(command, system));
        if (start instanceof NoPayment) return ExpiryEvidence.NO_PAYMENT;
        if (start instanceof FencedUnsent) return ExpiryEvidence.FENCED_UNSENT;
        if (start instanceof AlreadyClosed) return ExpiryEvidence.VERIFIED_TERMINAL_CLOSED;
        if (!(start instanceof ExpiryPlan plan)) return ExpiryEvidence.HOLD;
        PaymentChannel.VerifiedQuery before = queryAndRecord(plan);
        if (before == null || exceptionalState(before)) return ExpiryEvidence.HOLD;
        if (!plan.closeAlreadySent()) {
            if (!armClose(plan, system)) return ExpiryEvidence.HOLD;
            PaymentChannel.VerifiedClose close;
            try { close = channel.requestClose(closeInput(plan), nonce()); }
            catch (RuntimeException uncertain) { return ExpiryEvidence.HOLD; }
            if (!recordClose(plan, close, system)) return ExpiryEvidence.HOLD;
        }
        PaymentChannel.VerifiedQuery after = queryAndRecord(plan);
        if (after == null || after.result().tradeState() != LakalaProtocol.TradeState.CLOSE)
            return ExpiryEvidence.HOLD;
        return tx.execute(status -> finalizeClose(plan, system, after.responseSha256()));
    }

    private ExpiryStart fenceForExpiry(ReconcilePaymentExpiryCommand command, QueryContext system) {
        payments.session();
        guard.acquire(List.of(command.storeId()), system);
        guard.requireHeld(command.storeId(), source);
        OrderPaymentFact order = orders.readForPayment(command.orderId(), command.storeId(), system);
        if (!orders.isPaymentExpiryDue(command.orderId(), command.storeId(),
                command.expectedDeadline(), system)) return new Hold();
        PaymentFoundationStore.Row payment = payments.byOrder(id(command.orderId()));
        if (payment == null) return new NoPayment();
        Dispatch dispatch = dispatch(payment.id());
        requireBinding(payment, dispatch, order, payment.orderId(), command.storeId());
        if ("PAID".equals(payment.status()) || "RECONCILIATION_REQUIRED".equals(payment.dispatchState()))
            return new Hold();
        if ("PREPARED".equals(dispatch.state()) && dispatch.mayHaveSentAt() == null) {
            jdbc.update("UPDATE payment_dispatch SET state='FENCED_UNSENT',fenced_at=UTC_TIMESTAMP(3),"
                    + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE payment_id=?", payment.id());
            return new FencedUnsent();
        }
        if ("FENCED_UNSENT".equals(dispatch.state())) return new FencedUnsent();
        if ("TERMINAL_CLOSED".equals(dispatch.state())) return new AlreadyClosed();
        if (dispatch.mayHaveSentAt() == null || dispatch.tradeRequestDate() == null)
            return new Hold();
        if (dispatch.fencedAt() == null) jdbc.update("UPDATE payment_dispatch SET fenced_at=UTC_TIMESTAMP(3),"
                + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE payment_id=?", payment.id());
        return new ExpiryPlan(payment.id(), payment.no(), payment.orderId(), payment.storeId(),
                payment.merchantNo(), payment.termNo(), payment.amount(),
                dispatch.tradeRequestDate(), dispatch.closeMayHaveSentAt() != null);
    }

    private PaymentChannel.VerifiedQuery queryAndRecord(ExpiryPlan plan) {
        PaymentChannel.VerifiedQuery verified;
        try { verified = channel.lookup(queryInput(plan), expected(plan), nonce()); }
        catch (RuntimeException uncertain) { return null; }
        try { notifications.receiveQuery(Long.toString(plan.paymentId()), verified); }
        catch (RuntimeException uncertain) { return null; }
        return verified;
    }
    private static boolean exceptionalState(PaymentChannel.VerifiedQuery query) {
        var state = query.result().tradeState();
        return state == LakalaProtocol.TradeState.SUCCESS
                || state == LakalaProtocol.TradeState.REFUND
                || state == LakalaProtocol.TradeState.PART_REFUND;
    }

    private boolean armClose(ExpiryPlan plan, QueryContext system) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            payments.session();
            guard.acquire(List.of(Long.toString(plan.storeId())), system);
            guard.requireHeld(Long.toString(plan.storeId()), source);
            var payment = payments.byId(plan.paymentId(), true);
            var dispatch = dispatch(plan.paymentId());
            if (payment == null || dispatch == null || "PAID".equals(payment.status())
                    || dispatch.fencedAt() == null || dispatch.closeMayHaveSentAt() != null
                    || dispatch.preorderResponseSha256() == null
                    || "RECONCILIATION_REQUIRED".equals(payment.dispatchState())) return false;
            jdbc.update("UPDATE payment_dispatch SET state='CLOSE_MAY_HAVE_SENT',"
                    + "close_may_have_sent_at=UTC_TIMESTAMP(3),version=version+1,"
                    + "updated_at=UTC_TIMESTAMP(3) WHERE payment_id=?", plan.paymentId());
            return true;
        }));
    }

    private boolean recordClose(ExpiryPlan plan, PaymentChannel.VerifiedClose close,
            QueryContext system) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            payments.session();
            guard.acquire(List.of(Long.toString(plan.storeId())), system);
            guard.requireHeld(Long.toString(plan.storeId()), source);
            var payment = payments.byId(plan.paymentId(), true);
            var dispatch = dispatch(plan.paymentId());
            if (payment == null || dispatch == null || "PAID".equals(payment.status())
                    || dispatch.closeMayHaveSentAt() == null
                    || !"CLOSE_MAY_HAVE_SENT".equals(dispatch.state())
                    || close == null || close.result() == null
                    || !Long.toString(plan.paymentNo()).equals(close.result().originOutTradeNo())
                    || !digest(close.responseSha256())) return false;
            String receiptDigest = sha256(("CLOSE\0" + close.responseSha256())
                    .getBytes(StandardCharsets.US_ASCII));
            jdbc.update("INSERT INTO payment_channel_receipt(id,payment_id,receipt_sha256,"
                    + "channel_trade_no,channel_status,total_amount,paid_amount,paid_at,received_at,"
                    + "receipt_source,channel_response_sha256) "
                    + "VALUES(?,?,?,?, 'CLOSE',NULL,NULL,NULL,UTC_TIMESTAMP(3),'CLOSE',?) "
                    + "ON DUPLICATE KEY UPDATE id=id", receiptId(), plan.paymentId(),
                    receiptDigest, close.result().originTradeNo(), close.responseSha256());
            jdbc.update("UPDATE payment_dispatch SET state='CLOSE_ACKED',close_response_sha256=?,"
                    + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE payment_id=?",
                    close.responseSha256(), plan.paymentId());
            return true;
        }));
    }

    private ExpiryEvidence finalizeClose(ExpiryPlan plan, QueryContext system,
            String verifiedAfterCloseSha) {
        payments.session();
        guard.acquire(List.of(Long.toString(plan.storeId())), system);
        guard.requireHeld(Long.toString(plan.storeId()), source);
        var payment = payments.byId(plan.paymentId(), true);
        var dispatch = dispatch(plan.paymentId());
        if (payment == null || dispatch == null || !settings.terminalCloseCapability()
                || !"CLOSE_ACKED".equals(dispatch.state()) || dispatch.fencedAt() == null
                || dispatch.preorderResponseSha256() == null
                || dispatch.closeResponseSha256() == null
                || "PAID".equals(payment.status()) || payment.successEventId() != null
                || "RECONCILIATION_REQUIRED".equals(payment.dispatchState())) return ExpiryEvidence.HOLD;
        if (!digest(verifiedAfterCloseSha)) return ExpiryEvidence.HOLD;
        List<String> matchingQuery = jdbc.query("SELECT channel_response_sha256 "
                + "FROM payment_channel_receipt WHERE payment_id=? AND receipt_source='QUERY' "
                + "AND channel_status='CLOSE' AND channel_response_sha256=? LIMIT 1 FOR UPDATE",
                (rs,n) -> rs.getString(1), payment.id(), verifiedAfterCloseSha);
        if (matchingQuery.size() != 1) return ExpiryEvidence.HOLD;
        jdbc.update("UPDATE payment_dispatch SET state='TERMINAL_CLOSED',terminal_query_sha256=?,"
                + "terminal_confirmed_at=UTC_TIMESTAMP(3),terminal_close_capability=1,"
                + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE payment_id=?",
                verifiedAfterCloseSha, payment.id());
        return ExpiryEvidence.VERIFIED_TERMINAL_CLOSED;
    }

    private LakalaProtocol.QueryInput queryInput(ExpiryPlan p) {
        return new LakalaProtocol.QueryInput(settings.outOrgCode(), channelNow(), p.merchantNo(),
                p.termNo(), Long.toString(p.paymentNo()), p.tradeRequestDate());
    }
    private LakalaProtocol.CloseInput closeInput(ExpiryPlan p) {
        return new LakalaProtocol.CloseInput(settings.outOrgCode(), channelNow(), p.merchantNo(),
                p.termNo(), Long.toString(p.paymentNo()), settings.requestIp());
    }
    private LakalaProtocol.ExpectedPayment expected(ExpiryPlan p) {
        return new LakalaProtocol.ExpectedPayment(p.merchantNo(), Long.toString(p.paymentNo()),
                p.amount().movePointRight(2).longValueExact());
    }
    private LocalDateTime channelNow() {
        return LocalDateTime.ofInstant(clock.instant(), settings.channelTimeZone()).withNano(0);
    }
    private RequestNonce nonce() {
        char[] chars = new char[12];
        for (int i=0;i<chars.length;i++) chars[i] = NONCE_ALPHABET[RANDOM.nextInt(NONCE_ALPHABET.length)];
        return new RequestNonce(Long.toString(clock.instant().getEpochSecond()), new String(chars));
    }

    private Dispatch dispatch(long paymentId) {
        List<Dispatch> found = jdbc.query("SELECT payment_id,state,preorder_req_time,trade_req_date,"
                + "timeout_express_minutes,identity_hmac_sha256,may_have_sent_at,"
                + "preorder_response_sha256,parameters_ciphertext,parameters_iv,parameter_valid_until,"
                + "fenced_at,close_may_have_sent_at,close_response_sha256,terminal_query_sha256,"
                + "terminal_confirmed_at,terminal_close_capability FROM payment_dispatch "
                + "WHERE payment_id=? FOR UPDATE", (rs,n) -> new Dispatch(rs.getLong(1),rs.getString(2),
                rs.getObject(3,LocalDateTime.class),rs.getObject(4,LocalDate.class),
                rs.getObject(5,Integer.class),rs.getString(6),rs.getObject(7,LocalDateTime.class),
                rs.getString(8),rs.getBytes(9),rs.getBytes(10),rs.getObject(11,LocalDateTime.class),
                rs.getObject(12,LocalDateTime.class),rs.getObject(13,LocalDateTime.class),
                rs.getString(14),rs.getString(15),rs.getObject(16,LocalDateTime.class),
                rs.getBoolean(17)), paymentId);
        return found.size() == 1 ? found.getFirst() : null;
    }

    private static void requireBinding(PaymentFoundationStore.Row payment, Dispatch dispatch,
            OrderPaymentFact order, long orderId, String storeId) {
        if (payment == null || dispatch == null || dispatch.paymentId() != payment.id()
                || payment.orderId() != orderId || payment.storeId() != id(storeId)
                || payment.userId() != id(order.userId())
                || payment.merchantId() != id(order.merchantId())
                || payment.amount().compareTo(order.payAmount()) != 0
                || !payment.expires().equals(PaymentFoundationStore.utc(order.paymentExpireAt()))
                || !"LAKALA_WECHAT".equals(payment.channel())
                || !"CNY".equals(payment.currency()) || payment.merchantNo() == null
                || payment.termNo() == null || payment.subAppId() == null) throw unavailable();
    }

    private static void validatePreorder(PaymentChannel.VerifiedPreorder verified,
            PaymentFoundationStore.Row payment) {
        if (verified == null || verified.result() == null || !digest(verified.responseSha256()))
            throw unavailable();
        var result = verified.result();
        if (!payment.merchantNo().equals(result.merchantNo())
                || !Long.toString(payment.no()).equals(result.outTradeNo())
                || !payment.subAppId().equals(result.appId())
                || blank(result.prepayId())
                || !"prepay_id=".concat(result.prepayId()).equals(result.packageValue())
                || !"RSA".equals(result.signType()) || blank(result.paySign())
                || blank(result.nonceStr()) || blank(result.timeStamp())) throw unavailable();
    }
    private static WechatPayParameters parameters(LakalaProtocol.PreorderResult result) {
        return new WechatPayParameters(result.timeStamp(), result.nonceStr(),
                result.packageValue(), result.signType(), result.paySign());
    }

    private byte[] encrypt(PaymentFoundationStore.Row payment, WechatPayParameters value,
            byte[] iv) {
        try {
            byte[] plain = JSON.writeValueAsBytes(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, settings.parameterKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(payment));
            byte[] encrypted = cipher.doFinal(plain);
            Arrays.fill(plain, (byte) 0);
            if (encrypted.length > 2048) throw unavailable();
            return encrypted;
        } catch (ApiException known) { throw known; }
        catch (Exception failed) { throw unavailable(); }
    }
    private WechatPayParameters decrypt(PaymentFoundationStore.Row payment, Dispatch dispatch) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, settings.parameterKey(),
                    new GCMParameterSpec(128, dispatch.parametersIv()));
            cipher.updateAAD(aad(payment));
            byte[] plain = cipher.doFinal(dispatch.parametersCiphertext());
            var result = JSON.readValue(plain, WechatPayParameters.class);
            Arrays.fill(plain, (byte) 0);
            if (result == null || blank(result.paySign()) || blank(result.packageValue()))
                throw unavailable();
            return result;
        } catch (ApiException known) { throw known; }
        catch (Exception failed) { throw unavailable(); }
    }
    private static byte[] aad(PaymentFoundationStore.Row payment) {
        return ("PAYMENT_PARAMETERS_V1:" + payment.id() + ":" + payment.no())
                .getBytes(StandardCharsets.US_ASCII);
    }
    private String identityHmac(PaymentIdentity identity) {
        try {
            byte[] key = settings.parameterKey().getEncoded();
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] separate = derive.doFinal("PAYMENT_IDENTITY_KEY_V1".getBytes(StandardCharsets.US_ASCII));
            Arrays.fill(key, (byte) 0);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(separate, "HmacSHA256"));
            Arrays.fill(separate, (byte) 0);
            byte[] app = identity.appId().getBytes(StandardCharsets.UTF_8);
            byte[] open = identity.openId().getBytes(StandardCharsets.UTF_8);
            mac.update(ByteBuffer.allocate(4).putInt(app.length).array());
            mac.update(app);
            mac.update(ByteBuffer.allocate(4).putInt(open.length).array());
            mac.update(open);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception failed) { throw unavailable(); }
    }

    private static boolean constantEqual(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failed) { throw unavailable(); }
    }
    private static void validateExpiryCommand(ReconcilePaymentExpiryCommand command) {
        if (command == null || command.context() == null || command.context().operatorType() != OperatorType.SYSTEM)
            throw forbidden();
        try { PublicContractChecks.requireCommandRequestId(command.context()); }
        catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid payment expiry requestId");
        }
        id(command.orderId()); id(command.storeId());
        try { PublicContractChecks.requireMillisecondPrecision(command.expectedDeadline()); }
        catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid payment expiry deadline");
        }
        if (!command.context().requestId().equals("PAYMENT_EXPIRY_ORDER:" + command.orderId() + ":0"))
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "payment expiry key is invalid");
    }
    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
    }
    private static long id(String value) {
        try { return IDS.fromApi(value); }
        catch (RuntimeException invalid) { throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"invalid payment ID"); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ApiException forbidden() {
        return new ApiException(CommonApiCodes.FORBIDDEN,"payment dispatch actor forbidden");
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"payment dispatch requires reconciliation");
    }
    private long receiptId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable();
        return value;
    }

    private sealed interface Start permits Replay, Plan, Recovery {}
    private record Replay(InitiatedPayment payment) implements Start {}
    private record Plan(String paymentId,String paymentNo,String merchantNo,String termNo,
            String subAppId,BigDecimal amount,String openId,LocalDateTime requestTime,
            int timeoutMinutes) implements Start {
        @Override public String toString() { return "Plan[redacted]"; }
    }
    private record Recovery(long paymentId,long paymentNo,String merchantNo,String termNo,
            BigDecimal amount,LocalDate tradeRequestDate) implements Start {}
    private sealed interface ExpiryStart permits NoPayment,FencedUnsent,AlreadyClosed,Hold,ExpiryPlan {}
    private record NoPayment() implements ExpiryStart {}
    private record FencedUnsent() implements ExpiryStart {}
    private record AlreadyClosed() implements ExpiryStart {}
    private record Hold() implements ExpiryStart {}
    private record ExpiryPlan(long paymentId,long paymentNo,long orderId,long storeId,
            String merchantNo,String termNo,BigDecimal amount,LocalDate tradeRequestDate,
            boolean closeAlreadySent) implements ExpiryStart {}
    private record Dispatch(long paymentId,String state,LocalDateTime preorderRequestTime,
            LocalDate tradeRequestDate,Integer timeoutMinutes,String identityHmac,
            LocalDateTime mayHaveSentAt,String preorderResponseSha256,byte[] parametersCiphertext,
            byte[] parametersIv,LocalDateTime parameterValidUntil,LocalDateTime fencedAt,
            LocalDateTime closeMayHaveSentAt,String closeResponseSha256,
            String terminalQuerySha256,LocalDateTime terminalConfirmedAt,boolean terminalCapability) {}
}
