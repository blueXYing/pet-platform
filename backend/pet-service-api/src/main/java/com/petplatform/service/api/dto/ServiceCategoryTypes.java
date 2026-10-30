package com.petplatform.service.api.dto;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceAdminAuthorization;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Service category dictionary admin contract (CCR-SVC-CAT-001 B-SVC-02, contract 74 §4/§5).
 * {@code categoryCode} is the stable public identifier and is immutable after creation; every
 * write bumps the optimistic version by one. On update, an absent nullable patch field
 * ({@code parentCode}/{@code iconUrl}/{@code description}) keeps the stored value; an explicitly
 * supplied null or empty string clears it. The presence flags are carried separately so the HTTP
 * adapter can preserve that distinction.
 */
public final class ServiceCategoryTypes {

    private ServiceCategoryTypes() {}

    // ---- admin list ----

    public record ServiceCategoryAdminListQuery(
            int page,
            int pageSize,
            String status,
            String keyword,
            ServiceAdminAuthorization authorization,
            QueryContext context) {}

    public record ServiceCategoryAdminItem(
            String categoryId,
            String categoryCode,
            String categoryName,
            String parentCode,
            String iconUrl,
            String description,
            int sortNo,
            String status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record ServiceCategoryAdminPage(
            int page, int pageSize, long total, List<ServiceCategoryAdminItem> items) {}

    // ---- admin commands ----

    public record CreateServiceCategoryCommand(
            String categoryCode,
            String categoryName,
            String parentCode,
            String iconUrl,
            String description,
            Integer sortNo,
            ServiceAdminAuthorization authorization,
            CommandContext context) {}

    public record UpdateServiceCategoryCommand(
            String categoryCode,
            String categoryName,
            String parentCode,
            boolean parentCodePresent,
            String iconUrl,
            boolean iconUrlPresent,
            String description,
            boolean descriptionPresent,
            Integer sortNo,
            long expectedVersion,
            ServiceAdminAuthorization authorization,
            CommandContext context) {}

    public record EnableServiceCategoryCommand(
            String categoryCode,
            long expectedVersion,
            ServiceAdminAuthorization authorization,
            CommandContext context) {}

    public record DisableServiceCategoryCommand(
            String categoryCode,
            long expectedVersion,
            ServiceAdminAuthorization authorization,
            CommandContext context) {}

    /** First-success receipt; identical replays of the same requestId return the same shape. */
    public record ServiceCategoryResult(
            String categoryId, String categoryCode, String status, long version) {}
}
