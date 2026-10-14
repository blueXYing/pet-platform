package com.petplatform.community.api.dto;

import java.util.List;

/**
 * Breed card projection for the encyclopedia list and the related-recommendation row
 * (contract 62): published rows only, ID as decimal String (Snowflake BIGINT).
 */
public record EncyclopediaBreedCardView(
        String breedId,
        String categoryCode,
        String categoryName,
        String name,
        List<String> aliases,
        List<String> tags,
        String summary,
        String coverUrl
) {}
