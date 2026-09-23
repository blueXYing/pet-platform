package com.petplatform.service.api.dto;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.util.List;

/**
 * CCR-W2-API-001 service write-side commands (proposal v0.2, approved 2026-09-22). All identifiers
 * are decimal Snowflake strings; money is BigDecimal scaled to two decimals by the terminal adapter.
 * Draft fields are lenient (nullable); submission re-validates the full required set (SVCW-D4:
 * cover included) before DRAFT/REJECTED/OFFLINE may enter REVIEWING.
 */
public final class ServiceWriteTypes {

    private ServiceWriteTypes() {}

    /** Editable projection shared by create and update. Null means "not provided". */
    public record ServiceItemFields(
            String serviceName,
            String categoryId,
            String fulfillmentType,
            BigDecimal price,
            BigDecimal listPrice,
            Integer durationMinutes,
            String coverAssetId,
            List<String> applicablePetTypes,
            String staffRequirement,
            Boolean verificationRequired,
            String description,
            String aftersaleNote,
            String remark) {}

    public record CreateServiceItemCommand(
            String merchantId,
            String storeId,
            ServiceItemFields fields,
            CommandContext context) {}

    public record UpdateServiceItemCommand(
            String serviceId,
            ServiceItemFields fields,
            long expectedVersion,
            CommandContext context) {}

    public record SubmitServiceItemCommand(
            String serviceId, long expectedVersion, CommandContext context) {}

    public record TakeServiceOfflineCommand(
            String serviceId, long expectedVersion, CommandContext context) {}

    /** APPROVE -> ACTIVE, REJECT -> REJECTED (opinion required, 10-500 chars). */
    public record DecideServiceReviewCommand(
            String serviceId,
            long expectedVersion,
            String decisionType,
            String opinion,
            ServiceAdminAuthorization authorization,
            CommandContext context) {}

    /** ACTIVE -> OFFLINE with a governance audit row; not a review decision (SVCW-D3). */
    public record ForceOfflineServiceCommand(
            String serviceId,
            long expectedVersion,
            String reason,
            ServiceAdminAuthorization authorization,
            CommandContext context) {}

    /** Trusted admin session reference for the transactional final authorization check. */
    public record ServiceAdminAuthorization(String sessionId, long sessionGeneration) {}

    /** Minimal first-success receipt; idempotent replays return the same shape (23号 §6). */
    public record ServiceItemResult(
            String serviceId,
            String merchantId,
            String storeId,
            String status,
            long version,
            String decisionId,
            String actionId) {}
}
