package com.petplatform.user.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.api.command.AddressCommandApi;
import com.petplatform.user.api.command.AddressCommands.AddressReceipt;
import com.petplatform.user.api.command.AddressCommands.CreateAddress;
import com.petplatform.user.api.command.AddressCommands.DeleteAddress;
import com.petplatform.user.api.command.AddressCommands.SetDefaultAddress;
import com.petplatform.user.api.command.AddressCommands.UpdateAddress;
import com.petplatform.user.api.dto.AddressView;
import com.petplatform.user.api.query.AddressQueryApi;
import com.petplatform.user.biz.infrastructure.persistence.AddressStore;
import com.petplatform.user.biz.infrastructure.persistence.CommandIdempotencyStore;
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
 * Address book commands and owner-scoped reads per contract 59 (ruling-32 second batch):
 * anti-enumeration ADDRESS_NOT_FOUND for every not-mine/not-found/deleted case, USER_FROZEN
 * rejection for writes, atomic single default, soft delete, a conservative 20-address cap and
 * supplement 23 §5 idempotent writes (first receipt bound to requestId, scope USER_SELF).
 * Orders never reference these rows — the booking chain keeps its own text snapshot.
 */
public final class AddressService implements AddressCommandApi, AddressQueryApi {
    /** Conservative per-user ACTIVE cap (contract 59 §hard rules; disclosed in the slice PR). */
    static final int ADDRESS_LIMIT = 20;
    private static final String PHONE_PATTERN = "^1[3-9][0-9]{9}$";
    private static final ObjectMapper CODEC = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AddressStore store;
    private final CommandIdempotencyStore idempotency;
    private final SnowflakeIdGenerator ids;
    private final SessionControl sessionControl;
    private final TransactionTemplate execution;

