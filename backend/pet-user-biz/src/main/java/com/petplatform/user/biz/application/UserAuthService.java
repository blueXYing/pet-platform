package com.petplatform.user.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import com.petplatform.user.biz.infrastructure.provider.MiniAuthVolatileStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C-end (mini-program) authentication slice per the accepted AUTH-001 mapping and CCR-ACR-001:
 * anonymous WECHAT_LOGIN attempts, one-time code exchange through {@link WechatSessionProvider},
 * account bind-or-create over user_auth_identity (WECHAT_MINI) with a NULL phone for first
 * registrations, the VERIFY_PHONE intermediate step, and volatile Redis sessions. Attempt,
 * command-tombstone, 60-second grant-receipt and session facts are volatile by design (the mini
 * auth DDL is not authorized); losing the store only forces a re-login. Credential failures are
 * uniformly 401 "验证失败" so nothing about which factor failed leaks; provider trouble is 503.
 */
public final class UserAuthService {

    public static final String PURPOSE_WECHAT_LOGIN = "WECHAT_LOGIN";
    private static final Pattern PHONE = Pattern.compile("1[0-9]{10}");
    private static final Pattern NUMERIC_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final ObjectMapper CODEC = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Approved knobs only; defaults follow the accepted D1/B1 recommendations. */
    public record MiniAuthPolicy(
            int attemptTtlSeconds, int accessTtlSeconds, int grantWindowSeconds,
            int attemptCreatePerMinute, int attemptFailureLimit) {
        public MiniAuthPolicy {
            if (attemptTtlSeconds < 60 || accessTtlSeconds < 60 || grantWindowSeconds < 1
                    || attemptCreatePerMinute < 1 || attemptFailureLimit < 1) {
                throw new IllegalArgumentException("Policy values must stay positive and sane");
            }
        }
    }

    /** Resolved session principal for the adapter; carries the account status for routing. */
    public record MiniSessionView(
            String sessionId, String userId, Instant expiresAt,
            String phoneMasked, String userStatus) {}

    private final UserAuthStore store;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final WechatSessionProvider wechat;
    private final MiniAuthVolatileStore volatileStore;
    private final MiniAuthPolicy policy;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate execution;

    public UserAuthService(DataSource dataSource, SnowflakeIdGenerator ids, Clock clock,
                           WechatSessionProvider wechat, MiniAuthVolatileStore volatileStore,
                           MiniAuthPolicy policy) {
        this.store = new UserAuthStore(dataSource);
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
        this.clock = Objects.requireNonNull(clock);
        this.wechat = Objects.requireNonNull(wechat, "An authorized WechatSessionProvider is required");
        this.volatileStore = Objects.requireNonNull(volatileStore);
        this.policy = Objects.requireNonNull(policy);
        this.jdbc = new JdbcTemplate(dataSource);
        this.execution = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        execution.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        execution.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        execution.setTimeout(10);
        volatileStore.verifyVolatileConfiguration();
    }

    // ------------------------------------------------------------------ attempts

