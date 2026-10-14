package com.petplatform.community.api.dto;

import java.util.List;

/** Encyclopedia list page envelope (contract 62), matching the store-catalog page shape. */
public record EncyclopediaBreedPage(
        List<EncyclopediaBreedCardView> items,
        int page,
        int pageSize,
        long total
) {}
