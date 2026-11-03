package com.petplatform.marketing.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Marketing center read/write projections (contract 71). IDs are decimal Snowflake Strings;
 * money is a two-decimal String; times are ISO-8601 UTC with millisecond precision.
 * The C detail is intentionally store-link based: boot composes merchant display facts and
 * coupon campaign facts so the marketing kernel never depends on another biz module.
 */
public final class MarketingViews {

    private MarketingViews() {}

    public record Page<T>(List<T> items, int page, int pageSize, long total) {}

    public record CampaignCard(
            String campaignId,
            String campaignCode,
            String name,
            String ownerType,
            String campaignType,
            String participationMode,
            String status,
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
            int pageVersion,
            int storeCount,
            int sortWeight,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record CampaignStoreLink(
            String storeId,
            String merchantId,
            String source,
            String status,
            String offerTag,
            String offerTitle,
            String offerCopy,
            int sortWeight) {}

    public record PageVersion(int version, OffsetDateTime publishedAt) {}

    public record CampaignDetail(
            CampaignCard campaign,
            List<CampaignStoreLink> stores,
            String pageSchemaJson,
            String publishedPageSchemaJson,
            List<PageVersion> pageVersions) {}

    public record PlacementCard(
            String placementId,
            String positionCode,
            String campaignId,
            String title,
            String subtitle,
            String tagCode,
            String tagName,
            String coverUrl,
            String templateCode,
            String jumpType,
            String jumpParams,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            int sortWeight) {}

    public record PlacementRow(
            String placementId,
            String positionCode,
            String campaignId,
            String campaignTitle,
            String cityCode,
            String titleOverride,
            String subtitleOverride,
            String imageUrlOverride,
            String templateCode,
            int sortWeight,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String status,
            int version) {}

    public record TagView(String tagCode, String tagName, int campaignCount) {}

    /** CCR-MKT-003 C-side home content block; target navigation stays frontend-whitelisted. */
    public record HomeBlockCard(
            String blockId,
            String positionCode,
            String templateCode,
            String title,
            String subtitle,
            String tagName,
            String imageUrl,
            String targetType,
            String targetValue,
            String cityCode,
            String sponsorMerchantId,
            String sponsorStoreId,
            int sortWeight) {}

    /** Admin projection carries version/status/window for editing. */
    public record HomeBlockRow(
            String blockId,
            String blockCode,
            String positionCode,
            String templateCode,
            String title,
            String subtitle,
            String tagName,
            String imageUrl,
            String targetType,
            String targetValue,
            String cityCode,
            String sponsorMerchantId,
            String sponsorStoreId,
            int sortWeight,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String status,
            int version) {}

    public record EnrollmentView(
            String enrollmentId,
            String campaignId,
            String campaignTitle,
            String merchantId,
            String storeId,
            String storeName,
            String status,
            String offerTag,
            String offerTitle,
            String offerCopy,
            String materialsJson,
            String rejectReason,
            OffsetDateTime submittedAt,
            OffsetDateTime decidedAt,
            String decidedBy,
            int version) {}

    public record MerchantCampaignView(
            CampaignCard campaign,
            String enrollmentStatus,
            boolean enrolled) {}

    /** Public/uploaded marketing artwork metadata; bytes are served by the asset endpoint. */
    public record AssetView(
            String assetId,
            String contentType,
            long bytes,
            int width,
            int height,
            String sha256,
            OffsetDateTime createdAt) {}

    /** Common write receipt; unused fields stay null/zero. */
    public record Receipt(
            String id,
            String status,
            long version,
            int pageVersion,
            OffsetDateTime occurredAt) {}

    /** Admin preview: draft schema plus the selected store links (no merchant PII). */
    public record PagePreview(
            String campaignId,
            int draftVersion,
            int publishedVersion,
            String draftSchemaJson,
            List<CampaignStoreLink> stores) {}
}
