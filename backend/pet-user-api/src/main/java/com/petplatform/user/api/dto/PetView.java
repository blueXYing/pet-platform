package com.petplatform.user.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Full pet view per HTTP10 §3.2 PetView (CCR-W2-API-001 user domain 0.1). */
public record PetView(
        String petId,
        String name,
        String petType,
        String breedName,
        LocalDate birthDate,
        String sex,
        BigDecimal weightKg,
        String sterilizationStatus,
        String vaccineStatus,
        String healthNote,
        String avatarUrl,
        boolean isDefault,
        String status
) {}
