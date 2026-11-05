package com.petplatform.payment.biz.center;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.PaymentCenterApi;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPayment;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPaymentView;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CreateCenterPaymentCommand;
import com.petplatform.payment.api.dto.PaymentCenterTypes.QueryCenterPayment;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.WechatPayParameters;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.CenterRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore.Dispatch;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.payment.biz.task.PaymentCenterQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.user.api.dto.PaymentIdentity;
import com.petplatform.user.api.query.PaymentIdentityApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 75 §4.1/§5 business-agnostic create/query. Sending discipline (§5.3): durable MAY_HAVE_SENT and the
 * PAYCENTER_CHANNEL_QUERY task commit together before any network I/O; an uncertain preorder is never re-sent,
 * only queried with the original paymentNo. No database transaction is open during channel I/O.
 */
public final class PaymentCenterService implements PaymentCenterApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NONCE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private final SnowflakeIdGenerator ids;
    private final PaymentCenterBizPolicy policy;
    private final PaymentMerchantBindings bindings;
    private final PaymentIdentityApi identities;
    private final PaymentChannel channel;
    private final PaymentCenterReceiptWriter receipts;
    private final JdbcAsyncTaskSubmitter tasks;
    private final PaymentDispatchService.Settings settings;
    private final String settleType;
    private final Clock clock;
    private final CenterParameterCipher cipher;
    private final PaymentCenterStore store;
    private final TransactionTemplate tx;

    public PaymentCenterService(DataSource source, SnowflakeIdGenerator ids, PaymentCenterBizPolicy policy,
            PaymentMerchantBindings bindings, PaymentIdentityApi identities, PaymentChannel channel,
            PaymentCenterReceiptWriter receipts, JdbcAsyncTaskSubmitter tasks,
            PaymentDispatchService.Settings settings, String settleType, Clock clock) {
        this.ids = Objects.requireNonNull(ids);
        this.policy = Objects.requireNonNull(policy);
        this.bindings = Objects.requireNonNull(bindings);
        this.identities = Objects.requireNonNull(identities);
        this.channel = Objects.requireNonNull(channel);
        this.receipts = Objects.requireNonNull(receipts);
        this.tasks = Objects.requireNonNull(tasks);
        this.settings = Objects.requireNonNull(settings);
        if (!"0".equals(settleType) && !"1".equals(settleType))
            throw new IllegalArgumentException("settleType must be 0 or 1");
        this.settleType = settleType;
        this.clock = Objects.requireNonNull(clock);
        this.cipher = new CenterParameterCipher(settings.parameterKey());
        this.store = new PaymentCenterStore(Objects.requireNonNull(source));
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    // ---------------------------------------------------------------- create

    @Override public CenterPayment create(CreateCenterPaymentCommand command) {
        try { return createInternal(command); }
        catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw databaseFailure(failure); }
    }

    private CenterPayment createInternal(CreateCenterPaymentCommand c) {
        noOuterTransaction();
        if (c == null || c.context() == null) throw invalid("payment center command is required");
        if (c.context().operatorType() != OperatorType.USER) throw forbidden();
        try { PublicContractChecks.requireCommandRequestId(c.context()); }
        catch (IllegalArgumentException bad) { throw invalid("invalid payment center requestId"); }
        long userId = id(c.context().operatorId());
        var limits = policy.requireCommand(c.bizType(), c.bizNo(), c.amount());
        try { PublicContractChecks.requireMillisecondPrecision(c.expireAt()); }
        catch (IllegalArgumentException bad) { throw invalid("invalid payment center deadline"); }
        LocalDateTime expires = PaymentFoundationStore.utc(c.expireAt());
        byte[] key = ("payment.center.create/USER/" + c.context().operatorId() + "/" + c.context().requestId())
                .getBytes(StandardCharsets.UTF_8);
        // T1: the requestId binding commits even if later eligibility fails (Contract 23 §5).
        authoritative(() -> tx.execute(status -> {
            store.session();
            store.center.insertRequest(PaymentFoundationStore.values("id", nextId(), "key", key,
                    "bizType", c.bizType(), "bizNo", c.bizNo(), "userId", userId, "amount", c.amount(),
                    "expireAt", expires));
            checkRequest(key, c, userId, expires);
            return null;
        }));
        long paymentId = authoritative(() -> prepareRow(c, key, userId, expires, limits));
        QueryContext actor = new QueryContext(c.context().traceId(), OperatorType.USER, c.context().operatorId());
        Start start = tx.execute(status -> fence(paymentId, userId, actor));
        if (start instanceof Replay replay) return replay.payment();
        if (start instanceof Recovery recovery) {
            queryOnce(recovery);
            throw unavailable();
        }
        Plan plan = (Plan) start;
        PaymentChannel.VerifiedPreorder verified;
        try {
            verified = channel.submitPreorder(new LakalaProtocol.PreorderInput(settings.outOrgCode(),
                    plan.requestTime(), plan.merchantNo(), plan.termNo(), Long.toString(plan.paymentNo()),
                    plan.amount(), settings.subject(), plan.subAppId(), plan.openId(), settings.requestIp(),
                    settings.notifyUrl(), plan.timeoutMinutes(), plan.settleType()), nonce());
        } catch (RuntimeException uncertain) {
            // MAY_HAVE_SENT and the query task already committed: never re-send, the task confirms the result.
            markUnknown(plan.paymentId());
            throw unavailable();
        }
        CenterPayment recorded;
        try {
            recorded = tx.execute(status -> recordPreorder(plan, verified, c.bizType(), c.bizNo()));
        } catch (RuntimeException uncertainCommit) {
            // A lost commit ACK may mean the parameters were saved: re-read under the row lock once.
            Start reread = tx.execute(status -> fence(paymentId, userId, actor));
            if (reread instanceof Replay replay) return replay.payment();
            if (reread instanceof Recovery recovery) queryOnce(recovery);
            throw unavailable();
        }
        if (recorded == null) throw unavailable();
        return recorded;
    }

    private void checkRequest(byte[] key, CreateCenterPaymentCommand c, long userId, LocalDateTime expires) {
        var bound = store.center.selectRequestForUpdate(key);
        if (bound == null) throw unavailable();
        if (!c.bizType().equals(bound.bizType()) || !c.bizNo().equals(bound.bizNo()) || bound.userId() != userId
                || bound.amount() == null || bound.amount().compareTo(c.amount()) != 0
                || !expires.equals(bound.expireAt()))
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "payment center requestId already bound");
    }

    /** T2: at most one payment per (bizType, bizNo); a concurrent insert is retried once as a replay. */
    private long prepareRow(CreateCenterPaymentCommand c, byte[] key, long userId, LocalDateTime expires,
            PaymentCenterBizPolicy.Limits limits) {
        try {
            return tx.execute(status -> bindOrInsert(c, key, userId, expires, limits));
        } catch (DuplicateKeyException concurrentInsert) {
            return tx.execute(status -> bindOrInsert(c, key, userId, expires, limits));
        }
    }

    private long bindOrInsert(CreateCenterPaymentCommand c, byte[] key, long userId, LocalDateTime expires,
            PaymentCenterBizPolicy.Limits limits) {
        store.session();
        checkRequest(key, c, userId, expires);
        CenterRow existing = store.center.selectCenterByBizForUpdate(c.bizType(), c.bizNo());
        if (existing != null) {
            if (!existing.isCenter() || !Objects.equals(existing.userId(), userId)
                    || existing.amount() == null || existing.amount().compareTo(c.amount()) != 0
                    || !expires.equals(existing.expires()))
                throw new ApiException(CommonApiCodes.CONFLICT, "business object already has a different payment");
            if (store.center.bindRequest(existing.id(), key) != 1) throw unavailable();
            return existing.id();
        }
        PaymentCenterBizPolicy.requireWindow(limits, c.expireAt(), store.now());
        PaymentMerchantBindings.Binding binding = bindings.requirePlatform();
        if (binding == null || !valid(binding.merchantNo(), 32) || !valid(binding.termNo(), 32)
                || !valid(binding.subAppId(), 64)) throw unavailable();
        long paymentId = nextId(), paymentNo = nextId();
        store.center.insertCenterPayment(PaymentFoundationStore.values("id", paymentId, "paymentNo", paymentNo,
                "bizType", c.bizType(), "bizNo", c.bizNo(), "amount", c.amount(), "expires", expires,
                "userId", userId, "merchantNo", binding.merchantNo(), "termNo", binding.termNo(),
                "subAppId", binding.subAppId(), "settleType", settleType));
        store.shared.insertPreparedDispatch(paymentId);
        if (store.center.bindRequest(paymentId, key) != 1) throw unavailable();
        return paymentId;
    }

    /** T3: dispatch fence. Either replays stored parameters, asks for a recovery query, or arms one send. */
    private Start fence(long paymentId, long userId, QueryContext actor) {
        store.session();
        CenterRow row = store.center.selectCenterByIdForUpdate(paymentId);
        Dispatch dispatch = store.shared.selectDispatchForUpdate(paymentId);
        requireRow(row, dispatch, userId);
        if ("PAID".equals(row.status()) || "FAILED".equals(row.status()) || "CLOSED".equals(row.status()))
            throw new ApiException(CommonApiCodes.CONFLICT, "payment center payment is no longer payable");
        if (!("INIT".equals(row.status()) || "PAYING".equals(row.status())) || row.successEventId() != null
                || row.paidAt() != null || row.paidAmount() != null) throw unavailable();
        if (dispatch.fencedAt() != null || "RECONCILIATION_REQUIRED".equals(row.dispatchState())) throw unavailable();
        PaymentIdentity identity = identities.requireCurrentPaymentIdentity(Long.toString(userId), row.subAppId(), actor);
        if (identity == null || !row.subAppId().equals(identity.appId())) throw unavailable();
        String identityHmac = cipher.identityHmac(identity);
        if (dispatch.identityHmac() == null && !"PREPARED".equals(dispatch.state())) return recovery(row, dispatch);
        if (dispatch.identityHmac() != null && !CenterParameterCipher.constantEqual(dispatch.identityHmac(), identityHmac))
            throw unavailable();
        OffsetDateTime now = store.now();
        LocalDateTime utcNow = PaymentFoundationStore.utc(now);
        if ("MAY_HAVE_SENT".equals(dispatch.state()) || "UNKNOWN".equals(dispatch.state())) return recovery(row, dispatch);
        if ("PARAMETERS_READY".equals(dispatch.state())) {
            if (dispatch.parameterValidUntil() == null || !dispatch.parameterValidUntil().isAfter(utcNow)
                    || !row.expires().isAfter(utcNow) || dispatch.parametersCiphertext() == null
                    || dispatch.parametersIv() == null) return recovery(row, dispatch);
            try {
                WechatPayParameters parameters = cipher.decrypt(row.id(), row.no(),
                        dispatch.parametersCiphertext(), dispatch.parametersIv());
                return new Replay(new CenterPayment(Long.toString(row.id()), Long.toString(row.no()), row.bizType(),
                        row.bizNo(), row.channel(), parameters, dispatch.parameterValidUntil().atOffset(ZoneOffset.UTC),
                        true));
            } catch (ApiException unreadable) {
                return recovery(row, dispatch);
            }
        }
        if (!"PREPARED".equals(dispatch.state()) || dispatch.mayHaveSentAt() != null || dispatch.identityHmac() != null)
            throw unavailable();
        long wholeMinutes = Duration.between(now.toInstant(), row.expires().toInstant(ZoneOffset.UTC)).toMinutes();
        if (wholeMinutes < 1) throw new ApiException(CommonApiCodes.CONFLICT,
                "payment deadline has less than one channel minute remaining");
        int timeoutMinutes = (int) Math.min(10, wholeMinutes);
        LocalDateTime requestTime = LocalDateTime.ofInstant(now.toInstant(), settings.channelTimeZone()).withNano(0);
        LocalDate tradeDate = requestTime.toLocalDate();
        String channelSettleType = "1".equals(row.settleType()) ? "1" : null;
        // Pure protocol validation must finish before the durable MAY_HAVE_SENT fence.
        try {
            LakalaProtocol.preparePreorder(new LakalaProtocol.PreorderInput(settings.outOrgCode(), requestTime,
                    row.merchantNo(), row.termNo(), Long.toString(row.no()), row.amount(), settings.subject(),
                    row.subAppId(), identity.openId(), settings.requestIp(), settings.notifyUrl(), timeoutMinutes,
                    channelSettleType));
        } catch (RuntimeException malformed) { throw unavailable(); }
        if (store.shared.markMayHaveSent(PaymentFoundationStore.values("requestTime", requestTime,
                "tradeDate", tradeDate, "timeoutMinutes", timeoutMinutes, "identityHmac", identityHmac,
                "paymentId", row.id())) != 1) throw unavailable();
        // Same transaction as MAY_HAVE_SENT: a committed fence always has its confirming query task.
        tasks.enqueueAt(PaymentCenterQueryTask.taskKey(row.id()), PaymentCenterQueryTask.OWNER_MODULE,
                PaymentCenterQueryTask.TASK_TYPE, PaymentCenterQueryTask.BIZ_TYPE, row.id(), null,
                PaymentCenterQueryTask.payload(row.id()), PaymentCenterQueryTask.MAX_RETRY_COUNT,
                PaymentCenterQueryTask.RETRY_POLICY,
                now.plus(PaymentCenterQueryTask.FIRST_DELAY).truncatedTo(ChronoUnit.MILLIS));
        return new Plan(row.id(), row.no(), row.merchantNo(), row.termNo(), row.subAppId(), row.amount(),
                identity.openId(), requestTime, timeoutMinutes, channelSettleType);
    }

    /** T4: commit the verified preorder; parameters are AES-GCM sealed under the payment center AAD. */
    private CenterPayment recordPreorder(Plan plan, PaymentChannel.VerifiedPreorder verified, String bizType,
            String bizNo) {
        store.session();
        CenterRow row = store.center.selectCenterByIdForUpdate(plan.paymentId());
        Dispatch dispatch = store.shared.selectDispatchForUpdate(plan.paymentId());
        if (row == null || dispatch == null || !row.isCenter()) throw unavailable();
        validatePreorder(verified, row);
        if (dispatch.fencedAt() != null || row.successEventId() != null
                || !("INIT".equals(row.status()) || "PAYING".equals(row.status()))) {
            // Keep the signed completion proof but never hand out parameters for a settled payment.
            if (dispatch.mayHaveSentAt() != null && dispatch.preorderResponseSha256() == null)
                store.shared.savePreorderSha(PaymentFoundationStore.values("sha", verified.responseSha256(),
                        "paymentId", row.id()));
            return null;
        }
        if (!"MAY_HAVE_SENT".equals(dispatch.state()) || dispatch.mayHaveSentAt() == null
                || dispatch.preorderRequestTime() == null || dispatch.timeoutMinutes() == null) throw unavailable();
        OffsetDateTime now = store.now();
        LocalDateTime validUntil = dispatch.mayHaveSentAt().plusMinutes(dispatch.timeoutMinutes());
        LocalDateTime requestBasedUntil = LocalDateTime.ofInstant(dispatch.preorderRequestTime()
                .atZone(settings.channelTimeZone()).toInstant().plus(Duration.ofMinutes(dispatch.timeoutMinutes())),
                ZoneOffset.UTC);
        if (requestBasedUntil.isBefore(validUntil)) validUntil = requestBasedUntil;
        if (validUntil.isAfter(row.expires())) validUntil = row.expires();
        if (!validUntil.isAfter(PaymentFoundationStore.utc(now))) {
            store.shared.saveExpiredParameters(PaymentFoundationStore.values("sha", verified.responseSha256(),
                    "validUntil", validUntil, "paymentId", row.id()));
            return null;
        }
        var result = verified.result();
        WechatPayParameters parameters = new WechatPayParameters(result.timeStamp(), result.nonceStr(),
                result.packageValue(), result.signType(), result.paySign());
        CenterParameterCipher.Sealed sealed = cipher.encrypt(row.id(), row.no(), parameters);
        store.shared.saveParameters(PaymentFoundationStore.values("sha", verified.responseSha256(),
                "ciphertext", sealed.ciphertext(), "iv", sealed.iv(), "validUntil", validUntil, "paymentId", row.id()));
        return new CenterPayment(Long.toString(row.id()), Long.toString(row.no()), bizType, bizNo, row.channel(),
                parameters, validUntil.atOffset(ZoneOffset.UTC), false);
    }

    private void markUnknown(long paymentId) {
        try {
            tx.execute(status -> {
                store.session();
                store.center.selectCenterByIdForUpdate(paymentId);
                store.shared.markUnknown(paymentId);
                return null;
            });
        } catch (RuntimeException ignored) {
            // MAY_HAVE_SENT was committed before network I/O and already prevents another send.
        }
    }

    /** One out-of-transaction query with the original paymentNo; the durable task keeps confirming. */
    private void queryOnce(Recovery p) {
        try {
            var input = new LakalaProtocol.QueryInput(settings.outOrgCode(),
                    LocalDateTime.ofInstant(clock.instant(), settings.channelTimeZone()).withNano(0),
                    p.merchantNo(), p.termNo(), Long.toString(p.paymentNo()), p.tradeRequestDate());
            var expected = new LakalaProtocol.ExpectedPayment(p.merchantNo(), Long.toString(p.paymentNo()),
                    p.amount().movePointRight(2).longValueExact());
            receipts.receiveQuery(p.paymentId(), channel.lookup(input, expected, nonce()));
        } catch (RuntimeException uncertain) {
            // Indeterminate is never failure; no preorder retry is allowed.
        }
    }

    private Start recovery(CenterRow row, Dispatch dispatch) {
        if (dispatch.tradeRequestDate() == null) throw unavailable();
        return new Recovery(row.id(), row.no(), row.merchantNo(), row.termNo(), row.amount(),
                dispatch.tradeRequestDate());
    }

    private void requireRow(CenterRow row, Dispatch dispatch, long userId) {
        if (row == null || dispatch == null || dispatch.paymentId() != row.id() || !row.isCenter()
                || !Objects.equals(row.userId(), userId) || !"LAKALA_WECHAT".equals(row.channel())
                || !"CNY".equals(row.currency()) || row.merchantNo() == null || row.termNo() == null
                || row.subAppId() == null || row.amount() == null || row.amount().signum() <= 0
                || row.expires() == null) throw unavailable();
    }

    private static void validatePreorder(PaymentChannel.VerifiedPreorder verified, CenterRow row) {
        if (verified == null || verified.result() == null || !PaymentCenterReceiptWriter.digest(verified.responseSha256()))
            throw unavailable();
        var result = verified.result();
        if (!row.merchantNo().equals(result.merchantNo()) || !Long.toString(row.no()).equals(result.outTradeNo())
                || !row.subAppId().equals(result.appId()) || blank(result.prepayId())
                || !"prepay_id=".concat(result.prepayId()).equals(result.packageValue())
                || !"RSA".equals(result.signType()) || blank(result.paySign())
                || blank(result.nonceStr()) || blank(result.timeStamp())) throw unavailable();
    }

    // ---------------------------------------------------------------- query

    @Override public CenterPaymentView query(QueryCenterPayment query) {
        if (query == null || query.context() == null) throw invalid("payment center query is required");
        QueryContext context = query.context();
        if (context.operatorType() != OperatorType.USER && context.operatorType() != OperatorType.SYSTEM)
            throw forbidden();
        long actor = context.operatorType() == OperatorType.USER ? id(context.operatorId()) : 0L;
        PaymentCenterBizPolicy.requireBizType(query.bizType());
        PaymentCenterBizPolicy.requireBizNo(query.bizNo());
        CenterRow row;
        try { row = store.center.selectCenterByBiz(query.bizType(), query.bizNo()); }
        catch (RuntimeException failure) { throw unavailable(); }
        // Not found and somebody else's payment are indistinguishable to a USER.
        if (row == null || !row.isCenter() || (actor != 0L && !Objects.equals(row.userId(), actor)))
            throw new ApiException(CommonApiCodes.NOT_FOUND, "payment center payment not found");
        return new CenterPaymentView(Long.toString(row.id()), Long.toString(row.no()), row.bizType(), row.bizNo(),
                row.amount(), row.currency(), row.status(), row.paidAmount(),
                row.paidAt() == null ? null : row.paidAt().atOffset(ZoneOffset.UTC),
                row.expires().atOffset(ZoneOffset.UTC));
    }

    // ---------------------------------------------------------------- helpers

    /** One authoritative retry under the original binding when a commit outcome is unknown (SQLState 08). */
    private static <T> T authoritative(Supplier<T> work) {
        try { return work.get(); }
        catch (ApiException known) { throw known; }
        catch (RuntimeException failed) {
            if (!unknownCommit(failed)) throw failed;
            try { return work.get(); }
            catch (ApiException known) { throw known; }
            catch (RuntimeException rereadFailed) { throw databaseFailure(rereadFailed); }
        }
    }

    private RequestNonce nonce() {
        char[] chars = new char[12];
        for (int i = 0; i < chars.length; i++) chars[i] = NONCE_ALPHABET[RANDOM.nextInt(NONCE_ALPHABET.length)];
        return new RequestNonce(Long.toString(clock.instant().getEpochSecond()), new String(chars));
    }
    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable();
        return value;
    }
    private static long id(String value) {
        try { return IDS.fromApi(value); }
        catch (RuntimeException bad) { throw invalid("invalid payment center ID"); }
    }
    private static boolean valid(String s, int max) {
        return s != null && !s.isBlank() && s.length() <= max && s.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
    }
    static boolean unknownCommit(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause())
            if (at instanceof java.sql.SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))
                return true;
        return false;
    }
    static ApiException databaseFailure(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause())
            if (at instanceof java.sql.SQLException sql && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213))
                return new ApiException(CommonApiCodes.CONFLICT, "payment center busy; retry the original requestId");
        return unavailable();
    }
    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }
    private static ApiException forbidden() {
        return new ApiException(CommonApiCodes.FORBIDDEN, "payment center actor forbidden");
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "payment center requires reconciliation");
    }

    private sealed interface Start permits Replay, Plan, Recovery {}
    private record Replay(CenterPayment payment) implements Start {}
    private record Plan(long paymentId, long paymentNo, String merchantNo, String termNo, String subAppId,
            BigDecimal amount, String openId, LocalDateTime requestTime, int timeoutMinutes, String settleType)
            implements Start {
        @Override public String toString() { return "Plan[redacted]"; }
    }
    private record Recovery(long paymentId, long paymentNo, String merchantNo, String termNo, BigDecimal amount,
            LocalDate tradeRequestDate) implements Start {}
}
