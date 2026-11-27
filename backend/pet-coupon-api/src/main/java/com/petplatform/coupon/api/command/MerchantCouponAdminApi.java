package com.petplatform.coupon.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Operator review face for merchant self-built coupons (contract 79 section 4). Operators
 * approve, reject, pause and end merchant campaigns; they never edit coupon content.
 */
public interface MerchantCouponAdminApi {

    Page list(ListQuery query);

    Item get(GetQuery query);

    Receipt approve(ReviewCommand command);

    Receipt reject(ReviewCommand command);

    Receipt pause(ReviewCommand command);

    Receipt end(ReviewCommand command);

    record AdminAuthorization(String sessionId, long sessionGeneration, String operatorId) {}

    record ListQuery(String status, String merchantId, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    record GetQuery(String campaignId, AdminAuthorization authorization, QueryContext context) {}

    /** reason is required (1..500 code points) for reject and ignored by the other actions. */
    record ReviewCommand(String campaignId, CommandContext context,
            AdminAuthorization authorization, long expectedVersion, String reason) {}

    record Item(
            String campaignId,
            String merchantId,
            String merchantName,
            String storeId,
            String storeName,
            String templateId,
            String name,
            String couponType,
            BigDecimal amountOff,
            BigDecimal thresholdAmount,
            String typeLabel,
            int totalLimit,
            int perUserLimit,
            BigDecimal budgetLimit,
            int issuedCount,
            BigDecimal issuedAmount,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String description,
            String status,
            String rejectReason,
            long version,
            int claimedCount,
            int usedCount,
            int drivenOrderCount,
            BigDecimal budgetUsedAmount,
            OffsetDateTime submittedAt,
            OffsetDateTime reviewedAt,
            String reviewedByType,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    record Page(List<Item> items, int page, int pageSize, long total) {}

    record Receipt(String campaignId, String status, long version) {}
}
