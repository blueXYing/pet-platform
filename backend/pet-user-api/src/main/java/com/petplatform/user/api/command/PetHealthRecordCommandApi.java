package com.petplatform.user.api.command;

import com.petplatform.user.api.dto.PetHealthRecordView;
import java.util.List;

/**
 * Pet health-record command surface (contract 68). First-success receipts are bound to requestId
 * per supplement 23 §5: replay returns the original receipt (HTTP 201 first / 200 replay).
 */
public interface PetHealthRecordCommandApi {

    PetHealthRecordCommands.CreateRecordReceipt createVaccinationRecord(
            PetHealthRecordCommands.CreateVaccinationRecord command);

    PetHealthRecordCommands.CreateRecordReceipt createDewormingRecord(
            PetHealthRecordCommands.CreateDewormingRecord command);

    List<PetHealthRecordView> listVaccinationRecords(String petId, String ownerUserId);

    List<PetHealthRecordView> listDewormingRecords(String petId, String ownerUserId);
}
