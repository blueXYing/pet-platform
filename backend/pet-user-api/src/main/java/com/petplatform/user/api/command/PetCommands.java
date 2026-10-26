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
            String healthNote, String avatarAssetId, String avatarUrl, boolean defaultPet
    ) {
        /** Backward-compatible constructor for callers without the CCR-C-PET-AVATAR-001 asset id. */
        public CreatePet(CommandContext context, String name, String petType, String breedName,
                         LocalDate birthDate, String sex, BigDecimal weightKg,
                         String sterilizationStatus, String vaccineStatus, String healthNote,
                         String avatarUrl, boolean defaultPet) {
            this(context, name, petType, breedName, birthDate, sex, weightKg, sterilizationStatus,
                    vaccineStatus, healthNote, null, avatarUrl, defaultPet);
        }
    }

    public record UpdatePet(
            CommandContext context,
            String petId,
            String name, String breedName, LocalDate birthDate, String sex,
            BigDecimal weightKg, String sterilizationStatus, String vaccineStatus,
            String healthNote, String avatarAssetId, String avatarUrl, Boolean defaultPet
    ) {
        /** Backward-compatible constructor for callers without the CCR-C-PET-AVATAR-001 asset id. */
        public UpdatePet(CommandContext context, String petId, String name, String breedName,
                         LocalDate birthDate, String sex, BigDecimal weightKg,
                         String sterilizationStatus, String vaccineStatus, String healthNote,
                         String avatarUrl, Boolean defaultPet) {
            this(context, petId, name, breedName, birthDate, sex, weightKg, sterilizationStatus,
                    vaccineStatus, healthNote, null, avatarUrl, defaultPet);
        }
    }

    public record DeletePet(CommandContext context, String petId) {}

    public record PetReceipt(String petId, String status, boolean isDefault) {}
}
