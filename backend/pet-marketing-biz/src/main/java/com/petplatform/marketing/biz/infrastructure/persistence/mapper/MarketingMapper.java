package com.petplatform.marketing.biz.infrastructure.persistence.mapper;

import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.AssetRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.BindingRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignStoreRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.EnrollmentRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.HomeBlockRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.PageVersionRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.PlacementRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface MarketingMapper {

    // ---------------------------------------------------------------- campaigns

    List<CampaignRow> selectCampaigns(@Param("status") String status,
            @Param("ownerType") String ownerType, @Param("keyword") String keyword,
            @Param("limit") int limit, @Param("offset") long offset);

    long countCampaigns(@Param("status") String status, @Param("ownerType") String ownerType,
            @Param("keyword") String keyword);

    CampaignRow selectCampaignById(@Param("id") long id);

    CampaignRow selectCampaignForUpdate(@Param("id") long id);

    List<CampaignRow> selectPublicCampaigns(@Param("now") LocalDateTime now);

    List<CampaignRow> selectOpenEnrollmentCampaigns(@Param("now") LocalDateTime now);

    List<CampaignRow> selectMyCampaigns(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("status") String status,
            @Param("limit") int limit, @Param("offset") long offset);

    long countMyCampaigns(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("status") String status);

    int insertCampaign(CampaignRow row);

    int updateCampaign(CampaignRow row);

    int updateCampaignStatus(@Param("id") long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("expectedVersion") long expectedVersion,
            @Param("rejectReason") String rejectReason, @Param("now") LocalDateTime now);

    int updateCampaignPageDraft(@Param("id") long id, @Param("schemaJson") String schemaJson,
            @Param("draftVersion") int draftVersion, @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int touchCampaignVersion(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int publishCampaignPage(@Param("id") long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("pageVersion") int pageVersion,
            @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    int rollbackCampaignPage(@Param("id") long id, @Param("schemaJson") String schemaJson,
            @Param("publishedVersion") int publishedVersion, @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int insertPageVersion(PageVersionRow row);

    List<PageVersionRow> selectPageVersions(@Param("campaignId") long campaignId);

    PageVersionRow selectPageVersion(@Param("campaignId") long campaignId,
            @Param("version") int version);

    int selectMaxPageVersion(@Param("campaignId") long campaignId);

    // ------------------------------------------------------------ campaign stores

    int deleteCampaignStores(@Param("campaignId") long campaignId);

    int insertCampaignStore(CampaignStoreRow row);

    int upsertCampaignStore(CampaignStoreRow row);

    int removeCampaignStore(@Param("campaignId") long campaignId, @Param("storeId") long storeId,
            @Param("now") LocalDateTime now);

    List<CampaignStoreRow> selectCampaignStores(@Param("campaignId") long campaignId);

    long countCampaignStores(@Param("campaignId") long campaignId);

    CampaignStoreRow selectCampaignStore(@Param("campaignId") long campaignId,
            @Param("storeId") long storeId);

    List<CampaignStoreRow> selectActiveStoreCampaigns(@Param("storeId") long storeId,
            @Param("now") LocalDateTime now);

    // ---------------------------------------------------------------- placements

    List<PlacementRow> selectPlacements(@Param("positionCode") String positionCode,
            @Param("status") String status, @Param("limit") int limit, @Param("offset") long offset);

    long countPlacements(@Param("positionCode") String positionCode, @Param("status") String status);

    PlacementRow selectPlacementById(@Param("id") long id);

    PlacementRow selectPlacementForUpdate(@Param("id") long id);

    List<PlacementRow> selectActivePlacements(@Param("positionCode") String positionCode,
            @Param("now") LocalDateTime now);

    long countEnabledPlacements(@Param("positionCode") String positionCode,
            @Param("cityCode") String cityCode, @Param("now") LocalDateTime now);

    int insertPlacement(PlacementRow row);

    int updatePlacement(PlacementRow row);

    int updatePlacementStatus(@Param("id") long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    // ------------------------------------------------------------- home content blocks

    List<HomeBlockRow> selectHomeBlocks(@Param("positionCode") String positionCode,
            @Param("status") String status, @Param("limit") int limit, @Param("offset") long offset);

    long countHomeBlocks(@Param("positionCode") String positionCode,
            @Param("status") String status);

    HomeBlockRow selectHomeBlockById(@Param("id") long id);

    HomeBlockRow selectHomeBlockForUpdate(@Param("id") long id);

    List<HomeBlockRow> selectActiveHomeBlocks(@Param("cityCode") String cityCode,
            @Param("now") LocalDateTime now);

    int insertHomeBlock(HomeBlockRow row);

    int updateHomeBlock(HomeBlockRow row);

    int updateHomeBlockStatus(@Param("id") long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int insertHomeBlockAudit(@Param("id") long id, @Param("homeBlockId") long homeBlockId,
            @Param("actionType") String actionType, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("operatorId") long operatorId,
            @Param("requestId") String requestId, @Param("traceId") String traceId,
            @Param("detailJson") String detailJson, @Param("now") LocalDateTime now);

    // --------------------------------------------------------------- enrollments

    List<EnrollmentRow> selectEnrollments(@Param("campaignId") String campaignId,
            @Param("status") String status, @Param("limit") int limit, @Param("offset") long offset);

    long countEnrollments(@Param("campaignId") String campaignId, @Param("status") String status);

    EnrollmentRow selectEnrollmentById(@Param("id") long id);

    EnrollmentRow selectEnrollmentForUpdate(@Param("id") long id);

    EnrollmentRow selectEnrollmentByCampaignStore(@Param("campaignId") long campaignId,
            @Param("storeId") long storeId);

    List<EnrollmentRow> selectMerchantEnrollments(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("status") String status,
            @Param("limit") int limit, @Param("offset") long offset);

    long countMerchantEnrollments(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("status") String status);

    long countApprovedEnrollments(@Param("campaignId") long campaignId);

    int insertEnrollment(EnrollmentRow row);

    int updateEnrollment(EnrollmentRow row);

    int updateEnrollmentStatus(@Param("id") long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("expectedVersion") long expectedVersion,
            @Param("rejectReason") String rejectReason, @Param("decidedBy") Long decidedBy,
            @Param("now") LocalDateTime now);

    // ------------------------------------------------------------------ assets

    int insertAsset(AssetRow row);

    AssetRow selectAssetById(@Param("id") long id);

    int insertAssetAudit(@Param("id") long id, @Param("assetId") long assetId,
            @Param("actionType") String actionType, @Param("operatorId") long operatorId,
            @Param("requestId") String requestId, @Param("traceId") String traceId,
            @Param("now") LocalDateTime now);

    // ------------------------------------------------------------- audit/idempotency

    int insertCampaignAudit(@Param("id") long id, @Param("campaignId") long campaignId,
            @Param("actionType") String actionType, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("operatorType") String operatorType,
            @Param("operatorId") long operatorId, @Param("requestId") String requestId,
            @Param("traceId") String traceId, @Param("detailJson") String detailJson,
            @Param("now") LocalDateTime now);

    int insertEnrollmentAudit(@Param("id") long id, @Param("enrollmentId") long enrollmentId,
            @Param("actionType") String actionType, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("operatorType") String operatorType,
            @Param("operatorId") long operatorId, @Param("reason") String reason,
            @Param("requestId") String requestId, @Param("traceId") String traceId,
            @Param("now") LocalDateTime now);

    BindingRow selectBinding(@Param("requestKey") String requestKey);

    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
            @Param("commandType") String commandType, @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical, @Param("now") LocalDateTime now);

    int markBindingSucceeded(@Param("requestKey") String requestKey,
            @Param("receiptJson") String receiptJson, @Param("now") LocalDateTime now);

    int deleteBinding(@Param("requestKey") String requestKey);
}
