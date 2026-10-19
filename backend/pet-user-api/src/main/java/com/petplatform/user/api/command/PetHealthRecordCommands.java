package com.petplatform.user.api.command;

import com.petplatform.common.CommandContext;
import java.time.LocalDate;

/**
 * Pet health-record write commands per contract 68 (2026-10-19 ruling; PRD 5.1.3 field tables).
 * V1 carries add + list only — no edit/delete route exists yet; the soft-delete status column is
 * reserved for that later slice.
 */
public final class PetHealthRecordCommands {
    private PetHealthRecordCommands() {}

    public record CreateVaccinationRecord(
            CommandContext context,
            String petId,
            LocalDate recordDate,
            String vaccineName,
            Integer doseNumber,
            LocalDate nextRecordDate,
            String note
    ) {}

    public record CreateDewormingRecord(
            CommandContext context,
            String petId,
            LocalDate recordDate,
            String dewormingType,
            String productName,
            LocalDate nextRecordDate,
            String note
    ) {}

    public record CreateRecordReceipt(
            String petId, String recordId, String recordType, String status) {}
}
