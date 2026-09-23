package com.petplatform.service.api.dto;

import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Review (admin) and workbench (merchant) read projections for the service write slice. The
 * approved C-side read contract (ServiceQueryApi) is untouched: these views expose every status
 * to authorized operators/owners only and never widen consumer visibility.
 */
public final class ServiceAdminTypes {

    private ServiceAdminTypes() {}

    // ---- review (admin) queries ----

    public record ServiceReviewListQuery(
            int page,
            int pageSize,
            String status,
            String categoryId,
            String merchantId,
            ServiceWriteTypes.ServiceAdminAuthorization authorization,
            QueryContext context) {}

    public record ServiceReviewDetailQuery(
            String serviceId, ServiceWriteTypes.ServiceAdminAuthorization authorization, QueryContext context) {}

    public record ServiceReviewSummary(
            String serviceId,
            String merchantId,
            String storeId,
            String serviceName,
            String categoryId,
            String categoryName,
            String status,
            BigDecimal price,
            BigDecimal listPrice,
            int submissionNo,
            OffsetDateTime submittedAt,
            Long slaRemainingMinutes,
            int rejectCount,
            long version) {}

    public record ServiceReviewDecisionView(
            String decisionId,
            int submissionNo,
            String decisionType,
            String opinion,
            OffsetDateTime decidedAt) {}

    public record ServiceReviewDetail(
            String serviceId,
            String merchantId,
            String storeId,
            String serviceName,
            String categoryId,
            String categoryName,
            String status,
            BigDecimal price,
            BigDecimal listPrice,
            Integer durationMinutes,
            String fulfillmentType,
            String coverAssetId,
            String description,
            int submissionNo,
            OffsetDateTime submittedAt,
            Long slaRemainingMinutes,
            long version,
            OffsetDateTime updatedAt,
            List<ServiceReviewDecisionView> decisions) {}

    public record ServiceReviewPage(
            int page, int pageSize, long total, List<ServiceReviewSummary> items) {}

    // ---- merchant workbench queries ----

    public record ServiceManagementListQuery(
            String merchantId,
            String storeId,
            String status,
            int page,
            int pageSize,
            QueryContext context) {}

    public record ServiceManagementDetailQuery(
            String serviceId, String merchantId, String storeId, QueryContext context) {}

    public record ServiceManagementItem(
            String serviceId,
            String merchantId,
            String storeId,
            String serviceName,
            String categoryId,
            String categoryName,
            String status,
            BigDecimal price,
            BigDecimal listPrice,
            Integer durationMinutes,
            String fulfillmentType,
            String coverAssetId,
            String applicablePetTypes,
            String staffRequirement,
            Boolean verificationRequired,
            String description,
            String aftersaleNote,
            String remark,
            int submissionNo,
            OffsetDateTime submittedAt,
            long version,
            OffsetDateTime updatedAt,
            ServiceReviewDecisionView latestRejection) {}

    public record ServiceManagementPage(
            int page, int pageSize, long total, List<ServiceManagementItem> items) {}

    // ---- platform category dictionary (read-only) ----

    public record ServiceCategoryQuery(QueryContext context) {}

    public record ServiceCategoryItem(String categoryId, String categoryName, int sortNo) {}

    public record ServiceCategoryPage(List<ServiceCategoryItem> items) {}
}
