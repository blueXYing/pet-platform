package com.petplatform.community.api.dto;

/**
 * Enabled encyclopedia category (contract 62; PRD 5.1.4 five-value dictionary). The wire
 * field is sortOrder — the persistence column stays sort_no (snowflake-as-String rule keeps
 * *No-suffixed wire fields reserved for decimal IDs).
 */
public record EncyclopediaCategoryView(
        String categoryCode,
        String categoryName,
        int sortOrder
) {}
