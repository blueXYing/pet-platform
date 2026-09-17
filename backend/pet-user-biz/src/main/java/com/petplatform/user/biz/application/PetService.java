package com.petplatform.user.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.api.command.PetCommandApi;
import com.petplatform.user.api.command.PetCommands.CreatePet;
import com.petplatform.user.api.command.PetCommands.DeletePet;
import com.petplatform.user.api.command.PetCommands.PetReceipt;
import com.petplatform.user.api.command.PetCommands.UpdatePet;
import com.petplatform.user.api.dto.PetView;
import com.petplatform.user.api.query.PetQueryApi;
import com.petplatform.user.biz.infrastructure.persistence.CommandIdempotencyStore;
import com.petplatform.user.biz.infrastructure.persistence.PetStore;
import com.petplatform.user.biz.infrastructure.persistence.SessionControl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pet archive commands and owner-scoped reads per the approved user domain 0.1:
 * anti-enumeration PET_NOT_FOUND for every not-mine/not-found/disabled case,
 * USER_FROZEN rejection for writes, atomic single default, soft delete, and
 * supplement 23 §5 idempotent writes (first receipt bound to requestId).
 */
public final class PetService implements PetCommandApi, PetQueryApi {
    private static final ObjectMapper CODEC = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final PetStore store;
    private final CommandIdempotencyStore idempotency;
    private final SnowflakeIdGenerator ids;
    private final SessionControl sessionControl;
    private final TransactionTemplate execution;

