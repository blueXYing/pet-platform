package com.petplatform.payment.biz.center;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentNotificationService.ReceiptResult;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier.VerifiedNotice;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.CenterRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 75 §5/§6: verified notification or query → payment center row state, receipt, and
 * PaymentCenterSucceededEvent.v1 in one local transaction serialized by the payment row lock.
 * Notification and query share this writer so they dedupe and can only produce one success event.
 */
public final class PaymentCenterReceiptWriter {
    public static final String EVENT_TYPE = "PaymentCenterSucceededEvent.v1";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final Set<String> STATES = Set.of("INIT", "CREATE", "SUCCESS", "FAIL", "DEAL", "UNKNOWN",
            "CLOSE", "PART_REFUND", "REFUND", "REVOKED");
    static final Set<String> TERMINAL = Set.of("SUCCESS", "FAIL", "CLOSE");
    private final SnowflakeIdGenerator ids;
    private final PaymentReceiptVerifier verifier;
    private final IntegrationEventPublisher publisher;
    private final ZoneId channelTimeZone;
    private final String platformMerchantNo;
    private final PaymentCenterStore store;
    private final TransactionTemplate tx;

    public PaymentCenterReceiptWriter(DataSource source, SnowflakeIdGenerator ids, PaymentReceiptVerifier verifier,
            IntegrationEventPublisher publisher, ZoneId channelTimeZone, String platformMerchantNo) {
        this.ids = Objects.requireNonNull(ids);
        this.verifier = Objects.requireNonNull(verifier);
        this.publisher = Objects.requireNonNull(publisher);
        this.channelTimeZone = Objects.requireNonNull(channelTimeZone, "channel time zone must be confirmed");
        if (platformMerchantNo == null || platformMerchantNo.isBlank())
            throw new IllegalArgumentException("platform merchant is required");
        this.platformMerchantNo = platformMerchantNo;
        this.store = new PaymentCenterStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    /** Raw signed 1112 body for a payment center row. Unverified data is only a lookup hint. */
    public ReceiptResult receiveNotification(Map<String, String> headers, byte[] body) {
        noOuterTransaction();
        if (body == null || body.length == 0 || body.length > 65_536 || headers == null) throw invalid();
        byte[] copy = body.clone();
        Map<String, String> safeHeaders = Map.copyOf(headers);
        try {
            long no = hintNo(copy);
            CenterRow hint = store.center.selectCenterByNo(no);
            validRow(hint);
            VerifiedNotice notice = verifier.verify(safeHeaders, copy, new PaymentReceiptVerifier.ExpectedPayment(
                    hint.merchantNo(), Long.toString(hint.no()), hint.amount()));
            validateNotice(notice, hint);
            String digest = sha256(copy);
            return persist(hint, notice, digest, "NOTIFICATION", null);
        } catch (ApiException known) { throw known; }
        catch (Exception failure) { throw unavailable(); }
    }

    /** Only the in-module query coordinator and create recovery supply a signature-verified query. */
    ReceiptResult receiveQuery(long paymentId, PaymentChannel.VerifiedQuery query) {
        noOuterTransaction();
        try {
            if (query == null || query.result() == null || !digest(query.responseSha256())) throw unavailable();
            CenterRow hint = store.center.selectCenterById(paymentId);
            validRow(hint);
            var result = query.result();
            var notice = new VerifiedNotice(result.merchantNo(), result.outTradeNo(), result.channelTradeNo(),
                    result.tradeState().name(), BigDecimal.valueOf(result.totalAmountCents(), 2),
                    result.payerAmountCents() == null ? null : BigDecimal.valueOf(result.payerAmountCents(), 2),
                    result.channelTradeTime(), result.accountType(), result.logNo(), result.logDate());
            validateNotice(notice, hint);
            String digest = sha256(("QUERY\0" + query.responseSha256()).getBytes(StandardCharsets.US_ASCII));
            return persist(hint, notice, digest, "QUERY", query.responseSha256());
        } catch (ApiException known) { throw known; }
        catch (Exception failure) { throw unavailable(); }
    }

    static long hintNo(byte[] body) {
        JsonNode raw;
        try { raw = JSON.readTree(body); }
        catch (java.io.IOException malformed) { throw invalid(); }
        if (raw == null || !raw.isObject() || !raw.path("out_trade_no").isTextual()) throw invalid();
        try { return IDS.fromApi(raw.get("out_trade_no").textValue()); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private ReceiptResult persist(CenterRow hint, VerifiedNotice notice, String digest, String receiptSource,
            String channelResponseSha256) {
        return tx.execute(status -> {
            store.session();
            CenterRow row = store.center.selectCenterByIdForUpdate(hint.id());
            validRow(row);
            if (!Objects.equals(row.userId(), hint.userId()) || row.no() != hint.no()
                    || !row.bizType().equals(hint.bizType()) || !row.bizNo().equals(hint.bizNo())
                    || !row.termNo().equals(hint.termNo()) || !row.subAppId().equals(hint.subAppId()))
                throw unavailable();
            validateNotice(notice, row);
            OffsetDateTime now = store.now();
            // The trace columns are a DB-checked pair; a half-present trace is treated as absent.
            boolean trace = notice.logNo() != null && notice.logDate() != null;
            String logNo = trace ? notice.logNo() : null;
            LocalDate logDate = trace ? notice.logDate() : null;
            OffsetDateTime paidAt = channelTime(notice.channelTradeTime());
            if ("SUCCESS".equals(notice.status()) && (paidAt == null || paidAt.isAfter(now))) throw unavailable();
            boolean duplicate = store.shared.countReceiptByDigest(row.id(), digest) > 0;
            if ("PAID".equals(row.status()) && "SUCCESS".equals(notice.status())
                    && (!Objects.equals(row.tradeNo(), notice.channelTradeNo()) || row.paidAmount() == null
                        || row.paidAmount().compareTo(notice.paidAmount()) != 0 || row.paidAt() == null
                        || !row.paidAt().equals(PaymentFoundationStore.utc(paidAt)) || row.successEventId() == null))
                throw unavailable();
            if (row.tradeNo() != null && notice.channelTradeNo() != null && !row.tradeNo().equals(notice.channelTradeNo()))
                throw unavailable();
            if (!duplicate) store.shared.insertReceipt(PaymentFoundationStore.values(
                    "id", nextId(), "paymentId", row.id(), "digest", digest, "source", receiptSource,
                    "responseSha", channelResponseSha256, "tradeNo", notice.channelTradeNo(),
                    "status", notice.status(), "totalAmount", notice.totalAmount(),
                    "paidAmount", notice.paidAmount(),
                    "paidAt", paidAt == null ? null : PaymentFoundationStore.utc(paidAt),
                    "receivedAt", PaymentFoundationStore.utc(now)));
            // Refund/reversal is never an old failure; once reconciliation is required no later
            // receipt (including SUCCESS) may produce a success event.
            if (Set.of("PART_REFUND", "REFUND", "REVOKED").contains(notice.status())
                    || ("CLOSED".equals(row.status()) && !Set.of("CLOSE", "SUCCESS").contains(notice.status()) && !duplicate)
                    || "RECONCILIATION_REQUIRED".equals(row.dispatchState())) {
                if (!"RECONCILIATION_REQUIRED".equals(row.dispatchState())) store.shared.markReconciliation(
                        PaymentFoundationStore.values("now", PaymentFoundationStore.utc(now), "paymentId", row.id()));
                return new ReceiptResult(Long.toString(row.id()), duplicate, "PAID".equals(row.status()));
            }
            if ("PAID".equals(row.status())) {
                if ("SUCCESS".equals(notice.status()) && trace && row.logNo() == null && row.logDate() == null)
                    store.shared.fillCollectTrace(PaymentFoundationStore.values("logNo", logNo, "logDate", logDate,
                            "now", PaymentFoundationStore.utc(now), "paymentId", row.id()));
                return new ReceiptResult(Long.toString(row.id()), true, true);
            }
            if ("SUCCESS".equals(notice.status())) {
                long eventId = nextId();
                // A late SUCCESS after FAILED/CLOSED is still the real channel fact (Contract 40/75 §5.1).
                if (store.shared.markPaid(PaymentFoundationStore.values("tradeNo", notice.channelTradeNo(),
                        "paidAmount", notice.paidAmount(), "paidAt", PaymentFoundationStore.utc(paidAt),
                        "eventId", eventId, "now", PaymentFoundationStore.utc(now), "paymentId", row.id(),
                        "logNo", logNo, "logDate", logDate)) != 1) throw unavailable();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("paymentOrderId", Long.toString(row.id()));
                payload.put("paymentNo", Long.toString(row.no()));
                payload.put("bizType", row.bizType());
                payload.put("bizNo", row.bizNo());
                payload.put("channelTradeNo", notice.channelTradeNo());
                payload.put("paidAmount", notice.paidAmount());
                payload.put("paidAt", MILLIS.format(paidAt.withOffsetSameInstant(ZoneOffset.UTC)));
                publisher.publish(new IntegrationEvent<>(Long.toString(eventId), EVENT_TYPE, 1, now, "PAYMENT",
                        Long.toString(row.id()), null, payload));
                return new ReceiptResult(Long.toString(row.id()), false, true);
            }
            String next = switch (notice.status()) {
                case "FAIL" -> "FAILED";
                case "CLOSE" -> "CLOSED";
                default -> "PAYING";
            };
            if (!duplicate) store.shared.markObserved(PaymentFoundationStore.values("status", next,
                    "tradeNo", notice.channelTradeNo(), "now", PaymentFoundationStore.utc(now), "paymentId", row.id()));
            return new ReceiptResult(Long.toString(row.id()), duplicate, false);
        });
    }

    private OffsetDateTime channelTime(LocalDateTime local) {
        if (local == null) return null;
        var offsets = channelTimeZone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw unavailable();
        return local.atOffset(offsets.getFirst()).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private void validRow(CenterRow r) {
        if (r == null || !r.isCenter() || r.userId() == null || r.userId() <= 0 || r.no() <= 0
                || r.amount() == null || r.amount().signum() <= 0
                || !platformMerchantNo.equals(r.merchantNo()) || r.termNo() == null || r.subAppId() == null
                || !"CNY".equals(r.currency()) || !"LAKALA_WECHAT".equals(r.channel())
                || !Set.of("INIT", "PAYING", "PAID", "FAILED", "CLOSED").contains(r.status())
                || !Set.of("PREPARED", "OBSERVED", "RECONCILIATION_REQUIRED").contains(r.dispatchState()))
            throw unavailable();
    }

    static void validateNotice(VerifiedNotice n, CenterRow r) {
        if (n == null || !Objects.equals(r.merchantNo(), n.merchantNo()) || !Long.toString(r.no()).equals(n.paymentNo())
                || !"WECHAT".equals(n.accountType()) || !STATES.contains(n.status()) || n.totalAmount() == null
                || r.amount().compareTo(n.totalAmount()) != 0) throw unavailable();
        if (n.channelTradeNo() != null && (n.channelTradeNo().isBlank() || n.channelTradeNo().length() > 128
                || n.channelTradeNo().codePoints().anyMatch(Character::isISOControl))) throw unavailable();
        if ("SUCCESS".equals(n.status()) && (n.channelTradeNo() == null || n.paidAmount() == null
                || n.paidAmount().signum() <= 0 || n.paidAmount().scale() > 2
                || n.paidAmount().compareTo(n.totalAmount()) > 0 || n.channelTradeTime() == null))
            throw unavailable();
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable();
        return value;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
    }
    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid payment center notification");
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "payment center receipt requires reconciliation");
    }
}
