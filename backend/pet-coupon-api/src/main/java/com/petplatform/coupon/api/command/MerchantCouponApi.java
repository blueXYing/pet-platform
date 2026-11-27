package com.petplatform.coupon.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Merchant self-built coupon face (contract 79 section 3). Merchants create, edit, submit and
 * cancel their own coupon campaigns; publishing never happens here (operators review through
 * {@link MerchantCouponAdminApi}). Money is DECIMAL(18,2); ids are decimal strings.
 */
public interface MerchantCouponApi {

    Page list(ListQuery query);

    Item get(GetQuery query);

    Overview overview(ScopeQuery query);

    Receipt create(CreateCommand command);

    Receipt update(UpdateCommand command);

    Receipt submit(StatusCommand command);

    Receipt cancel(StatusCommand command);

    /** Campaign input; a null thresholdAmount means a no-threshold coupon. */
    record Input(
            String name,
            BigDecimal amountOff,
            BigDecimal thresholdAmount,
            int totalLimit,
            int perUserLimit,
            BigDecimal budgetLimit,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String description) {}

    record ListQuery(String merchantId, String storeId, String status, int page, int pageSize,
            QueryContext context) {}

    record GetQuery(String campaignId, String merchantId, String storeId, QueryContext context) {}

    record ScopeQuery(String merchantId, String storeId, QueryContext context) {}

    record CreateCommand(String merchantId, String storeId, CommandContext context, Input input) {}

    record UpdateCommand(String campaignId, String merchantId, String storeId,
            CommandContext context, long expectedVersion, Input input) {}

    record StatusCommand(String campaignId, String merchantId, String storeId,
            CommandContext context, long expectedVersion) {}

    record Item(
            String campaignId,
            String merchantId,
            String storeId,
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
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    record Page(List<Item> items, int page, int pageSize, long total) {}

    /** redemptionRate = usedCount / issuedCount at scale 4; zero when nothing was issued. */
    record Overview(
            int totalCampaignCount,
            int activeCampaignCount,
            int pendingReviewCount,
            int monthIssuedCount,
            BigDecimal redemptionRate,
            int drivenOrderCount) {}

    record Receipt(String campaignId, String status, long version) {}
}
