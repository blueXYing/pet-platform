package com.petplatform.marketing.api;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import com.petplatform.marketing.api.dto.MarketingViews;
import java.time.OffsetDateTime;

/**
 * Merchant-facing marketing writes/queries (contract 71 §5). Every command is scoped to one
 * owned merchant/store; ownership/visibility is re-proved by the boot-wired store facts port.
 * Merchants never issue coupons, never publish, and never bind another merchant's store.
 */
public interface MarketingMerchantApi {

    MarketingViews.Page<MarketingViews.MerchantCampaignView> listOpenCampaigns(
            MerchantCampaignListQuery query);

    MarketingViews.Receipt enroll(EnrollCommand command);

    MarketingViews.Page<MarketingViews.EnrollmentView> listMyEnrollments(
            MerchantEnrollmentListQuery query);

    MarketingViews.Receipt withdraw(WithdrawCommand command);

    MarketingViews.Page<MarketingViews.CampaignCard> listMyCampaigns(
            MerchantMyCampaignListQuery query);

    MarketingViews.Receipt createMyCampaign(MerchantCreateCampaignCommand command);

    MarketingViews.Receipt updateMyCampaign(MerchantUpdateCampaignCommand command);

    MarketingViews.Receipt submitMyCampaign(MerchantCampaignStatusCommand command);

    MarketingViews.Receipt cancelMyCampaign(MerchantCampaignStatusCommand command);

    record MerchantScope(long userId, long merchantId, long storeId) {}

    record MerchantCampaignListQuery(
            MerchantScope scope, int page, int pageSize, QueryContext context) {}

    record EnrollCommand(
            MerchantScope scope, String campaignId, String offerTag, String offerTitle,
            String offerCopy, String materialsJson, CommandContext context) {}

    record MerchantEnrollmentListQuery(
            MerchantScope scope, String status, int page, int pageSize, QueryContext context) {}

    record WithdrawCommand(
            String enrollmentId, long expectedVersion, MerchantScope scope, CommandContext context) {}

    record MerchantMyCampaignListQuery(
            MerchantScope scope, String status, int page, int pageSize, QueryContext context) {}

    record MerchantCreateCampaignCommand(
            MerchantScope scope, String name, String title, String subtitle,
            String tagCode, String tagName, String coverUrl,
            OffsetDateTime startAt, OffsetDateTime endAt, CommandContext context) {}

    record MerchantUpdateCampaignCommand(
            String campaignId, String name, String title, String subtitle,
            String tagCode, String tagName, String coverUrl,
            OffsetDateTime startAt, OffsetDateTime endAt, long expectedVersion,
            MerchantScope scope, CommandContext context) {}

    record MerchantCampaignStatusCommand(
            String campaignId, long expectedVersion, MerchantScope scope, CommandContext context) {}
}
