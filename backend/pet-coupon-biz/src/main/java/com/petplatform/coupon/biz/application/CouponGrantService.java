package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.coupon.api.command.AdminCouponGrantApi;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.BatchGrantCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.BatchGrantItem;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.BatchGrantReceipt;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.GrantCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.GrantReceipt;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Check;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Phase;
import com.petplatform.coupon.biz.application.AdminCouponUserFactsPort.CouponPort;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Coupon grant kernel (contract 65 §4.3, user rulings 2026-10-16 minimum + 2026-10-19
 * hardening batch). Deliberate industry-minimum reading of each ruled extension, disclosed
 * in CCR-CPN-GRANT-001. Pipeline per user: authorize (action {@code coupon.instance.grant}
 * re-proved against the injected module-local port, scope ALL enforced there) → prove the
 * recipient exists through the boot-wired user-owner read port (missing user 404 — no
 * ownerless coupons; any account status counts as existing) → admit the command_idempotency
 * binding (same-parameter replay returns the first receipt, other payloads 409) → one
 * READ_COMMITTED execution transaction that row-locks the template, re-checks the frozen
 * 61 §3 precondition ④ usability fact (status ACTIVE and now inside the validity window),
 * then the hardening guards — per-user-template hold cap (AVAILABLE+FROZEN count vs the
 * configured maximum), the 60-second operator sliding-window frequency control (plain DB
 * counting on the ISSUE ledger, no Redis) and the total_stock drawdown limit (NULL stays
 * unlimited) — inserts the AVAILABLE instance (expire_at = template.valid_end_at), appends
 * the ISSUE ledger row (request_id 'CPN:GRANT:{instanceId}', remark carries the operator
 * audit) and draws down issued_count with a stock-conditioned statement. Guards run after
 * the template row lock, so a concurrent grant for the same template serializes either
 * before (visible, counted) or after (its own guard pass) — no double-pass races. Commit-
 * unknown recovers with one replay; a settled binding that cannot be marked fails closed
 * 503. Batch commands keep every user on this pipeline with its own binding key
 * ({@code :u:{userId}} suffix) and its own execution transaction: business rejections
 * (404/409) become per-user REJECTED receipt lines and never roll back granted users,
 * while authorization or infrastructure failures abort the whole command unchanged.
 * SQL errors never escape with bound values.
 */
public final class CouponGrantService {

    /** Ruled grant frequency control: one grant per (operator, user, template) per window. */
    private static final int FREQUENCY_WINDOW_SECONDS = 60;

    /** Deployment settings; the no-arg factory values are the CCR-registered defaults. */
    public record Settings(int maxPerTemplate, int maxBatchUsers) {
        public Settings {
            if (maxPerTemplate < 1) {
                throw new IllegalArgumentException("maxPerTemplate must be >= 1");
            }
            if (maxBatchUsers < 1 || maxBatchUsers > 100) {
                throw new IllegalArgumentException("maxBatchUsers must be within 1..100");
            }
        }

        public static Settings defaults() {
            return new Settings(10, 100);
        }
    }

    private final CouponAdminStore store;
    private final AdminCouponAuthorizationPort.CouponPort authorization;
    private final CouponPort userFacts;
    private final Settings settings;
    private final java.time.Clock clock;

    public CouponGrantService(CouponAdminStore store,
            AdminCouponAuthorizationPort.CouponPort authorization, CouponPort userFacts,
            Settings settings, java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
        this.userFacts = Objects.requireNonNull(userFacts, "userFacts is required");
        this.settings = Objects.requireNonNull(settings, "settings are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public GrantReceipt grant(GrantCouponCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = commandContext(command.context());
        long templateId = id(command.templateId());
        long userId = id(command.userId());
        authorize(templateId, context, command.authorization());
        requireUserExists(userId, context);
        return grantOnce(singleKey(context, templateId), templateId, userId, context);
    }

    public BatchGrantReceipt grantBatch(BatchGrantCouponCommand command) {
        Objects.requireNonNull(command, "command is required");
        CommandContext context = commandContext(command.context());
        long templateId = id(command.templateId());
        List<Long> userIds = batchUserIds(command.userIds());
        authorize(templateId, context, command.authorization());
        List<BatchGrantItem> results = new ArrayList<>(userIds.size());
        int granted = 0;
        int rejected = 0;
        for (long userId : userIds) {
            try {
                requireUserExists(userId, context);
                GrantReceipt receipt = grantOnce(batchKey(context, templateId, userId),
                        templateId, userId, context);
                results.add(new BatchGrantItem(Long.toUnsignedString(userId), "GRANTED",
                        receipt.instanceId(), receipt.expireAt(), receipt.occurredAt(), null, null));
                granted++;
            } catch (ApiException business) {
                // Per-user independent semantics: a business rejection (missing user, any
                // guard conflict) records the line and keeps the batch running; authorization
                // or infrastructure failures abort the whole command unchanged.
                if (!isBusinessRejection(business)) throw business;
                results.add(new BatchGrantItem(Long.toUnsignedString(userId), "REJECTED",
                        null, null, null, business.code(), business.getMessage()));
                rejected++;
            }
        }
        return new BatchGrantReceipt(Long.toUnsignedString(templateId), List.copyOf(results),
                granted, rejected, now());
    }

    /** One fully independent per-user grant: admit binding → execution transaction → receipt. */
    private GrantReceipt grantOnce(String key, long templateId, long userId, CommandContext context) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "grant");
        params.put("templateId", templateId);
        params.put("userId", userId);
        var canonical = AdminCouponCanonicalParams.of(params);
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

