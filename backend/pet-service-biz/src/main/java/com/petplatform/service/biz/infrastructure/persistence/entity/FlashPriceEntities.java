package com.petplatform.service.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mutable MyBatis row projections for the contract-77 flash-price tables (SQL73). Public fields
 * keep the slice compact; the MyBatis reflector maps snake_case columns onto camelCase fields
 * (mapUnderscoreToCamelCase=true in FlashPriceStore).
 */
public final class FlashPriceEntities {

    private FlashPriceEntities() {}

    /** One service_flash_price row; serviceName is only filled by the admin join query. */
    public static final class FlashPriceRow {
        public Long id;
        public Long serviceId;
        public Long merchantId;
        public Long storeId;
        public String title;
        public String description;
        public String priceMode;
        public BigDecimal discountRate;
        public BigDecimal flashPrice;
        public BigDecimal listPriceSnapshot;
        public LocalDateTime startAt;
        public LocalDateTime endAt;
        public Integer totalQuota;
        public Integer perUserLimit;
        public Integer soldCount;
        public String status;
        public String rejectReason;
        public Integer version;
        public String createdByType;
        public Long createdById;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public String serviceName;
    }

    /** Minimal locked service_item facts used for ownership, price and overlap serialization. */
    public static final class ServiceFactsRow {
        public Long id;
        public Long merchantId;
        public Long storeId;
        public String serviceName;
        public BigDecimal price;
        public String status;
    }

    /** One service_flash_price_order ledger row. */
    public static final class ReservationRow {
        public Long id;
        public Long flashPriceId;
        public Long orderId;
        public Long userId;
        public BigDecimal reservedPrice;
        public String status;
        public LocalDateTime expireAt;
        public LocalDateTime reservedAt;
        public LocalDateTime consumedAt;
        public LocalDateTime releasedAt;
        public Integer version;
    }

    /** service_flash_price_command requestId binding (IN_PROGRESS/SUCCEEDED). */
    public static final class BindingRow {
        public Long id;
        public String requestKey;
        public String commandType;
        public String paramsSha256;
        public byte[] paramsCanonical;
        public String status;
        public String receiptJson;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    /** Grouped COUNT(DISTINCT user_id) per flash_price_id for list decoration. */
    public static final class ParticipantCountRow {
        public Long flashPriceId;
        public Long participants;
    }
}
