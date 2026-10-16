package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.coupon.api.command.AdminCouponVoidApi;
import com.petplatform.coupon.api.command.AdminCouponVoidApi.VoidCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponVoidApi.VoidReceipt;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Check;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Phase;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Port;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Resource;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminInstanceEntity;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Coupon void kernel (contract 65 §4.4, user ruling 2026-10-16 doc 36, minimal authorization):
 * the operator voids one AVAILABLE coupon_instance. Pipeline per write: authorize (action
 * {@code coupon.instance.void} re-proved against the injected module-local port, scope ALL
 * enforced there) → admit the command_idempotency binding (same-parameter replay returns the
 * first receipt, other payloads 409) → one READ_COMMITTED execution transaction that row-locks
 * the instance, re-checks the only voidable status fact ({@code AVAILABLE}; USED /
 * FROZEN / RISK_FROZEN / EXPIRED answer 409 — a FROZEN coupon is locked by an in-flight order
 * and must never be voided away), CASes {@code status='VOID'} and appends the VOID ledger row
 * (request_id 'CPN:VOID:{instanceId}', remark carries the operator audit and the required
 * reason). No compensation path exists (CCR-CPN-VOID-001 minimum). Commit-unknown recovers
 * with one replay; a settled binding that cannot be marked fails closed 503. SQL errors never
 * escape with bound values.
 */
public final class CouponVoidService {

    private final CouponAdminStore store;
    private final Port authorization;
    private final java.time.Clock clock;

    public CouponVoidService(CouponAdminStore store, Port authorization, java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public VoidReceipt voidInstance(VoidCouponCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = commandContext(command);
        long instanceId = id(command.instanceId());
        String reason = reason(command.reason());
        authorize(instanceId, context, command.authorization());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "void");
        params.put("instanceId", instanceId);
        params.put("reason", reason);
        var canonical = AdminCouponCanonicalParams.of(params);
        String key = "rk-v1:coupon.instance.void:" + context.operatorType().name() + ":"
                + context.operatorId() + ":" + instanceId + ":" + context.requestId();
        CouponAdminStore.Binding binding = admitWithRecovery(key, canonical);
        if (binding.paramsSha256() == null
                || !binding.paramsSha256().equals(canonical.sha256())
                || !java.util.Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "coupon void idempotency conflict");
        }
        if ("SUCCEEDED".equals(binding.status())) {
            return parseReceipt(binding.receiptJson());
        }
        return runWithRecovery(key, instanceId, reason, context);
    }

    /** One execution transaction: lock instance → guard → CAS VOID + ledger + receipt. */
    private VoidReceipt executeVoid(String key, long instanceId, String reason,
            CommandContext context) {
        LocalDateTime now = nowUtc();
        VoidReceipt receipt = store.execute(m -> {
            AdminInstanceEntity instance = m.selectInstanceForUpdate(instanceId);
            if (instance == null) throw notFound();
            if (!"AVAILABLE".equals(instance.getStatus())) {
                throw conflict("券不在可作废状态（仅未使用且未被订单冻结的券可作废）");
            }
            if (m.voidInstance(instanceId, now) != 1) {
                throw conflict("券不在可作废状态（仅未使用且未被订单冻结的券可作废）");
            }
            if (m.insertVoidLedger(store.nextId(), instanceId, instance.getUserId(),
                    "CPN:VOID:" + instanceId, auditRemark(context, reason), now) != 1) {
                throw internal("coupon void ledger insert failed");
            }
            String json = "{\"instanceId\":\"" + Long.toUnsignedString(instanceId)
                    + "\",\"templateId\":\"" + Long.toUnsignedString(instance.getTemplateId())
                    + "\",\"userId\":\"" + Long.toUnsignedString(instance.getUserId())
                    + "\",\"status\":\"VOID\",\"occurredAt\":\"" + now() + "\"}";
            if (m.markBindingSucceeded(key, json) != 1) {
                throw unavailable("coupon void idempotency marking failed");
            }
            return new VoidReceipt(Long.toUnsignedString(instanceId),
                    Long.toUnsignedString(instance.getTemplateId()),
                    Long.toUnsignedString(instance.getUserId()), "VOID", now());
        });
        return receipt;
    }

    /** Audit fact on the VOID row: who voided, from which console channel, and why. */
    private static String auditRemark(CommandContext context, String reason) {
        return "operator=" + context.operatorId() + ";source=" + String.valueOf(context.source())
                + ";reason=" + reason.replace(';', '，').replace('\n', ' ').replace('\r', ' ');
    }

    /** Reason is mandatory audit content: 10..200 code points, no lone surrogates. */
    private static String reason(String value) {
        if (value == null || value.isBlank()) invalid("reason is required");
        int count = value.codePointCount(0, value.length());
        if (count < 10 || count > 200
                || value.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
            invalid("reason must be 10..200 code points");
        }
        return value;
    }

    private static VoidReceipt parseReceipt(String json) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return new VoidReceipt(root.path("instanceId").asText(), root.path("templateId").asText(),
                    root.path("userId").asText(), root.path("status").asText(),
                    OffsetDateTime.parse(root.path("occurredAt").asText()));
        } catch (Exception failure) {
            throw unavailable("stored coupon void receipt is not readable");
        }
    }

    private VoidReceipt runWithRecovery(
            String key, long instanceId, String reason, CommandContext context) {
        try {
            return executeVoid(key, instanceId, reason, context);
        } catch (CouponAdminStore.CommitUnknown first) {
            try {
                return executeVoid(key, instanceId, reason, context);
            } catch (CouponAdminStore.CommitUnknown second) {
                throw unavailable("coupon void commit result remains unknown");
            }
        }
    }

    private CouponAdminStore.Binding admitWithRecovery(
            String key, AdminCouponCanonicalParams.Canonical canonical) {
        try {
            return store.admit(key, canonical);
        } catch (CouponAdminStore.CommitUnknown first) {
            try {
                return store.admit(key, canonical);
            } catch (CouponAdminStore.CommitUnknown second) {
                throw unavailable("coupon void admission result remains unknown");
            }
        }
    }

    private void authorize(long instanceId, CommandContext context,
            AdminCouponVoidApi.AdminAuthorization auth) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
        Decision decision = authorization.check(new Check(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(),
                "coupon.instance.void", new Resource("COUPON_INSTANCE",
                        Long.toUnsignedString(instanceId), null, null, null),
                "COUPON_INSTANCE_VOID", Phase.EXECUTE));
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    private CommandContext commandContext(VoidCouponCommand command) {
        CommandContext context = command.context();
        if (context == null || context.operatorType() == null || context.operatorId() == null
                || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
        }
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "coupon void belongs to operators");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException bad) {
            invalid("requestId is invalid");
        }
        return context;
    }

    private static long id(String value) {
        Objects.requireNonNull(value, "id is required");
        if (!value.matches("[1-9][0-9]{0,18}")) invalid("id must be a decimal Snowflake String");
        return Long.parseLong(value);
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException notFound() {
        return new ApiException(CommonApiCodes.NOT_FOUND, "资源不存在");
    }

    private static ApiException conflict(String message) {
        return new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    private static ApiException internal(String message) {
        return new ApiException(CommonApiCodes.INTERNAL_ERROR, message);
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
