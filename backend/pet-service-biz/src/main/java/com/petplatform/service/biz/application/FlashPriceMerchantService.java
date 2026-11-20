package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CancelCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CreateFlashPriceCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPricePage;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.SubmitCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.UpdateFlashPriceCommand;
import com.petplatform.service.api.error.ServiceFlashPriceApiCodes;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.FlashPriceRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ParticipantCountRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ServiceFactsRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Contract 77 §3 merchant face. Owner admission reuses the service-domain gate (SVCW-D5: 404
 * anti-enumeration without the owned-store fact, 503 on unreadable facts). Every write is
 * requestId-idempotent over service_flash_price_command, optimistic-versioned and audited; the
 * overlap check runs under the locked service_item row so two concurrent creates serialize
 * (77 §3.2). Merchants never publish — no approve/pause/end path exists here.
 */
public final class FlashPriceMerchantService extends FlashPriceSupport {

    private static final Set<String> EDITABLE = Set.of("DRAFT", "REJECTED");
    private static final Set<String> SUBMITTABLE = Set.of("DRAFT", "REJECTED");
    private static final Set<String> CANCELLABLE = Set.of("DRAFT", "REJECTED", "PENDING_REVIEW");

    private final ServiceAdmissionGate admissions;

    public FlashPriceMerchantService(
            FlashPriceStore store, ServiceAdmissionGate admissions, Clock clock) {
        super(store, clock);
        this.admissions = Objects.requireNonNull(admissions, "admissions is required");
    }

    // ------------------------------------------------------------------ list

