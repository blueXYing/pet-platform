package com.petplatform.service.api.dto;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contract 77 (service flash price / limited-time price, CCR-SVC-FLASH-PRICE-001): the §2.1 row
 * projection plus the list payloads. Money is BigDecimal; ids are decimal Snowflake strings on
 * the wire. Merchant inputs never carry status/merchantId/storeId/flashPrice/listPriceSnapshot/
 * soldCount — those are server-fixed.
 */
public final class ServiceFlashPriceTypes {

    private ServiceFlashPriceTypes() {}

    /** Full §2.1 row plus participantCount; serviceName/storeName are admin-list decorations. */
    public record FlashPriceItem(
            String flashPriceId,
            String serviceId,
            String merchantId,
            String storeId,
            String title,
            String description,
            String priceMode,
            BigDecimal discountRate,
            BigDecimal flashPrice,
            BigDecimal listPriceSnapshot,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Integer totalQuota,
            Integer perUserLimit,
            int soldCount,
            String status,
            String rejectReason,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long participantCount,
            String serviceName,
            String storeName) {}

    /** Merchant create body (contract 77 §3.2): no price input, only the discount rate. */
    public record CreateFlashPriceCommand(
            CommandContext context,
            String serviceId,
            String title,
            String description,
            String priceModeLabel,
            BigDecimal discountRate,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Integer totalQuota,
            Integer perUserLimit) {}

    /** Merchant edit (contract 77 §3.3): full revalidation, optimistic expectedVersion. */
    public record UpdateFlashPriceCommand(
            CommandContext context,
            String serviceId,
            String flashPriceId,
            long expectedVersion,
            String title,
            String description,
            String priceModeLabel,
            BigDecimal discountRate,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Integer totalQuota,
            Integer perUserLimit) {}

    /** DRAFT/REJECTED -> PENDING_REVIEW (contract 77 §3.4). */
    public record SubmitCommand(
            CommandContext context, String serviceId, String flashPriceId, long expectedVersion) {}

    /** DRAFT/REJECTED/PENDING_REVIEW -> CANCELED (contract 77 §3.5). */
    public record CancelCommand(
            CommandContext context, String serviceId, String flashPriceId, long expectedVersion) {}

    /** Contract 77 §4 list answer; page 1..10000, pageSize 1..50, default 1/20. */
    public record FlashPricePage(
            List<FlashPriceItem> items, int page, int pageSize, long total) {}

    /** Admin review face (contract 77 §4.2); reason is mandatory only for reject (1..500). */
    public record AdminReviewCommand(
            CommandContext context, String flashPriceId, long expectedVersion, String reason) {}
}
