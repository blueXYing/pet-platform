package com.petplatform.order.biz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.FixedDecimalMoneyCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.dto.BookingMerchantFacts;
import com.petplatform.merchant.api.query.BookingMerchantFactsApi;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderResult;
import com.petplatform.order.biz.infrastructure.persistence.OrderCreationStore;
import com.petplatform.order.biz.infrastructure.persistence.entity.OrderCreationBinding;
import com.petplatform.schedule.api.command.ReservationHoldApi;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldResult;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.service.api.dto.BookingServiceFacts;
import com.petplatform.service.api.query.BookingServiceFactsApi;
import com.petplatform.user.api.dto.PetSnapshotDTO;
import com.petplatform.user.api.query.BookingUserFactsApi;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 38号 internal create: durable admission and one guarded local business transaction. */
public final class OrderCreationService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final FixedDecimalMoneyCodec MONEY = new FixedDecimalMoneyCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CANONICAL_VERSION = "canonical-v1";
    private final SnowflakeIdGenerator ids;
    private final BookingUserFactsApi users;
    private final BookingMerchantFactsApi merchants;
    private final BookingServiceFactsApi services;
    private final ScheduleCapacityGuardApi guard;
    private final ReservationHoldApi reservations;
    private final OrderCreationInputProtection protection;
    private final OrderCreationRemarkPolicy remarkPolicy;
    private final Clock clock;
    private final OrderCreationStore store;
    private final TransactionTemplate admission;
    private final TransactionTemplate execution;

    public OrderCreationService(DataSource source, SnowflakeIdGenerator ids,
            BookingUserFactsApi users, BookingMerchantFactsApi merchants,
            BookingServiceFactsApi services, ScheduleCapacityGuardApi guard,
            ReservationHoldApi reservations, OrderCreationInputProtection protection,
            OrderCreationRemarkPolicy remarkPolicy, Clock clock) {
        this.ids = Objects.requireNonNull(ids);
        this.users = Objects.requireNonNull(users);
        this.merchants = Objects.requireNonNull(merchants);
        this.services = Objects.requireNonNull(services);
        this.guard = Objects.requireNonNull(guard);
        this.reservations = Objects.requireNonNull(reservations);
        this.protection = protection;
        this.remarkPolicy = remarkPolicy;
        this.clock = Objects.requireNonNull(clock);
        this.store = new OrderCreationStore(Objects.requireNonNull(source));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        this.admission = transaction(manager);
        this.execution = transaction(manager);
    }

    private static TransactionTemplate transaction(DataSourceTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(15);
        return template;
    }

    public CreateOrderResult create(CreateOrderCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable("ORDER Admission requires no outer business transaction");
        }
        Prepared prepared = prepare(command);
        QueryContext queryContext = queryContext(command.context());
        // Initial identity/availability check precedes any persistent binding disclosure.
        users.checkActor(prepared.userId(), queryContext);
        try {
            if (command.remark() != null) {
                Receipt previous = existingSuccess(prepared);
                if (previous != null) return replay(prepared, previous, queryContext);
                try {
                    validateRemark(command.remark());
                } catch (ApiException unavailableOrRejected) {
                    // A concurrent first execution may have committed while moderation was checked.
                    Receipt completed = existingSuccess(prepared);
                    if (completed != null) return replay(prepared, completed, queryContext);
                    throw unavailableOrRejected;
                }
            }
            Receipt bound = admit(prepared);
            if (bound != null) return replay(prepared, bound, queryContext);
            Outcome outcome = execution.execute(status -> execute(prepared, queryContext));
            if (outcome == null) throw unavailable("ORDER execution did not return a result");
            return outcome.created() ? result(outcome.receipt(), true)
                    : replay(prepared, outcome.receipt(), queryContext);
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            // A failed commit acknowledgement may hide an already committed success. Consult
            // the original durable key on the primary database before reporting uncertainty.
            Receipt committed = null;
            try {
                committed = existingSuccess(prepared);
            } catch (RuntimeException readUnavailable) {
                // The original failure remains authoritative for busy vs unavailable mapping.
            }
            if (committed != null) return replay(prepared, committed, queryContext);
            throw databaseFailure(failure);
        }
    }

    private Receipt admit(Prepared input) {
        return admission.execute(status -> {
            store.sessionDefaults();
            Map<String, Object> row = values();
            row.put("id", nextId());
            row.put("requestKey", input.key());
            row.put("canonicalVersion", CANONICAL_VERSION);
            row.put("paramsSha256", input.sha256());
            row.put("paramsCanonical", input.canonical());
            row.put("traceId", input.command().context().traceId());
            try {
                store.insertBinding(row);
                return null;
            } catch (DuplicateKeyException duplicate) {
                OrderCreationBinding bound = requireMatching(store.lockBinding(input.key()), input);
                return "SUCCEEDED".equals(bound.getStatus()) ? parseReceipt(bound.getReceiptJson()) : null;
            }
        });
    }

    private Receipt existingSuccess(Prepared input) {
        return admission.execute(status -> {
            store.sessionDefaults();
            OrderCreationBinding existing = store.lockBinding(input.key());
            if (existing == null) return null;
            OrderCreationBinding matched = requireMatching(existing, input);
            return "SUCCEEDED".equals(matched.getStatus()) ? parseReceipt(matched.getReceiptJson()) : null;
        });
    }

    private void validateRemark(String remark) {
        if (remarkPolicy == null) throw unavailable("remark validation is unavailable");
        try { remarkPolicy.validate(remark); }
        catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable("remark validation is unavailable"); }
    }

    private Outcome execute(Prepared input, QueryContext context) {
        store.sessionDefaults();
        OrderCreationBinding bound = requireMatching(store.lockBinding(input.key()), input);
        if ("SUCCEEDED".equals(bound.getStatus())) {
            return new Outcome(parseReceipt(bound.getReceiptJson()), false);
        }
        if (!"RESERVED".equals(bound.getStatus())) throw unavailable("unknown ORDER request status");
        CreateOrderCommand command = input.command();
        guard.acquire(List.of(command.storeId()), context);
        // Every Owner read below is current and joins this guarded READ_COMMITTED transaction.
        users.requireCurrentActor(input.userId(), command.storeId(), context);
        BookingMerchantFacts merchant = merchants.readCurrentStore(command.storeId(), context);
        BookingServiceFacts service = services.readCurrentService(
                command.storeId(), command.serviceId(), context);
        PetSnapshotDTO pet = users.readCurrentPet(input.userId(), command.petId(),
                command.storeId(), context);
        verifyFacts(input, merchant, service, pet);
        if (command.couponInstanceId() != null) {
            throw unavailable("coupon freeze provider is not implemented");
        }
        long orderId = nextId();
        long orderNo = nextId();
        HoldResult held = reservations.hold(new HoldCommand(command.context(), Long.toString(orderId),
                input.userId(), merchant.merchantId(), command.storeId(), command.serviceId(),
                command.fulfillmentType(), command.appointmentStart(), command.appointmentEnd(),
                command.pickupStart(), command.returnStart(), command.selectedGeneralWindowId(),
                command.selectedPickupWindowId(), command.selectedReturnWindowId()));
        verifyHold(input, held, orderId);
        writeOrder(input, merchant, service, pet, held, orderId, orderNo);
        Receipt receipt = new Receipt(Long.toString(orderId), Long.toString(orderNo),
                MONEY.format(service.price()), held.holdExpireAt().toString());
        store.succeed(input.key(), serializeReceipt(receipt));
        return new Outcome(receipt, true);
    }

    private CreateOrderResult replay(Prepared input, Receipt receipt, QueryContext context) {
        users.checkActor(input.userId(), context);
        Long owner = store.owner(IDS.fromApi(receipt.orderId()));
        if (owner == null || owner != IDS.fromApi(input.userId())) {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "订单不存在");
        }
        return result(receipt, false);
    }

    private static CreateOrderResult result(Receipt receipt, boolean created) {
        return new CreateOrderResult(receipt.orderId(), receipt.orderNo(), "PENDING_PAYMENT",
                receipt.payAmount(), OffsetDateTime.parse(receipt.paymentExpireAt()), created, !created);
    }

    private static QueryContext queryContext(CommandContext context) {
        return new QueryContext(context.traceId(), context.operatorType(), context.operatorId());
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable("Snowflake ID provider returned a nonpositive ID");
        return value;
    }

    private static OrderCreationBinding requireMatching(OrderCreationBinding binding, Prepared input) {
        if (binding == null) throw unavailable("ORDER binding disappeared");
        if (!CANONICAL_VERSION.equals(binding.getCanonicalVersion())) {
            throw unavailable("ORDER canonical version cannot be replayed");
        }
        if (!input.sha256().equals(binding.getParamsSha256())
                || !Arrays.equals(input.canonical(), binding.getParamsCanonical())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "相同 requestId 对应不同参数");
        }
        return binding;
    }

    private record Receipt(String orderId, String orderNo, String payAmount, String paymentExpireAt) {}
    private record Outcome(Receipt receipt, boolean created) {}
    private record Prepared(CreateOrderCommand command, String userId, byte[] key, byte[] canonical,
            String sha256, byte[] addressCiphertext, byte[] remarkCiphertext) {}

    private Prepared prepare(CreateOrderCommand command) {
        if (command == null || command.context() == null) throw invalid("订单请求不能为空");
        CommandContext caller;
        try {
            caller = PublicContractChecks.requireCommandRequestId(command.context());
        } catch (IllegalArgumentException malformed) {
            throw invalid("requestId 不合法");
        }
        if (caller.operatorType() != OperatorType.USER) throw new ApiException(
                CommonApiCodes.FORBIDDEN, "仅用户可创建订单");
        String userId = positiveId(caller.operatorId());
        positiveId(command.storeId());
        positiveId(command.serviceId());
        positiveId(command.petId());
        optionalId(command.selectedGeneralWindowId());
        optionalId(command.selectedPickupWindowId());
        optionalId(command.selectedReturnWindowId());
        optionalId(command.couponInstanceId());
        if (caller.traceId() != null && caller.traceId().getBytes(StandardCharsets.UTF_8).length > 128) {
            throw invalid("traceId 过长");
        }
        if ("IN_STORE".equals(command.fulfillmentType())) {
            minute(command.appointmentStart());
            minute(command.appointmentEnd());
            if (!command.appointmentEnd().isAfter(command.appointmentStart())
                    || command.pickupStart() != null || command.returnStart() != null
                    || command.selectedPickupWindowId() != null
                    || command.selectedReturnWindowId() != null || command.serviceAddress() != null) {
                throw invalid("到店预约字段不合法");
            }
        } else if ("PICKUP_DELIVERY".equals(command.fulfillmentType())) {
            minute(command.pickupStart());
            minute(command.returnStart());
            if (command.appointmentStart() != null || command.appointmentEnd() != null
                    || command.selectedGeneralWindowId() != null
                    || command.selectedPickupWindowId() == null
                    || command.selectedReturnWindowId() == null
                    || command.selectedPickupWindowId().equals(command.selectedReturnWindowId())
                    || command.returnStart().isBefore(command.pickupStart().plusMinutes(120))) {
                throw invalid("接送预约字段不合法");
            }
            protectedText(command.serviceAddress(), 65536, true);
        } else throw invalid("履约方式不合法");

        OrderCreationInputProtection.ProtectedInput address = null;
        if (command.serviceAddress() != null) {
            address = protect("SERVICE_ADDRESS", command.serviceAddress());
        }
        OrderCreationInputProtection.ProtectedInput remark = null;
        if (command.remark() != null) {
            protectedText(command.remark(), 65536, true);
            if (command.remark().codePointCount(0, command.remark().length()) > 200) {
                throw invalid("服务备注超过200字");
            }
            remark = protect("CUSTOMER_REMARK", command.remark());
        }

        Map<String, Object> params = new TreeMap<>();
        params.put("action", "order.create");
        params.put("storeId", command.storeId());
        params.put("serviceId", command.serviceId());
        params.put("petId", command.petId());
        params.put("fulfillmentType", command.fulfillmentType());
        params.put("appointmentStart", canonicalTime(command.appointmentStart()));
        params.put("appointmentEnd", canonicalTime(command.appointmentEnd()));
        params.put("pickupStart", canonicalTime(command.pickupStart()));
        params.put("returnStart", canonicalTime(command.returnStart()));
        params.put("selectedGeneralWindowId", command.selectedGeneralWindowId());
        params.put("selectedPickupWindowId", command.selectedPickupWindowId());
        params.put("selectedReturnWindowId", command.selectedReturnWindowId());
        params.put("couponInstanceId", command.couponInstanceId());
        params.put("serviceAddressToken", token(address));
        params.put("remarkToken", token(remark));
        byte[] canonical;
        try {
            canonical = JSON.writeValueAsBytes(params);
        } catch (JsonProcessingException broken) {
            throw invalid("订单参数不能规范化");
        }
        if (canonical.length > 65536) throw invalid("订单参数过长");
        byte[] key = key(userId, caller.requestId());
        return new Prepared(command, userId, key, canonical, sha256(canonical),
                address == null ? null : address.ciphertext(),
                remark == null ? null : remark.ciphertext());
    }

    private OrderCreationInputProtection.ProtectedInput protect(String purpose, String value) {
        if (protection == null) throw unavailable("ORDER protected input adapter is unavailable");
        try {
            OrderCreationInputProtection.ProtectedInput secured = protection.protect(purpose, value);
            if (secured == null || secured.ciphertext() == null || secured.ciphertext().length == 0
                    || secured.equalityToken() == null || secured.equalityToken().length != 32) {
                throw unavailable("ORDER protected input adapter returned incomplete evidence");
            }
            return secured;
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable("ORDER protected input adapter is unavailable");
        }
    }

    private static String token(OrderCreationInputProtection.ProtectedInput input) {
        return input == null ? null : HexFormat.of().formatHex(input.equalityToken());
    }

    private static void protectedText(String text, int maxBytes, boolean required) {
        if (text == null || (required && text.isBlank())
                || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw invalid("受保护文本不合法");
        }
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (Character.isHighSurrogate(c)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    throw invalid("受保护文本不合法");
                }
            } else if (Character.isLowSurrogate(c)) throw invalid("受保护文本不合法");
        }
    }

    private static String canonicalTime(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static byte[] key(String userId, String requestId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream stream = new DataOutputStream(bytes);
            for (String part : List.of("order.create", "USER", userId, "CONSUMER", requestId)) {
                byte[] encoded = part.getBytes(StandardCharsets.UTF_8);
                stream.writeInt(encoded.length);
                stream.write(encoded);
            }
            stream.flush();
            byte[] key = bytes.toByteArray();
            if (key.length > 1024) throw invalid("requestId 过长");
            return key;
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory key encoding failed", impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }

    private static String positiveId(String value) {
        try { return IDS.toApi(IDS.fromApi(value)); }
        catch (RuntimeException malformed) { throw invalid("ID 不合法"); }
    }

    private static void optionalId(String value) {
        if (value != null) positiveId(value);
    }

    private static void minute(OffsetDateTime value) {
        try {
            PublicContractChecks.requireMillisecondPrecision(value);
            if (value.getSecond() != 0 || value.getNano() != 0) throw invalid("预约须为整分钟");
        } catch (IllegalArgumentException malformed) {
            throw invalid("预约时间不合法");
        }
    }

    private static void verifyFacts(Prepared input, BookingMerchantFacts merchant,
            BookingServiceFacts service, PetSnapshotDTO pet) {
        CreateOrderCommand command = input.command();
        if (merchant == null || service == null || pet == null) {
            throw unavailable("ORDER current Owner facts are incomplete");
        }
        if (!command.storeId().equals(merchant.storeId())
                || !command.storeId().equals(service.storeId())
                || !command.serviceId().equals(service.serviceId())
                || !merchant.merchantId().equals(service.merchantId())
                || !input.userId().equals(pet.ownerUserId())
                || !command.petId().equals(pet.petId())
                || !command.fulfillmentType().equals(service.fulfillmentType())
                || blank(merchant.merchantName()) || blank(merchant.storeName())
                || blank(merchant.storeAddress()) || blank(service.serviceName())
                || blank(pet.name()) || blank(pet.categoryCode())
                || service.durationMinutes() <= 0) {
            throw unavailable("ORDER current Owner facts disagree");
        }
        if (service.price() == null || service.price().signum() <= 0) {
            throw unavailable("ORDER service price is incomplete");
        }
        try { MONEY.format(service.price()); }
        catch (IllegalArgumentException malformed) { throw unavailable("ORDER service price is invalid"); }
        if ("IN_STORE".equals(command.fulfillmentType())
                && !Duration.between(command.appointmentStart().toInstant(),
                        command.appointmentEnd().toInstant()).equals(Duration.ofMinutes(service.durationMinutes()))) {
            throw new ApiException("SERVICE_NOT_BOOKABLE", "预约时长与服务不一致");
        }
        if (service.applicablePetTypes() == null || service.applicablePetTypes().isEmpty()
                || service.applicablePetTypes().stream().anyMatch(type ->
                        !List.of("ALL", "DOG", "CAT", "EXOTIC").contains(type))
                || (service.applicablePetTypes().contains("ALL")
                    && service.applicablePetTypes().size() != 1)) {
            throw unavailable("service pet type facts are invalid");
        }
        if (!List.of("DOG", "CAT", "OTHER").contains(pet.categoryCode())) {
            throw unavailable("pet type fact is unknown");
        }
        if (!service.applicablePetTypes().contains("ALL")) {
            // Approved booking-only mapping; the USER snapshot itself keeps OTHER.
            String bookableType = "OTHER".equals(pet.categoryCode()) ? "EXOTIC" : pet.categoryCode();
            if (!service.applicablePetTypes().contains(bookableType)) {
                throw new ApiException("SERVICE_NOT_BOOKABLE", "服务不适用于该宠物");
            }
        }
    }

    private void verifyHold(Prepared input, HoldResult held, long expectedOrderId) {
        CreateOrderCommand command = input.command();
        if (held == null || !Long.toString(expectedOrderId).equals(held.orderId())
                || held.reservationId() == null || held.holdExpireAt() == null
                || held.claims() == null || held.claims().isEmpty()
                || held.startAt() == null || held.endAt() == null) {
            throw unavailable("SCH hold result is incomplete");
        }
        positiveId(held.reservationId());
        if ("IN_STORE".equals(command.fulfillmentType())) {
            if (held.claims().size() != 1 || !held.startAt().isEqual(command.appointmentStart())
                    || !held.endAt().isEqual(command.appointmentEnd())) {
                throw unavailable("SCH GENERAL hold does not match ORDER intent");
            }
            var claim = held.claims().getFirst();
            if (!"GENERAL".equals(claim.kind())
                    || !claim.startAt().isEqual(command.appointmentStart())
                    || !claim.endAt().isEqual(command.appointmentEnd())
                    || (command.selectedGeneralWindowId() != null
                        && !command.selectedGeneralWindowId().equals(claim.windowId()))) {
                throw unavailable("SCH GENERAL claim does not match ORDER intent");
            }
        } else {
            if (held.claims().size() != 2) throw unavailable("SCH directional claims are incomplete");
            var pickup = held.claims().stream().filter(c -> "PICKUP".equals(c.kind())).toList();
            var returning = held.claims().stream().filter(c -> "RETURN".equals(c.kind())).toList();
            if (pickup.size() != 1 || returning.size() != 1
                    || !command.selectedPickupWindowId().equals(pickup.getFirst().windowId())
                    || !command.selectedReturnWindowId().equals(returning.getFirst().windowId())
                    || !command.pickupStart().isEqual(pickup.getFirst().startAt())
                    || !command.returnStart().isEqual(returning.getFirst().startAt())) {
                throw unavailable("SCH directional hold does not match ORDER intent");
            }
            OffsetDateTime lastEnd = pickup.getFirst().endAt().isAfter(returning.getFirst().endAt())
                    ? pickup.getFirst().endAt() : returning.getFirst().endAt();
            if (!held.startAt().isEqual(command.pickupStart()) || !held.endAt().isEqual(lastEnd)) {
                throw unavailable("SCH pickup envelope does not match ORDER intent");
            }
        }
        for (var claim : held.claims()) {
            if (claim == null || claim.startAt() == null || claim.endAt() == null
                    || !claim.endAt().isAfter(claim.startAt())) {
                throw unavailable("SCH claim is incomplete");
            }
            positiveId(claim.claimId());
            positiveId(claim.windowId());
        }
        if (!held.holdExpireAt().isAfter(OffsetDateTime.now(clock).minusSeconds(1))) {
            throw unavailable("SCH hold expiry is stale");
        }
    }

    private void writeOrder(Prepared input, BookingMerchantFacts merchant,
            BookingServiceFacts service, PetSnapshotDTO pet, HoldResult held,
            long orderId, long orderNo) {
        CreateOrderCommand command = input.command();
        Map<String, Object> order = values();
        order.put("id", orderId);
        order.put("orderNo", orderNo);
        order.put("userId", IDS.fromApi(input.userId()));
        order.put("merchantId", IDS.fromApi(merchant.merchantId()));
        order.put("storeId", IDS.fromApi(command.storeId()));
        order.put("serviceId", IDS.fromApi(command.serviceId()));
        order.put("petId", IDS.fromApi(command.petId()));
        order.put("reservationId", IDS.fromApi(held.reservationId()));
        order.put("fulfillmentType", command.fulfillmentType());
        order.put("price", service.price());
        order.put("appointmentStart", utc(held.startAt()));
        order.put("appointmentEnd", utc(held.endAt()));
        order.put("paymentExpireAt", utc(held.holdExpireAt()));
        store.order(order);

        Map<String, Object> serviceRow = values();
        serviceRow.put("id", nextId());
        serviceRow.put("orderId", orderId);
        serviceRow.put("serviceId", IDS.fromApi(service.serviceId()));
        serviceRow.put("serviceName", service.serviceName());
        serviceRow.put("categoryId", service.categoryId() == null ? null : IDS.fromApi(service.categoryId()));
        serviceRow.put("categoryName", service.categoryName());
        serviceRow.put("servicePrice", service.price());
        serviceRow.put("serviceDurationMinutes", service.durationMinutes());
        serviceRow.put("serviceDescription", service.description());
        serviceRow.put("merchantName", merchant.merchantName());
        serviceRow.put("storeName", merchant.storeName());
        serviceRow.put("storeAddress", merchant.storeAddress());
        serviceRow.put("snapshotJson", serviceFactsJson(service));
        store.serviceSnapshot(serviceRow);

        Map<String, Object> petRow = values();
        petRow.put("id", nextId());
        petRow.put("orderId", orderId);
        petRow.put("petId", IDS.fromApi(pet.petId()));
        petRow.put("petName", pet.name());
        petRow.put("petType", pet.categoryCode());
        petRow.put("breedName", pet.breedName());
        petRow.put("sex", pet.genderCode());
        petRow.put("weightKg", pet.weightKg());
        petRow.put("healthNote", pet.healthRemark());
        store.petSnapshot(petRow);

        Map<String, Object> inputs = values();
        inputs.put("orderId", orderId);
        inputs.put("addressCiphertext", input.addressCiphertext());
        inputs.put("remarkCiphertext", input.remarkCiphertext());
        store.inputSnapshot(inputs);

        Map<String, Object> status = values();
        status.put("id", nextId());
        status.put("orderId", orderId);
        status.put("userId", IDS.fromApi(input.userId()));
        status.put("requestId", command.context().requestId().length() <= 64
                ? command.context().requestId() : null);
        store.statusLog(status);

        Map<String, Object> audit = values();
        audit.put("id", nextId());
        audit.put("orderId", orderId);
        audit.put("userId", IDS.fromApi(input.userId()));
        audit.put("requestKey", input.key());
        audit.put("requestId", command.context().requestId().getBytes(StandardCharsets.UTF_8));
        audit.put("traceId", command.context().traceId());
        store.creationAudit(audit);
    }

    private static Timestamp utc(OffsetDateTime time) {
        return Timestamp.from(time.toInstant());
    }

    private static Map<String, Object> values() { return new HashMap<>(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String serviceFactsJson(BookingServiceFacts service) {
        Map<String, Object> extra = new TreeMap<>();
        extra.put("applicablePetTypes", service.applicablePetTypes().stream().sorted().toList());
        extra.put("fulfillmentType", service.fulfillmentType());
        extra.put("verificationRequired", service.verificationRequired());
        extra.put("serviceVersion", service.version());
        try { return JSON.writeValueAsString(extra); }
        catch (JsonProcessingException broken) { throw unavailable("ORDER service snapshot cannot be serialized"); }
    }

    private static String serializeReceipt(Receipt receipt) {
        try { return JSON.writeValueAsString(receipt); }
        catch (JsonProcessingException broken) { throw unavailable("ORDER receipt could not be serialized"); }
    }

    private static Receipt parseReceipt(String json) {
        if (json == null) throw unavailable("ORDER success receipt is missing");
        try {
            Receipt receipt = JSON.readValue(json, Receipt.class);
            positiveId(receipt.orderId());
            positiveId(receipt.orderNo());
            MONEY.parse(receipt.payAmount());
            OffsetDateTime.parse(receipt.paymentExpireAt());
            return receipt;
        } catch (Exception broken) {
            throw unavailable("ORDER success receipt is corrupt");
        }
    }

    private static ApiException databaseFailure(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql
                    && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213)) {
                return new ApiException(CommonApiCodes.CONFLICT, "订单创建繁忙，请使用原 requestId 稍后重试");
            }
            current = current.getCause();
        }
        return unavailable("ORDER creation dependency unavailable");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, detail);
    }

    private static ApiException unavailable(String detail) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, detail);
    }
}