    public AddressService(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.store = new AddressStore(dataSource);
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
    public AddressView createAddress(CreateAddress command) {
        return createAddressOutcome(command).receipt();
    }

    /** Same execution as {@link #createAddress} plus the replay flag for status mapping. */
    public CommandOutcome<AddressView> createAddressOutcome(CreateAddress command) {
        CommandContext context = trustedUserContext(command.context());
        validateFields(command.contactName(), command.contactPhone(), command.region(), command.detail());
        long userId = actorUserId(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("contactName", command.contactName());
        params.put("contactPhone", command.contactPhone());
        params.put("defaultAddress", command.defaultAddress());
        params.put("detail", command.detail());
        params.put("region", command.region());
        Idempotent<AddressView> outcome = withIdempotencyOutcome("address.create", context, params, AddressView.class,
                ignored -> {
                    requireActiveUser(userId);
                    if (store.countActive(userId) >= ADDRESS_LIMIT) {
                        throw new ApiException("ADDRESS_LIMIT_EXCEEDED",
                                "地址数量已达上限" + ADDRESS_LIMIT + "条，请先删除不需要的地址");
                    }
                    long addressId = freshId();
                    if (command.defaultAddress()) store.clearDefault(userId, addressId);
                    store.insertAddress(new AddressStore.AddressRow(addressId, userId,
                            command.contactName(), command.contactPhone(), command.region(),
                            command.detail(), command.defaultAddress(), "ACTIVE"));
                    return toView(store.findAddress(addressId, false));
                },
                view -> {
                    requireActiveUser(userId);
                    return null;
                });
        return new CommandOutcome<>(outcome.value(), outcome.replayed());
    }

    @Override
    public AddressView updateAddress(UpdateAddress command) {
        CommandContext context = trustedUserContext(command.context());
        long addressId = PetService.numericId(command.addressId(), "addressId");
        validateFields(command.contactName(), command.contactPhone(), command.region(), command.detail());
        long userId = actorUserId(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("addressId", command.addressId());
        params.put("contactName", command.contactName());
        params.put("contactPhone", command.contactPhone());
        params.put("defaultAddress", command.defaultAddress());
        params.put("detail", command.detail());
        params.put("region", command.region());
        return withIdempotency("address.update", context, params, AddressView.class,
                ignored -> {
                    requireActiveUser(userId);
                    AddressStore.AddressRow current = requireOwnedActiveAddress(addressId, userId, true);
                    boolean nextDefault = command.defaultAddress() == null ? current.isDefault() : command.defaultAddress();
                    if (nextDefault) store.clearDefault(userId, addressId);
                    store.updateAddressFields(new AddressStore.AddressRow(current.id(), current.userId(),
                            command.contactName(), command.contactPhone(), command.region(),
                            command.detail(), nextDefault, current.status()));
                    return toView(store.findAddress(addressId, false));
                },
                view -> {
                    requireActiveUser(userId);
                    return null;
                });
    }

    @Override
    public AddressReceipt deleteAddress(DeleteAddress command) {
        CommandContext context = trustedUserContext(command.context());
        long addressId = PetService.numericId(command.addressId(), "addressId");
        long userId = actorUserId(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("addressId", command.addressId());
        return withIdempotency("address.delete", context, params, AddressReceipt.class,
                ignored -> {
                    requireActiveUser(userId);
                    requireOwnedActiveAddress(addressId, userId, true);
                    store.softDelete(addressId, userId);
                    return new AddressReceipt(Long.toUnsignedString(addressId), "DELETED");
                },
                ignored -> {
                    requireActiveUser(userId);
                    return null;
                });
    }

    @Override
    public AddressView setDefaultAddress(SetDefaultAddress command) {
        CommandContext context = trustedUserContext(command.context());
        long addressId = PetService.numericId(command.addressId(), "addressId");
        long userId = actorUserId(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("addressId", command.addressId());
        return withIdempotency("address.setdefault", context, params, AddressView.class,
                ignored -> {
                    requireActiveUser(userId);
                    requireOwnedActiveAddress(addressId, userId, true);
                    store.clearDefault(userId, addressId);
                    store.markDefault(addressId, userId);
                    return toView(store.findAddress(addressId, false));
                },
                view -> {
                    requireActiveUser(userId);
                    return null;
                });
    }

    @Override
    public List<AddressView> listActiveAddresses(String ownerUserId) {
        return store.listActiveAddresses(PetService.numericId(ownerUserId, "ownerUserId")).stream()
                .map(AddressService::toView).toList();
    }

    @Override
    public AddressView getAddress(String addressId, String ownerUserId) {
        long owner = PetService.numericId(ownerUserId, "ownerUserId");
        return toView(requireOwnedActiveAddress(PetService.numericId(addressId, "addressId"), owner, false));
    }

    // --- idempotency frame (supplement 23 §5, scope USER_SELF) ---

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
            throw new ApiException("IDEMPOTENCY_KEY_CONFLICT", "相同 requestId 对应不同参数");
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
            throw new IllegalArgumentException("Address commands require a USER operator");
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
        com.petplatform.user.biz.infrastructure.persistence.PetStore.UserRow user = store.findUser(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
        }
    }

    private AddressStore.AddressRow requireOwnedActiveAddress(long addressId, long ownerUserId, boolean forUpdate) {
        AddressStore.AddressRow address = store.findAddress(addressId, forUpdate);
        if (address == null || !"ACTIVE".equals(address.status()) || address.userId() != ownerUserId) {
            // Anti-enumeration: not-mine, gone and deleted are indistinguishable.
            throw new ApiException("ADDRESS_NOT_FOUND", "地址不存在");
        }
        return address;
    }

    private static void validateFields(String contactName, String contactPhone, String region, String detail) {
        text(contactName, 32, "contactName");
        if (contactPhone == null || !contactPhone.matches(PHONE_PATTERN)) {
            throw new IllegalArgumentException("contactPhone must be an 11-digit mainland mobile number");
        }
        text(region, 64, "region");
        text(detail, 256, "detail");
    }

    private static void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException(field + " must contain 1.." + max + " characters");
        }
    }

    private static AddressView toView(AddressStore.AddressRow address) {
        return new AddressView(Long.toUnsignedString(address.id()), address.contactName(),
                address.contactPhone(), address.region(), address.detail(), address.isDefault(),
                address.status());
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