    public PetService(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.store = new PetStore(dataSource);
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

    @Override
    public PetView createPet(CreatePet command) {
        return createPetOutcome(command).receipt();
    }

    /** Same execution as {@link #createPet} plus the replay flag for status mapping. */
    public CommandOutcome<PetView> createPetOutcome(CreatePet command) {
        CommandContext context = trustedUserContext(command.context());
        validateCreate(command);
        long userId = actorUserId(context);
        requireActiveUser(userId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("breedName", command.breedName());
        params.put("birthDate", command.birthDate() == null ? null : command.birthDate().toString());
        params.put("healthNote", command.healthNote());
        params.put("name", command.name());
        params.put("petType", command.petType());
        params.put("sex", command.sex());
        params.put("sterilizationStatus", command.sterilizationStatus());
        params.put("vaccineStatus", command.vaccineStatus());
        params.put("weightKg", command.weightKg() == null ? null : command.weightKg().toPlainString());
        Idempotent<PetView> outcome = withIdempotencyOutcome("pet.create", context, params, PetView.class,
                ignored -> {
                    long petId = freshId();
                    String sex = command.sex() == null ? "UNKNOWN" : command.sex();
                    if (command.defaultPet()) store.clearDefault(userId, petId);
                    store.insertPet(new PetStore.PetRow(petId, userId, command.name(), command.petType(),
                            command.breedName(), command.birthDate(), sex, command.weightKg(),
                            command.sterilizationStatus(), command.vaccineStatus(), command.healthNote(),
                            command.avatarUrl(), command.defaultPet(), "ACTIVE"));
                    return toView(store.findPet(petId, false));
                },
                view -> {
                    requireActiveUser(userId);
                    return null;
                });
        return new CommandOutcome<>(outcome.value(), outcome.replayed());
    }

    @Override
    public PetView updatePet(UpdatePet command) {
        CommandContext context = trustedUserContext(command.context());
        long petId = numericId(command.petId(), "petId");
        validateUpdate(command);
        long userId = actorUserId(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("avatarUrl", command.avatarUrl());
        params.put("birthDate", command.birthDate() == null ? null : command.birthDate().toString());
        params.put("breedName", command.breedName());
        params.put("defaultPet", command.defaultPet());
        params.put("healthNote", command.healthNote());
        params.put("name", command.name());
        params.put("petId", command.petId());
        params.put("sex", command.sex());
        params.put("sterilizationStatus", command.sterilizationStatus());
        params.put("vaccineStatus", command.vaccineStatus());
        params.put("weightKg", command.weightKg() == null ? null : command.weightKg().toPlainString());
        return withIdempotency("pet.update", context, params, PetView.class,
                ignored -> {
                    requireActiveUser(userId);
                    PetStore.PetRow current = requireOwnedActivePet(petId, userId, true);
                    boolean nextDefault = command.defaultPet() == null ? current.isDefault() : command.defaultPet();
                    String sex = command.sex() == null ? "UNKNOWN" : command.sex();
                    if (nextDefault) store.clearDefault(userId, petId);
                    store.updatePetFields(new PetStore.PetRow(current.id(), current.userId(), command.name(),
                            current.petType(), command.breedName(), command.birthDate(), sex,
                            command.weightKg(), command.sterilizationStatus(), command.vaccineStatus(),
                            command.healthNote(), command.avatarUrl(), nextDefault, current.status()));
                    return toView(store.findPet(petId, false));
                },
                view -> {
                    requireActiveUser(userId);
                    return null;
                });
    }

    @Override
    public PetReceipt deletePet(DeletePet command) {
        CommandContext context = trustedUserContext(command.context());
        long petId = numericId(command.petId(), "petId");
        long userId = actorUserId(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("petId", command.petId());
        return withIdempotency("pet.delete", context, params, PetReceipt.class,
                ignored -> {
                    requireActiveUser(userId);
                    requireOwnedActivePet(petId, userId, true);
                    store.softDelete(petId, userId);
                    return new PetReceipt(Long.toUnsignedString(petId), "DISABLED", false);
                },
                ignored -> {
                    requireActiveUser(userId);
                    return null;
                });
    }

    @Override
    public List<PetView> listActivePets(String ownerUserId) {
        return store.listActivePets(numericId(ownerUserId, "ownerUserId")).stream()
                .map(PetService::toView).toList();
    }

    @Override
    public PetView getPet(String petId, String ownerUserId) {
        long owner = numericId(ownerUserId, "ownerUserId");
        return toView(requireOwnedActivePet(numericId(petId, "petId"), owner, false));
    }

    // --- idempotency frame (supplement 23 §5) ---

    private record Idempotent<R>(R value, boolean replayed) {}

    private <R> R withIdempotency(String namespace, CommandContext context, Map<String, Object> params,
                                  Class<R> receiptType, Function<Void, R> executionBody,
                                  Function<R, Void> replayRevalidation) {
        return withIdempotencyOutcome(namespace, context, params, receiptType,
                executionBody, replayRevalidation).value();
    }

    private <R> Idempotent<R> withIdempotencyOutcome(String namespace, CommandContext context,
                                                     Map<String, Object> params, Class<R> receiptType,
                                                     Function<Void, R> executionBody,
                                                     Function<R, Void> replayRevalidation) {
        String requestKey = namespace + "|USER|" + context.operatorId() + "|USER_SELF|" + context.requestId();
        CanonicalParams.Canonical canonical = CanonicalParams.of(params);
        Optional<String> replay;
        try {
            replay = idempotency.admit(requestKey, canonical);
        } catch (CommandIdempotencyStore.ParamsConflict conflict) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "相同 requestId 对应不同参数");
        }
        if (replay.isPresent()) {
            R receipt = deserialize(replay.orElseThrow(), receiptType);
            replayRevalidation.apply(receipt);
            return new Idempotent<>(receipt, true);
        }
        return new Idempotent<>(execution.execute(status -> {
            sessionControl.applyExecutionDefaults();
            idempotency.lockForExecution(requestKey);
            R result = executionBody.apply(null);
            idempotency.succeed(requestKey, serialize(result));
            return result;
        }), false);
    }

    // --- guards and helpers ---

    private static CommandContext trustedUserContext(CommandContext context) {
        CommandContext checked = PublicContractChecks.requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.USER) {
            throw new IllegalArgumentException("Pet commands require a USER operator");
        }
        return checked;
    }

    private static long actorUserId(CommandContext context) {
        return numericId(context.operatorId(), "operatorId");
    }

    private long freshId() {
        long id = ids.nextId();
        if (id <= 0) throw new IllegalStateException("Invalid ID from provider");
        return id;
    }

    private void requireActiveUser(long userId) {
        PetStore.UserRow user = store.findUser(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
        }
    }

    private PetStore.PetRow requireOwnedActivePet(long petId, long ownerUserId, boolean forUpdate) {
        PetStore.PetRow pet = store.findPet(petId, forUpdate);
        if (pet == null || !"ACTIVE".equals(pet.status()) || pet.userId() != ownerUserId) {
            // Anti-enumeration: not-mine, gone and disabled are indistinguishable.
            throw new ApiException("PET_NOT_FOUND", "宠物不存在");
        }
        return pet;
    }

    private static void validateCreate(CreatePet command) {
        PetValidator.text(command.name(), 64, "name");
        PetValidator.choice(command.petType(), PetValidator.PET_TYPES, "petType");
        PetValidator.optional(command.breedName(), 64, "breedName");
        PetValidator.pastDate(command.birthDate());
        PetValidator.choice(command.sex() == null ? "UNKNOWN" : command.sex(), PetValidator.SEXES, "sex");
        PetValidator.weight(command.weightKg());
        PetValidator.optionalChoice(command.sterilizationStatus(), PetValidator.STERILIZATION, "sterilizationStatus");
        PetValidator.optionalChoice(command.vaccineStatus(), PetValidator.VACCINE, "vaccineStatus");
        PetValidator.optional(command.healthNote(), 1000, "healthNote");
        PetValidator.avatar(command.avatarUrl());
    }

    private static void validateUpdate(UpdatePet command) {
        PetValidator.text(command.name(), 64, "name");
        PetValidator.optional(command.breedName(), 64, "breedName");
        PetValidator.pastDate(command.birthDate());
        PetValidator.optionalChoice(command.sex(), PetValidator.SEXES, "sex");
        PetValidator.weight(command.weightKg());
        PetValidator.optionalChoice(command.sterilizationStatus(), PetValidator.STERILIZATION, "sterilizationStatus");
        PetValidator.optionalChoice(command.vaccineStatus(), PetValidator.VACCINE, "vaccineStatus");
        PetValidator.optional(command.healthNote(), 1000, "healthNote");
        PetValidator.avatar(command.avatarUrl());
    }

    public static long numericId(String value, String field) {
        if (value == null || value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(field + " must be the numeric String form of the id");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + " must be a positive BIGINT", error);
        }
    }

    private static PetView toView(PetStore.PetRow pet) {
        return new PetView(Long.toUnsignedString(pet.id()), pet.name(), pet.petType(), pet.breedName(),
                pet.birthDate(), pet.sex(), pet.weightKg(), pet.sterilizationStatus(), pet.vaccineStatus(),
                pet.healthNote(), pet.avatarUrl(), pet.isDefault(), pet.status());
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
