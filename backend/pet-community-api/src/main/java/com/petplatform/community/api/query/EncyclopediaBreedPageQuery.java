package com.petplatform.community.api.query;

/**
 * Encyclopedia list query (contract 62): category must be an ENABLED dictionary code
 * (null defaults to DOG per PRD), keyword matches name/aliases (trimmed, ≤40 codepoints),
 * tag is an exact tag-dictionary match. Page/pageSize bounds match the store catalog.
 */
public record EncyclopediaBreedPageQuery(
        String categoryCode,
        String keyword,
        String tag,
        Integer page,
        Integer pageSize
) {}