    /** One execution transaction: lock template → guards → instance + ledger + drawdown + receipt. */
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
            if (m.countHeldInstances(templateId, userId) >= settings.maxPerTemplate()) {
                throw conflict("该用户持有此模板的可用券已达上限，不能发放");
            }
            if (m.countRecentOperatorGrants(templateId, userId, context.operatorId(),
                    now.minusSeconds(FREQUENCY_WINDOW_SECONDS)) > 0) {
                throw conflict("同一用户同一模板发放过于频繁，请稍后再试");
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
            // Stock-conditioned drawdown: total_stock NULL stays unlimited; an exhausted
            // stock answers 409 here — the row lock already serialized concurrent issuers,
            // so the predicate is defense in depth rather than the primary guard.
            if (m.incrementIssuedCount(templateId, now) != 1) {
                throw conflict("模板库存已用尽，不能发放");
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

    /** Recipient existence: the port answers, the kernel maps a missing row to 404. */
    private void requireUserExists(long userId, CommandContext context) {
        try {
            if (!userFacts.exists(userId, context.operatorId())) throw notFound("用户不存在，不能发放");
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable("目标用户存在性校验不可用");
        }
    }

    private static boolean isBusinessRejection(ApiException failure) {
        return CommonApiCodes.NOT_FOUND.equals(failure.code())
                || CommonApiCodes.CONFLICT.equals(failure.code())
                || CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT.equals(failure.code());
    }

    /** Audit fact on the ISSUE row: who granted, from which console channel. */
    private static String auditRemark(CommandContext context) {
        return "operator=" + context.operatorId() + ";source=" + String.valueOf(context.source());
    }

    /** Original single-grant binding: template-scoped, unchanged from the 2026-10-16 minimum. */
    private static String singleKey(CommandContext context, long templateId) {
        return "rk-v1:coupon.instance.grant:" + context.operatorType().name() + ":"
                + context.operatorId() + ":" + templateId + ":" + context.requestId();
    }

    /** Batch per-user binding: the terminal requestId stays per-user independently replayable. */
    private static String batchKey(CommandContext context, long templateId, long userId) {
        return "rk-v1:coupon.instance.grant:" + context.operatorType().name() + ":"
                + context.operatorId() + ":" + templateId + ":u:" + userId + ":"
                + context.requestId();
    }

    private List<Long> batchUserIds(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) invalid("userIds is required for batch grant");
        if (userIds.size() > settings.maxBatchUsers()) {
            invalid("userIds exceeds the batch limit of " + settings.maxBatchUsers());
        }
        List<Long> parsed = new ArrayList<>(userIds.size());
        Set<Long> seen = new HashSet<>(userIds.size() * 2);
        for (String userId : userIds) {
            long parsedId = id(userId);
            if (!seen.add(parsedId)) invalid("userIds must be distinct");
            parsed.add(parsedId);
        }
        return List.copyOf(parsed);
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
                // Pre-load check: the template row is not locked yet, so no version fact exists;
                // the owner's scope DTO requires a non-blank fact, rendered "0" like the
                // contract-63 kernels' absent-version columns (validated, never read).
                "coupon.instance.grant", new AdminCouponAuthorizationPort.Resource("COUPON_TEMPLATE",
                        Long.toUnsignedString(templateId), null, null, "0"),
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

    private static ApiException notFound(String message) {
        return new ApiException(CommonApiCodes.NOT_FOUND, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static ApiException unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    private static ApiException internal(String message) {
        throw new ApiException(CommonApiCodes.INTERNAL_ERROR, message);
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
