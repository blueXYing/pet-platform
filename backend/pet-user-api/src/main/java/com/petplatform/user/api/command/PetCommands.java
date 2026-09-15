package com.petplatform.user.api.command;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Pet write commands per CCR-W2-API-001 user domain 0.1 (HTTP10 §3.2). petType is immutable after create. */
public final class PetCommands {
    private PetCommands() {}

    public record CreatePet(
            CommandContext context,
            String name, String petType, String breedName, LocalDate birthDate, String sex,
            BigDecimal weightKg, String sterilizationStatus, String vaccineStatus,
            String healthNote, String avatarUrl, boolean defaultPet
    ) {}

    public record UpdatePet(
            CommandContext context,
            String petId,
            String name, String breedName, LocalDate birthDate, String sex,
            BigDecimal weightKg, String sterilizationStatus, String vaccineStatus,
            String healthNote, String avatarUrl, Boolean defaultPet
    ) {}

    public record DeletePet(CommandContext context, String petId) {}

    public record PetReceipt(String petId, String status, boolean isDefault) {}
}
