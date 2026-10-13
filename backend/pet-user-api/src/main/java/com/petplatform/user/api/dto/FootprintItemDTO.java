package com.petplatform.user.api.dto;

import java.time.OffsetDateTime;

/** Contract 58 footprint list row: deduped by (user, target), ordered by lastViewedAt DESC. */
public record FootprintItemDTO(
        String footprintId,
        String targetType,
        String targetId,
        OffsetDateTime lastViewedAt,
        UserTargetDisplayDTO display) {}
