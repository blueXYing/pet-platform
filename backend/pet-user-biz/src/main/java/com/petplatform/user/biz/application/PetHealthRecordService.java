package com.petplatform.user.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.api.command.PetHealthRecordCommandApi;
import com.petplatform.user.api.command.PetHealthRecordCommands;
import com.petplatform.user.api.dto.PetHealthRecordView;
import com.petplatform.user.biz.infrastructure.persistence.CommandIdempotencyStore;
import com.petplatform.user.biz.infrastructure.persistence.PetHealthRecordStore;
import com.petplatform.user.biz.infrastructure.persistence.PetStore;
import com.petplatform.user.biz.infrastructure.persistence.SessionControl;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pet health-record commands and owner-scoped reads per contract 68 (2026-10-19 ruling): the
 * PRD 5.1.3 vaccine/deworming field tables, anti-enumeration PET_NOT_FOUND for every
 * not-mine/not-found/disabled pet, USER_FROZEN rejection for writes, a conservative 200-records
 * per-type cap and the supplement 23 §5 idempotent-write frame (first receipt bound to the
 * requestId, scope USER_SELF). Every first submission writes one metadata-only audit line in the
 * same transaction (contract 68 hard rule 4); replays never duplicate it — the audit table's
 * unique request key enforces the same fact at the storage layer. No event/outbox subscriber
 * exists for health records: the write path is idempotency + insert + audit, nothing else.
 */
public final class PetHealthRecordService implements PetHealthRecordCommandApi {

    /** Conservative per-pet per-type ACTIVE cap (contract 68 §hard rules; disclosed in the PR). */
    static final int RECORD_LIMIT = 200;
    static final String VACCINATION = "VACCINATION";
    static final String DEWORMING = "DEWORMING";
    private static final Set<String> DEWORMING_TYPES = Set.of("EXTERNAL", "INTERNAL", "INTERNAL_EXTERNAL");
    private static final String CREATE_AUDIT_ACTION = "pet.health-record.create";
    private static final ObjectMapper CODEC = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final PetHealthRecordStore store;
    private final PetStore pets;
    private final CommandIdempotencyStore idempotency;
    private final SnowflakeIdGenerator ids;
    private final SessionControl sessionControl;
    private final TransactionTemplate execution;

    public PetHealthRecordService(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.store = new PetHealthRecordStore(dataSource);
        this.pets = new PetStore(dataSource);
        this.idempotency = new CommandIdempotencyStore(dataSource, ids);
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
        this.sessionControl = new SessionControl(dataSource);
        this.execution = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        execution.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        execution.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        execution.setTimeout(10);
    }

    /** First-create versus idempotent-replay distinction for the HTTP adapter (23 §5: 201/200). */
    public record CommandOutcome<R>(R receipt, boolean replayed) {}

    /** Same execution as {@link #createVaccinationRecord} plus the replay flag for status mapping. */
    public CommandOutcome<PetHealthRecordCommands.CreateRecordReceipt> createVaccinationOutcome(
            PetHealthRecordCommands.CreateVaccinationRecord command) {
        return createVaccination(command);
    }

    /** Same execution as {@link #createDewormingRecord} plus the replay flag for status mapping. */
    public CommandOutcome<PetHealthRecordCommands.CreateRecordReceipt> createDewormingOutcome(
            PetHealthRecordCommands.CreateDewormingRecord command) {
        return createDeworming(command);
    }

    @Override
    public PetHealthRecordCommands.CreateRecordReceipt createVaccinationRecord(
            PetHealthRecordCommands.CreateVaccinationRecord command) {
        return createVaccination(command).receipt();
    }

    @Override
    public PetHealthRecordCommands.CreateRecordReceipt createDewormingRecord(
            PetHealthRecordCommands.CreateDewormingRecord command) {
        return createDeworming(command).receipt();
    }

    private CommandOutcome<PetHealthRecordCommands.CreateRecordReceipt> createVaccination(
            PetHealthRecordCommands.CreateVaccinationRecord command) {
        CommandContext context = trustedUserContext(command.context());
        validateVaccination(command.recordDate(), command.vaccineName(), command.doseNumber(),
                command.nextRecordDate(), command.note());
        long petId = PetService.numericId(command.petId(), "petId");
        long userId = actorUserId(context);
        Map<String, Object> params = baseParams(command.petId(), command.recordDate());
        params.put("doseNumber", command.doseNumber());
        params.put("nextRecordDate", date(command.nextRecordDate()));
        params.put("note", command.note());
        params.put("vaccineName", command.vaccineName());
        return create(context, petId, userId, VACCINATION, params, ignored -> insertRow(
                userId, petId, VACCINATION, command.recordDate(),
                command.vaccineName(), command.doseNumber(), null, null,
                command.nextRecordDate(), command.note()));
    }

