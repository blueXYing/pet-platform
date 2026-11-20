package com.petplatform.service.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.service.api.error.ServiceFlashPriceApiCodes;
import com.petplatform.service.biz.application.ServiceCanonicalParams.Canonical;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.BindingRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.FlashPriceRow;
import com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared kernel for the contract-77 flash-price commands: field validation, server-side price
 * computation, requestId idempotency over service_flash_price_command (marketing admit pattern),
 * append-only audit rows and the FlashPriceItem receipt codec.
 */
abstract class FlashPriceSupport {

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final BigDecimal RATE_CEILING = new BigDecimal("10");
    protected static final Set<String> PRICE_MODE_LABELS = Set.of("LIMITED_DISCOUNT", "FLASH_SALE");
    protected static final Set<String> OVERLAP_STATUSES =
            Set.of("PENDING_REVIEW", "SCHEDULED", "ACTIVE", "PAUSED");
    protected static final Set<String> FLASH_STATUSES =
            Set.of("DRAFT", "PENDING_REVIEW", "SCHEDULED", "ACTIVE", "PAUSED", "ENDED",
                    "REJECTED", "CANCELED");

    protected final FlashPriceStore store;
    protected final Clock clock;

    protected FlashPriceSupport(FlashPriceStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------ time

    protected LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    protected static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ ids + text

    protected static long id(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            invalid(field + " must be a decimal Snowflake String");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException tooLarge) {
            invalid(field + " is too large");
            return 0;
        }
    }

    protected static String idString(Long value) {
        return value == null ? null : Long.toUnsignedString(value);
    }

