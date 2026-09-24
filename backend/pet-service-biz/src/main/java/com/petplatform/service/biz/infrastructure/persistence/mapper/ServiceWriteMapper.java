package com.petplatform.service.biz.infrastructure.persistence.mapper;

import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceCategoryEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceCommandBindingEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceItemEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceReviewDecisionEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** service write-side statements. SQL is kept in ServiceWriteMapper.xml. */
public interface ServiceWriteMapper {

    void setTimeZoneUtc();

    void setLockWaitTimeout2Seconds();

    // ---- command_idempotency binding (14号 shared table) ----

    ServiceCommandBindingEntity selectBindingForUpdate(@Param("requestKey") String requestKey);

    int insertBinding(
            @Param("id") long id,
            @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion,
            @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    int markBindingSucceeded(
            @Param("requestKey") String requestKey, @Param("receiptJson") String receiptJson);

    // ---- service_item ----

    ServiceItemEntity selectItemById(@Param("serviceId") long serviceId);

    ServiceItemEntity selectItemByIdForUpdate(@Param("serviceId") long serviceId);

    /**
     * Loose-draft insert (10号 §4.10.1): business columns are wrapper-typed so a minimal draft
     * stores NULL — primitives would NPE on unboxing and surface as a spurious 503. The submit
     * gate re-validates the required set before REVIEWING.
     */
    int insertItem(
            @Param("id") long id,
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId,
            @Param("ownerUserId") long ownerUserId,
            @Param("categoryId") Long categoryId,
            @Param("serviceName") String serviceName,
            @Param("description") String description,
            @Param("price") BigDecimal price,
            @Param("listPrice") BigDecimal listPrice,
            @Param("durationMinutes") Integer durationMinutes,
            @Param("fulfillmentType") String fulfillmentType,
            @Param("coverAssetId") Long coverAssetId,
            @Param("applicablePetTypes") String applicablePetTypes,
            @Param("staffRequirement") String staffRequirement,
            @Param("verificationRequired") boolean verificationRequired,
            @Param("aftersaleNote") String aftersaleNote,
            @Param("remark") String remark,
            @Param("now") LocalDateTime now);

    /** Field overwrite guarded by version CAS and the editable-status set. */
    int updateItem(
            @Param("id") long id,
            @Param("categoryId") Long categoryId,
            @Param("serviceName") String serviceName,
            @Param("description") String description,
            @Param("price") BigDecimal price,
            @Param("listPrice") BigDecimal listPrice,
            @Param("durationMinutes") Integer durationMinutes,
            @Param("fulfillmentType") String fulfillmentType,
            @Param("coverAssetId") Long coverAssetId,
            @Param("applicablePetTypes") String applicablePetTypes,
            @Param("staffRequirement") String staffRequirement,
            @Param("verificationRequired") Boolean verificationRequired,
            @Param("aftersaleNote") String aftersaleNote,
            @Param("remark") String remark,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** EDITABLE -> REVIEWING; bumps submission_no and submitted_at. */
    int submitItem(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** ACTIVE -> OFFLINE (merchant). */
    int takeOfflineItem(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** REVIEWING -> ACTIVE (admin APPROVE). */
    int approveItem(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** REVIEWING -> REJECTED (admin REJECT). */
    int rejectItem(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    /** ACTIVE -> OFFLINE (admin FORCE_OFFLINE). */
    int forceOfflineItem(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    // ---- category dictionary ----

    ServiceCategoryEntity selectCategoryById(@Param("categoryId") long categoryId);

    List<ServiceCategoryEntity> selectEnabledCategories();

    // ---- review decisions / governance ----

    int insertDecision(
            @Param("id") long id,
            @Param("serviceId") long serviceId,
            @Param("submissionNo") long submissionNo,
            @Param("decisionType") String decisionType,
            @Param("opinion") String opinion,
            @Param("operatorId") long operatorId,
            @Param("decidedAt") LocalDateTime decidedAt,
            @Param("authzVersion") String authzVersion,
            @Param("scopeVersion") String scopeVersion,
            @Param("requestId") byte[] requestId,
            @Param("traceId") String traceId,
            @Param("now") LocalDateTime now);

    List<ServiceReviewDecisionEntity> selectDecisionsByService(
            @Param("serviceId") long serviceId);

    int insertGovernance(
            @Param("id") long id,
            @Param("serviceId") long serviceId,
            @Param("reason") String reason,
            @Param("operatorId") long operatorId,
            @Param("actedAt") LocalDateTime actedAt,
            @Param("authzVersion") String authzVersion,
            @Param("scopeVersion") String scopeVersion,
            @Param("requestId") byte[] requestId,
            @Param("traceId") String traceId,
            @Param("now") LocalDateTime now);

    // ---- admin / workbench listings (Map rows with camelCase aliases) ----

    List<Map<String, Object>> listForReview(
            @Param("status") String status,
            @Param("categoryId") Long categoryId,
            @Param("merchantId") Long merchantId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countForReview(
            @Param("status") String status,
            @Param("categoryId") Long categoryId,
            @Param("merchantId") Long merchantId);

    Map<String, Object> selectReviewItem(@Param("serviceId") long serviceId);

    List<Map<String, Object>> listManaged(
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countManaged(
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId,
            @Param("status") String status);

    Map<String, Object> selectManagedItem(@Param("serviceId") long serviceId);
}
