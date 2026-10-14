package com.petplatform.community.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Breed detail projection (contract 62): PRD 5.1.4 field set (name/aliases/tags/cover/
 * description/care advice/health tips) plus the design-driven structured blocks registered
 * in the contract (body type chip, fact cards, five-axis ability radar, related row).
 * Status is always PUBLISHED on this face — offline rows answer 404 BREED_NOT_FOUND.
 */
public record EncyclopediaBreedDetailView(
        String breedId,
        String categoryCode,
        String categoryName,
        String name,
        List<String> aliases,
        List<String> tags,
        String summary,
        String coverUrl,
        String bodyType,
        List<Map<String, String>> facts,
        Map<String, Integer> radar,
        String descriptionHtml,
        String careAdviceHtml,
        String healthTipsHtml,
        String status,
        List<EncyclopediaBreedCardView> related
) {}