    /** POST /api/v1/c/auth/attempts: anonymous, rate limited, no user/merchant facts. */
    public Map<String, Object> createAttempt(String requestId, String purpose, String remoteIp) {
        requireUuid(requestId);
        if (!PURPOSE_WECHAT_LOGIN.equals(purpose)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "本阶段仅支持微信登录");
        }
        Instant now = clock.instant();
        long window = now.getEpochSecond() / 60;
        long count = volatileStore.incrementWindow(
                "rate:attempt-create:" + safe(remoteIp) + ":" + window, Duration.ofSeconds(60));
        if (count > policy.attemptCreatePerMinute()) {
            throw new ApiException(CommonApiCodes.RATE_LIMITED, "请求过于频繁，请稍后重试");
        }
        long attemptId = freshId();
        String token = randomToken();
        if (!volatileStore.putIfAbsent("attempt-create:" + requestId,
                Long.toUnsignedString(attemptId), Duration.ofSeconds(policy.attemptTtlSeconds()))) {
            // First secret is shown exactly once; the original key can only restart explicitly.
            throw new ApiException(CommonApiCodes.CONFLICT, "请使用新的 X-Request-Id 重新开始");
        }
        Instant expiresAt = now.plusSeconds(policy.attemptTtlSeconds());
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("attemptId", Long.toUnsignedString(attemptId));
        attempt.put("purpose", purpose);
        attempt.put("secret", sha256Hex(token));
        attempt.put("status", "PROVE_IDENTITY");
        attempt.put("userId", "");
        attempt.put("failures", 0);
        attempt.put("expiresAtMs", expiresAt.toEpochMilli());
        volatileStore.put("attempt:" + attemptId, json(attempt), Duration.between(now, expiresAt));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attemptId", Long.toUnsignedString(attemptId));
        data.put("attemptToken", token);
        data.put("expiresAt", iso(expiresAt));
        data.put("nextStep", "PROVE_IDENTITY");
        return data;
    }

    // ------------------------------------------------------------- wechat-login

    /**
     * POST /api/v1/c/auth/wechat-login: exchanges the one-time code, binds or creates the
     * account and — once a verified phone is known — issues the volatile session. Without any
     * phone fact the response is the 200 VERIFY_PHONE progress and no SessionGrant.
     */
    public Map<String, Object> wechatLogin(String requestId, String attemptIdText,
                                           String attemptSecret, String wechatCode, String phoneCode) {
        requireUuid(requestId);
        if (wechatCode == null || wechatCode.isEmpty() || wechatCode.length() > 512
                || (phoneCode != null && (phoneCode.isEmpty() || phoneCode.length() > 512))) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
        Map<String, Object> attempt = requireAttempt(attemptIdText, attemptSecret, PURPOSE_WECHAT_LOGIN);
        requireBelowFailureLimit(attempt);
        String commandKey = commandKey(attemptIdText, "WECHAT_LOGIN", requestId);
        String paramsSha = sha256Hex((phoneCode == null ? "-" : phoneCode) + "\n" + wechatCode);
        Map<String, Object> replay = replayCommand(commandKey, paramsSha);
        if (replay != null) return replay;
        if ("COMPLETED".equals(attempt.get("status"))) {
            // A finished attempt only replays its own key inside the window; fresh logins
            // start a new attempt.
            throw unauthorized();
        }

        WechatSessionProvider.WechatIdentity identity = exchangeIdentity(attempt, wechatCode);
        String phone = phoneCode == null ? null : exchangePhone(attempt, phoneCode);

        long userId = execution.execute(status -> {
            jdbc.execute("SET SESSION time_zone = '+00:00'");
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 2");
            UserAuthStore.IdentityRow row = store.findIdentity(identity.appId(), identity.openId());
            long id;
            if (row == null) {
                id = freshId();
                try {
                    store.insertAccount(id);
                    store.insertIdentity(freshId(), id, identity.appId(), identity.openId(),
                            identity.unionId());
                } catch (DuplicateKeyException race) {
                    // Concurrent first login on the same WeChat identity: keep one account.
                    row = store.findIdentity(identity.appId(), identity.openId());
                    if (row == null) throw dependency();
                    id = row.userId();
                }
            } else {
                id = row.userId();
            }
            return finishIdentityProof(id, phone);
        });

        Map<String, Object> account = readAccount(userId);
        boolean complete = !"".equals(account.get("phone"));
        if (complete) {
            return completeLogin(commandKey, paramsSha, attempt, userId);
        }
        writeAttemptStatus(attempt, "VERIFY_PHONE", Long.toUnsignedString(userId));
        Map<String, Object> progress = progressBody(attemptIdText, attempt);
        publishCommand(commandKey, paramsSha, "PROGRESS", progress, attemptRemaining(attempt));
        return progress;
    }

    // ------------------------------------------------------------ phone-binding

    /** Attempt path of POST /api/v1/c/account/phone-binding: first binding completes login. */
    public Map<String, Object> bindPhoneWithAttempt(String requestId, String attemptIdText,
                                                    String attemptSecret, String phoneCode) {
        requireUuid(requestId);
        requireCode(phoneCode);
        Map<String, Object> attempt = requireAttempt(attemptIdText, attemptSecret, PURPOSE_WECHAT_LOGIN);
        requireBelowFailureLimit(attempt);
        String commandKey = commandKey(attemptIdText, "PHONE_BINDING", requestId);
        String paramsSha = sha256Hex(String.valueOf(phoneCode));
        // Replay first: a COMPLETED attempt still returns its grant inside the recovery window.
        Map<String, Object> replay = replayCommand(commandKey, paramsSha);
        if (replay != null) return replay;
        if (!"VERIFY_PHONE".equals(attempt.get("status"))) {
            throw unauthorized();
        }

        String phone = exchangePhone(attempt, phoneCode);
        long userId = Long.parseLong(String.valueOf(attempt.get("userId")));
        execution.executeWithoutResult(status -> {
            jdbc.execute("SET SESSION time_zone = '+00:00'");
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 2");
            finishIdentityProof(userId, phone);
        });
        return completeLogin(commandKey, paramsSha, attempt, userId);
    }

    /** Bearer path of phone-binding: change the phone of the logged-in account. */
    public Map<String, Object> rebindPhoneWithSession(String requestId, String accessToken,
                                                      String phoneCode) {
        requireUuid(requestId);
        requireCode(phoneCode);
        MiniSessionView session = resolveSession(accessToken);
        String commandKey = "cmd:u" + session.userId() + ":PHONE_BINDING:" + requestId;
        String paramsSha = sha256Hex(String.valueOf(phoneCode));
        Map<String, Object> replay = replayCommand(commandKey, paramsSha);
        if (replay != null) return replay;

        String phone = exchangePhone(null, phoneCode);
        long userId = Long.parseLong(session.userId());
        execution.executeWithoutResult(status -> {
            jdbc.execute("SET SESSION time_zone = '+00:00'");
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 2");
            UserAuthStore.AccountRow account = store.findAccount(userId, true);
            if (account == null) throw unauthorized();
            if ("FROZEN".equals(account.status())) throw frozen();
            Long owner = store.findAccountIdByPhone(phone);
            if (owner != null && owner != userId) throw phoneConflict();
            if (!phone.equals(account.phone())) store.setPhone(userId, phone);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phoneMasked", mask(phone));
        publishCommand(commandKey, paramsSha, "MASKED", data, Duration.ofSeconds(policy.attemptTtlSeconds()));
        return data;
    }

    // ----------------------------------------------------------------- sessions

    /** Bearer validation for the C-end filter and GET /api/v1/c/auth/session. */
    public MiniSessionView resolveSession(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > 1100
                || accessToken.contains(" ")) {
            throw unauthorized();
        }
        Map<String, Object> session = readJson(
                volatileStore.get("session:" + sha256Hex(accessToken)).orElse(null));
        if (session == null) throw unauthorized();
        long expiresAtMs = ((Number) session.get("expiresAtMs")).longValue();
        if (expiresAtMs <= clock.instant().toEpochMilli()) throw unauthorized();
        long userId = ((Number) session.get("userId")).longValue();
        UserAuthStore.AccountRow account = store.findAccount(userId, false);
        // CANCELED accounts lose their sessions immediately; FROZEN keeps read access while
        // every write path keeps rejecting with USER_FROZEN (approved frozen semantics).
        if (account == null || "CANCELED".equals(account.status())) throw unauthorized();
        return new MiniSessionView(
                Long.toUnsignedString(((Number) session.get("sessionId")).longValue()),
                Long.toUnsignedString(userId),
                Instant.ofEpochMilli(expiresAtMs),
                mask(account.phone()),
                account.status());
    }

    /** POST /api/v1/c/auth/logout: convergent, repeating it stays safe. */
    public Map<String, Object> logout(String requestId, String accessToken) {
        requireUuid(requestId);
        if (accessToken != null && !accessToken.isBlank() && accessToken.length() <= 1100) {
            volatileStore.delete("session:" + sha256Hex(accessToken));
        }
        return Map.of("loggedOut", true);
    }

    // ------------------------------------------------------------- internals

    /**
     * Locks the account, refuses CANCELED logins, binds the freshly proven phone under the
     * unique-key conflict rule and records the login time. Never merges two accounts.
     */
    private long finishIdentityProof(long userId, String phone) {
        UserAuthStore.AccountRow account = store.findAccount(userId, true);
        if (account == null) throw dependency();
        if ("CANCELED".equals(account.status())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "账号状态不可用");
        }
        if (phone != null) {
            if (account.phone() == null) {
                Long owner = store.findAccountIdByPhone(phone);
                if (owner != null && owner != userId) throw phoneConflict();
                store.setPhone(userId, phone);
            } else if (!phone.equals(account.phone())) {
                throw new ApiException(CommonApiCodes.FORBIDDEN, "请使用已登录换绑流程修改手机号");
            }
        }
        store.touchLogin(userId);
        return userId;
    }

    /**
     * Issues the session, closes the attempt and publishes the grant receipt with the frozen
     * B1 anchor: secretUntil = pre-issue clock time + grant window, so the recoverable span can
     * only be shorter than the nominal window, never longer.
     */
    private Map<String, Object> completeLogin(String commandKey, String paramsSha,
                                              Map<String, Object> attempt, long userId) {
        Instant anchor = clock.instant();
        Map<String, Object> grant = issueSession(userId);
        Instant secretUntil = anchor.plusSeconds(policy.grantWindowSeconds());
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("secretUntilMs", secretUntil.toEpochMilli());
        boolean first = publishCommand(commandKey, paramsSha, "GRANT", receipt,
                attemptRemaining(attempt));
        writeAttemptStatus(attempt, "COMPLETED", "");
        if (first) {
            volatileStore.put(grantKey(commandKey), json(grant),
                    Duration.between(clock.instant(), secretUntil));
            return grant;
        }
        // A same-key execution won the race; only its grant is recoverable.
        Map<String, Object> winner = replayCommand(commandKey, paramsSha);
        if (winner != null) return winner;
        throw dependency();
    }

    private Map<String, Object> issueSession(long userId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(policy.accessTtlSeconds());
        String token = randomToken();
        long sessionId = freshId();
        volatileStore.put("session:" + sha256Hex(token), json(Map.of(
                        "sessionId", sessionId, "userId", userId,
                        "issuedAtMs", now.toEpochMilli(), "expiresAtMs", expiresAt.toEpochMilli())),
                Duration.between(now, expiresAt));
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("sessionId", Long.toUnsignedString(sessionId));
        grant.put("userId", Long.toUnsignedString(userId));
        grant.put("audience", "MINIAPP");
        grant.put("tokenType", "Bearer");
        grant.put("accessToken", token);
        grant.put("expiresAt", iso(expiresAt));
        return grant;
    }

    private WechatSessionProvider.WechatIdentity exchangeIdentity(
            Map<String, Object> attempt, String wechatCode) {
        try {
            return wechat.exchangeIdentity(wechatCode);
        } catch (WechatSessionProvider.ProofRejected rejected) {
            countFailure(attempt);
            throw unauthorized();
        } catch (RuntimeException unavailable) {
            throw dependency();
        }
    }

    private String exchangePhone(Map<String, Object> attempt, String phoneCode) {
        try {
            String phone = wechat.exchangePhone(phoneCode);
            if (phone == null || !PHONE.matcher(phone).matches()) {
                throw new WechatSessionProvider.ProofRejected();
            }
            return phone;
        } catch (WechatSessionProvider.ProofRejected rejected) {
            if (attempt != null) countFailure(attempt);
            throw unauthorized();
        } catch (RuntimeException unavailable) {
            throw dependency();
        }
    }

    private Map<String, Object> requireAttempt(
            String attemptIdText, String secret, String purpose) {
        if (attemptIdText == null || !NUMERIC_ID.matcher(attemptIdText).matches()) throw unauthorized();
        Map<String, Object> attempt = readJson(
                volatileStore.get("attempt:" + attemptIdText).orElse(null));
        if (attempt == null || !purpose.equals(attempt.get("purpose"))) throw unauthorized();
        if (secret == null || secret.isBlank() || !MessageDigest.isEqual(
                utf8(String.valueOf(attempt.get("secret"))), utf8(sha256Hex(secret)))) {
            throw unauthorized();
        }
        if (((Number) attempt.get("expiresAtMs")).longValue() <= clock.instant().toEpochMilli()) {
            throw unauthorized();
        }
        return attempt;
    }

    private void requireBelowFailureLimit(Map<String, Object> attempt) {
        if (((Number) attempt.get("failures")).intValue() >= policy.attemptFailureLimit()) {
            throw new ApiException(CommonApiCodes.RATE_LIMITED, "请求过于频繁，请稍后重试");
        }
    }

    private void countFailure(Map<String, Object> attempt) {
        attempt.put("failures", ((Number) attempt.get("failures")).intValue() + 1);
        volatileStore.put("attempt:" + attempt.get("attemptId"), json(attempt), attemptRemaining(attempt));
    }

    private void writeAttemptStatus(Map<String, Object> attempt, String status, String userId) {
        attempt.put("status", status);
        attempt.put("userId", userId);
        volatileStore.put("attempt:" + attempt.get("attemptId"), json(attempt), attemptRemaining(attempt));
    }

    private Duration attemptRemaining(Map<String, Object> attempt) {
        long remaining = ((Number) attempt.get("expiresAtMs")).longValue()
                - clock.instant().toEpochMilli();
        return remaining <= 0 ? Duration.ofSeconds(1) : Duration.ofMillis(remaining);
    }

    private Map<String, Object> progressBody(String attemptIdText, Map<String, Object> attempt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attemptId", attemptIdText);
        data.put("nextStep", "VERIFY_PHONE");
        data.put("expiresAt", iso(Instant.ofEpochMilli(
                ((Number) attempt.get("expiresAtMs")).longValue())));
        return data;
    }

    /**
     * Stores the params tombstone (only the digest — raw codes are never written). Returns true
     * when this call owned the write. {@code data} carries kind-specific receipt facts.
     */
    private boolean publishCommand(String commandKey, String paramsSha, String kind,
                                   Map<String, Object> data, Duration ttl) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("params", paramsSha);
        record.put("kind", kind);
        record.put("data", data == null ? Map.of() : data);
        return volatileStore.putIfAbsent(commandKey, json(record), ttl);
    }

    /**
     * Same-key replay semantics: different bytes are 409; a GRANT receipt is recoverable only
     * inside the frozen secret window while its session is still alive — after the window it is
     * a plain 401 with no re-issuance; PROGRESS/MASKED receipts replay while their tombstone
     * lives; a GRANT tombstone whose receipt is not yet published is a transient 503.
     */
    private Map<String, Object> replayCommand(String commandKey, String paramsSha) {
        Map<String, Object> record = readJson(volatileStore.get(commandKey).orElse(null));
        if (record == null) return null;
        if (!MessageDigest.isEqual(utf8(String.valueOf(record.get("params"))), utf8(paramsSha))) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "相同 requestId 对应不同参数");
        }
        String kind = String.valueOf(record.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) record.getOrDefault("data", Map.of());
        if ("PROGRESS".equals(kind) || "MASKED".equals(kind)) {
            return data;
        }
        // GRANT: recover only the first secret inside the window, and only live sessions.
        String grantJson = volatileStore.get(grantKey(commandKey)).orElse(null);
        long nowMs = clock.instant().toEpochMilli();
        long secretUntilMs = data.get("secretUntilMs") instanceof Number n ? n.longValue() : 0;
        if (grantJson == null) {
            if (nowMs < secretUntilMs) throw dependency();
            throw unauthorized();
        }
        Map<String, Object> grant = readJson(grantJson);
        if (grant == null) throw dependency();
        String tokenSha = sha256Hex(String.valueOf(grant.get("accessToken")));
        if (volatileStore.get("session:" + tokenSha).isEmpty()) throw unauthorized();
        return grant;
    }

    private String grantKey(String commandKey) {
        return "grant" + commandKey.substring(3);
    }

    private Map<String, Object> readAccount(long userId) {
        UserAuthStore.AccountRow account = store.findAccount(userId, false);
        if (account == null) throw dependency();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phone", account.phone() == null ? "" : account.phone());
        data.put("status", account.status());
        return data;
    }

    private static String commandKey(String attemptId, String namespace, String requestId) {
        return "cmd:" + attemptId + ":" + namespace + ":" + requestId;
    }

    private long freshId() {
        long id = ids.nextId();
        if (id <= 0) throw new IllegalStateException("Invalid ID from provider");
        return id;
    }

    private static void requireUuid(String requestId) {
        try {
            PublicContractChecks.requireTerminalRequestId(requestId);
        } catch (RuntimeException e) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
    }

    private static void requireCode(String code) {
        if (code == null || code.isEmpty() || code.length() > 512) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(CommonApiCodes.UNAUTHORIZED, "验证失败，请重试");
    }

    private static ApiException dependency() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "服务暂不可用，请稍后重试");
    }

    private static ApiException phoneConflict() {
        return new ApiException(CommonApiCodes.CONFLICT, "该手机号已绑定其他账号，请联系客服");
    }

    private static ApiException frozen() {
        return new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
    }

    private static String safe(String ip) {
        return ip == null || ip.isBlank() || !ip.matches("[A-Za-z0-9.:_-]{1,64}")
                ? "unknown" : ip;
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() != 11) return null;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String iso(Instant value) {
        return new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String json(Object value) {
        try {
            return CODEC.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Auth record serialization failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String raw) {
        if (raw == null) return null;
        try {
            return CODEC.readValue(raw, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Volatile key prefixes this service owns; exposed for teardown in tests. */
    public List<String> volatileKeyPrefixes() {
        return List.of("attempt:", "attempt-create:", "cmd:", "grant:", "session:", "rate:");
    }
}