    private CommandOutcome<PetHealthRecordCommands.CreateRecordReceipt> createDeworming(
            PetHealthRecordCommands.CreateDewormingRecord command) {
        CommandContext context = trustedUserContext(command.context());
        validateDeworming(command.recordDate(), command.dewormingType(), command.productName(),
                command.nextRecordDate(), command.note());
        long petId = PetService.numericId(command.petId(), "petId");
        long userId = actorUserId(context);
        Map<String, Object> params = baseParams(command.petId(), command.recordDate());
        params.put("dewormingType", command.dewormingType());
        params.put("nextRecordDate", date(command.nextRecordDate()));
        params.put("note", command.note());
        params.put("productName", command.productName());
        return create(context, petId, userId, DEWORMING, params, ignored -> insertRow(
                userId, petId, DEWORMING, command.recordDate(),
                null, null, command.dewormingType(), command.productName(),
                command.nextRecordDate(), command.note()));
    }

    @Override
    public List<PetHealthRecordView> listVaccinationRecords(String petId, String ownerUserId) {
        return list(petId, ownerUserId, VACCINATION);
    }

    @Override
    public List<PetHealthRecordView> listDewormingRecords(String petId, String ownerUserId) {
        return list(petId, ownerUserId, DEWORMING);
    }

    // --- shared create frame (supplement 23 §5, scope USER_SELF; audit first-write-only) ---

    private CommandOutcome<PetHealthRecordCommands.CreateRecordReceipt> create(
            CommandContext context, long petId, long userId, String recordType,
            Map<String, Object> params, Function<Void, PetHealthRecordStore.RecordRow> body) {
        String requestKey = "pet.health-record.create|USER|" + context.operatorId() + "|USER_SELF|"
                + context.requestId();
        CanonicalParams.Canonical canonical = CanonicalParams.of(params);
        Optional<String> replay;
        try {
            replay = idempotency.admit(requestKey, canonical);
        } catch (CommandIdempotencyStore.ParamsConflict conflict) {
            throw new ApiException("IDEMPOTENCY_KEY_CONFLICT", "相同 requestId 对应不同参数");
        }
        if (replay.isPresent()) {
            PetHealthRecordCommands.CreateRecordReceipt receipt =
                    deserialize(replay.orElseThrow(), PetHealthRecordCommands.CreateRecordReceipt.class);
            requireActiveUser(userId);
            return new CommandOutcome<>(receipt, true);
        }
        PetHealthRecordCommands.CreateRecordReceipt receipt = execution.execute(status -> {
            sessionControl.applyExecutionDefaults();
            idempotency.lockForExecution(requestKey);
            requireActiveUser(userId);
            requireOwnedActivePet(petId, userId);
            if (store.countActive(petId, recordType) >= RECORD_LIMIT) {
                throw new ApiException("PET_HEALTH_RECORD_LIMIT_EXCEEDED",
                        "该宠物此类健康记录已达上限" + RECORD_LIMIT + "条");
            }
            PetHealthRecordStore.RecordRow row = body.apply(null);
            store.insertAudit(freshId(), userId, CREATE_AUDIT_ACTION, row.id(), petId, recordType,
                    requestKey, context.requestId(), context.traceId());
            var created = new PetHealthRecordCommands.CreateRecordReceipt(
                    Long.toUnsignedString(row.petId()), Long.toUnsignedString(row.id()),
                    row.recordType(), row.status());
            idempotency.succeed(requestKey, serialize(created));
            return created;
        });
        return new CommandOutcome<>(receipt, false);
    }

    private PetHealthRecordStore.RecordRow insertRow(
            long userId, long petId, String recordType,
            LocalDate recordDate, String vaccineName, Integer doseNumber, String dewormingType,
            String productName, LocalDate nextRecordDate, String note) {
        long recordId = freshId();
        PetHealthRecordStore.RecordRow row = new PetHealthRecordStore.RecordRow(
                recordId, userId, petId, recordType, recordDate, vaccineName, doseNumber,
                dewormingType, productName, nextRecordDate, note, "ACTIVE");
        store.insertRecord(row);
        return row;
    }

