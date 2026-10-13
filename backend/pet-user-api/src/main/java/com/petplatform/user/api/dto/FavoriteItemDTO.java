package com.petplatform.user.api.dto;

import java.time.OffsetDateTime;

/** Contract 58 favorite list row: the private favorite fact plus the catalog display projection. */
public record FavoriteItemDTO(
        String favoriteId,
        String targetType,
        String targetId,
        OffsetDateTime favoritedAt,
        UserTargetDisplayDTO display) {}
