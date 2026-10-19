package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.user.api.command.PetHealthRecordCommands.CreateDewormingRecord;
import com.petplatform.user.api.command.PetHealthRecordCommands.CreateRecordReceipt;
import com.petplatform.user.api.command.PetHealthRecordCommands.CreateVaccinationRecord;
import com.petplatform.user.api.command.PetCommands.CreatePet;
import com.petplatform.user.api.dto.PetHealthRecordView;
import com.petplatform.user.biz.application.PetHealthRecordService;
import com.petplatform.user.biz.application.PetService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Contract 68 pet health-record kernel over a real isolated MySQL: ownership anti-enumeration
 * (PET_NOT_FOUND), the 200-per-type cap, the supplement-23 five-tuple idempotency, the
 * same-transaction metadata audit (first write only) and the PRD 5.1.3 field validations.
 */
class UserPetHealthRecordDomainMySqlTest {
    private final AtomicLong ids = new AtomicLong(900_000);
    private static final String USER_A = "620001";
    private static final String USER_B = "620002";

    private PetHealthRecordService records(MySqlUserDomainTestDatabase db) {
        return new PetHealthRecordService(db.dataSource(), ids::incrementAndGet);
    }

    private PetService pets(MySqlUserDomainTestDatabase db) {
        return new PetService(db.dataSource(), ids::incrementAndGet);
    }

    private static CommandContext userContext(String userId, String requestId) {
        return new CommandContext(requestId, "trace-" + requestId, OperatorType.USER, userId, "C_MINIAPP");
    }

    private String seedPet(MySqlUserDomainTestDatabase db, String ownerUserId) {
        PetService petService = pets(db);
        PetService.CommandOutcome<com.petplatform.user.api.dto.PetView> outcome = petService.createPetOutcome(
                new CreatePet(userContext(ownerUserId, UUID.randomUUID().toString()), "豆豆", "DOG",
                        "金毛寻回犬", LocalDate.of(2024, 6, 18), "MALE", new BigDecimal("28.50"),
                        "INTACT", "PARTIAL", "性格温顺", null, true));
        return outcome.receipt().petId();
    }

    private CreateVaccinationRecord vaccine(String userId, String petId, String requestId) {
        return new CreateVaccinationRecord(userContext(userId, requestId), petId,
                LocalDate.now(), "狂犬疫苗", 3, LocalDate.now().plusYears(1), "无不良反应");
    }

    private CreateDewormingRecord deworm(String userId, String petId, String requestId) {
        return new CreateDewormingRecord(userContext(userId, requestId), petId,
                LocalDate.now(), "INTERNAL", "拜宠清", LocalDate.now().plusMonths(3), null);
    }

    @Test void createListAndOwnershipAntiEnumerationHold() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(620_001L, "13800000001", "ACTIVE");
            db.seedUser(620_002L, "13800000002", "ACTIVE");
            PetHealthRecordService service = records(db);
            String petId = seedPet(db, USER_A);

            CreateRecordReceipt created = service.createVaccinationRecord(vaccine(USER_A, petId, UUID.randomUUID().toString()));
            assertEquals("ACTIVE", created.status());
            assertEquals("VACCINATION", created.recordType());

            List<PetHealthRecordView> list = service.listVaccinationRecords(petId, USER_A);
            assertEquals(1, list.size());
            assertEquals("狂犬疫苗", list.get(0).vaccineName());
            assertEquals(3, list.get(0).doseNumber());
            assertTrue(service.listDewormingRecords(petId, USER_A).isEmpty(), "facets do not mix");

