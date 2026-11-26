package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.WalletCommandApi;
import com.petplatform.payment.api.dto.WalletTypes.ListWithdrawalsQuery;
import com.petplatform.payment.api.dto.WalletTypes.MerchantWalletView;
import com.petplatform.payment.api.dto.WalletTypes.WalletPage;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawCommand;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawalOrderView;
import com.petplatform.payment.api.query.WalletQueryApi;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentWalletStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentWalletMapper.AcquiringMerchantRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentWalletMapper.PendingSettlementRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentWalletMapper.ReceiverSnapshotRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentWalletMapper.StuckWithdrawalRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentWalletMapper.WithdrawalRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Input;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol;
import com.petplatform.payment.biz.task.WalletQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskRecoverer;
import com.petplatform.task.core.JdbcAsyncTaskStatusReader;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 80 §4.4 & §5 / Ruling 40 §5.5 merchant wallet balance & D1 withdrawal service (Stage 5).
 *
 * <p>Hard invariants:
 * <ul>
 *   <li>Only merchant OWNER callers ({@link OperatorType#MERCHANT_STAFF}; OWNER membership enforced at
 *       HTTP boundary) may initiate withdrawals; admin ({@link OperatorType#PLATFORM_OPERATOR}) is
 *       strictly read-only on withdrawal ledgers (Ruling 22).</li>
 *   <li>No SMS OTP on withdrawal (Ruling 40 §5.5); every write enforces {@code requestId} idempotency.</li>
 *   <li>Live balance comes strictly from Lakala 1269 {@code curBalance} ({@code payType="04"}); merchant
 *       wallet never exposes platform subsidy payable (Ruling 40 §9).</li>
 *   <li>3-phase send discipline: no Lakala HTTP calls inside DB transactions; unknown 1319 outcomes
 *       transition to {@code PROCESSING} and enqueue durable 1271 polling by {@code merOrderNo}.</li>
 * </ul>
 */
public final class WalletService implements WalletCommandApi, WalletQueryApi {
    private static final Logger LOG = LoggerFactory.getLogger(WalletService.class);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String CURRENCY_CNY = "CNY";
    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration STUCK_GRACE = Duration.ofMinutes(5);
    private static final Set<String> VALID_STATUS_FILTERS = Set.of("CREATED", "PROCESSING", "SUCCEEDED", "FAILED");
    private static final Set<String> ALIVE_TASK_STATES = Set.of("READY", "RUNNING", "RETRY_WAIT");

    public record Settings(String orgNo, String withdrawNotifyUrl) {
        public Settings {
            if (orgNo == null || orgNo.isBlank() || orgNo.length() > 32) {
                throw new IllegalArgumentException("wallet orgNo is required");
            }
            orgNo = orgNo.trim();
            withdrawNotifyUrl = withdrawNotifyUrl == null || withdrawNotifyUrl.isBlank()
                    ? null : withdrawNotifyUrl.trim();
        }
    }

    private final SnowflakeIdGenerator ids;
    private final PaymentWalletStore store;
    private final SettlementReceiverLookup receiverLookup;
    private final WalletChannel channel;
    private final WalletReceiptWriter receiptWriter;
    private final JdbcAsyncTaskSubmitter taskSubmitter;
    private final JdbcAsyncTaskStatusReader taskReader;
    private final JdbcAsyncTaskRecoverer taskRecoverer;
    private final Settings settings;
    private final Clock clock;
    private final TransactionTemplate tx;

    public WalletService(
            DataSource source,
            SnowflakeIdGenerator ids,
            SettlementReceiverLookup receiverLookup,
            WalletChannel channel,
            WalletReceiptWriter receiptWriter,
            JdbcAsyncTaskSubmitter taskSubmitter,
            Settings settings,
            Clock clock) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.receiverLookup = Objects.requireNonNull(receiverLookup, "receiverLookup");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.receiptWriter = Objects.requireNonNull(receiptWriter, "receiptWriter");
        this.taskSubmitter = Objects.requireNonNull(taskSubmitter, "taskSubmitter");
        this.taskReader = new JdbcAsyncTaskStatusReader(source);
        this.taskRecoverer = new JdbcAsyncTaskRecoverer(source, ids);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = new PaymentWalletStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    // ---- WalletQueryApi --------------------------------------------------------------------

    @Override
    public MerchantWalletView getMerchantWallet(String merchantId, QueryContext context) {
        noOuterTransaction();
        if (context == null || context.operatorType() != OperatorType.MERCHANT_STAFF) {
            throw forbidden("merchant wallet requires authenticated merchant OWNER");
        }
        long merchant = parsePositiveId(merchantId, "merchantId");

        // Read receiver status and local pending settlement projection (never includes platform_subsidy_due).
        ReceiverSnapshotRow snapshot = store.wallet.selectReceiverSnapshot(merchant);
        PendingSettlementRow pending = store.wallet.selectPendingSettlementSummary(merchant);
        long pendingCount = pending == null ? 0L : pending.pendingCount;
        BigDecimal pendingAmount = pending == null || pending.pendingAmount == null
                ? ZERO : pending.pendingAmount.setScale(2, RoundingMode.UNNECESSARY);

        Optional<WalletAccount> account = resolveActiveAccount(merchant);
        boolean receiverActive = account.isPresent();
        String receiverStatus = receiverActive
                ? "ACTIVE"
                : (snapshot != null && snapshot.status != null ? snapshot.status : "UNCONFIGURED");

        if (!receiverActive) {
            return new MerchantWalletView(receiverStatus, false, ZERO, pendingAmount, pendingCount,
                    CURRENCY_CNY, null, false);
        }

        // Outside DB tx: query live available balance from Lakala 1269 (Mode A payType="01", legacy "04", yuan).
        try {
            var verified = channel.queryBalance(
                    new BalanceQueryInput(settings.orgNo(), account.get().accountNo(), account.get().payType()));
            OffsetDateTime asOf = clock.instant().atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            BigDecimal available = verified.result().curBalance().setScale(2, RoundingMode.UNNECESSARY);
            return new MerchantWalletView(receiverStatus, true, available, pendingAmount, pendingCount,
                    CURRENCY_CNY, asOf, true);
        } catch (LakalaV2HttpClient.ChannelUnknownException
                | LakalaV2HttpClient.ChannelRejectedException
                | LakalaV2Protocol.ProtocolException degraded) {
            LOG.warn("Lakala 1269 balance query unavailable merchantId={} accountNo={} payType={} reason={}",
                    merchantId, account.get().accountNo(), account.get().payType(), degraded.getMessage());
            return new MerchantWalletView(receiverStatus, true, ZERO, pendingAmount, pendingCount,
                    CURRENCY_CNY, null, false);
        }
    }

    @Override
    public WalletPage<WithdrawalOrderView> listWithdrawals(ListWithdrawalsQuery query) {
        if (query == null || query.context() == null) {
            throw invalid("missing list withdrawals query");
        }
        OperatorType opType = query.context().operatorType();
        Long merchantId = parseNullableId(query.merchantId());
        if (opType == OperatorType.MERCHANT_STAFF) {
            if (merchantId == null) {
                throw invalid("merchantId is required for merchant withdrawal queries");
            }
        } else if (opType != OperatorType.PLATFORM_OPERATOR && opType != OperatorType.SYSTEM) {
            throw forbidden("withdrawal ledger requires merchant OWNER or platform operator");
        }

        String status = normalizeOptional(query.status());
        if (status != null && !VALID_STATUS_FILTERS.contains(status)) {
            throw invalid("invalid withdrawal status filter");
        }
        int page = Math.max(1, query.page());
        int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
        int offset = (page - 1) * size;

        Map<String, Object> params = PaymentFoundationStore.values(
                "merchantId", merchantId,
                "status", status,
                "limit", size,
                "offset", offset);
        long total = store.wallet.countWithdrawals(params);
        List<WithdrawalOrderView> items = total == 0 ? List.of()
                : store.wallet.listWithdrawals(params).stream().map(WalletService::toView).toList();
        return new WalletPage<>(items, total, page, size);
    }

    // ---- WalletCommandApi ------------------------------------------------------------------

    @Override
    public WithdrawalOrderView withdraw(WithdrawCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) {
            throw invalid("missing withdraw command");
        }
        if (command.context().operatorType() != OperatorType.MERCHANT_STAFF) {
            throw forbidden("withdrawal is restricted to authenticated merchant OWNER");
        }
        long requestedByUserId = parsePositiveId(command.context().operatorId(), "operatorId");
        long merchantId = parsePositiveId(command.merchantId(), "merchantId");
        String requestId = requireRequestId(command.context());
        BigDecimal amount = requirePositiveAmount(command.amount());

        byte[] requestKey = requestKey(merchantId, command.context(), requestId);
        String fingerprint = sha256((merchantId + "|" + amount.toPlainString()).getBytes(StandardCharsets.UTF_8));

        // Phase 1 (short DB tx): idempotency check + insert durable CREATED withdrawal_order fence.
        Phase1Result phase1 = tx.execute(st -> {
            store.session();
            OffsetDateTime now = store.now();
            WithdrawalRow existing = store.wallet.selectByRequestKeyForUpdate(requestKey);
            if (existing != null) {
                if (!fingerprint.equals(existing.requestFingerprint)) {
                    throw idempotencyConflict("withdrawal requestId already used with different amount");
                }
                String payType = resolvePayType(merchantId, existing.receiverNo);
                if (!"CREATED".equals(existing.status)) {
                    return new Phase1Result(existing, true, payType);
                }
                return new Phase1Result(existing, false, payType);
            }

            WalletAccount account = resolveActiveAccount(merchantId)
                    .orElseThrow(() -> conflict("active merchant acquiring account or split receiver is required before withdrawal"));

            long id = nextId();
            String merOrderNo = "WD" + id;
            try {
                store.wallet.insertWithdrawal(PaymentFoundationStore.values(
                        "id", id,
                        "merOrderNo", merOrderNo,
                        "merchantId", merchantId,
                        "receiverNo", account.accountNo(),
                        "amount", amount,
                        "requestedByUserId", requestedByUserId,
                        "requestKey", requestKey,
                        "requestFingerprint", fingerprint,
                        "now", PaymentWalletStore.utc(now)));
            } catch (DuplicateKeyException race) {
                throw idempotencyConflict("concurrent withdrawal requestId collision");
            }
            return new Phase1Result(store.wallet.selectById(id), false, account.payType());
        });

        if (phase1.replayed()) {
            return toView(phase1.row());
        }

        WithdrawalRow created = phase1.row();
        // Phase 2 (strictly outside DB tx): pre-check 1269 available balance if reachable, then invoke 1319 D1 withdraw.
        try {
            var balance = channel.queryBalance(
                    new BalanceQueryInput(settings.orgNo(), created.receiverNo, phase1.payType()));
            if (!balance.result().isAccountNormal()) {
                failSubmitAndThrow(created, "Receiver channel account is not NORMAL: " + balance.result().acctSt());
            }
            if (balance.result().curBalance().compareTo(created.amount) < 0) {
                failSubmitAndThrow(created, "Insufficient available balance: available="
                        + balance.result().curBalance().toPlainString()
                        + ", requested=" + created.amount.toPlainString());
            }
        } catch (ApiException businessConflict) {
            throw businessConflict;
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            failSubmitAndThrow(created, formatReject(rejected));
        } catch (LakalaV2HttpClient.ChannelUnknownException | LakalaV2Protocol.ProtocolException balanceUnknown) {
            // Balance pre-check failed before 1319 was ever sent; keep row CREATED? Better fail closed as 503
            // while row stays CREATED so same X-Request-Id retry safely resumes Phase 2 without double-withdraw!
            throw unavailable("Lakala 1269 balance pre-check unavailable; retry with the same X-Request-Id");
        }

        final WalletChannel.VerifiedWithdrawSubmit submit;
        try {
            submit = channel.withdrawD1(new WithdrawD1Input(
                    settings.orgNo(),
                    created.receiverNo,
                    created.amount,
                    created.merOrderNo,
                    settings.withdrawNotifyUrl(),
                    "merchant-" + created.merchantId,
                    phase1.payType()));
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            failSubmitAndThrow(created, formatReject(rejected));
            throw new AssertionError(); // unreachable
        } catch (LakalaV2HttpClient.ChannelUnknownException | LakalaV2Protocol.ProtocolException unknown) {
            // Unknown 1319 outcome: move to PROCESSING and enqueue 1271 polling by merOrderNo so funds are tracked!
            tx.executeWithoutResult(st -> {
                store.session();
                OffsetDateTime now = store.now();
                WithdrawalRow locked = store.wallet.selectByIdForUpdate(created.id);
                if (locked != null && "CREATED".equals(locked.status)) {
                    store.wallet.markProcessingUnknown(PaymentFoundationStore.values(
                            "id", locked.id,
                            "version", locked.version,
                            "channelState", "UNKNOWN",
                            "now", PaymentWalletStore.utc(now)));
                    enqueueQueryTask(locked.id, now.plus(WalletQueryTask.FIRST_DELAY));
                }
            });
            throw unavailable("Lakala 1319 withdrawal outcome unknown; query task scheduled");
        }

        // Phase 3 (short DB tx): persist drawJnl -> PROCESSING + enqueue durable 1271 query task.
        return tx.execute(st -> {
            store.session();
            OffsetDateTime now = store.now();
            WithdrawalRow locked = store.wallet.selectByIdForUpdate(created.id);
            if (locked == null) throw unavailable("withdrawal vanished during commit");
            if ("CREATED".equals(locked.status)) {
                int updated = store.wallet.markProcessing(PaymentFoundationStore.values(
                        "id", locked.id,
                        "version", locked.version,
                        "drawJnl", submit.result().drawJnl(),
                        "submitResponseSha256", submit.rawResponseSha256(),
                        "now", PaymentWalletStore.utc(now)));
                if (updated != 1) throw unavailable("concurrent withdrawal state change");
                enqueueQueryTask(locked.id, now.plus(WalletQueryTask.FIRST_DELAY));
            }
            return toView(store.wallet.selectById(created.id));
        });
    }

    @Override
    public void handleWithdrawalNotification(Map<String, String> headers, byte[] rawBody) {
        receiptWriter.applyNotification(headers, rawBody);
    }

    // ---- Durable 1271 Polling & Compensation Hook ------------------------------------------

    /** Executed by {@link WalletQueryTask} worker to reconcile PROCESSING withdrawals via Lakala 1271. */
    public WalletQueryTask.Outcome pollWithdrawalStatus(long withdrawalId) {
        noOuterTransaction();
        WithdrawalRow row = store.wallet.selectById(withdrawalId);
        if (row == null) return WalletQueryTask.Outcome.NOT_FOUND;
        if ("SUCCEEDED".equals(row.status) || "FAILED".equals(row.status)) {
            return WalletQueryTask.Outcome.DONE;
        }
        try {
            var verified = channel.queryWithdrawal(new WithdrawQueryInput(
                    settings.orgNo(), row.receiverNo, row.drawJnl, row.merOrderNo));
            var result = receiptWriter.applyQueryOutcome(row.id, verified.outcome(), verified.rawResponseSha256());
            return result.terminal() ? WalletQueryTask.Outcome.DONE : WalletQueryTask.Outcome.PENDING;
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            LOG.warn("Lakala 1271 query rejected withdrawalId={} retCode={} retMsg={}",
                    IDS.toApi(withdrawalId), rejected.retCode(), rejected.retMsg());
            return WalletQueryTask.Outcome.PENDING;
        } catch (LakalaV2HttpClient.ChannelUnknownException | LakalaV2Protocol.ProtocolException unknown) {
            LOG.warn("Lakala 1271 query transient failure withdrawalId={} reason={}",
                    IDS.toApi(withdrawalId), unknown.getMessage());
            return WalletQueryTask.Outcome.PENDING;
        }
    }

    /** Stage 6 compensation hook: recovers stuck PROCESSING/CREATED withdrawals missing an alive 1271 task. */
    public int scanStuckWithdrawals() {
        noOuterTransaction();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime cutoffTime = now.minus(STUCK_GRACE).truncatedTo(ChronoUnit.MILLIS);
        List<StuckWithdrawalRow> candidates = store.wallet.selectStuckWithdrawals(
                PaymentFoundationStore.values("cutoff", PaymentWalletStore.utc(cutoffTime), "limit", 100));
        int recovered = 0;
        for (StuckWithdrawalRow candidate : candidates) {
            String taskKey = WalletQueryTask.taskKey(candidate.id);
            if (isAlive(taskReader.status(taskKey))) continue;

            Boolean ok = tx.execute(st -> {
                store.session();
                WithdrawalRow locked = store.wallet.selectByIdForUpdate(candidate.id);
                if (locked == null || (!"CREATED".equals(locked.status) && !"PROCESSING".equals(locked.status))) {
                    return Boolean.FALSE;
                }
                if (isAlive(taskReader.status(taskKey))) {
                    return Boolean.FALSE;
                }
                OffsetDateTime anchor = locked.sentAt != null
                        ? PaymentWalletStore.offset(locked.sentAt).plus(WalletQueryTask.FIRST_DELAY)
                        : PaymentWalletStore.offset(locked.createdAt).plus(WalletQueryTask.FIRST_DELAY);
                try {
                    taskRecoverer.recover(
                            taskKey,
                            WalletQueryTask.OWNER_MODULE,
                            WalletQueryTask.TASK_TYPE,
                            WalletQueryTask.BIZ_TYPE,
                            locked.id,
                            null,
                            WalletQueryTask.payload(locked.id),
                            WalletQueryTask.MAX_RETRY_COUNT,
                            WalletQueryTask.RETRY_POLICY,
                            anchor.truncatedTo(ChronoUnit.MILLIS));
                    return Boolean.TRUE;
                } catch (IllegalArgumentException fallbackToNullSchedule) {
                    taskRecoverer.recover(
                            taskKey,
                            WalletQueryTask.OWNER_MODULE,
                            WalletQueryTask.TASK_TYPE,
                            WalletQueryTask.BIZ_TYPE,
                            locked.id,
                            null,
                            WalletQueryTask.payload(locked.id),
                            WalletQueryTask.MAX_RETRY_COUNT,
                            WalletQueryTask.RETRY_POLICY,
                            null);
                    return Boolean.TRUE;
                }
            });
            if (Boolean.TRUE.equals(ok)) {
                recovered++;
                LOG.warn("Recovered stuck WALLET_WITHDRAW_QUERY task withdrawalId={}", IDS.toApi(candidate.id));
            }
        }
        return recovered;
    }

    // ---- Internal Helpers ------------------------------------------------------------------

    private record WalletAccount(String accountNo, String payType) {}

    private record Phase1Result(WithdrawalRow row, boolean replayed, String payType) {}

    private Optional<WalletAccount> resolveActiveAccount(long merchantId) {
        AcquiringMerchantRow acquiring = store.wallet.selectMerchantAcquiringAccount(merchantId);
        if (acquiring != null && acquiring.enabled
                && acquiring.lakalaMerchantNo != null && !acquiring.lakalaMerchantNo.isBlank()) {
            return Optional.of(new WalletAccount(
                    acquiring.lakalaMerchantNo.trim(),
                    LakalaLaepProtocol.PAY_TYPE_MERCHANT_COLLECT));
        }
        return receiverLookup.findActiveByMerchantId(merchantId)
                .map(r -> new WalletAccount(r.receiverNo(), LakalaLaepProtocol.PAY_TYPE_RECEIVER));
    }

    private String resolvePayType(long merchantId, String accountNo) {
        AcquiringMerchantRow acquiring = store.wallet.selectMerchantAcquiringAccount(merchantId);
        if (acquiring != null && acquiring.lakalaMerchantNo != null
                && acquiring.lakalaMerchantNo.trim().equals(accountNo)) {
            return LakalaLaepProtocol.PAY_TYPE_MERCHANT_COLLECT;
        }
        return LakalaLaepProtocol.PAY_TYPE_RECEIVER;
    }

    private void failSubmitAndThrow(WithdrawalRow created, String reason) {
        tx.executeWithoutResult(st -> {
            store.session();
            OffsetDateTime now = store.now();
            WithdrawalRow locked = store.wallet.selectByIdForUpdate(created.id);
            if (locked != null && "CREATED".equals(locked.status)) {
                receiptWriter.recordSubmitRejection(locked.id, locked.version, reason, now);
            }
        });
        throw conflict(reason);
    }

    private void enqueueQueryTask(long withdrawalId, OffsetDateTime executeAt) {
        taskSubmitter.enqueueAt(
                WalletQueryTask.taskKey(withdrawalId),
                WalletQueryTask.OWNER_MODULE,
                WalletQueryTask.TASK_TYPE,
                WalletQueryTask.BIZ_TYPE,
                withdrawalId,
                null,
                WalletQueryTask.payload(withdrawalId),
                WalletQueryTask.MAX_RETRY_COUNT,
                WalletQueryTask.RETRY_POLICY,
                executeAt.truncatedTo(ChronoUnit.MILLIS));
    }

    private static WithdrawalOrderView toView(WithdrawalRow row) {
        return new WithdrawalOrderView(
                IDS.toApi(row.id),
                row.merOrderNo,
                IDS.toApi(row.merchantId),
                row.receiverNo,
                row.amount.setScale(2, RoundingMode.UNNECESSARY),
                row.feeAmount == null ? null : row.feeAmount.setScale(2, RoundingMode.UNNECESSARY),
                row.netAmount == null ? null : row.netAmount.setScale(2, RoundingMode.UNNECESSARY),
                row.status,
                row.drawJnl,
                row.failReason,
                row.channelState,
                IDS.toApi(row.requestedByUserId),
                PaymentWalletStore.offset(row.requestedAt),
                PaymentWalletStore.offset(row.finishedAt),
                PaymentWalletStore.offset(row.createdAt));
    }

    private static String formatReject(LakalaV2HttpClient.ChannelRejectedException rejected) {
        return rejected.retCode() + (rejected.retMsg() == null || rejected.retMsg().isBlank()
                ? "" : ": " + rejected.retMsg());
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw invalid("withdrawal amount must be positive with at most 2 decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String requireRequestId(CommandContext ctx) {
        String req = ctx.requestId();
        if (req == null || req.isBlank() || req.length() > 128) {
            throw invalid("requestId is required");
        }
        return req.trim();
    }

    private static byte[] requestKey(long merchantId, CommandContext ctx, String requestId) {
        return ("wallet.withdraw/" + ctx.operatorType().name() + "/" + ctx.operatorId()
                + "/" + merchantId + "/" + requestId).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private long nextId() {
        long id = ids.nextId();
        if (id <= 0) throw unavailable("snowflake id generator returned invalid id");
        return id;
    }

    private static boolean isAlive(String status) {
        return status != null && ALIVE_TASK_STATES.contains(status);
    }

    private static String normalizeOptional(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static long parsePositiveId(String raw, String field) {
        try { return IDS.fromApi(raw); }
        catch (IllegalArgumentException bad) { throw invalid("invalid " + field); }
    }

    private static Long parseNullableId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return parsePositiveId(raw, "merchantId");
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("wallet operation must not run inside caller transaction");
        }
    }

    private static ApiException invalid(String msg) { return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg); }
    private static ApiException forbidden(String msg) { return new ApiException(CommonApiCodes.FORBIDDEN, msg); }
    private static ApiException conflict(String msg) { return new ApiException(CommonApiCodes.CONFLICT, msg); }
    private static ApiException idempotencyConflict(String msg) {
        return new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, msg);
    }
    private static ApiException unavailable(String msg) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
    }
}