    public FlashPricePage list(
            String serviceIdValue, String status, int page, int pageSize, QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "merchant session is required");
        }
        long serviceId = id(serviceIdValue, "serviceId");
        if (page < 1 || page > 10_000) invalid("page is invalid");
        if (pageSize < 1 || pageSize > 50) invalid("pageSize is invalid");
        if (status != null && !FLASH_STATUSES.contains(status)) invalid("status is invalid");
        return store.read(mapper -> {
            ServiceFactsRow service = mapper.selectServiceFacts(serviceId);
            if (service == null || service.id == null) notFound();
            admissions.requireOperable(context, service.merchantId, service.storeId);
            List<FlashPriceRow> rows =
                    mapper.selectMerchantPage(serviceId, status, pageSize, (page - 1) * pageSize);
            long total = mapper.countMerchant(serviceId, status);
            Map<Long, Long> participants = participants(mapper, rows);
            List<FlashPriceItem> items = new ArrayList<>(rows.size());
            for (FlashPriceRow row : rows) {
                items.add(item(row, participants.getOrDefault(row.id, 0L), service.serviceName, null));
            }
            return new FlashPricePage(List.copyOf(items), page, pageSize, total);
        });
    }

    // ------------------------------------------------------------------ create

    public FlashPriceItem create(CreateFlashPriceCommand command) {
        CommandContext ctx = requireUser(command == null ? null : command.context());
        long serviceId = id(command.serviceId(), "serviceId");
        PreparedFlash fields = prepare(
                command.title(), command.description(), command.priceModeLabel(),
                command.discountRate(), command.startAt(), command.endAt(),
                command.totalQuota(), command.perUserLimit());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceId", Long.toUnsignedString(serviceId));
        params.put("fields", fields.canonical());
        return idempotent(
                requestKey("create", ctx),
                "create",
                params,
                FlashPriceSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    ServiceFactsRow service = lockedService(mapper, serviceId);
                    admissions.requireOperable(queryOf(ctx), service.merchantId, service.storeId);
                    requireServiceActive(service);
                    requireNoOverlap(mapper, serviceId, null, fields);
                    long rowId = store.nextId();
                    insertDraft(mapper, rowId, service, fields, "MERCHANT", operatorId(ctx));
                    audit(mapper, rowId, "CREATE", null, "DRAFT", ctx, null,
                            requestKey("create", ctx));
                    return item(mapper.selectById(rowId), 0L, service.serviceName, null);
                }),
                FlashPriceSupport::receiptJson);
    }

    // ------------------------------------------------------------------ update

    public FlashPriceItem update(UpdateFlashPriceCommand command) {
        CommandContext ctx = requireUser(command == null ? null : command.context());
        long serviceId = id(command.serviceId(), "serviceId");
        long flashPriceId = id(command.flashPriceId(), "flashPriceId");
        PreparedFlash fields = prepare(
                command.title(), command.description(), command.priceModeLabel(),
                command.discountRate(), command.startAt(), command.endAt(),
                command.totalQuota(), command.perUserLimit());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceId", Long.toUnsignedString(serviceId));
        params.put("flashPriceId", Long.toUnsignedString(flashPriceId));
        params.put("expectedVersion", Long.toString(command.expectedVersion()));
        params.put("fields", fields.canonical());
        return idempotent(
                requestKey("update", ctx),
                "update",
                params,
                FlashPriceSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    ServiceFactsRow service = lockedService(mapper, serviceId);
                    admissions.requireOperable(queryOf(ctx), service.merchantId, service.storeId);
                    requireServiceActive(service);
                    FlashPriceRow row = mapper.selectByIdForUpdate(flashPriceId);
                    requireRowSanity(row);
                    if (row.serviceId != serviceId) flashNotFound();
                    if (!EDITABLE.contains(row.status)) stateNotAllowed();
                    requireVersion(row, command.expectedVersion());
                    requireNoOverlap(mapper, serviceId, row.id, fields);
                    LocalDateTime now = nowUtc();
                    if (mapper.updateFlashPrice(
                            flashPriceId, title(fields, service), fields.description(),
                            fields.discountRate(), computeFlashPrice(service.price, fields.discountRate()),
                            service.price, fields.startAt(), fields.endAt(), fields.totalQuota(),
                            fields.perUserLimit(), null, (int) command.expectedVersion(), now) != 1) {
                        conflict("version changed");
                    }
                    audit(mapper, flashPriceId, "UPDATE", row.status, row.status, ctx, null,
                            requestKey("update", ctx));
                    return item(mapper.selectById(flashPriceId), participantsOf(mapper, flashPriceId),
                            service.serviceName, null);
                }),
                FlashPriceSupport::receiptJson);
    }

    // ------------------------------------------------------------------ submit / cancel

    public FlashPriceItem submit(SubmitCommand command) {
        return statusChange(
                command == null ? null : command.context(),
                command.serviceId(),
                command.flashPriceId(),
                command.expectedVersion(),
                SUBMITTABLE,
                "PENDING_REVIEW",
                "SUBMIT",
                null);
    }

    public FlashPriceItem cancel(CancelCommand command) {
        return statusChange(
                command == null ? null : command.context(),
                command.serviceId(),
                command.flashPriceId(),
                command.expectedVersion(),
                CANCELLABLE,
                "CANCELED",
                "CANCEL",
                null);
    }

    private FlashPriceItem statusChange(
            CommandContext ctx,
            String serviceIdValue,
            String flashPriceIdValue,
            long expectedVersion,
            Set<String> fromStatuses,
            String toStatus,
            String actionType,
            String reason) {
        final CommandContext context = requireUser(ctx);
        final long serviceId = id(serviceIdValue, "serviceId");
        final long flashPriceId = id(flashPriceIdValue, "flashPriceId");
        final long version = expectedVersion;
        String commandType = "merchant." + actionType.toLowerCase();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceId", Long.toUnsignedString(serviceId));
        params.put("flashPriceId", Long.toUnsignedString(flashPriceId));
        params.put("expectedVersion", Long.toString(expectedVersion));
        return idempotent(
                requestKey(commandType, context),
                commandType,
                params,
                FlashPriceSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    ServiceFactsRow service = lockedService(mapper, serviceId);
                    admissions.requireOperable(queryOf(context), service.merchantId, service.storeId);
                    FlashPriceRow row = mapper.selectByIdForUpdate(flashPriceId);
                    requireRowSanity(row);
                    if (row.serviceId != serviceId) flashNotFound();
                    if (!fromStatuses.contains(row.status)) stateNotAllowed();
                    requireVersion(row, version);
                    if (mapper.updateStatus(flashPriceId, List.copyOf(fromStatuses), toStatus,
                            null, (int) version, nowUtc()) != 1) {
                        conflict("version changed");
                    }
                    audit(mapper, flashPriceId, actionType, row.status, toStatus, context, reason,
                            requestKey(commandType, context));
                    return item(mapper.selectById(flashPriceId), participantsOf(mapper, flashPriceId),
                            service.serviceName, null);
                }),
                FlashPriceSupport::receiptJson);
    }

    // ------------------------------------------------------------------ shared helpers

    /** Locks the service row: ownership read + overlap serialization in one place. */
    private ServiceFactsRow lockedService(com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper mapper,
            long serviceId) {
        ServiceFactsRow service = mapper.lockServiceFacts(serviceId);
        if (service == null || service.id == null || service.merchantId == null
                || service.storeId == null) notFound();
        return service;
    }

    private static void requireServiceActive(ServiceFactsRow service) {
        if (!"ACTIVE".equals(service.status)) {
            throw new ApiException(
                    com.petplatform.service.api.error.ServiceWriteApiCodes.SERVICE_STATE_NOT_ALLOWED,
                    "服务必须为已上架状态");
        }
        if (service.price == null || service.price.signum() <= 0) {
            unavailable("service price facts are damaged");
        }
    }

    private void requireNoOverlap(
            com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper mapper,
            long serviceId, Long excludeId, PreparedFlash fields) {
        if (mapper.countOverlap(serviceId, excludeId, fields.startAt(), fields.endAt()) > 0) overlap();
    }

    private static void requireVersion(FlashPriceRow row, long expectedVersion) {
        if (row.version != expectedVersion) conflict("version changed");
    }

    private void insertDraft(
            com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper mapper,
            long rowId, ServiceFactsRow service, PreparedFlash fields, String creatorType,
            long creatorId) {
        BigDecimal flash = computeFlashPrice(service.price, fields.discountRate());
        if (mapper.insertFlashPrice(rowId, service.id, service.merchantId, service.storeId,
                title(fields, service), fields.description(), fields.discountRate(), flash,
                service.price, fields.startAt(), fields.endAt(), fields.totalQuota(),
                fields.perUserLimit(), "DRAFT", creatorType, creatorId, nowUtc()) != 1) {
            unavailable("flash-price insert failed");
        }
    }

    private static String title(PreparedFlash fields, ServiceFactsRow service) {
        if (fields.title() != null) return fields.title();
        if (service.serviceName == null || service.serviceName.isBlank()) {
            unavailable("service name fact is missing for the default title");
        }
        return service.serviceName;
    }

    private long participantsOf(
            com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper mapper,
            long flashPriceId) {
        return mapper.countParticipants(flashPriceId);
    }

    private static Map<Long, Long> participants(
            com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper mapper,
            List<FlashPriceRow> rows) {
        Map<Long, Long> out = new HashMap<>();
        if (rows.isEmpty()) return out;
        List<Long> ids = rows.stream().map(row -> row.id).toList();
        for (ParticipantCountRow bucket : mapper.countParticipantsBatch(ids)) {
            if (bucket.flashPriceId != null && bucket.participants != null) {
                out.put(bucket.flashPriceId, bucket.participants);
            }
        }
        return out;
    }

    private static QueryContext queryOf(CommandContext ctx) {
        return new QueryContext(ctx.traceId(), OperatorType.USER, ctx.operatorId());
    }

    /** Wire projection with fail-closed row sanity; money keeps its 2-decimal stored scale. */
    static FlashPriceItem item(FlashPriceRow row, long participantCount, String serviceName,
            String storeName) {
        requireRowSanity(row);
        return new FlashPriceItem(
                idString(row.id), idString(row.serviceId), idString(row.merchantId),
                idString(row.storeId), row.title, row.description, row.priceMode,
                row.discountRate, row.flashPrice, row.listPriceSnapshot,
                offset(row.startAt), offset(row.endAt), row.totalQuota, row.perUserLimit,
                row.soldCount, row.status, row.rejectReason, row.version,
                offset(row.createdAt), offset(row.updatedAt), participantCount, serviceName,
                storeName);
    }

    // ------------------------------------------------------------------ field preparation

    protected record PreparedFlash(
            String title, String description, String priceModeLabel, BigDecimal discountRate,
            LocalDateTime startAt, LocalDateTime endAt, Integer totalQuota, Integer perUserLimit,
            Map<String, Object> canonical) {}

    /** 77 §2.1/§3.2 field rules; the canonical map feeds the requestId binding. */
    static PreparedFlash prepare(
            String title, String description, String priceModeLabel, BigDecimal discountRate,
            OffsetDateTime startAt, OffsetDateTime endAt, Integer totalQuota,
            Integer perUserLimit) {
        String normalizedTitle = optionalText(title, 100, "title");
        String normalizedDescription = optionalText(description, 300, "description");
        String label = optionalPriceModeLabel(priceModeLabel);
        BigDecimal rate = requireDiscountRate(discountRate);
        if (startAt == null || endAt == null) invalid("time window is required");
        requireWindow(startAt, endAt);
        Integer quota = optionalQuota(totalQuota, 1_000_000, "totalQuota");
        Integer limit = optionalQuota(perUserLimit, 1_000, "perUserLimit");
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("title", normalizedTitle);
        canonical.put("description", normalizedDescription);
        canonical.put("priceModeLabel", label);
        canonical.put("discountRate", rate.toPlainString());
        canonical.put("startAt", startAt.toString());
        canonical.put("endAt", endAt.toString());
        canonical.put("totalQuota", quota);
        canonical.put("perUserLimit", limit);
        return new PreparedFlash(normalizedTitle, normalizedDescription, label, rate,
                utc(startAt), utc(endAt), quota, limit, canonical);
    }
}