    protected static String optionalText(String value, int max, String field) {
        if (value == null) return null;
        String text = value.strip();
        if (text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) > max) invalid(field + " length is invalid");
        return text;
    }

    // ------------------------------------------------------------------ flash fields

    /** discountRate: (0,10) exclusive, at most one decimal place (contract 77 §2.1). */
    protected static BigDecimal requireDiscountRate(BigDecimal value) {
        if (value == null) invalid("discountRate is required");
        BigDecimal rate = value.stripTrailingZeros();
        if (rate.signum() <= 0 || rate.compareTo(RATE_CEILING) >= 0 || rate.scale() > 1) {
            invalid("discountRate must be within (0,10) with at most one decimal place");
        }
        return rate.setScale(1, RoundingMode.UNNECESSARY);
    }

    protected static String optionalPriceModeLabel(String value) {
        if (value == null) return null;
        String label = value.strip();
        if (label.isEmpty()) return null;
        if (!PRICE_MODE_LABELS.contains(label)) invalid("priceModeLabel is invalid");
        return label; // display-only tag: the stored priceMode is always DISCOUNT (77 §2.1)
    }

    protected static void requireWindow(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) invalid("time window is invalid");
    }

    protected static Integer optionalQuota(Integer value, int max, String field) {
        if (value == null) return null;
        if (value < 1 || value > max) invalid(field + " is invalid");
        return value;
    }

    /**
     * flashPrice = round(listPrice × discountRate / 10, 2, HALF_UP); must stay strictly positive
     * and strictly below the list price (77 §2.1, CCR decision 3) — otherwise the amount is
     * unusable and the caller gets FLASH_PRICE_INVALID, never a silently clamped price.
     */
    protected static BigDecimal computeFlashPrice(BigDecimal listPrice, BigDecimal discountRate) {
        Objects.requireNonNull(listPrice, "listPrice");
        Objects.requireNonNull(discountRate, "discountRate");
        BigDecimal flash = listPrice.multiply(discountRate)
                .divide(new BigDecimal("10"), 2, RoundingMode.HALF_UP);
        if (flash.signum() <= 0 || flash.compareTo(listPrice) >= 0) {
            throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_INVALID,
                    "折扣后的秒杀价必须大于0且低于服务当前价，请调整折扣");
        }
        return flash;
    }

    protected static void requireRowSanity(FlashPriceRow row) {
        if (row == null || row.id == null || row.id <= 0 || row.serviceId == null
                || row.serviceId <= 0 || row.merchantId == null || row.merchantId <= 0
                || row.storeId == null || row.storeId <= 0
                || row.title == null || row.title.isBlank()
                || row.discountRate == null || row.flashPrice == null
                || row.listPriceSnapshot == null || row.startAt == null || row.endAt == null
                || row.startAt.isAfter(row.endAt) || row.startAt.equals(row.endAt)
                || row.soldCount == null || row.soldCount < 0
                || row.version == null || row.version < 1
                || !FLASH_STATUSES.contains(String.valueOf(row.status))) {
            unavailable("flash-price row facts are damaged");
        }
    }

    // ------------------------------------------------------------------ contexts

    protected static CommandContext requireUser(CommandContext context) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "merchant session is required");
        }
        requireRequestId(context);
        return context;
    }

    protected static CommandContext requireOperator(CommandContext context) {
        if (context == null || context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "platform operator context is required");
        }
        requireRequestId(context);
        return context;
    }

    private static void requireRequestId(CommandContext context) {
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException | NullPointerException failure) {
            invalid("requestId is invalid");
        }
    }

    protected static long operatorId(CommandContext context) {
        try {
            long operator = Long.parseLong(context.operatorId());
            if (operator <= 0) invalid("operator is invalid");
            return operator;
        } catch (NumberFormatException malformed) {
            invalid("operator is invalid");
            return 0;
        }
    }

    // ------------------------------------------------------------------ idempotency

    /** SVC-FLASH:{commandType}:{sha256(operatorType:operatorId:requestId)} (task brief). */
    protected static String requestKey(String commandType, CommandContext context) {
        String raw = context.operatorType() + ":" + context.operatorId() + ":" + context.requestId();
        return "SVC-FLASH:" + commandType + ":"
                + ServiceCanonicalParams.sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    protected <T> T idempotent(
            String requestKey,
            String commandType,
            Map<String, Object> params,
            Function<String, T> parseReceipt,
            Supplier<T> work,
            Function<T, String> receiptJson) {
        Canonical canonical = ServiceCanonicalParams.of(params);
        Binding binding = admitWithRecovery(requestKey, commandType, canonical);
        requireSame(binding, canonical);
        if ("SUCCEEDED".equals(binding.status())) return parseReceipt.apply(binding.receiptJson());
        if (!"IN_PROGRESS".equals(binding.status())) unavailable("idempotency binding is damaged");
        if (!binding.fresh()) conflict("flash-price command is already in progress");
        try {
            T result = work.get();
            store.execute(mapper -> mapper.markBindingSucceeded(
                    requestKey, receiptJson.apply(result), LocalDateTime.now(clock)));
            return result;
        } catch (FlashPriceStore.CommitUnknown unknown) {
            Binding replay = admitWithRecovery(requestKey, commandType, canonical);
            requireSame(replay, canonical);
            if ("SUCCEEDED".equals(replay.status())) return parseReceipt.apply(replay.receiptJson());
            unavailable("flash-price commit result is unknown");
            return null;
        } catch (RuntimeException failure) {
            try {
                store.execute(mapper -> mapper.deleteBinding(requestKey));
            } catch (RuntimeException ignored) {
                // The actionable failure is the original one; a stale IN_PROGRESS row is
                // recovered by the operator retrying with a fresh requestId.
            }
            throw failure;
        }
    }

    private Binding admitWithRecovery(String requestKey, String commandType, Canonical canonical) {
        try {
            return admit(requestKey, commandType, canonical);
        } catch (FlashPriceStore.CommitUnknown first) {
            try {
                return admit(requestKey, commandType, canonical);
            } catch (FlashPriceStore.CommitUnknown second) {
                unavailable("flash-price idempotency admission result is unknown");
                return null;
            }
        }
    }

    private Binding admit(String requestKey, String commandType, Canonical canonical) {
        return store.execute(mapper -> {
            BindingRow row = mapper.selectBinding(requestKey);
            if (row == null) {
                try {
                    mapper.insertBinding(store.nextId(), requestKey, commandType, canonical.sha256(),
                            canonical.bytes(), LocalDateTime.now(clock));
                } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                    row = requireBinding(mapper, requestKey);
                    return binding(row, false);
                }
                row = requireBinding(mapper, requestKey);
                return binding(row, true);
            }
            return binding(row, false);
        });
    }

    private static BindingRow requireBinding(FlashPriceMapper mapper, String requestKey) {
        BindingRow row = mapper.selectBinding(requestKey);
        if (row == null) unavailable("idempotency binding is missing");
        return row;
    }

    private static Binding binding(BindingRow row, boolean fresh) {
        if (row.paramsCanonical == null || row.paramsSha256 == null || row.status == null
                || !Set.of("IN_PROGRESS", "SUCCEEDED").contains(row.status)
                || ("SUCCEEDED".equals(row.status) && row.receiptJson == null)) {
            unavailable("idempotency binding is damaged");
        }
        if (!ServiceCanonicalParams.sha256Hex(row.paramsCanonical).equals(row.paramsSha256)) {
            unavailable("idempotency binding digest is damaged");
        }
        return new Binding(row.status, row.receiptJson, row.paramsSha256, row.paramsCanonical, fresh);
    }

    private static void requireSame(Binding binding, Canonical canonical) {
        if (!Objects.equals(binding.paramsSha256(), canonical.sha256())
                || !Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "requestId is bound to different parameters");
        }
    }

    protected record Binding(
            String status, String receiptJson, String paramsSha256, byte[] paramsCanonical,
            boolean fresh) {
        public Binding {
            paramsCanonical = paramsCanonical == null ? null : paramsCanonical.clone();
        }

        @Override
        public byte[] paramsCanonical() {
            return paramsCanonical == null ? null : paramsCanonical.clone();
        }
    }

    // ------------------------------------------------------------------ receipt codec

    /** Flat string-only JSON so the plain ObjectMapper round-trips the replayed item. */
    protected static String receiptJson(
            com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flashPriceId", item.flashPriceId());
        out.put("serviceId", item.serviceId());
        out.put("merchantId", item.merchantId());
        out.put("storeId", item.storeId());
        out.put("title", item.title());
        out.put("description", item.description());
        out.put("priceMode", item.priceMode());
        out.put("discountRate", item.discountRate() == null ? null : item.discountRate().toPlainString());
        out.put("flashPrice", item.flashPrice() == null ? null : item.flashPrice().toPlainString());
        out.put("listPriceSnapshot",
                item.listPriceSnapshot() == null ? null : item.listPriceSnapshot().toPlainString());
        out.put("startAt", item.startAt() == null ? null : item.startAt().toString());
        out.put("endAt", item.endAt() == null ? null : item.endAt().toString());
        out.put("totalQuota", item.totalQuota());
        out.put("perUserLimit", item.perUserLimit());
        out.put("soldCount", item.soldCount());
        out.put("status", item.status());
        out.put("rejectReason", item.rejectReason());
        out.put("version", item.version());
        out.put("createdAt", item.createdAt() == null ? null : item.createdAt().toString());
        out.put("updatedAt", item.updatedAt() == null ? null : item.updatedAt().toString());
        out.put("participantCount", item.participantCount());
        out.put("serviceName", item.serviceName());
        out.put("storeName", item.storeName());
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception failure) {
            throw new IllegalStateException("flash-price receipt serialization failed", failure);
        }
    }

    protected static com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem parseReceipt(
            String json) {
        try {
            JsonNode root = JSON.readTree(json);
            return new com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem(
                    root.path("flashPriceId").asText(null),
                    root.path("serviceId").asText(null),
                    root.path("merchantId").asText(null),
                    root.path("storeId").asText(null),
                    root.path("title").asText(null),
                    root.path("description").asText(null),
                    root.path("priceMode").asText(null),
                    decimal(root, "discountRate"),
                    decimal(root, "flashPrice"),
                    decimal(root, "listPriceSnapshot"),
                    time(root, "startAt"),
                    time(root, "endAt"),
                    root.hasNonNull("totalQuota") ? root.get("totalQuota").asInt() : null,
                    root.hasNonNull("perUserLimit") ? root.get("perUserLimit").asInt() : null,
                    root.path("soldCount").asInt(0),
                    root.path("status").asText(null),
                    root.path("rejectReason").asText(null),
                    root.path("version").asLong(0),
                    time(root, "createdAt"),
                    time(root, "updatedAt"),
                    root.path("participantCount").asLong(0),
                    root.path("serviceName").asText(null),
                    root.path("storeName").asText(null));
        } catch (Exception failure) {
            unavailable("stored flash-price receipt is not readable");
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : new BigDecimal(value.asText());
    }

    private static OffsetDateTime time(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : OffsetDateTime.parse(value.asText());
    }

    // ------------------------------------------------------------------ audit

    /**
     * Append-only audit row; the requestKey keeps the unique request_id column meaningful. The
     * stored operator_type is the SQL73 dictionary (MERCHANT/ADMIN, VARCHAR(16)) — the raw
     * context enum PLATFORM_OPERATOR would overflow the column.
     */
    protected void audit(
            FlashPriceMapper mapper,
            long flashPriceId,
            String actionType,
            String fromStatus,
            String toStatus,
            CommandContext context,
            String reason,
            String requestKey) {
        String operatorType = context.operatorType() == OperatorType.PLATFORM_OPERATOR
                ? "ADMIN" : "MERCHANT";
        LocalDateTime now = nowUtc();
        mapper.insertAudit(store.nextId(), flashPriceId, actionType, fromStatus, toStatus,
                operatorType, operatorId(context), requestKey, context.traceId(), reason, now);
    }

    // ------------------------------------------------------------------ errors

    protected static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    protected static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "service resource not found");
    }

    protected static void flashNotFound() {
        throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_NOT_FOUND,
                "秒杀记录不存在或不属于该服务");
    }

    protected static void stateNotAllowed() {
        throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_STATE_NOT_ALLOWED,
                "当前秒杀状态不允许该操作");
    }

    protected static void overlap() {
        throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_WINDOW_OVERLAP,
                "同服务已存在生效时间重叠的秒杀窗口");
    }

    protected static void conflict(String message) {
        throw new ApiException(CommonApiCodes.CONFLICT, message);
    }

    protected static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
