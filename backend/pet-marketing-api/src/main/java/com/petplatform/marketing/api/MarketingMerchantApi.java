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

    /**
     * Contract 76 §4.1: P1 marketing hub overview. Only the four provable counters are
     * returned; no order/revenue/conversion figures are ever computed here.
     */
    MarketingViews.MerchantOverview overview(MerchantOverviewQuery query);

    /** Contract 76 §4.2: merchant campaign-cover artwork upload (PNG/JPEG, sniffed server-side). */
    MarketingViews.AssetView uploadAsset(UploadMerchantAssetCommand command);

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
            String description, String themeCode, String highlightText,
            String playType, String coverAssetId,
            OffsetDateTime startAt, OffsetDateTime endAt, CommandContext context) {}

    record MerchantUpdateCampaignCommand(
            String campaignId, String name, String title, String subtitle,
            String tagCode, String tagName, String coverUrl,
            String description, String themeCode, String highlightText,
            String playType, String coverAssetId,
            OffsetDateTime startAt, OffsetDateTime endAt, long expectedVersion,
            MerchantScope scope, CommandContext context) {}

    record MerchantOverviewQuery(MerchantScope scope, QueryContext context) {}

    record UploadMerchantAssetCommand(
            MerchantScope scope, byte[] content, String contentType, CommandContext context) {

        public UploadMerchantAssetCommand {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    record MerchantCampaignStatusCommand(
            String campaignId, long expectedVersion, MerchantScope scope, CommandContext context) {}
}