    // --- reads ---

    private List<PetHealthRecordView> list(String petIdRaw, String ownerUserId, String recordType) {
        long petId = PetService.numericId(petIdRaw, "petId");
        long owner = PetService.numericId(ownerUserId, "ownerUserId");
        requireOwnedActivePet(petId, owner);
        return store.listActiveRecords(petId, recordType).stream()
                .map(PetHealthRecordService::toView).toList();
    }

    private static PetHealthRecordView toView(PetHealthRecordStore.RecordRow row) {
        return new PetHealthRecordView(
                Long.toUnsignedString(row.id()), Long.toUnsignedString(row.petId()),
                row.recordType(), row.recordDate(), row.vaccineName(), row.doseNumber(),
                row.dewormingType(), row.productName(), row.nextRecordDate(), row.note(),
                row.status());
    }

    // --- validation (PRD 5.1.3 field tables) ---

    private static void validateVaccination(LocalDate recordDate, String vaccineName,
            Integer doseNumber, LocalDate nextRecordDate, String note) {
        requiredNotFutureDate(recordDate);
        text(vaccineName, 50, "vaccineName");
        if (doseNumber != null && (doseNumber < 1 || doseNumber > 20)) {
            throw new IllegalArgumentException("doseNumber must be 1..20");
        }
        notBefore(nextRecordDate, recordDate);
        optionalText(note, 200, "note");
    }

    private static void validateDeworming(LocalDate recordDate, String dewormingType,
            String productName, LocalDate nextRecordDate, String note) {
        requiredNotFutureDate(recordDate);
        if (dewormingType == null || !DEWORMING_TYPES.contains(dewormingType)) {
            throw new IllegalArgumentException("dewormingType must be one of " + DEWORMING_TYPES);
        }
        optionalText(productName, 50, "productName");
        notBefore(nextRecordDate, recordDate);
        optionalText(note, 200, "note");
    }

    private static void requiredNotFutureDate(LocalDate value) {
        if (value == null) throw new IllegalArgumentException("recordDate is required");
        if (!value.isBefore(LocalDate.now().plusDays(1))) {
            throw new IllegalArgumentException("recordDate must not be in the future");
        }
    }

    private static void notBefore(LocalDate next, LocalDate anchor) {
        if (next == null) return;
        if (next.isBefore(anchor)) {
            throw new IllegalArgumentException("nextRecordDate must not be earlier than recordDate");
        }
    }

    private static void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException(field + " must contain 1.." + max + " code points");
        }
    }

    private static void optionalText(String value, int max, String field) {
        if (value == null) return;
        if (value.isBlank() || value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException(field + " must be absent or contain 1.." + max + " code points");
        }
    }

    // --- guards and helpers (PetService/AddressService same frame) ---

    private static Map<String, Object> baseParams(String petId, LocalDate recordDate) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("petId", petId);
        params.put("recordDate", date(recordDate));
        return params;
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static CommandContext trustedUserContext(CommandContext context) {
        CommandContext checked = PublicContractChecks.requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.USER) {
            throw new IllegalArgumentException("Health record commands require a USER operator");
        }
        return checked;
    }

    private static long actorUserId(CommandContext context) {
        return PetService.numericId(context.operatorId(), "operatorId");
    }

    private long freshId() {
        long id = ids.nextId();
        if (id <= 0) throw new IllegalStateException("Invalid ID from provider");
        return id;
    }

    private void requireActiveUser(long userId) {
        PetStore.UserRow user = pets.findUser(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
        }
    }

    private void requireOwnedActivePet(long petId, long ownerUserId) {
        PetStore.PetRow pet = pets.findPet(petId, false);
        if (pet == null || !"ACTIVE".equals(pet.status()) || pet.userId() != ownerUserId) {
            // Anti-enumeration: not-mine, gone and disabled are indistinguishable.
            throw new ApiException("PET_NOT_FOUND", "宠物不存在");
        }
    }

    private String serialize(Object value) {
        try {
            return CODEC.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Receipt serialization failed; transaction rolled back", error);
        }
    }

    private <R> R deserialize(String receiptJson, Class<R> receiptType) {
        try {
            return CODEC.readValue(receiptJson, receiptType);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Stored receipt is not readable", error);
        }
    }
}