            // User B never learns the pet or its records exist — same 404 code for reads/writes.
            ApiException foreignList = assertThrows(ApiException.class,
                    () -> service.listVaccinationRecords(petId, USER_B));
            assertEquals("PET_NOT_FOUND", foreignList.code());
            ApiException foreignWrite = assertThrows(ApiException.class,
                    () -> service.createVaccinationRecord(vaccine(USER_B, petId, UUID.randomUUID().toString())));
            assertEquals("PET_NOT_FOUND", foreignWrite.code());
        }
    }

    @Test void prdFieldValidationsRejectBadRows() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(620_001L, "13800000001", "ACTIVE");
            PetHealthRecordService service = records(db);
            String petId = seedPet(db, USER_A);
            String requestId = UUID.randomUUID().toString();
            assertThrows(IllegalArgumentException.class, () -> service.createVaccinationRecord(
                    new CreateVaccinationRecord(userContext(USER_A, requestId), petId,
                            LocalDate.now().plusDays(1), "狂犬疫苗", null, null, null)), "future recordDate");
            assertThrows(IllegalArgumentException.class, () -> service.createVaccinationRecord(
                    new CreateVaccinationRecord(userContext(USER_A, requestId), petId,
                            LocalDate.now(), " ", null, null, null)), "blank vaccineName");
            assertThrows(IllegalArgumentException.class, () -> service.createVaccinationRecord(
                    new CreateVaccinationRecord(userContext(USER_A, requestId), petId,
                            LocalDate.now(), "狂犬疫苗", 21, null, null)), "doseNumber over 20");
            assertThrows(IllegalArgumentException.class, () -> service.createVaccinationRecord(
                    new CreateVaccinationRecord(userContext(USER_A, requestId), petId,
                            LocalDate.now(), "狂犬疫苗", null, LocalDate.now().minusDays(1), null)),
                    "nextDate before recordDate");
            assertThrows(IllegalArgumentException.class, () -> service.createVaccinationRecord(
                    new CreateVaccinationRecord(userContext(USER_A, requestId), petId,
                            LocalDate.now(), "狂犬疫苗", null, null, "注".repeat(201))), "201-codepoint note");
            assertThrows(IllegalArgumentException.class, () -> service.createDewormingRecord(
                    new CreateDewormingRecord(userContext(USER_A, requestId), petId,
                            LocalDate.now(), "内外同驱", null, null, null)), "non-enum dewormingType");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createVaccinationRecord(vaccine(USER_A, petId, null)), "missing requestId");
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_pet_health_record", Integer.class));
        }
    }

    @Test void idempotencyReplayAndConflictHold() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(620_001L, "13800000001", "ACTIVE");
            PetHealthRecordService service = records(db);
            String petId = seedPet(db, USER_A);
            String requestId = UUID.randomUUID().toString();

            PetHealthRecordService.CommandOutcome<CreateRecordReceipt> first =
                    service.createVaccinationOutcome(vaccine(USER_A, petId, requestId));
            PetHealthRecordService.CommandOutcome<CreateRecordReceipt> replay =
                    service.createVaccinationOutcome(vaccine(USER_A, petId, requestId));
            assertFalse(first.replayed());
            assertTrue(replay.replayed());
            assertEquals(first.receipt().recordId(), replay.receipt().recordId());
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_pet_health_record", Integer.class));

            // Same requestId, different params: 409 IDEMPOTENCY_KEY_CONFLICT.
            ApiException conflict = assertThrows(ApiException.class, () -> service.createVaccinationRecord(
                    new CreateVaccinationRecord(userContext(USER_A, requestId), petId,
                            LocalDate.now(), "犬六联疫苗", null, null, null)));
            assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.code());
        }
    }

    @Test void capAuditAndFrozenGuardsHold() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(620_001L, "13800000001", "ACTIVE");
            PetHealthRecordService service = records(db);
            String petId = seedPet(db, USER_A);
            // Fill to the cap: 200 inserts succeed, the 201st is rejected.
            for (int index = 0; index < 200; index++) {
                service.createDewormingRecord(new CreateDewormingRecord(
                        userContext(USER_A, UUID.randomUUID().toString()), petId,
                        LocalDate.now(), "EXTERNAL", null, null, null));
            }
            ApiException capped = assertThrows(ApiException.class, () -> service.createDewormingRecord(
                    deworm(USER_A, petId, UUID.randomUUID().toString())));
            assertEquals("PET_HEALTH_RECORD_LIMIT_EXCEEDED", capped.code());
            assertEquals(200, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_pet_health_record WHERE record_type='DEWORMING'", Integer.class));

            // One metadata audit line per first submission; replays never duplicate it.
            String replayKey = UUID.randomUUID().toString();
            service.createVaccinationRecord(vaccine(USER_A, petId, replayKey));
            service.createVaccinationRecord(vaccine(USER_A, petId, replayKey));
            assertEquals(201, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_pet_health_record_audit", Integer.class));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_pet_health_record_audit WHERE action_code<>'pet.health-record.create'",
                    Integer.class));

            // Frozen owner: reads stay open, writes rejected.
            db.jdbc().update("UPDATE user_account SET status='FROZEN' WHERE id=620001");
            ApiException frozen = assertThrows(ApiException.class,
                    () -> service.createVaccinationRecord(vaccine(USER_A, petId, UUID.randomUUID().toString())));
            assertEquals("USER_FROZEN", frozen.code());
            assertEquals(1, service.listVaccinationRecords(petId, USER_A).size(), "reads stay open");
        }
    }
}
