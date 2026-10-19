package com.petplatform.user.api.dto;

import java.time.LocalDate;

/**
 * One health-record row projection (contract 68). Raw facts only: the design's 接种状态 chips are
 * a C-side display derivation off the dates, never a stored or API field. Same lexical rules as
 * the HTTP face: ids are decimal Strings, dates ISO yyyy-MM-dd.
 */
public record PetHealthRecordView(
        String recordId,
        String petId,
        String recordType,
        LocalDate recordDate,
        String vaccineName,
        Integer doseNumber,
        String dewormingType,
        String productName,
        LocalDate nextRecordDate,
        String note,
        String status
) {}
