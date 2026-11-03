package com.petplatform.marketing.api;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import com.petplatform.marketing.api.dto.MarketingViews;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Platform-ops marketing center writes/queries (contract 71 §4). Every write carries a terminal
 * requestId; idempotency binds in the marketing kernel and the admin authorization owner is
 * re-proved through the boot-wired port before execution.
 */
public interface MarketingAdminApi {

    MarketingViews.Page<MarketingViews.CampaignCard> listCampaigns(CampaignListQuery query);

    MarketingViews.CampaignDetail getCampaign(CampaignQuery query);

    MarketingViews.Receipt createCampaign(CreateCampaignCommand command);

    MarketingViews.Receipt updateCampaign(UpdateCampaignCommand command);

    MarketingViews.Receipt replaceStores(ReplaceStoresCommand command);

    MarketingViews.Receipt savePage(SavePageCommand command);

    MarketingViews.Receipt publishCampaign(CampaignStatusCommand command);

    MarketingViews.Receipt pauseCampaign(CampaignStatusCommand command);

    MarketingViews.Receipt endCampaign(CampaignStatusCommand command);

    MarketingViews.Receipt cancelCampaign(CampaignStatusCommand command);

    MarketingViews.Receipt rejectCampaign(RejectCampaignCommand command);

    MarketingViews.Receipt rollbackPage(RollbackPageCommand command);

    MarketingViews.PagePreview previewCampaign(CampaignQuery query);

    /** CCR-MKT-002: upload one PNG/JPEG artwork; requestId-bound and audited. */
    MarketingViews.AssetView uploadAsset(AssetUploadCommand command);

    MarketingViews.Page<MarketingViews.PlacementRow> listPlacements(PlacementListQuery query);

    MarketingViews.Page<MarketingViews.HomeBlockRow> listHomeBlocks(HomeBlockListQuery query);

    MarketingViews.Receipt createHomeBlock(CreateHomeBlockCommand command);

    MarketingViews.Receipt updateHomeBlock(UpdateHomeBlockCommand command);

    MarketingViews.Receipt enableHomeBlock(HomeBlockStatusCommand command);

    MarketingViews.Receipt disableHomeBlock(HomeBlockStatusCommand command);

    MarketingViews.Receipt createPlacement(CreatePlacementCommand command);

    MarketingViews.Receipt updatePlacement(UpdatePlacementCommand command);

    MarketingViews.Receipt enablePlacement(PlacementStatusCommand command);

    MarketingViews.Receipt disablePlacement(PlacementStatusCommand command);

    MarketingViews.Page<MarketingViews.EnrollmentView> listEnrollments(EnrollmentListQuery query);

    MarketingViews.Receipt reviewEnrollment(ReviewEnrollmentCommand command);

    /**
     * Session reference plus the already-authenticated data scope. The kernel never trusts a
     * caller-supplied scope: boot copies it from the ADMIN_WEB session snapshot, and every write
     * still re-proves the action through the admin authorization owner.
     */
    record AdminAuthorization(String sessionId, long sessionGeneration, String operatorId,
            String scopeMode, List<String> cityCodes, List<String> merchantIds) {
        public AdminAuthorization {
            scopeMode = scopeMode == null || scopeMode.isBlank() ? "ALL" : scopeMode;
            cityCodes = cityCodes == null ? List.of() : List.copyOf(cityCodes);
            merchantIds = merchantIds == null ? List.of() : List.copyOf(merchantIds);
        }

        public AdminAuthorization(String sessionId, long sessionGeneration, String operatorId) {
            this(sessionId, sessionGeneration, operatorId, "ALL", List.of(), List.of());
        }
    }

    record CampaignInput(
            String name,
            String campaignType,
            String participationMode,
            String title,
            String subtitle,
            String tagCode,
            String tagName,
            String coverUrl,
            List<String> cityCodes,
            List<String> serviceCategoryCodes,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            OffsetDateTime enrollStartAt,
            OffsetDateTime enrollEndAt,
            Integer enrollmentQuota,
            String couponCampaignId,
            Integer sortWeight) {}

    record CampaignStoreInput(
            String storeId, String offerTag, String offerTitle, String offerCopy, Integer sortWeight) {}

    record CampaignListQuery(
            String status, String ownerType, String keyword, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    record CampaignQuery(
            String campaignId, AdminAuthorization authorization, QueryContext context) {}

    record CreateCampaignCommand(
            CampaignInput input, AdminAuthorization authorization, CommandContext context) {}

    record UpdateCampaignCommand(
            String campaignId, CampaignInput input, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record ReplaceStoresCommand(
            String campaignId, List<CampaignStoreInput> stores, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record SavePageCommand(
            String campaignId, String schemaJson, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record CampaignStatusCommand(
            String campaignId, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record RejectCampaignCommand(
            String campaignId, String reason, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record RollbackPageCommand(
            String campaignId, int pageVersion, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record PlacementListQuery(
            String positionCode, String status, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    /** CCR-MKT-003: manual home content block input (sponsor ids reserved for later bidding). */
    record HomeBlockInput(
            String blockCode, String positionCode, String templateCode, String title,
            String subtitle, String tagName, String imageUrl, String targetType,
            String targetValue, String cityCode, String sponsorMerchantId,
            String sponsorStoreId, Integer sortWeight, OffsetDateTime startAt,
            OffsetDateTime endAt) {}

    record HomeBlockListQuery(
            String positionCode, String status, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    record CreateHomeBlockCommand(
            HomeBlockInput input, AdminAuthorization authorization, CommandContext context) {}

    record UpdateHomeBlockCommand(
            String blockId, HomeBlockInput input, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record HomeBlockStatusCommand(
            String blockId, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record PlacementInput(
            String positionCode, String campaignId, String cityCode,
            String titleOverride, String subtitleOverride, String imageUrlOverride,
            String templateCode, Integer sortWeight, OffsetDateTime startAt, OffsetDateTime endAt) {}

    record AssetUploadCommand(
            byte[] content, String contentType, AdminAuthorization authorization,
            CommandContext context) {
        public AssetUploadCommand {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    record CreatePlacementCommand(
            PlacementInput input, AdminAuthorization authorization, CommandContext context) {}

    record UpdatePlacementCommand(
            String placementId, PlacementInput input, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record PlacementStatusCommand(
            String placementId, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record EnrollmentListQuery(
            String campaignId, String status, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    record ReviewEnrollmentCommand(
            String enrollmentId, String decision, String reason, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}
}
