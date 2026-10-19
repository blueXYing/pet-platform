package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.NoteView;
import com.petplatform.order.api.query.MerchantCustomerNoteCommandApi;
import com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderCustomerNoteMapper;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Merchant-internal customer note write service (contract 69 expansion, 2026-10-19 CCR
 * ruling): exactly one note per (store, derived customer), 0..200 code points, empty string
 * clears. Same discipline family as the 45号 decide command — supplement-23 binding reserved
 * in an independent short transaction (no guard held while waiting), then one REQUIRES_NEW
 * transaction that takes the store guard, re-proves the real OWNER (write family: FROZEN is
 * denied), upserts the note row under its optimistic version, and appends the same-row audit
 * (field NAMES only). Replay returns the stored note without re-writing.
 */
public final class MerchantCustomerNoteService implements MerchantCustomerNoteCommandApi {

    static final int MAX_NOTE_CODE_POINTS = 200;
    private static final byte[] NAMESPACE = "merchant.customer.note".getBytes(StandardCharsets.UTF_8);

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final MerchantOrderAuthorityApi authority;
    private final SnowflakeIdGenerator ids;
    private final OrderCustomerNoteMapper mapper;
    private final TransactionTemplate tx;

    public MerchantCustomerNoteService(DataSource source, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, SnowflakeIdGenerator ids) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.authority = Objects.requireNonNull(authority, "authority is required");
        this.ids = Objects.requireNonNull(ids, "ids are required");
        this.mapper = new com.petplatform.order.biz.infrastructure.persistence.OrderCustomerNoteStore(
                source).mapper();
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    @Override
    public NoteView updateNote(UpdateNoteCommand command) {
        Intent intent = intent(command);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("customer note command requires no outer transaction");
        // Reserve the supplement-23 binding without holding the store guard (45号 decide shape).
        OrderCustomerNoteMapper.Binding admitted = tx.execute(s -> {
            sessionDefaults();
            var input = new HashMap<String, Object>();
            input.put("id", next());
            input.put("namespace", NAMESPACE);
            input.put("actor", intent.actor);
            input.put("scope", scope(intent.storeId));
            input.put("requestId", intent.requestId.getBytes(StandardCharsets.UTF_8));
            input.put("hash", sha(intent.canonical));
            input.put("canonical", intent.canonical);
            mapper.reserve(input);
            return sameParams(mapper.binding(key(intent.storeId, intent)), intent.canonical);
        });
        try {
            return execute(intent, admitted);
        } catch (ApiException known) {
            throw known;
        } catch (DeadlockLoserDataAccessException busy) {
            throw new ApiException(CommonApiCodes.CONFLICT, "操作繁忙，请重试");
        } catch (RuntimeException failure) {
            throw unavailable("customer note commit result is unknown");
        }
    }

    private NoteView execute(Intent intent, OrderCustomerNoteMapper.Binding admitted) {
        return tx.execute(s -> {
            sessionDefaults();
            OrderCustomerNoteMapper.Binding binding = sameParams(
                    mapper.binding(key(intent.storeId, intent)), intent.canonical());
            // Guard + real-OWNER write proof (write family: FROZEN denied) runs before both the
            // replay and the write, so a replay can never outlive its authority.
            guard.acquire(List.of(Long.toString(intent.storeId)), new QueryContext(intent.trace,
                    OperatorType.SYSTEM, null));
            authority.requireOwner(Long.toString(intent.merchantId), Long.toString(intent.storeId),
                    new QueryContext(intent.trace, OperatorType.USER, IDS.toApi(intent.actor)));
            if ("SUCCEEDED".equals(binding.state)) return readReceipt(binding.resultBytes);
            if (!"RESERVED".equals(binding.state)) throw unavailable("command binding is damaged");
            OrderCustomerNoteMapper.NoteRow row = mapper.lockNote(intent.storeId, intent.userId);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            String note = intent.note;
            String changed;
            long toVersion;
            if (row == null) {
                if (intent.expectedVersion != 0) conflict("customer note version changed");
                if (mapper.customerHasStoreOrder(intent.merchantId, intent.storeId,
                        intent.userId) != 1) notFound();
                if (mapper.insertNote(intent.storeId, intent.merchantId, intent.userId, note,
                        now) != 1) throw unavailable("customer note insert failed");
                changed = "created";
                toVersion = 1;
            } else {
                if (!Objects.equals(row.merchantId, intent.merchantId))
                    throw unavailable("customer note merchant ownership is damaged");
                if (row.version == null || row.version != intent.expectedVersion)
                    conflict("customer note version changed");
                if (row.version == Long.MAX_VALUE) conflict("customer note version exhausted");
                if (mapper.updateNote(intent.storeId, intent.merchantId, intent.userId, note,
                        intent.expectedVersion, now) != 1)
                    conflict("customer note write lost a concurrent version race");
                changed = "note";
                toVersion = intent.expectedVersion + 1;
            }
            NoteView view = new NoteView(note, Long.toString(toVersion),
                    now.atOffset(ZoneOffset.UTC));
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("id", next());
            audit.put("actor", intent.actor);
            audit.put("merchantId", intent.merchantId);
            audit.put("storeId", intent.storeId);
            audit.put("userId", intent.userId);
            audit.put("commandId", binding.id);
            audit.put("requestId", intent.requestId.getBytes(StandardCharsets.UTF_8));
            audit.put("traceId", intent.trace);
            audit.put("fromVersion", row == null ? null : row.version);
            audit.put("toVersion", toVersion);
            audit.put("changedFields", changed);
            audit.put("now", now);
            if (mapper.insertAudit(audit) != 1)
                throw unavailable("customer note audit insert failed");
            if (mapper.succeed(binding.id, receiptJson(view)) != 1)
                throw unavailable("customer note idempotency result update failed");
            return view;
        });
    }

