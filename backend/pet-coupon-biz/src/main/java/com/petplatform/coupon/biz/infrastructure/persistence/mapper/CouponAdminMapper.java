package com.petplatform.coupon.biz.infrastructure.persistence.mapper;

import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminBindingEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminInstanceEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminLedgerEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignUsageRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.ClaimRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.MerchantCouponBudgetRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.MerchantCouponStatsRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.MerchantCouponUsageRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * coupon_template / coupon_instance / coupon_ledger admin face (contract 65 §4).
 * SQL lives in resources/mapper/CouponAdminMapper.xml (22号 MyBatis ruling).
 */
public interface CouponAdminMapper {

    List<AdminTemplateEntity> selectTemplates(
            @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

    long countTemplates(@Param("status") String status);

    AdminTemplateEntity selectTemplateById(@Param("templateId") long templateId);

    /** Row-locked read for the grant guard (status/window checked under the same lock). */
    AdminTemplateEntity selectTemplateForUpdate(@Param("templateId") long templateId);

    /** Row-locked read for the void guard (status checked under the same lock). */
    AdminInstanceEntity selectInstanceForUpdate(@Param("instanceId") long instanceId);

    /** Void write: terminal AVAILABLE→VOID transition (CAS on the AVAILABLE state). */
    int voidInstance(
            @Param("instanceId") long instanceId,
            @Param("now") LocalDateTime now);

    /** Void audit: the append-only VOID ledger row (uk request_id, terminal once forever). */
    int insertVoidLedger(
            @Param("id") long id,
            @Param("instanceId") long instanceId,
            @Param("userId") long userId,
            @Param("requestId") String requestId,
            @Param("remark") String remark,
            @Param("now") LocalDateTime now);

    /** Grant write: one AVAILABLE instance inheriting the template validity window. */
    int insertInstance(
            @Param("id") long id,
            @Param("templateId") long templateId,
            @Param("userId") long userId,
            @Param("originalExpireAt") LocalDateTime originalExpireAt,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    /**
     * Grant guard (hardening 2026-10-19): AVAILABLE+FROZEN instances the user already holds
     * of the template, compared against the configured per-template hold cap.
     */
    long countHeldInstances(
            @Param("templateId") long templateId,
            @Param("userId") long userId);

    /**
     * Grant guard (hardening 2026-10-19): ISSUE ledger rows the operator produced for the
     * (user, template) pair inside the sliding frequency window; the remark carries the
     * operator audit prefix written by the grant itself.
     */
    long countRecentOperatorGrants(
            @Param("templateId") long templateId,
            @Param("userId") long userId,
            @Param("operatorId") String operatorId,
            @Param("since") LocalDateTime since);

    /** Grant audit: the append-only ISSUE ledger row (uk request_id). */
    int insertIssueLedger(
            @Param("id") long id,
            @Param("instanceId") long instanceId,
            @Param("userId") long userId,
            @Param("requestId") String requestId,
            @Param("remark") String remark,
            @Param("now") LocalDateTime now);

    /**
     * Grant drawdown: issued_count is the only template counter the grant face maintains.
     * Hardening 2026-10-19: the statement refuses (returns 0) once total_stock is set and
     * exhausted; NULL stays unlimited. The row is already locked by the guard read.
     */
    int incrementIssuedCount(
            @Param("templateId") long templateId,
            @Param("now") LocalDateTime now);

    List<AdminInstanceEntity> selectInstances(
            @Param("templateId") Long templateId,
            @Param("status") String status,
            @Param("orderId") Long orderId,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countInstances(
            @Param("templateId") Long templateId,
            @Param("status") String status,
            @Param("orderId") Long orderId,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    AdminInstanceEntity selectInstanceById(@Param("instanceId") long instanceId);

    List<AdminLedgerEntity> selectLedgerByInstance(@Param("instanceId") long instanceId);

    int insertTemplate(
            @Param("id") long id,
            @Param("name") String name,
            @Param("status") String status,
            @Param("validStartAt") LocalDateTime validStartAt,
            @Param("validEndAt") LocalDateTime validEndAt,
            @Param("ruleJson") String ruleJson,
            @Param("now") LocalDateTime now);

    int updateTemplateFields(
            @Param("templateId") long templateId,
            @Param("name") String name,
            @Param("validStartAt") LocalDateTime validStartAt,
            @Param("validEndAt") LocalDateTime validEndAt,
            @Param("ruleJson") String ruleJson,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int updateTemplateStatus(
            @Param("templateId") long templateId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int insertBinding(
            @Param("id") long id,
            @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion,
            @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    CouponAdminBindingEntity selectBindingForUpdate(@Param("requestKey") String requestKey);

    int markBindingSucceeded(
            @Param("requestKey") String requestKey,
            @Param("receiptJson") String receiptJson);

    // ---------------------------------------------------------- coupon campaigns (contract 72)

    List<CampaignRow> selectCouponCampaigns(@Param("status") String status,
            @Param("limit") int limit, @Param("offset") int offset);

    long countCouponCampaigns(@Param("status") String status);

    CampaignRow selectCouponCampaignById(@Param("campaignId") long campaignId);

    CampaignRow selectCouponCampaignForUpdate(@Param("campaignId") long campaignId);

    int insertCouponCampaign(
            @Param("id") long id,
            @Param("name") String name,
            @Param("templateId") long templateId,
            @Param("audienceType") String audienceType,
            @Param("perUserLimit") int perUserLimit,
            @Param("totalLimit") int totalLimit,
            @Param("budgetLimit") BigDecimal budgetLimit,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("claimMethod") String claimMethod,
            @Param("status") String status,
            @Param("createdById") long createdById,
            @Param("now") LocalDateTime now);

    int updateCouponCampaign(
            @Param("campaignId") long campaignId,
            @Param("name") String name,
            @Param("templateId") long templateId,
            @Param("audienceType") String audienceType,
            @Param("perUserLimit") int perUserLimit,
            @Param("totalLimit") int totalLimit,
            @Param("budgetLimit") BigDecimal budgetLimit,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int updateCouponCampaignStatus(
            @Param("campaignId") long campaignId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /**
     * Claim drawdown: total_limit and budget are checked in the same conditional statement
     * that increments issued_count/issued_amount. Returns 0 when exhausted.
     */
    int incrementCouponCampaignIssue(
            @Param("campaignId") long campaignId,
            @Param("amountOff") BigDecimal amountOff,
            @Param("now") LocalDateTime now);

    List<ClaimRow> selectCouponClaims(@Param("campaignId") long campaignId,
            @Param("limit") int limit, @Param("offset") int offset);

    long countCouponClaims(@Param("campaignId") long campaignId);

    long countUserClaims(@Param("campaignId") long campaignId, @Param("userId") long userId);

    ClaimRow selectClaimByCampaignUser(@Param("campaignId") long campaignId,
            @Param("userId") long userId);

    ClaimRow selectClaimByRequestId(@Param("requestId") String requestId);

    /** claim_no is the user's ordinal claim within the campaign (1..perUserLimit, contract 79). */
    int insertClaim(
            @Param("id") long id,
            @Param("campaignId") long campaignId,
            @Param("userId") long userId,
            @Param("couponInstanceId") long couponInstanceId,
            @Param("claimNo") int claimNo,
            @Param("requestId") String requestId,
            @Param("now") LocalDateTime now);

    int insertCouponCampaignAudit(
            @Param("id") long id,
            @Param("campaignId") long campaignId,
            @Param("actionType") String actionType,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("operatorId") long operatorId,
            @Param("requestId") String requestId,
            @Param("traceId") String traceId,
            @Param("detailJson") String detailJson,
            @Param("now") LocalDateTime now);

    // ---------------------------------------------------------------- merchant coupons (contract 79)

    /** Merchant campaign template is created DRAFT; operator approval activates it. */
    int insertMerchantTemplate(
            @Param("id") long id,
            @Param("name") String name,
            @Param("merchantId") long merchantId,
            @Param("validStartAt") LocalDateTime validStartAt,
            @Param("validEndAt") LocalDateTime validEndAt,
            @Param("ruleJson") String ruleJson,
            @Param("now") LocalDateTime now);

    /** Editable only while DRAFT and never issued. */
    int updateMerchantTemplate(
            @Param("id") long id,
            @Param("name") String name,
            @Param("validStartAt") LocalDateTime validStartAt,
            @Param("validEndAt") LocalDateTime validEndAt,
            @Param("ruleJson") String ruleJson,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int activateMerchantTemplate(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int insertMerchantCouponCampaign(
            @Param("id") long id,
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId,
            @Param("name") String name,
            @Param("templateId") long templateId,
            @Param("perUserLimit") int perUserLimit,
            @Param("totalLimit") int totalLimit,
            @Param("budgetLimit") BigDecimal budgetLimit,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("description") String description,
            @Param("createdById") long createdById,
            @Param("now") LocalDateTime now);

    /** DRAFT/REJECTED only; version CAS. */
    int updateMerchantCouponCampaign(
            @Param("campaignId") long campaignId,
            @Param("name") String name,
            @Param("perUserLimit") int perUserLimit,
            @Param("totalLimit") int totalLimit,
            @Param("budgetLimit") BigDecimal budgetLimit,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("description") String description,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** DRAFT/REJECTED -> PENDING_REVIEW. */
    int submitMerchantCouponCampaign(
            @Param("campaignId") long campaignId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** DRAFT/REJECTED/PENDING_REVIEW -> CANCELED. */
    int cancelMerchantCouponCampaign(
            @Param("campaignId") long campaignId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** PENDING_REVIEW -> toStatus (SCHEDULED/ACTIVE/REJECTED) with the review facts. */
    int reviewMerchantCouponCampaign(
            @Param("campaignId") long campaignId,
            @Param("expectedVersion") long expectedVersion,
            @Param("toStatus") String toStatus,
            @Param("rejectReason") String rejectReason,
            @Param("reviewerType") String reviewerType,
            @Param("reviewerId") long reviewerId,
            @Param("now") LocalDateTime now);

    /** SCHEDULED/ACTIVE -> PAUSED. */
    int pauseMerchantCouponCampaign(
            @Param("campaignId") long campaignId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** SCHEDULED/ACTIVE/PAUSED -> ENDED. */
    int endMerchantCouponCampaign(
            @Param("campaignId") long campaignId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    List<CampaignRow> selectMerchantCouponCampaigns(
            @Param("merchantId") long merchantId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countMerchantCouponCampaigns(
            @Param("merchantId") long merchantId, @Param("status") String status);

    /** Operator review list across merchants, newest update first. */
    List<CampaignRow> selectMerchantCouponCampaignsForReview(
            @Param("status") String status,
            @Param("merchantId") Long merchantId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countMerchantCouponCampaignsForReview(
            @Param("status") String status, @Param("merchantId") Long merchantId);

    /** SCHEDULED/ACTIVE merchant campaigns inside their claim window with a usable template. */
    List<CampaignRow> selectClaimableMerchantCouponCampaigns(
            @Param("merchantId") long merchantId, @Param("now") LocalDateTime now);

    MerchantCouponStatsRow selectMerchantCouponStats(
            @Param("merchantId") long merchantId,
            @Param("monthStart") LocalDateTime monthStart,
            @Param("monthEnd") LocalDateTime monthEnd);

    /** One row per merchant campaign: used_count / driven_order_count by its template. */
    List<CampaignUsageRow> selectMerchantCouponUsage(@Param("merchantId") long merchantId);

    /** The same counters for explicit merchant campaigns (operator review list across merchants). */
    List<CampaignUsageRow> selectMerchantCouponUsageByIds(@Param("campaignIds") List<Long> campaignIds);

    /** The coupon instance currently USED for the order and the campaign it was claimed from. */
    MerchantCouponUsageRow selectMerchantCouponByOrder(@Param("orderId") long orderId);

    /** The campaign and template funding behind a claimed instance, whatever its status. */
    MerchantCouponUsageRow selectMerchantCouponByInstance(@Param("instanceId") long instanceId);

    int insertMerchantCouponBudget(
            @Param("id") long id,
            @Param("campaignId") long campaignId,
            @Param("merchantId") long merchantId,
            @Param("budgetLimit") BigDecimal budgetLimit,
            @Param("now") LocalDateTime now);

    /** Budget of a never-issued campaign may follow the campaign edit. */
    int updateMerchantCouponBudgetLimit(
            @Param("campaignId") long campaignId,
            @Param("budgetLimit") BigDecimal budgetLimit,
            @Param("now") LocalDateTime now);

    MerchantCouponBudgetRow selectMerchantCouponBudgetForUpdate(@Param("campaignId") long campaignId);

    /** Version CAS; the non-negative guards keep the account from crossing zero. */
    int updateMerchantCouponBudgetAmounts(
            @Param("campaignId") long campaignId,
            @Param("issuedDelta") BigDecimal issuedDelta,
            @Param("usedDelta") BigDecimal usedDelta,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int insertMerchantCouponBudgetLedger(
            @Param("id") long id,
            @Param("campaignId") long campaignId,
            @Param("merchantId") long merchantId,
            @Param("couponInstanceId") long couponInstanceId,
            @Param("orderId") Long orderId,
            @Param("entryType") String entryType,
            @Param("amount") BigDecimal amount,
            @Param("issuedAfter") BigDecimal issuedAfter,
            @Param("usedAfter") BigDecimal usedAfter,
            @Param("requestId") String requestId,
            @Param("now") LocalDateTime now);

    /** Null when the request id has no ledger row (idempotency probe). */
    Long selectMerchantCouponBudgetLedgerId(@Param("requestId") String requestId);

    /** Every merchant budget account joined with the campaign facts needed to audit it. */
    List<MerchantCouponBudgetRow> selectMerchantCouponBudgetAudit();
}
