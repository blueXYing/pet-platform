package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.coupon.api.command.AdminCouponGrantApi;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.GrantCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.GrantReceipt;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Check;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Phase;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Port;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Resource;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Coupon grant kernel (contract 65 §4.3, user ruling 2026-10-16): the operator issues one
 * AVAILABLE coupon_instance from an ACTIVE template to one user. Deliberate minimum — one
 * user, one template, one instance per terminal requestId; batch/hold-cap/frequency/stock
 * extensions are registered unimplemented (CCR-CPN-GRANT-001), SSOT §9 stays refund-restore
 * only. Pipeline per write: authorize (action {@code coupon.instance.grant} re-proved against
 * the injected module-local port, scope ALL enforced there) → admit the command_idempotency
 * binding (same-parameter replay returns the first receipt, other payloads 409) → one
 * READ_COMMITTED execution transaction that row-locks the template, re-checks the frozen
 * 61 §3 precondition ④ usability fact (status ACTIVE and now within the validity window),
 * inserts the AVAILABLE instance (expire_at = template.valid_end_at), appends the ISSUE
 * ledger row (request_id 'CPN:GRANT:{instanceId}', remark carries the operator audit) and
 * draws down issued_count. Commit-unknown recovers with one replay; a settled binding that
 * cannot be marked fails closed 503. SQL errors never escape with bound values.
 */
public final class CouponGrantService {

    private final CouponAdminStore store;
    private final Port authorization;
    private final java.time.Clock clock;

    public CouponGrantService(CouponAdminStore store, Port authorization, java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public GrantReceipt grant(GrantCouponCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = commandContext(command.context());
        long templateId = id(command.templateId());
        long userId = id(command.userId());
        authorize(templateId, context, command.authorization());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "grant");
        params.put("templateId", templateId);
        params.put("userId", userId);
        var canonical = AdminCouponCanonicalParams.of(params);
        String key = "rk-v1:coupon.instance.grant:" + context.operatorType().name() + ":"
                + context.operatorId() + ":" + templateId + ":" + context.requestId();
        CouponAdminStore.Binding binding = admitWithRecovery(key, canonical);
        if (binding.paramsSha256() == null
                || !binding.paramsSha256().equals(canonical.sha256())
                || !java.util.Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "coupon grant idempotency conflict");
        }
        if ("SUCCEEDED".equals(binding.status())) {
            return parseReceipt(binding.receiptJson());
        }
        return runWithRecovery(key, templateId, userId, context);
    }

    /** One execution transaction: lock template → guard → instance + ledger + drawdown + receipt. */
    private GrantReceipt executeGrant(
            String key, long templateId, long userId, CommandContext context) {
        LocalDateTime now = nowUtc();
        GrantReceipt receipt = store.execute(m -> {
            AdminTemplateEntity template = m.selectTemplateForUpdate(templateId);
            if (template == null) throw notFound();
            if (!"ACTIVE".equals(template.getStatus())) throw conflict("模板不在启用状态，不能发放");
            if (now.isBefore(template.getValidStartAt())
                    || now.isAfter(template.getValidEndAt())) {
                throw conflict("模板不在有效期内，不能发放");
            }
            long instanceId = store.nextId();
            if (m.insertInstance(instanceId, templateId, userId,
                    template.getValidEndAt(), template.getValidEndAt(), now) != 1) {
                throw internal("coupon grant instance insert failed");
            }
            if (m.insertIssueLedger(store.nextId(), instanceId, userId,
                    "CPN:GRANT:" + instanceId, auditRemark(context), now) != 1) {
                throw internal("coupon grant ledger insert failed");
            }
            if (m.incrementIssuedCount(templateId, now) != 1) {
                throw internal("coupon grant issued-count drawdown failed");
            }
            String json = "{\"instanceId\":\"" + Long.toUnsignedString(instanceId)
                    + "\",\"templateId\":\"" + Long.toUnsignedString(templateId)
                    + "\",\"userId\":\"" + Long.toUnsignedString(userId)
                    + "\",\"status\":\"AVAILABLE\",\"expireAt\":\"" + utc(template.getValidEndAt())
                    + "\",\"occurredAt\":\"" + now() + "\"}";
            if (m.markBindingSucceeded(key, json) != 1) {
                throw unavailable("coupon grant idempotency marking failed");
            }
            return new GrantReceipt(Long.toUnsignedString(instanceId),
                    Long.toUnsignedString(templateId), Long.toUnsignedString(userId),
                    "AVAILABLE", utc(template.getValidEndAt()), now());
        });
        return receipt;
    }

    /** Audit fact on the ISSUE row: who granted, from which console channel. */
    private static String auditRemark(CommandContext context) {
        return "operator=" + context.operatorId() + ";source=" + String.valueOf(context.source());
    }

    private static GrantReceipt parseReceipt(String json) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return new GrantReceipt(root.path("instanceId").asText(), root.path("templateId").asText(),
                    root.path("userId").asText(), root.path("status").asText(),
                    OffsetDateTime.parse(root.path("expireAt").asText()),
                    OffsetDateTime.parse(root.path("occurredAt").asText()));
        } catch (Exception failure) {
            throw unavailable("stored coupon grant receipt is not readable");
        }
    }

    private GrantReceipt runWithRecovery(
            String key, long templateId, long userId, CommandContext context) {
        try {
            return executeGrant(key, templateId, userId, context);
        } catch (CouponAdminStore.CommitUnknown first) {
            try {
                return executeGrant(key, templateId, userId, context);
            } catch (CouponAdminStore.CommitUnknown second) {
                throw unavailable("coupon grant commit result remains unknown");
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
                throw unavailable("coupon grant admission result remains unknown");
            }
        }
    }

    private void authorize(long templateId, CommandContext context,
            AdminCouponGrantApi.AdminAuthorization auth) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
        Decision decision = authorization.check(new Check(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(),
                "coupon.instance.grant", new Resource("COUPON_TEMPLATE",
                        Long.toUnsignedString(templateId), null, null, null),
                "COUPON_INSTANCE_GRANT", Phase.EXECUTE));
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    private CommandContext commandContext(CommandContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null
                || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
        }
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "coupon grant belongs to operators");
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

    private static OffsetDateTime utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC);
    }
}