    private static NoteView readReceipt(String value) {
        try {
            NoteView receipt = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .readValue(value, NoteView.class);
            if (receipt == null || receipt.version() == null
                    || !receipt.version().matches("(0|[1-9][0-9]{0,18})"))
                throw unavailable("customer note receipt is damaged");
            return receipt;
        } catch (ApiException known) {
            throw known;
        } catch (Exception failure) {
            throw unavailable("customer note receipt is damaged");
        }
    }

    private Intent intent(UpdateNoteCommand command) {
        if (command == null) invalid();
        long merchant = coordinate(command.merchantId());
        long store = coordinate(command.storeId());
        long userId = coordinate(command.userId());
        CommandContext context = command.context();
        if (context == null || context.operatorType() == null || context.operatorId() == null)
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context required");
        if (context.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "only the merchant owner may edit customer notes");
        long actor;
        try {
            actor = IDS.fromApi(context.operatorId());
        } catch (RuntimeException bad) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "invalid authenticated user");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException bad) {
            invalid();
        }
        String note = command.note();
        if (note == null || note.codePointCount(0, note.length()) > MAX_NOTE_CODE_POINTS
                || hasUnpairedSurrogate(note)) invalid();
        String storedNote = note.isEmpty() ? null : note;
        if (command.expectedVersion() == null
                || !command.expectedVersion().matches("(0|[1-9][0-9]{0,18})")) invalid();
        long expectedVersion = Long.parseLong(command.expectedVersion());
        String trace = context.traceId();
        if (trace != null && (trace.isBlank() || trace.getBytes(StandardCharsets.UTF_8).length > 128
                || trace.codePoints().anyMatch(Character::isISOControl))) invalid();
        // canonical-v1 payload: identifier-only fields plus the note text (merchant memo, not a
        // credential; stored plaintext in the binding like the merchant module's canonical bytes).
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("command", "merchant.customer.note.update");
        fields.put("merchantId", command.merchantId());
        fields.put("storeId", command.storeId());
        fields.put("userId", command.userId());
        fields.put("note", storedNote);
        fields.put("expectedVersion", command.expectedVersion());
        final byte[] canonical;
        try {
            canonical = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(new java.util.TreeMap<>(fields));
        } catch (Exception failure) {
            throw unavailable("customer note parameters are not canonicalizable");
        }
        return new Intent(merchant, store, userId, actor, storedNote, expectedVersion,
                context.requestId(), trace, canonical);
    }

    private Map<String, Object> key(long store, Intent intent) {
        Map<String, Object> key = new HashMap<>();
        key.put("namespace", NAMESPACE);
        key.put("actor", intent.actor);
        key.put("scope", scope(store));
        key.put("requestId", intent.requestId.getBytes(StandardCharsets.UTF_8));
        return key;
    }

    private static byte[] scope(long store) {
        return ("STORE:" + store).getBytes(StandardCharsets.UTF_8);
    }

    private OrderCustomerNoteMapper.Binding sameParams(OrderCustomerNoteMapper.Binding binding,
            byte[] canonical) {
        // 同 45号 decide：hash 为 SHA-256 的 hex 字符串；canonical 字节逐位比较。
        if (binding == null || !"canonical-v1".equals(binding.canonicalVersion)
                || binding.canonicalBytes == null || binding.payloadSha256 == null
                || !MessageDigest.isEqual(sha(canonical).getBytes(StandardCharsets.US_ASCII),
                        binding.payloadSha256.getBytes(StandardCharsets.US_ASCII))
                || !MessageDigest.isEqual(canonical, binding.canonicalBytes))
            throw unavailable("customer note command binding is damaged");
        return binding;
    }

    private void sessionDefaults() {
        // Same session contract as the other order write stores (UTC clock, short lock waits).
        mapper.setUtcTimeZone();
        mapper.setLockWaitTimeout();
    }

    private long next() {
        return ids.nextId();
    }

    private static String receiptJson(NoteView view) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsString(view);
        } catch (Exception failure) {
            throw unavailable("customer note receipt serialization failed");
        }
    }

    private static String sha(byte[] payload) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return true;
            } else if (Character.isLowSurrogate(ch)) return true;
        }
        return false;
    }

    private record Intent(long merchantId, long storeId, long userId, long actor, String note,
            long expectedVersion, String requestId, String trace, byte[] canonical) {}

    private static long coordinate(String value) {
        try {
            if (value == null) invalid();
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "客户备注参数不合法");
    }

    private static void conflict(String message) {
        throw new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "客户不存在");
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
