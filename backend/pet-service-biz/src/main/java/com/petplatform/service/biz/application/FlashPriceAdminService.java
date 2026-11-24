package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.AdminReviewCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPricePage;
import com.petplatform.service.api.error.ServiceFlashPriceApiCodes;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.FlashPriceRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ParticipantCountRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ServiceFactsRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Contract 77 §4 operations face. Approve re-reads the locked service row for the authoritative
 * current price, re-snapshots list_price_snapshot, recomputes flash_price and refuses when the
 * flash price no longer beats the current price (FLASH_PRICE_INVALID). Operators never set a
 * price; every action lands in the append-only audit table.
 */
public final class FlashPriceAdminService extends FlashPriceSupport {

    /** Module-local port for the admin-list store-name decoration (merchant domain owner). */
    public interface FlashPriceStoreFactsPort {
        /** Returns the store display name, or null when the store id is not addressable. */
        String storeName(long storeId);
    }

    private final FlashPriceStoreFactsPort storeFacts;

    public FlashPriceAdminService(FlashPriceStore store, FlashPriceStoreFactsPort storeFacts,
            Clock clock) {
        super(store, clock);
        this.storeFacts = storeFacts; // nullable: without the port the decoration stays null
    }

    // ------------------------------------------------------------------ list

    public FlashPricePage list(String status, int page, int pageSize, QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "platform operator context is required");
        }
        if (page < 1 || page > 10_000) invalid("page is invalid");
        if (pageSize < 1 || pageSize > 50) invalid("pageSize is invalid");
        if (status != null && !FLASH_STATUSES.contains(status)) invalid("status is invalid");
        List<FlashPriceItem> items = store.read(mapper -> {
            List<FlashPriceRow> rows = mapper.selectAdminPage(status, pageSize, (page - 1) * pageSize);
            Map<Long, Long> participants = new HashMap<>();
            if (!rows.isEmpty()) {
                for (ParticipantCountRow bucket
                        : mapper.countParticipantsBatch(rows.stream().map(r -> r.id).toList())) {
                    if (bucket.flashPriceId != null && bucket.participants != null) {
                        participants.put(bucket.flashPriceId, bucket.participants);
                    }
                }
            }
            Map<Long, String> storeNames = new HashMap<>();
            List<FlashPriceItem> out = new ArrayList<>(rows.size());
            for (FlashPriceRow row : rows) {
                requireRowSanity(row);
                String storeName = storeNames.get(row.storeId);
                if (!storeNames.containsKey(row.storeId)) {
                    storeName = storeFacts == null ? null : storeFacts.storeName(row.storeId);
                    storeNames.put(row.storeId, storeName);
                }
                out.add(FlashPriceMerchantService.item(
                        row, participants.getOrDefault(row.id, 0L), row.serviceName, storeName));
            }
            return out;
        });
        long total = store.read(mapper -> mapper.countAdmin(status));
        return new FlashPricePage(List.copyOf(items), page, pageSize, total);
    }

    // ------------------------------------------------------------------ approve

    public FlashPriceItem approve(AdminReviewCommand command) {
        CommandContextHelper ctx =
                new CommandContextHelper(requireOperator(command == null ? null : command.context()));
        long flashPriceId = id(command.flashPriceId(), "flashPriceId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("flashPriceId", Long.toUnsignedString(flashPriceId));
        params.put("expectedVersion", Long.toString(command.expectedVersion()));
        return idempotent(
                ctx.key("approve"),
                "admin.approve",
                params,
                FlashPriceSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    // Lock order service -> flash matches the merchant write path, so the two
                    // faces can never deadlock on the same pair.
                    FlashPriceRow preview = mapper.selectById(flashPriceId);
                    requireRowSanity(preview);
                    if (!Set.of("PENDING_REVIEW", "PAUSED").contains(preview.status)) {
                        stateNotAllowed();
                    }
                    ServiceFactsRow service = mapper.lockServiceFacts(preview.serviceId);
                    if (service == null || service.id == null) {
                        throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_INVALID,
                                "服务已不存在，无法通过审核");
                    }
                    FlashPriceRow row = mapper.selectByIdForUpdate(flashPriceId);
                    requireRowSanity(row);
                    if (row.serviceId.longValue() != preview.serviceId.longValue()) {
                        unavailable("flash-price facts changed");
                    }
                    if (!Set.of("PENDING_REVIEW", "PAUSED").contains(row.status)) stateNotAllowed();
                    if (row.version != command.expectedVersion()) conflict("version changed");
                    LocalDateTime now = nowUtc();
                    if (!now.isBefore(row.endAt)) stateNotAllowed();
                    String toStatus = now.isBefore(row.startAt) ? "SCHEDULED" : "ACTIVE";
                    if ("PENDING_REVIEW".equals(row.status)) {
                        if (!"ACTIVE".equals(service.status)) {
                            throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_INVALID,
                                    "服务当前未上架，无法通过审核");
                        }
                        BigDecimal current = service.price;
                        if (current == null || current.signum() <= 0) {
                            unavailable("service price facts are damaged");
                        }
                        // 77 §4.2 revalidation under the locked service row: the submitted
                        // flash price must still beat the current service price (a price cut
                        // below it means the merchant must adjust; 409 FLASH_PRICE_INVALID),
                        // the window must not collide with another live window, and only then
                        // does the fresh snapshot + recomputed flash price land.
                        if (row.flashPrice.compareTo(current) >= 0) {
                            throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_INVALID,
                                    "服务当前价已不高于秒杀价，请调整折扣后重新提交");
                        }
                        if (mapper.countOverlap(row.serviceId, row.id, row.startAt, row.endAt) > 0) {
                            overlap();
                        }
                        BigDecimal flash = computeFlashPrice(current, row.discountRate);
                        if (mapper.updateApprovedPrice(flashPriceId, List.of("PENDING_REVIEW"),
                                toStatus, current, flash, (int) command.expectedVersion(), now) != 1) {
                            conflict("version changed");
                        }
                    } else {
                        // PAUSED resume: derive the restored status from the window clock.
                        if (mapper.updateStatus(flashPriceId, List.of("PAUSED"), toStatus, null,
                                (int) command.expectedVersion(), now) != 1) {
                            conflict("version changed");
                        }
                    }
                    audit(mapper, flashPriceId, "APPROVE", row.status, toStatus, ctx.context, null,
                            ctx.key("approve"));
                    return decorated(mapper, flashPriceId, service);
                }),
                FlashPriceSupport::receiptJson);
    }

    // ------------------------------------------------------------------ reject / pause / end

    public FlashPriceItem reject(AdminReviewCommand command) {
        String reason = command == null ? null : command.reason();
        if (reason == null) invalid("reason is required");
        String text = reason.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > 500) {
            invalid("reason length is invalid");
        }
        return decide(command, "reject", "REJECT", "REJECTED", Set.of("PENDING_REVIEW"), text);
    }

    public FlashPriceItem pause(AdminReviewCommand command) {
        return decide(command, "pause", "PAUSE", "PAUSED", Set.of("SCHEDULED", "ACTIVE"), null);
    }

    public FlashPriceItem end(AdminReviewCommand command) {
        return decide(command, "end", "END", "ENDED",
                Set.of("SCHEDULED", "ACTIVE", "PAUSED"), null);
    }

    private FlashPriceItem decide(
            AdminReviewCommand command,
            String commandType,
            String actionType,
            String toStatus,
            Set<String> fromStatuses,
            String reason) {
        CommandContextHelper ctx =
                new CommandContextHelper(requireOperator(command == null ? null : command.context()));
        long flashPriceId = id(command.flashPriceId(), "flashPriceId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("flashPriceId", Long.toUnsignedString(flashPriceId));
        params.put("expectedVersion", Long.toString(command.expectedVersion()));
        params.put("reason", reason);
        return idempotent(
                ctx.key(commandType),
                "admin." + commandType,
                params,
                FlashPriceSupport::parseReceipt,
                () -> store.execute(mapper -> {
                    FlashPriceRow row = mapper.selectByIdForUpdate(flashPriceId);
                    requireRowSanity(row);
                    if (!fromStatuses.contains(row.status)) stateNotAllowed();
                    if (row.version != command.expectedVersion()) conflict("version changed");
                    if (mapper.updateStatus(flashPriceId, List.copyOf(fromStatuses), toStatus,
                            reason, (int) command.expectedVersion(), nowUtc()) != 1) {
                        conflict("version changed");
                    }
                    audit(mapper, flashPriceId, actionType, row.status, toStatus, ctx.context,
                            reason, ctx.key(commandType));
                    ServiceFactsRow service = mapper.selectServiceFacts(row.serviceId);
                    return decorated(mapper, flashPriceId, service);
                }),
                FlashPriceSupport::receiptJson);
    }

    // ------------------------------------------------------------------ helpers

    private FlashPriceItem decorated(
            com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper mapper,
            long flashPriceId, ServiceFactsRow service) {
        FlashPriceRow row = mapper.selectById(flashPriceId);
        requireRowSanity(row);
        String serviceName = service == null ? null : service.serviceName;
        String storeName = storeFacts == null ? null : storeFacts.storeName(row.storeId);
        return FlashPriceMerchantService.item(
                row, mapper.countParticipants(flashPriceId), serviceName, storeName);
    }

    /** Small carrier so the audit and the binding share one requestKey per command. */
    private record CommandContextHelper(com.petplatform.common.CommandContext context) {
        String key(String commandType) {
            return requestKey("admin." + commandType, context);
        }
    }
}
