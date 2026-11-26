package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.ReconcileAdminApi;
import com.petplatform.payment.api.dto.ReconcileTypes.ListReconcileDiffsQuery;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileDiffView;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcilePage;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileRunSummary;
import com.petplatform.payment.api.dto.ReconcileTypes.ResolveReconcileDiffCommand;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentReconcileStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReconcileMapper.DiffRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReconcileMapper.JobRunRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReconcileMapper.ReconcileInstructionRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedDetailPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedResultListPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.DetailItem;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.ResultListItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 80 §4.6 daily SACS reconciliation service (Lakala 1879 + 1877) and manual diff resolution.
 *
 * <p>Hard invariants:
 * <ul>
 *   <li>All Lakala 1879/1877 HTTP calls execute strictly outside DB transactions.</li>
 *   <li>Never mutates {@code settlement_order} or {@code settlement_instruction} ledger states; only
 *       records deduped {@code reconcile_diff} alerts and {@code reconcile_job_run} summaries.</li>
 *   <li>{@link #resolveDiff} enforces {@code requestId} idempotency, non-blank note, and optimistic CAS.</li>
 * </ul>
 */
public final class SettlementReconcileService implements ReconcileAdminApi {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementReconcileService.class);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CHANNEL_PAGES = 1000;
    private static final int CHANNEL_PAGE_SIZE = LakalaSacsReconcileProtocol.MAX_PAGE_SIZE;
    private static final Set<String> VALID_DIFF_TYPES = Set.of(
            "MISSING_LOCAL", "MISSING_CHANNEL", "AMOUNT_MISMATCH", "STATUS_MISMATCH", "FEE_MISMATCH");
    private static final Set<String> VALID_DIFF_STATUSES = Set.of("OPEN", "RESOLVED");
    private static final Set<String> CHANNEL_INFLIGHT = Set.of("ACCEPTED", "PROCESSING", "ACC_ACCEPTED");

    private final SnowflakeIdGenerator ids;
    private final PaymentReconcileStore store;
    private final SettlementReconcileChannel channel;
    private final String platformMerchantNo;
    private final String platformReceiverNo;
    private final ZoneId channelTimeZone;
    private final Clock clock;
    private final TransactionTemplate tx;

    public SettlementReconcileService(
            DataSource source,
            SnowflakeIdGenerator ids,
            SettlementReconcileChannel channel,
            String platformMerchantNo,
            ZoneId channelTimeZone,
            Clock clock) {
        this(source, ids, channel, platformMerchantNo, null, channelTimeZone, clock);
    }

    public SettlementReconcileService(
            DataSource source,
            SnowflakeIdGenerator ids,
            SettlementReconcileChannel channel,
            String platformMerchantNo,
            String platformReceiverNo,
            ZoneId channelTimeZone,
            Clock clock) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.channel = Objects.requireNonNull(channel, "channel");
        if (platformMerchantNo == null || platformMerchantNo.isBlank() || platformMerchantNo.length() > 32) {
            throw new IllegalArgumentException("platformMerchantNo is required");
        }
        this.platformMerchantNo = platformMerchantNo.trim();
        this.platformReceiverNo = platformReceiverNo == null || platformReceiverNo.isBlank()
                ? null : platformReceiverNo.trim();
        this.channelTimeZone = Objects.requireNonNull(channelTimeZone, "channelTimeZone");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = new PaymentReconcileStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    @Override
    public ReconcilePage<ReconcileDiffView> listDiffs(ListReconcileDiffsQuery query) {
        if (query == null) throw invalid("missing reconcile diffs query");
        requireAdminOrSystemQuery(query.context());

        String diffType = normalizeOptional(query.diffType());
        if (diffType != null && !VALID_DIFF_TYPES.contains(diffType)) {
            throw invalid("invalid diffType filter");
        }
        String status = normalizeOptional(query.status());
        if (status != null && !VALID_DIFF_STATUSES.contains(status)) {
            throw invalid("invalid status filter");
        }
        int page = Math.max(1, query.page());
        int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
        int offset = (page - 1) * size;

        Map<String, Object> params = PaymentFoundationStore.values(
                "reconcileDate", query.reconcileDate(),
                "diffType", diffType,
                "status", status,
                "limit", size,
                "offset", offset);
        long total = store.reconcile.countDiffs(params);
        List<ReconcileDiffView> items = total == 0 ? List.of()
                : store.reconcile.listDiffs(params).stream()
                        .map(SettlementReconcileService::toDiffView)
                        .toList();
        return new ReconcilePage<>(items, total, page, size);
    }

    @Override
    public ReconcileDiffView resolveDiff(ResolveReconcileDiffCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing resolve command");
        requireAdminOrSystem(command.context());
        String requestId = requireRequestId(command.context());
        long diffId = parsePositiveId(command.diffId(), "diffId");
        String note = requireNote(command.note());
        Long operatorId = parseNullableId(command.context().operatorId());
        byte[] resolveRequestKey = requestKey("settlement.reconcile.diff.resolve", command.context(), requestId);

        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            DiffRow byReq = store.reconcile.selectDiffByResolveRequestKeyForUpdate(resolveRequestKey);
            if (byReq != null) {
                if (byReq.id == diffId && note.equals(byReq.resolveNote)) {
                    return toDiffView(byReq);
                }
                throw idempotencyConflict("reconcile diff resolve requestId conflict");
            }

            DiffRow row = store.reconcile.selectDiffByIdForUpdate(diffId);
            if (row == null) throw notFound("reconcile diff not found");
            if (!"OPEN".equals(row.status)) {
                throw conflict("reconcile diff status " + row.status + " cannot be resolved");
            }

            int updated;
            try {
                updated = store.reconcile.resolveDiff(PaymentFoundationStore.values(
                        "id", row.id,
                        "version", row.version,
                        "operatorId", operatorId,
                        "resolveRequestKey", resolveRequestKey,
                        "resolveNote", note,
                        "now", PaymentReconcileStore.utc(now)));
            } catch (DuplicateKeyException race) {
                throw idempotencyConflict("reconcile diff resolve requestId conflict");
            }
            if (updated != 1) throw conflict("reconcile diff changed concurrently");
            return toDiffView(store.reconcile.selectDiffById(diffId));
        });
    }

    @Override
    public ReconcileRunSummary runDailyReconcile(LocalDate date, CommandContext context) {
        noOuterTransaction();
        if (date == null || context == null) throw invalid("reconcileDate and context are required");
        requireAdminOrSystem(context);

        LocalDateTime dayStartUtc = date.atStartOfDay(channelTimeZone)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime dayEndUtcExclusive = date.plusDays(1).atStartOfDay(channelTimeZone)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        List<String> merchantNos = store.reconcile.selectDistinctReconcileMerchantNos(
                PaymentFoundationStore.values(
                        "dayStartUtc", dayStartUtc,
                        "dayEndUtcExclusive", dayEndUtcExclusive,
                        "reconcileDate", date));
        if (merchantNos == null || merchantNos.isEmpty()) {
            merchantNos = List.of(platformMerchantNo);
        }
        if (merchantNos.size() == 1) {
            return runDailyReconcileForMerchant(date, merchantNos.getFirst(), dayStartUtc, dayEndUtcExclusive);
        }

        List<ReconcileRunSummary> completed = new ArrayList<>(merchantNos.size());
        RuntimeException firstFailure = null;
        for (String merchantNo : merchantNos) {
            try {
                completed.add(runDailyReconcileForMerchant(date, merchantNo, dayStartUtc, dayEndUtcExclusive));
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        ReconcileRunSummary first = completed.getFirst();
        ReconcileRunSummary last = completed.getLast();
        int totalChannel = completed.stream().mapToInt(ReconcileRunSummary::channelCount).sum();
        int totalLocal = completed.stream().mapToInt(ReconcileRunSummary::localCount).sum();
        int totalDiff = completed.stream().mapToInt(ReconcileRunSummary::diffCount).sum();
        return new ReconcileRunSummary(
                last.runId(),
                date,
                "MULTI(" + completed.size() + ")",
                "COMPLETED",
                totalChannel,
                totalLocal,
                totalDiff,
                null,
                first.startedAt(),
                last.finishedAt());
    }

    private ReconcileRunSummary runDailyReconcileForMerchant(
            LocalDate date, String merchantNo, LocalDateTime dayStartUtc, LocalDateTime dayEndUtcExclusive) {
        long runId = startOrRestartJobRun(date, merchantNo);
        try {
            // Phase 1 (outside DB tx): fetch all 1879 pages for this business date & acquiring merchant.
            Map<String, ResultListItem> channelByOutNo = fetchAllResultListItems(date, merchantNo);

            // Phase 2 (read-only DB): load local instructions for this business date & acquiring merchant.
            List<ReconcileInstructionRow> localRows = store.reconcile.selectInstructionsForReconcileDate(
                    PaymentFoundationStore.values(
                            "merchantNo", merchantNo,
                            "dayStartUtc", dayStartUtc,
                            "dayEndUtcExclusive", dayEndUtcExclusive,
                            "reconcileDate", date));
            Map<String, ReconcileInstructionRow> localByOutNo = new LinkedHashMap<>();
            for (ReconcileInstructionRow row : localRows) {
                localByOutNo.put(row.outSeparateNo, row);
            }

            // Phase 3 (outside DB tx): compare channel vs local; fetch 1877 details as needed.
            List<PendingDiff> diffs = compareChannelAndLocal(date, merchantNo, channelByOutNo, localByOutNo);

            // Phase 4 (short DB tx): persist deduped reconcile_diff alerts + job run summary.
            int channelCount = channelByOutNo.size();
            int localCount = localByOutNo.size();
            int diffCount = diffs.size();
            return finishJobRun(runId, date, merchantNo, channelCount, localCount, diffCount, diffs);
        } catch (ChannelUnknownException channelFailure) {
            markJobRunFailed(runId, date, merchantNo, "CHANNEL_UNKNOWN:" + channelFailure.reason().name());
            throw unavailable("Lakala 1879/1877 reconciliation query failed: " + channelFailure.reason());
        } catch (RuntimeException failure) {
            markJobRunFailed(runId, date, merchantNo, failure.getMessage() != null
                    ? failure.getMessage() : failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private long startOrRestartJobRun(LocalDate date, String merchantNo) {
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            JobRunRow existing = store.reconcile.selectJobRunByDateAndMerchantForUpdate(
                    PaymentFoundationStore.values("reconcileDate", date, "merchantNo", merchantNo));
            if (existing == null) {
                long id = nextId();
                try {
                    store.reconcile.insertJobRun(PaymentFoundationStore.values(
                            "id", id, "reconcileDate", date, "merchantNo", merchantNo,
                            "now", PaymentReconcileStore.utc(now)));
                    return id;
                } catch (DuplicateKeyException race) {
                    throw conflict("concurrent daily reconcile start for " + date);
                }
            }
            if ("RUNNING".equals(existing.status) && existing.startedAt != null
                    && PaymentReconcileStore.offset(existing.startedAt).isAfter(now.minusMinutes(5))) {
                throw conflict("daily reconcile job is already running for " + date);
            }
            store.reconcile.restartJobRun(PaymentFoundationStore.values(
                    "id", existing.id, "now", PaymentReconcileStore.utc(now)));
            return existing.id;
        });
    }

    private Map<String, ResultListItem> fetchAllResultListItems(LocalDate date, String merchantNo) {
        Map<String, ResultListItem> byOutNo = new LinkedHashMap<>();
        int pageNo = 1;
        int totalPages = 1;
        while (pageNo <= totalPages && pageNo <= MAX_CHANNEL_PAGES) {
            VerifiedResultListPage verified = channel.queryResultList(
                    QueryResultListInput.byDateRange(
                            channelNow(), merchantNo, date, date, pageNo, CHANNEL_PAGE_SIZE));
            var page = verified.page();
            totalPages = Math.max(page.totalPageNum(), 0);
            for (ResultListItem item : page.datas()) {
                byOutNo.put(item.outSeparateNo(), item);
            }
            if (page.datas().isEmpty() || pageNo >= totalPages) {
                break;
            }
            pageNo++;
        }
        return byOutNo;
    }

    private List<DetailItem> fetchDetailsForInstruction(String merchantNo, String outSeparateNo) {
        List<DetailItem> all = new ArrayList<>();
        int pageNo = 1;
        int totalPages = 1;
        while (pageNo <= totalPages && pageNo <= MAX_CHANNEL_PAGES) {
            VerifiedDetailPage verified = channel.queryDetail(
                    QueryDetailInput.byOutSeparateNo(
                            channelNow(), merchantNo, outSeparateNo, pageNo, CHANNEL_PAGE_SIZE));
            var page = verified.page();
            totalPages = Math.max(page.totalPageNum(), 0);
            all.addAll(page.datas());
            if (page.datas().isEmpty() || pageNo >= totalPages) {
                break;
            }
            pageNo++;
        }
        return all;
    }

    private List<PendingDiff> compareChannelAndLocal(
            LocalDate date,
            String merchantNo,
            Map<String, ResultListItem> channelByOutNo,
            Map<String, ReconcileInstructionRow> localByOutNo) {
        List<PendingDiff> diffs = new ArrayList<>();
        Set<String> matchedLocalOutNos = new HashSet<>();

        for (ResultListItem item : channelByOutNo.values()) {
            ReconcileInstructionRow local = localByOutNo.get(item.outSeparateNo());
            if (local == null) {
                local = store.reconcile.selectInstructionByOutSeparateNo(item.outSeparateNo());
            }
            if (local == null && item.separateNo() != null && !item.separateNo().isBlank()) {
                local = store.reconcile.selectInstructionBySeparateNo(item.separateNo());
            }

            if (local == null) {
                String bizType = mapBizType(item.cmdType());
                diffs.add(new PendingDiff(
                        diffKey(date, bizType, "MISSING_LOCAL", item.outSeparateNo()),
                        bizType, "MISSING_LOCAL",
                        null, null, item.outSeparateNo(), item.separateNo(), null,
                        null, item.amount(), null, item.totalFeeAmount(),
                        null, item.status(),
                        truncate("Channel SACS instruction absent from local ledger (cmdType="
                                + item.cmdType() + ",logNo=" + item.logNo() + ")", 512)));
                continue;
            }

            matchedLocalOutNos.add(local.outSeparateNo);
            String bizType = local.instructionType;
            boolean statusMatch = isStatusCompatible(local.status, item.status(), item.cmdType());
            boolean totalAmountMatch = item.amount().compareTo(local.totalAmount) == 0;
            boolean needsDetail = (isChannelSucceeded(item.status(), item.cmdType()) || "SUCCEEDED".equals(local.status))
                    && (item.totalFeeCents() == null || !totalAmountMatch || !statusMatch);

            List<DetailItem> details = List.of();
            if (needsDetail) {
                try {
                    details = fetchDetailsForInstruction(merchantNo, item.outSeparateNo());
                } catch (ChannelUnknownException detailUnavailable) {
                    LOG.warn("Lakala 1877 detail enrichment unavailable merchantNo={} outSeparateNo={} reason={}",
                            merchantNo, item.outSeparateNo(), detailUnavailable.reason());
                }
            }

            BigDecimal detailFeeSum = sumDetailFees(details);
            BigDecimal channelFee = item.totalFeeAmount() != null ? item.totalFeeAmount() : detailFeeSum;
            BigDecimal localFee = local.totalFeeAmount != null ? local.totalFeeAmount.setScale(2) : null;

            if (!statusMatch) {
                diffs.add(new PendingDiff(
                        diffKey(date, bizType, "STATUS_MISMATCH", local.outSeparateNo),
                        bizType, "STATUS_MISMATCH",
                        local.settlementOrderId, local.id, local.outSeparateNo,
                        coalesce(item.separateNo(), local.separateNo), local.merchantId,
                        local.totalAmount, item.amount(), localFee, channelFee,
                        local.status, item.status(),
                        truncate("Instruction status mismatch: local=" + local.status
                                + ", channel=" + item.status()
                                + (item.failReason() != null ? " (" + item.failReason() + ")" : ""), 512)));
            }

            boolean modeA = SettlementModeDetector.isModeA(
                    local.merchantNo, local.receiverNo, platformMerchantNo, platformReceiverNo);
            DetailItem expectedDetail;
            BigDecimal expectedDetailAmt;
            if ("FALLBACK".equals(local.instructionType)) {
                expectedDetail = findDetailByRecv(details, local.receiverNo);
                expectedDetailAmt = local.totalAmount;
            } else if (modeA) {
                expectedDetail = findDetailByRecv(details, local.merchantNo);
                expectedDetailAmt = local.merchantAmount;
            } else {
                expectedDetail = findDetailByRecv(details, local.receiverNo);
                expectedDetailAmt = local.merchantAmount;
            }
            boolean receiverAmtMismatch = expectedDetail != null
                    && expectedDetail.amount().compareTo(expectedDetailAmt) != 0;
            boolean actualAmtMismatch = "SUCCEEDED".equals(local.status)
                    && isChannelSucceeded(item.status(), item.cmdType())
                    && item.actualAmount() != null
                    && local.actualSeparateAmount != null
                    && item.actualAmount().compareTo(local.actualSeparateAmount) != 0;
            if (!totalAmountMatch || receiverAmtMismatch || actualAmtMismatch) {
                String reason = !totalAmountMatch
                        ? "Total amount mismatch: local=" + local.totalAmount + ", channel=" + item.amount()
                        : receiverAmtMismatch
                                ? "Receiver detail amount mismatch: localExpected=" + expectedDetailAmt
                                        + ", channelDetail=" + expectedDetail.amount()
                                : "Actual settled amount mismatch: localActual=" + local.actualSeparateAmount
                                        + ", channelActual=" + item.actualAmount();
                BigDecimal reportedLocal = receiverAmtMismatch ? expectedDetailAmt
                        : actualAmtMismatch ? local.actualSeparateAmount : local.totalAmount;
                BigDecimal reportedChannel = receiverAmtMismatch ? expectedDetail.amount()
                        : actualAmtMismatch ? item.actualAmount() : item.amount();
                diffs.add(new PendingDiff(
                        diffKey(date, bizType, "AMOUNT_MISMATCH", local.outSeparateNo),
                        bizType, "AMOUNT_MISMATCH",
                        local.settlementOrderId, local.id, local.outSeparateNo,
                        coalesce(item.separateNo(), local.separateNo), local.merchantId,
                        reportedLocal, reportedChannel, localFee, channelFee,
                        local.status, item.status(),
                        truncate(reason, 512)));
            }

            boolean bothSucceeded = "SUCCEEDED".equals(local.status)
                    && isChannelSucceeded(item.status(), item.cmdType());
            if (bothSucceeded || channelFee != null) {
                BigDecimal normLocalFee = localFee != null ? localFee : ZERO;
                BigDecimal normChannelFee = channelFee != null ? channelFee.setScale(2) : ZERO;
                if (normLocalFee.compareTo(normChannelFee) != 0) {
                    diffs.add(new PendingDiff(
                            diffKey(date, bizType, "FEE_MISMATCH", local.outSeparateNo),
                            bizType, "FEE_MISMATCH",
                            local.settlementOrderId, local.id, local.outSeparateNo,
                            coalesce(item.separateNo(), local.separateNo), local.merchantId,
                            local.totalAmount, item.amount(), normLocalFee, normChannelFee,
                            local.status, item.status(),
                            truncate("Split fee mismatch: localFee=" + normLocalFee
                                    + ", channelFee=" + normChannelFee, 512)));
                }
            }
        }

        for (ReconcileInstructionRow local : localByOutNo.values()) {
            if (matchedLocalOutNos.contains(local.outSeparateNo)) continue;
            diffs.add(new PendingDiff(
                    diffKey(date, local.instructionType, "MISSING_CHANNEL", local.outSeparateNo),
                    local.instructionType, "MISSING_CHANNEL",
                    local.settlementOrderId, local.id, local.outSeparateNo, local.separateNo, local.merchantId,
                    local.totalAmount, null,
                    local.totalFeeAmount != null ? local.totalFeeAmount.setScale(2) : null, null,
                    local.status, null,
                    truncate("Local SACS instruction missing in Lakala 1879 result list (status="
                            + local.status + ")", 512)));
        }

        return diffs;
    }

    private ReconcileRunSummary finishJobRun(
            long runId, LocalDate date, String merchantNo,
            int channelCount, int localCount, int diffCount, List<PendingDiff> diffs) {
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            LocalDateTime utcNow = PaymentReconcileStore.utc(now);
            for (PendingDiff diff : diffs) {
                store.reconcile.insertDiffIgnore(PaymentFoundationStore.values(
                        "id", nextId(),
                        "reconcileDate", date,
                        "diffKey", diff.diffKey(),
                        "bizType", diff.bizType(),
                        "diffType", diff.diffType(),
                        "settlementOrderId", diff.settlementOrderId(),
                        "instructionId", diff.instructionId(),
                        "outSeparateNo", diff.outSeparateNo(),
                        "separateNo", diff.separateNo(),
                        "merchantId", diff.merchantId(),
                        "localAmount", diff.localAmount(),
                        "channelAmount", diff.channelAmount(),
                        "localFeeAmount", diff.localFeeAmount(),
                        "channelFeeAmount", diff.channelFeeAmount(),
                        "localStatus", diff.localStatus(),
                        "channelStatus", diff.channelStatus(),
                        "detail", diff.detail(),
                        "now", utcNow));
            }
            store.reconcile.completeJobRun(PaymentFoundationStore.values(
                    "id", runId,
                    "channelCount", channelCount,
                    "localCount", localCount,
                    "diffCount", diffCount,
                    "now", utcNow));
            if (diffCount > 0) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        LOG.error("Daily SACS reconciliation alert: discrepancies detected "
                                        + "reconcileDate={} merchantNo={} channelCount={} localCount={} diffCount={}",
                                date, merchantNo, channelCount, localCount, diffCount);
                    }
                });
            }
            return toRunSummary(store.reconcile.selectJobRunById(runId));
        });
    }

    private void markJobRunFailed(long runId, LocalDate date, String merchantNo, String errorMessage) {
        try {
            tx.executeWithoutResult(status -> {
                store.session();
                store.reconcile.failJobRun(PaymentFoundationStore.values(
                        "id", runId,
                        "errorMessage", truncate(errorMessage, 256),
                        "now", PaymentReconcileStore.utc(store.now())));
            });
        } catch (RuntimeException secondary) {
            LOG.error("Failed to persist reconcile_job_run FAILED state runId={} reconcileDate={} merchantNo={}",
                    runId, date, merchantNo, secondary);
        }
        LOG.error("Daily SACS reconciliation failed reconcileDate={} merchantNo={} error={}",
                date, merchantNo, truncate(errorMessage, 256));
    }

    // ---- Comparison helpers ----------------------------------------------------------------

    static boolean isStatusCompatible(String localStatus, String channelStatus, CmdType cmdType) {
        if (localStatus == null || channelStatus == null) return false;
        if ("SUCCESS".equals(channelStatus)) {
            return "SUCCEEDED".equals(localStatus);
        }
        if ("FALLBACK_END".equals(channelStatus)) {
            return "SUCCEEDED".equals(localStatus);
        }
        if ("FALLBACKING".equals(channelStatus)) {
            if (cmdType == CmdType.SEPARATE) {
                return "SUCCEEDED".equals(localStatus);
            }
            return "SENT".equals(localStatus) || "PROCESSING".equals(localStatus);
        }
        if ("FAIL".equals(channelStatus)) {
            return "FAILED".equals(localStatus);
        }
        if (CHANNEL_INFLIGHT.contains(channelStatus)) {
            return "SENT".equals(localStatus) || "PROCESSING".equals(localStatus);
        }
        return false;
    }

    private static boolean isChannelSucceeded(String channelStatus, CmdType cmdType) {
        if ("SUCCESS".equals(channelStatus) || "FALLBACK_END".equals(channelStatus)) {
            return true;
        }
        return cmdType == CmdType.SEPARATE && "FALLBACKING".equals(channelStatus);
    }

    private static BigDecimal sumDetailFees(List<DetailItem> details) {
        if (details == null || details.isEmpty()) return null;
        boolean anyFee = false;
        BigDecimal sum = ZERO;
        for (DetailItem detail : details) {
            if (detail.feeAmountCents() != null) {
                anyFee = true;
                sum = sum.add(detail.feeAmount());
            }
        }
        return anyFee ? sum.setScale(2) : null;
    }

    private static DetailItem findDetailByRecv(List<DetailItem> details, String targetRecv) {
        if (details == null || details.isEmpty() || targetRecv == null || targetRecv.isBlank()) return null;
        for (DetailItem detail : details) {
            if (targetRecv.equals(detail.recvNo())) {
                return detail;
            }
        }
        return null;
    }

    private static String mapBizType(CmdType cmdType) {
        return cmdType == CmdType.FALLBACK ? "FALLBACK" : "SPLIT";
    }

    private static String diffKey(LocalDate date, String bizType, String diffType, String refNo) {
        String raw = date + "|" + bizType + "|" + diffType + "|" + refNo;
        return raw.length() > 128 ? raw.substring(0, 128) : raw;
    }

    // ---- View mappers & guards -------------------------------------------------------------

    private record PendingDiff(
            String diffKey,
            String bizType,
            String diffType,
            Long settlementOrderId,
            Long instructionId,
            String outSeparateNo,
            String separateNo,
            Long merchantId,
            BigDecimal localAmount,
            BigDecimal channelAmount,
            BigDecimal localFeeAmount,
            BigDecimal channelFeeAmount,
            String localStatus,
            String channelStatus,
            String detail) {}

    private static ReconcileDiffView toDiffView(DiffRow row) {
        return new ReconcileDiffView(
                IDS.toApi(row.id),
                row.reconcileDate,
                row.bizType,
                row.diffType,
                row.settlementOrderId == null ? null : IDS.toApi(row.settlementOrderId),
                row.instructionId == null ? null : IDS.toApi(row.instructionId),
                row.outSeparateNo,
                row.separateNo,
                row.merchantId == null ? null : IDS.toApi(row.merchantId),
                row.localAmount,
                row.channelAmount,
                row.localFeeAmount,
                row.channelFeeAmount,
                row.localStatus,
                row.channelStatus,
                row.detail,
                row.status,
                row.resolvedByOperatorId == null ? null : IDS.toApi(row.resolvedByOperatorId),
                row.resolveNote,
                PaymentReconcileStore.offset(row.resolvedAt),
                PaymentReconcileStore.offset(row.createdAt));
    }

    private static ReconcileRunSummary toRunSummary(JobRunRow row) {
        return new ReconcileRunSummary(
                IDS.toApi(row.id),
                row.reconcileDate,
                row.merchantNo,
                row.status,
                row.channelCount,
                row.localCount,
                row.diffCount,
                row.errorMessage,
                PaymentReconcileStore.offset(row.startedAt),
                PaymentReconcileStore.offset(row.finishedAt));
    }

    private LocalDateTime channelNow() {
        return LocalDateTime.ofInstant(clock.instant(), channelTimeZone).withNano(0);
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable("snowflake id generator returned invalid id");
        return value;
    }

    private static String coalesce(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("reconcile operation must not run inside caller transaction");
        }
    }

    private static void requireAdminOrSystem(CommandContext ctx) {
        if (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR && ctx.operatorType() != OperatorType.SYSTEM) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "reconcile command requires PLATFORM_OPERATOR or SYSTEM");
        }
    }

    private static void requireAdminOrSystemQuery(QueryContext ctx) {
        if (ctx == null || (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR
                && ctx.operatorType() != OperatorType.SYSTEM)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "reconcile query requires PLATFORM_OPERATOR or SYSTEM");
        }
    }

    private static String requireRequestId(CommandContext ctx) {
        String req = ctx.requestId();
        if (req == null || req.isBlank() || req.length() > 128) {
            throw invalid("requestId is required");
        }
        return req.trim();
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw invalid("resolve note is required");
        }
        String trimmed = note.trim();
        if (trimmed.length() > 256) {
            throw invalid("resolve note must not exceed 256 characters");
        }
        return trimmed;
    }

    private static byte[] requestKey(String action, CommandContext ctx, String requestId) {
        String op = ctx.operatorId() == null ? "SYSTEM" : ctx.operatorId();
        return (action + "/" + ctx.operatorType().name() + "/" + op + "/" + requestId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static long parsePositiveId(String raw, String field) {
        try { return IDS.fromApi(raw); }
        catch (IllegalArgumentException bad) { throw invalid("invalid " + field); }
    }

    private static Long parseNullableId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return parsePositiveId(raw, "id");
    }

    private static ApiException invalid(String msg) { return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg); }
    private static ApiException conflict(String msg) { return new ApiException(CommonApiCodes.CONFLICT, msg); }
    private static ApiException idempotencyConflict(String msg) {
        return new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, msg);
    }
    private static ApiException notFound(String msg) { return new ApiException(CommonApiCodes.NOT_FOUND, msg); }
    private static ApiException unavailable(String msg) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
    }
}
