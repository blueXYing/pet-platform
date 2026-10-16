package com.petplatform.community.api.command;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Encyclopedia maintenance writes for the single operator (contract 65 §3, PRD 6.10, A-005):
 * category display-name/sort maintenance plus enable/disable, breed white-listed field
 * maintenance plus publish/offline (the 62 leftover "分类停用、内容上下线运营面"). Category
 * creation/deletion does not exist (PRD enum of five codes is closed); breed creation/deletion
 * and export stay outside V1 (CCR-ADM-A005-OPS-001). Every command carries a terminal
 * requestId; idempotency binds inside the community kernel on command_idempotency (14号) and
 * optimistic {@code expectedVersion} guards concurrent edits (409 on mismatch). Writes never
 * touch the C-end read filter facts beyond the documented status columns.
 */
public interface AdminEncyclopediaCommandApi {

    /** Category edit: display name (2..10 codepoints) and sortNo (0..999), version-guarded. */
    CategoryMutation updateCategory(UpdateCategoryCommand command);

    CategoryMutation enableCategory(CategoryStatusCommand command);

    CategoryMutation disableCategory(CategoryStatusCommand command);

    /** Breed edit: summary/coverUrl/bodyType/aliases/tags/sortNo, version-guarded. */
    BreedMutation updateBreed(UpdateBreedCommand command);

    /** OFFLINE → PUBLISHED; the row re-enters the C-end list immediately (62 filter). */
    BreedMutation publishBreed(BreedStatusCommand command);

    /** PUBLISHED → OFFLINE; the C-end detail answers 404 BREED_NOT_FOUND while offline. */
    BreedMutation offlineBreed(BreedStatusCommand command);

    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    record CategoryMutation(
            String categoryCode, String categoryName, String status, int sortNo,
            long version, OffsetDateTime occurredAt) {}

    record BreedMutation(
            String breedId, String name, String status, int sortNo,
            long version, OffsetDateTime occurredAt) {}

    record UpdateCategoryCommand(
            String categoryCode, String categoryName, int sortNo, long expectedVersion,
            CommandContext context, AdminAuthorization authorization) {}

    record CategoryStatusCommand(
            String categoryCode, long expectedVersion,
            CommandContext context, AdminAuthorization authorization) {}

    record UpdateBreedCommand(
            String breedId, String summary, String coverUrl, String bodyType,
            List<String> aliases, List<String> tags, int sortNo, long expectedVersion,
            CommandContext context, AdminAuthorization authorization) {}

    record BreedStatusCommand(
            String breedId, long expectedVersion,
            CommandContext context, AdminAuthorization authorization) {}
}
