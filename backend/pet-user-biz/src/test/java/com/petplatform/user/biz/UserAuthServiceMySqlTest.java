package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import com.petplatform.user.biz.application.WechatSessionProvider;
import com.petplatform.user.biz.infrastructure.provider.MiniAuthVolatileStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * W2-AUTH C-login slice over a real isolated MySQL. The WeChat provider and the volatile store
 * are honest doubles (fixed codes / in-memory TTL map); the Redis store itself is exercised by
 * the boot HTTP test in CI. Provider codes: {@code ok:<openid>[:<unionid>]},
 * {@code phone:<11-digit>}, anything else rejects, {@code down*} is a provider outage.
 */
class UserAuthServiceMySqlTest {

    private final AtomicLong ids = new AtomicLong(900_000);
    private final MutableClock clock = new MutableClock();

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
    }

    /** Fixed-code test double — no real WeChat credentials exist anywhere in this repo. */
    private static final class FixedWechatProvider implements WechatSessionProvider {
        @Override public WechatIdentity exchangeIdentity(String code) {
            if (code.startsWith("down")) throw new ProviderUnavailable();
            if (!code.startsWith("ok:")) throw new ProofRejected();
            String[] parts = code.split(":");
            return new WechatIdentity("qa-app", parts[1], parts.length > 2 ? parts[2] : null);
        }
        @Override public String exchangePhone(String code) {
            if (code.startsWith("down")) throw new ProviderUnavailable();
            if (!code.startsWith("phone:")) throw new ProofRejected();
            return code.substring(6);
        }
    }

    private static final class Entry {
        final String value;
        final long expiresAtMs;
        Entry(String value, long expiresAtMs) { this.value = value; this.expiresAtMs = expiresAtMs; }
    }

    /** In-memory TTL map standing in for the isolated volatile Redis. */
    private static final class InMemoryVolatileStore implements MiniAuthVolatileStore {
        final Map<String, Entry> data = new ConcurrentHashMap<>();
        private final Clock clock;
        InMemoryVolatileStore(Clock clock) { this.clock = clock; }
        private boolean alive(String key) {
            Entry entry = data.get(key);
            return entry != null && entry.expiresAtMs > clock.instant().toEpochMilli();
        }
        @Override public boolean putIfAbsent(String key, String value, Duration ttl) {
            if (alive(key)) return false;
            data.put(key, new Entry(value, clock.instant().plusMillis(ttl.toMillis()).toEpochMilli()));
            return true;
        }
        @Override public void put(String key, String value, Duration ttl) {
            data.put(key, new Entry(value, clock.instant().plusMillis(ttl.toMillis()).toEpochMilli()));
        }
        @Override public Optional<String> get(String key) {
            return alive(key) ? Optional.of(data.get(key).value) : Optional.empty();
        }
        @Override public void delete(String key) { data.remove(key); }
        @Override public long incrementWindow(String key, Duration ttl) {
            Entry entry = data.get(key);
            if (entry == null || entry.expiresAtMs <= clock.instant().toEpochMilli()) {
                data.put(key, new Entry("0", clock.instant().plusMillis(ttl.toMillis()).toEpochMilli()));
                return 1;
            }
            long next = Long.parseLong(entry.value) + 1;
            data.put(key, new Entry(Long.toString(next), entry.expiresAtMs));
            return next;
        }
        @Override public void verifyVolatileConfiguration() {}
        @Override public void close() {}
    }

    private record Fixture(MySqlUserDomainTestDatabase db, UserAuthService service,
                           InMemoryVolatileStore store) implements AutoCloseable {
        @Override public void close() throws Exception { db.close(); }
    }

    private Fixture service() {
        MySqlUserDomainTestDatabase db = null;
        try {
            db = new MySqlUserDomainTestDatabase();
            InMemoryVolatileStore store = new InMemoryVolatileStore(clock);
            UserAuthService service = new UserAuthService(
                    db.dataSource(), ids::incrementAndGet, clock,
                    new FixedWechatProvider(), store,
                    new UserAuthService.MiniAuthPolicy(600, 900, 60, 30, 3));
            return new Fixture(db, service, store);
        } catch (Exception failure) {
            if (db != null) try { db.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException(failure);
        }
    }

    private static String rid() { return UUID.randomUUID().toString(); }

    private record Attempt(String id, String token) { @Override public String toString() { return "Attempt[REDACTED]"; } }

    private Attempt attempt(UserAuthService service) {
        Map<String, Object> data = service.createAttempt(rid(), "WECHAT_LOGIN", "127.0.0.1");
        assertEquals("PROVE_IDENTITY", data.get("nextStep"));
        return new Attempt(String.valueOf(data.get("attemptId")), String.valueOf(data.get("attemptToken")));
    }

    private static ApiException code(Runnable call) {
        return assertThrows(ApiException.class, call::run);
    }

    @Test void firstWechatLoginWithoutPhoneStopsAtVerifyPhone() throws Exception {
        try (Fixture f = service()) {
            Attempt attempt = attempt(f.service());
            Map<String, Object> result = f.service().wechatLogin(
                    rid(), attempt.id(), attempt.token(), "ok:open-1", null);
            assertEquals("VERIFY_PHONE", result.get("nextStep"));
            assertNull(result.get("accessToken"));
            var account = f.db().jdbc().queryForMap(
                    "SELECT a.* FROM user_account a JOIN user_auth_identity i ON i.user_id=a.id"
                            + " WHERE i.identity_type='WECHAT_MINI' AND i.open_id='open-1'");
            assertNull(account.get("phone"));
            assertNull(account.get("password_hash"), "first registration never gets a random password");
            assertEquals(false, account.get("password_enabled"));
            assertEquals("ACTIVE", account.get("status"));
        }
    }

    @Test void phoneBindingCompletesLoginIssuesSessionAndReplaysInsideWindow() throws Exception {
        try (Fixture f = service()) {
            Attempt attempt = attempt(f.service());
            f.service().wechatLogin(rid(), attempt.id(), attempt.token(), "ok:open-2", null);
            String requestId = rid();
            Map<String, Object> grant = f.service().bindPhoneWithAttempt(
                    requestId, attempt.id(), attempt.token(), "phone:13800002222");
            assertEquals("MINIAPP", grant.get("audience"));
            assertEquals("Bearer", grant.get("tokenType"));
            assertEquals(43, String.valueOf(grant.get("accessToken")).length(), "256-bit token");
            MiniSessionView view = f.service().resolveSession(String.valueOf(grant.get("accessToken")));
            assertEquals(grant.get("userId"), view.userId());
            assertEquals("138****2222", view.phoneMasked());
            assertEquals("ACTIVE", view.userStatus());
            // Same key, same params: identical recoverable grant (B1 window).
            assertEquals(grant, f.service().bindPhoneWithAttempt(
                    requestId, attempt.id(), attempt.token(), "phone:13800002222"));
            // Completed attempt cannot be reused.
            assertEquals(CommonApiCodes.UNAUTHORIZED, code(() ->
                    f.service().wechatLogin(rid(), attempt.id(), attempt.token(), "ok:open-2", null)).code());
        }
    }

    @Test void grantWindowClosesThen401WithoutNewToken() throws Exception {
        try (Fixture f = service()) {
            Attempt attempt = attempt(f.service());
            f.service().wechatLogin(rid(), attempt.id(), attempt.token(), "ok:open-3", null);
            String requestId = rid();
            Map<String, Object> grant = f.service().bindPhoneWithAttempt(
                    requestId, attempt.id(), attempt.token(), "phone:13800003333");
            String grantKey = "grant:" + attempt.id() + ":PHONE_BINDING:" + requestId;
            f.store().delete(grantKey);
            // Before the frozen secretUntil: transient dependency failure, no re-issue.
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, code(() ->
                    f.service().bindPhoneWithAttempt(requestId, attempt.id(), attempt.token(),
                            "phone:13800003333")).code());
            clock.advanceSeconds(61);
            assertEquals(CommonApiCodes.UNAUTHORIZED, code(() ->
                    f.service().bindPhoneWithAttempt(requestId, attempt.id(), attempt.token(),
                            "phone:13800003333")).code());
            assertNotNull(grant.get("accessToken"));
        }
    }

    @Test void rejectedProofsAreUniform401AndLockTheAttempt() throws Exception {
        try (Fixture f = service()) {
            Attempt attempt = attempt(f.service());
            for (int i = 0; i < 3; i++) {
                String forged = "forged-code-" + i;
                ApiException rejected = code(() -> f.service().wechatLogin(
                        rid(), attempt.id(), attempt.token(), forged, null));
                assertEquals(CommonApiCodes.UNAUTHORIZED, rejected.code());
                assertEquals("验证失败，请重试", rejected.getMessage());
            }
            assertEquals(CommonApiCodes.RATE_LIMITED, code(() -> f.service().wechatLogin(
                    rid(), attempt.id(), attempt.token(), "ok:open-4", null)).code());
        }
    }

    @Test void providerOutageIs503AndCreatesNothing() throws Exception {
        try (Fixture f = service()) {
            Attempt attempt = attempt(f.service());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, code(() -> f.service().wechatLogin(
                    rid(), attempt.id(), attempt.token(), "down:now", null)).code());
            assertEquals(0, f.db().jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_auth_identity", Integer.class));
        }
    }

    @Test void wrongAttemptSecretAndUnknownAttemptAre401() throws Exception {
        try (Fixture f = service()) {
            Attempt attempt = attempt(f.service());
            assertEquals(CommonApiCodes.UNAUTHORIZED, code(() -> f.service().wechatLogin(
                    rid(), attempt.id(), "wrong-secret", "ok:open-5", null)).code());
            assertEquals(CommonApiCodes.UNAUTHORIZED, code(() -> f.service().wechatLogin(
                    rid(), "1234567890123456789", attempt.token(), "ok:open-5", null)).code());
            assertEquals(CommonApiCodes.UNAUTHORIZED, code(() -> f.service().wechatLogin(
                    rid(), "not-numeric", attempt.token(), "ok:open-5", null)).code());
        }
    }

    @Test void duplicateAttemptCreationRequestIdOnlyRestarts() throws Exception {
        try (Fixture f = service()) {
            String requestId = rid();
            assertNotNull(f.service().createAttempt(requestId, "WECHAT_LOGIN", "127.0.0.1")
                    .get("attemptToken"));
            ApiException duplicate = code(() ->
                    f.service().createAttempt(requestId, "WECHAT_LOGIN", "127.0.0.1"));
            assertEquals(CommonApiCodes.CONFLICT, duplicate.code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() ->
                    f.service().createAttempt(rid(), "SMS_LOGIN", "127.0.0.1")).code());
        }
    }

    @Test void sameOpenIdRebindsSameAccountWithoutSecondUser() throws Exception {
        try (Fixture f = service()) {
            Attempt first = attempt(f.service());
            Map<String, Object> grant = f.service().wechatLogin(
                    rid(), first.id(), first.token(), "ok:open-6", "phone:13800004444");
            assertEquals("MINIAPP", grant.get("audience"));
            Attempt second = attempt(f.service());
            Map<String, Object> relogin = f.service().wechatLogin(
                    rid(), second.id(), second.token(), "ok:open-6", null);
            assertEquals(grant.get("userId"), relogin.get("userId"), "existing account only re-logs-in");
            assertNotEquals(grant.get("accessToken"), relogin.get("accessToken"));
            assertEquals(1, f.db().jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_account", Integer.class));
            var account = f.db().jdbc().queryForMap("SELECT * FROM user_account");
            assertNotNull(account.get("last_login_at"), "existing account only updates login time");
        }
    }

    @Test void phoneOwnedByAnotherAccountIsRefusedWithoutMerge() throws Exception {
        try (Fixture f = service()) {
            f.db().seedUser(600_001L, "13800000001", "ACTIVE");
            Attempt attempt = attempt(f.service());
            ApiException conflict = code(() -> f.service().wechatLogin(
                    rid(), attempt.id(), attempt.token(), "ok:open-7", "phone:13800000001"));
            assertEquals(CommonApiCodes.CONFLICT, conflict.code());
            // The whole login transaction rolled back: no orphan account or identity exists.
            assertEquals(0, f.db().jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_auth_identity WHERE open_id='open-7'", Integer.class));
            assertEquals(1, f.db().jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_account", Integer.class));
            assertEquals(600_001L, ((Number) f.db().jdbc().queryForObject(
                    "SELECT id FROM user_account WHERE phone='13800000001'", Long.class)).longValue());
        }
    }

    @Test void canceledAccountCannotLoginWhileFrozenKeepsReadSession() throws Exception {
        try (Fixture f = service()) {
            Attempt ok = attempt(f.service());
            Map<String, Object> grant = f.service().wechatLogin(
                    rid(), ok.id(), ok.token(), "ok:open-8", "phone:13800005555");
            long userId = Long.parseLong(String.valueOf(grant.get("userId")));
            f.db().jdbc().update("UPDATE user_account SET status='FROZEN' WHERE id=?", userId);
            assertEquals("FROZEN", f.service().resolveSession(
                    String.valueOf(grant.get("accessToken"))).userStatus(),
                    "frozen keeps its read session; writes reject downstream");
            f.db().jdbc().update("UPDATE user_account SET status='CANCELED' WHERE id=?", userId);
            assertEquals(CommonApiCodes.UNAUTHORIZED, code(() -> f.service().resolveSession(
                    String.valueOf(grant.get("accessToken")))).code());
            f.db().jdbc().update("INSERT INTO user_auth_identity"
                    + " (id,user_id,identity_type,app_id,open_id,created_at,updated_at)"
                    + " VALUES (?,?,'WECHAT_MINI','qa-app','open-9',NOW(3),NOW(3))",
                    ids.incrementAndGet(), userId);
            Attempt canceled = attempt(f.service());
            assertEquals(CommonApiCodes.FORBIDDEN, code(() -> f.service().wechatLogin(
                    rid(), canceled.id(), canceled.token(), "ok:open-9", null)).code());
        }
    }

    @Test void logoutKillsTheSessionAndIsConvergent() throws Exception {
        try (Fixture f = service()) {
            Attempt attempt = attempt(f.service());
            Map<String, Object> grant = f.service().wechatLogin(
                    rid(), attempt.id(), attempt.token(), "ok:open-10", "phone:13800006666");
            String token = String.valueOf(grant.get("accessToken"));
            assertEquals(Map.of("loggedOut", true), f.service().logout(rid(), token));
            assertEquals(Map.of("loggedOut", true), f.service().logout(rid(), token));
            assertEquals(CommonApiCodes.UNAUTHORIZED, code(() -> f.service().resolveSession(token)).code());
        }
    }

    @Test void bearerRebindChangesPhoneAndReplaysMaskedReceipt() throws Exception {
        try (Fixture f = service()) {
            Attempt attempt = attempt(f.service());
            Map<String, Object> grant = f.service().wechatLogin(
                    rid(), attempt.id(), attempt.token(), "ok:open-11", "phone:13800007777");
            String token = String.valueOf(grant.get("accessToken"));
            String requestId = rid();
            assertEquals(Map.of("phoneMasked", "139****8888"),
                    f.service().rebindPhoneWithSession(requestId, token, "phone:13900008888"));
            assertEquals(Map.of("phoneMasked", "139****8888"),
                    f.service().rebindPhoneWithSession(requestId, token, "phone:13900008888"));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, code(() ->
                    f.service().rebindPhoneWithSession(requestId, token, "phone:13900009999")).code());
            assertEquals("139****8888", f.service().resolveSession(token).phoneMasked());
        }
    }

    @Test void attemptPathCannotSilentlyChangeAnExistingDifferentPhone() throws Exception {
        try (Fixture f = service()) {
            // Attempt 1 proves the identity and waits at VERIFY_PHONE without a phone.
            Attempt waiting = attempt(f.service());
            f.service().wechatLogin(rid(), waiting.id(), waiting.token(), "ok:open-12", null);
            // Meanwhile the same account completes a full login with phone X on attempt 2.
            Attempt finisher = attempt(f.service());
            f.service().wechatLogin(rid(), finisher.id(), finisher.token(),
                    "ok:open-12", "phone:13800001212");
            // The stale VERIFY_PHONE attempt cannot rebind to a different phone.
            ApiException refused = code(() -> f.service().bindPhoneWithAttempt(
                    rid(), waiting.id(), waiting.token(), "phone:13900001313"));
            assertEquals(CommonApiCodes.FORBIDDEN, refused.code());
            assertEquals("13800001212", f.db().jdbc().queryForObject(
                    "SELECT phone FROM user_account", String.class));
        }
    }
}
