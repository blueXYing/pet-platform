package com.petplatform.user.api.dto;

import java.math.BigDecimal;

/** Internal API 07 §3.1: only what order creation needs; an immediate copy the caller must persist itself. */
public record PetSnapshotDTO(
        String petId,
        String ownerUserId,
        String name,
        String categoryCode,
        String breedName,
        String genderCode,
        BigDecimal weightKg,
        String healthRemark
) {}
