package com.petplatform.admin.biz.application;

import com.petplatform.admin.api.dto.*;
import com.petplatform.admin.api.query.AdminSessionQueryApi;
import com.petplatform.admin.biz.domain.service.AdminPermissionEvaluator;
import com.petplatform.admin.biz.infrastructure.persistence.AdminAuthStore;
import com.petplatform.admin.biz.infrastructure.persistence.AdminAuthStore.Tx;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.AuthAttempt;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.AuthCommand;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.Captcha;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.LoginFailure;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.WebSession;
import com.petplatform.admin.biz.infrastructure.persistence.mapper.AdminAuthMapper;
import com.petplatform.admin.biz.infrastructure.provider.*;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;

/** Real Web authentication only. No business CRUD, miniapp proof or signing provider shortcuts. */
public final class AdminAuthService implements AdminSessionQueryApi {
  private static final ThreadLocal<String> REQUEST_TRACE = new ThreadLocal<>();

  public static void bindTrace(String trace) {
    if (trace == null || !trace.matches("[A-Za-z0-9._:-]{1,64}")) throw AdminAuthFailure.invalid();
    REQUEST_TRACE.set(trace);
  }

  public static void clearTrace() {
    REQUEST_TRACE.remove();
  }

  private static final DateTimeFormatter TIME =
      new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
  private static final String[] ROLES = {
    "CONTENT_EDITOR",
    "REVIEWER",
    "OPERATIONS_ADMIN",
    "FINANCE_READER",
    "AUDIT_READER",
    "PLATFORM_SUPER_ADMIN"
  };
  private static final String[] ROLE_NAMES = {"运营编辑", "审核员", "运营管理员", "财务只读", "审计只读", "平台超级管理员"};
  private final AdminAuthStore store;
  private final SnowflakeIdGenerator ids;
  private final Clock clock;
  private final AdminPasswordHasher passwords;
  private final AdminSecretCodec secrets;
  private final AdminGrantCache cache;

  public AdminAuthService(
      DataSource source,
      SnowflakeIdGenerator ids,
      Clock clock,
      AdminPasswordHasher passwords,
      AdminSecretCodec secrets,
      AdminGrantCache cache) {
    this.store = new AdminAuthStore(source);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
    this.passwords = Objects.requireNonNull(passwords);
    this.secrets = Objects.requireNonNull(secrets);
    this.cache = Objects.requireNonNull(cache);
    cache.verifyVolatileConfiguration();
  }

  private long id() {
    try {
      long value = ids.nextId();
      if (value <= 0) throw AdminAuthFailure.unavailable();
      return value;
    } catch (RuntimeException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  private static String request(String value) {
    try {
      return PublicContractChecks.requireTerminalRequestId(value);
    } catch (RuntimeException e) {
      throw AdminAuthFailure.invalid();
    }
  }

  private static String text(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) throw AdminAuthFailure.invalid();
    return value;
  }

  private static byte[] key(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String lookup(String account) {
    text(account, 128);
    if (account.contains("@")) {
      int at = account.lastIndexOf('@');
      if (at < 1 || at == account.length() - 1) throw AdminAuthFailure.invalid();
      return "EMAIL:"
          + account.substring(0, at + 1)
          + account.substring(at + 1).toLowerCase(Locale.ROOT);
    }
    return (account.matches("1[0-9]{10}") ? "PHONE:" : "STAFF:") + account;
  }

  private static String time(Instant value) {
    return TIME.format(value);
  }

  private static OffsetDateTime date(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private void active(Tx tx) {
    var b = tx.auth().selectBootstrap();
    if (b == null || !b.bootstrapComplete || b.maintenanceMode)
      throw AdminAuthFailure.unavailable();
  }

  private AuthAttempt attempt(Tx tx, long attemptId, String token, String binding, boolean lock) {
    AuthAttempt a =
        lock ? tx.auth().selectAttemptForUpdate(attemptId) : tx.auth().selectAttempt(attemptId);
    if (a == null
        || !MessageDigest.isEqual(a.secretDigest, AdminSecretCodec.digest(token))
        || !MessageDigest.isEqual(a.bindingDigest, AdminSecretCodec.digest(binding))
        || !a.expiresAt.isAfter(tx.now())) throw AdminAuthFailure.unauthorized();
    return a;
  }

  private void audit(
      Tx tx,
      Long actor,
      Long attempt,
      String action,
      Long resource,
      String rid,
      String outcome,
      String reason) {
    boolean host = Set.of("AUTH_BOOTSTRAP", "AUTH_RECOVERY", "AUTH_MAINTENANCE").contains(action);
    String actorReference =
        host
            ? "HOST:"
                + ProcessHandle.current().info().user().orElseThrow(AdminAuthFailure::unavailable)
                + ":"
                + ProcessHandle.current().pid()
            : actor == null ? "ANONYMOUS" : "OPERATOR:" + actor;
    Instant now = tx.now();
    tx.auth()
        .insertAuditIntent(
            id(),
            host ? null : actor,
            actorReference,
            attempt,
            action,
            resource,
            rid == null ? null : key(rid),
            Objects.requireNonNullElseGet(REQUEST_TRACE.get(), () -> UUID.randomUUID().toString()),
            outcome,
            text(reason, 500),
            now,
            now);
  }

  /** Fixed event names only; callers must never pass a raw URL, password or token as the reason. */
  public void recordRejected(String event, String requestId) {
    if (!Set.of(
            "AUTH_CREDENTIAL_REJECTED", "AUTH_FORBIDDEN", "AUTH_LOCKED", "AUTH_REQUEST_REJECTED")
        .contains(event)) throw AdminAuthFailure.invalid();
    String rid = null;
    try {
      rid = request(requestId);
    } catch (AdminAuthFailure ignored) {
    }
    String safe = rid;
    try {
      store.write(
          tx -> {
            audit(tx, null, null, event, null, safe, "DENIED", "Request rejected");
            return null;
          });
    } catch (RuntimeException e) {
      System.getLogger(AdminAuthService.class.getName())
          .log(
              System.Logger.Level.WARNING,
              "Auth rejection audit unavailable; no credentials logged");
    }
  }

  private AuthCommand command(Tx tx, String scope, String namespace, String rid, boolean lock) {
    return lock
        ? tx.auth().selectCommandByScopeForUpdate(key(scope), namespace, key(rid))
        : tx.auth().selectCommandByScope(key(scope), namespace, key(rid));
  }

  private AuthCommand bind(
      long attemptId,
      String token,
      String cookie,
      String namespace,
      String rid,
      String... parameters) {
    request(rid);
    long commandId = id();
    String current = secrets.currentKeyId();
    try {
      return store.write(
          tx -> {
            AdminAuthMapper m = tx.auth();
            active(tx);
            attempt(tx, attemptId, token, cookie, true);
            String scope = "A:" + attemptId;
            m.insertCommandForAttempt(
                commandId,
                key(scope),
                attemptId,
                namespace,
                key(rid),
                secrets.mac(current, parameters),
                current);
            AuthCommand c = command(tx, scope, namespace, rid, true);
            if (c == null) throw AdminAuthFailure.unavailable();
            if (!MessageDigest.isEqual(c.parameterMac, secrets.mac(c.macKeyId, parameters)))
              throw new AdminAuthFailure(409, "IDEMPOTENCY_KEY_CONFLICT");
            return c;
          });
    } catch (AdminAuthStore.CommitUnknown e) {
      return store.read(
          tx -> {
            active(tx);
            attempt(tx, attemptId, token, cookie, false);
            AuthCommand c = command(tx, "A:" + attemptId, namespace, rid, false);
            if (c == null) throw AdminAuthFailure.unavailable();
            if (!MessageDigest.isEqual(c.parameterMac, secrets.mac(c.macKeyId, parameters)))
              throw new AdminAuthFailure(409, "IDEMPOTENCY_KEY_CONFLICT");
            return c;
          });
    }
  }

  private AuthCommand lockCommand(Tx tx, long id) {
    AuthCommand c = tx.auth().selectCommandForUpdate(id);
    if (c == null) throw AdminAuthFailure.unavailable();
    return c;
  }

  private AuthCommand readCommand(long id) {
    AuthCommand c = store.read(tx -> tx.auth().selectCommand(id));
    if (c == null) throw AdminAuthFailure.unavailable();
    return c;
  }

  private static boolean succeeded(AuthCommand c) {
    return "SUCCEEDED".equals(c.state);
  }

  private void complete(
      Tx tx, long commandId, String kind, long resultId, Instant resultExpires, String cacheRef) {
    Instant anchor = tx.now();
    int changed =
        tx.auth()
            .completeCommand(
                kind,
                resultId,
                UUID.randomUUID().toString(),
                cacheRef,
                anchor,
                cacheRef == null ? null : anchor,
                cacheRef == null ? null : anchor.plusSeconds(60),
                resultExpires,
                commandId);
    if (changed != 1) throw AdminAuthFailure.conflict();
  }

  private static String aad(AuthCommand c) {
    return "ADMIN_WEB|"
        + c.id
        + "|"
        + c.namespace
        + "|"
        + c.resultId
        + "|"
        + c.cacheRef
        + "|"
        + c.secretExpiresAt.toEpochMilli();
  }

  private WebSession validSession(Tx tx, long sessionId, boolean lock) {
    WebSession session =
        lock ? tx.auth().selectSessionForUpdate(sessionId) : tx.auth().selectSession(sessionId);
    if (session == null) throw AdminAuthFailure.unauthorized();
    var a = tx.auth().selectAccount(session.accountId);
    if (a == null
        || !"ENABLED".equals(a.status)
        || a.sessionGeneration.longValue() != session.generation.longValue()
        || !"ACTIVE".equals(session.status)
        || !session.idleExpiresAt.isAfter(tx.now()))
      throw AdminAuthFailure.unauthorized();
    return session;
  }

  private void readableResult(Tx tx, AuthCommand c) {
    active(tx);
    if (!succeeded(c)) throw AdminAuthFailure.unavailable();
    if (c.cacheRef != null && !c.secretExpiresAt.isAfter(tx.now()))
      throw AdminAuthFailure.unauthorized();
    if ("SESSION_GRANT".equals(c.resultKind) || "ACTIVITY".equals(c.resultKind))
      validSession(tx, c.resultId, false);
    if ("CAPTCHA_PROOF".equals(c.resultKind)) {
      Captcha cap = tx.auth().selectCaptcha(c.resultId);
      if (cap.proofConsumedAt != null
          || cap.proofExpiresAt == null
          || !cap.proofExpiresAt.isAfter(tx.now())) throw AdminAuthFailure.unauthorized();
    }
  }

  private boolean ownSecret(Tx tx, AuthCommand c, Map<String, Object> body) {
    if (body == null || body.isEmpty()) return false;
    if ("SESSION_GRANT".equals(c.resultKind)) {
      if (!body.keySet()
          .equals(
              Set.of(
                  "sessionId", "operatorId", "audience", "tokenType", "accessToken", "expiresAt")))
        return false;
      WebSession session = tx.auth().selectSession(c.resultId);
      return String.valueOf(session.id).equals(body.get("sessionId"))
          && String.valueOf(session.accountId).equals(body.get("operatorId"))
          && MessageDigest.isEqual(
              session.tokenDigest,
              AdminSecretCodec.digest((String) body.get("accessToken")));
    }
    if ("CAPTCHA_PROOF".equals(c.resultKind)) {
      if (!body.keySet().equals(Set.of("captchaProof", "expiresAt"))) return false;
      Captcha cap = tx.auth().selectCaptcha(c.resultId);
      return MessageDigest.isEqual(
          cap.proofDigest, AdminSecretCodec.digest((String) body.get("captchaProof")));
    }
    return false;
  }

  private AdminSecretResult render(AuthCommand command, Map<String, Object> freshlyCreated) {
    AuthCommand c =
        store.read(
            tx -> {
              AuthCommand current = tx.auth().selectCommand(command.id);
              if (current == null) throw AdminAuthFailure.unavailable();
              readableResult(tx, current);
              return current;
            });
    Map<String, Object> body;
    if (c.cacheRef != null) {
      boolean own = store.read(tx -> ownSecret(tx, c, freshlyCreated));
      if (own) {
        Instant now = store.read(Tx::now);
        if (!c.secretExpiresAt.isAfter(now)) throw AdminAuthFailure.unauthorized();
        byte[] encrypted = secrets.encrypt(c.macKeyId, aad(c), freshlyCreated);
        cache.putIfAbsent(
            c.cacheRef, encrypted, Duration.between(now, c.secretExpiresAt));
      }
      byte[] encrypted = awaitPublication(c.cacheRef);
      body = secrets.decrypt(c.macKeyId, aad(c), encrypted);
      // A fresh transaction after cache I/O: a previous RR snapshot cannot hide
      // logout/relogin/revocation.
      store.read(
          tx -> {
            AuthCommand current = tx.auth().selectCommand(c.id);
            if (current == null) throw AdminAuthFailure.unavailable();
            readableResult(tx, current);
            if (!Objects.equals(current.cacheRef, c.cacheRef)
                || !ownSecret(tx, current, body)) throw AdminAuthFailure.unavailable();
            return null;
          });
    } else {
      body =
          store.read(
              tx -> {
                AdminAuthMapper m = tx.auth();
                AuthCommand current = m.selectCommand(command.id);
                if (current == null) throw AdminAuthFailure.unavailable();
                readableResult(tx, current);
                return switch (current.resultKind) {
                  case "CAPTCHA_CHALLENGE" -> {
                    Captcha cap = m.selectCaptcha(current.resultId);
                    if (cap == null
                        || cap.challengeConsumedAt != null
                        || !cap.challengeExpiresAt.isAfter(tx.now()))
                      throw AdminAuthFailure.unauthorized();
                    yield Map.<String, Object>of(
                        "captchaId",
                        Long.toString(cap.id),
                        "imageDataUrl",
                        "data:image/png;base64,"
                            + Base64.getEncoder().encodeToString(cap.imagePng),
                        "expiresAt",
                        time(cap.challengeExpiresAt));
                  }
                  case "LOGGED_OUT" -> Map.<String, Object>of("loggedOut", true);
                  case "ACTIVITY" ->
                      Map.<String, Object>of(
                          "idleExpiresAt", time(current.resultExpiresAt));
                  default -> throw AdminAuthFailure.unavailable();
                };
              });
    }
    return new AdminSecretResult(body);
  }

  private byte[] awaitPublication(String reference) {
    // The same-key winner may have committed immediately before publishing. Never re-sign.
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
    do {
      var value = cache.get(reference);
      if (value.isPresent()) return value.get();
      if (Thread.currentThread().isInterrupted()) throw AdminAuthFailure.unavailable();
      java.util.concurrent.locks.LockSupport.parkNanos(
          java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
    } while (System.nanoTime() < deadline);
    throw AdminAuthFailure.unavailable();
  }

  public AdminSecretResult createAttempt(String requestId, String remoteIp) {
    request(requestId);
    String token = secrets.token("at"), cookie = secrets.token("ab");
    long aid = id();
    try {
      return store.write(
          tx -> {
            AdminAuthMapper m = tx.auth();
            active(tx);
            if (m.selectAttemptCreation(key(requestId)) != null) throw AdminAuthFailure.conflict();
            Instant now = tx.now();
            byte[] ip =
                secrets.mac(
                    secrets.currentKeyId(), "IP", Objects.requireNonNullElse(remoteIp, "unknown"));
            byte[] creationRisk =
                secrets.mac(
                    secrets.currentKeyId(),
                    "BOOTSTRAP_IP",
                    Objects.requireNonNullElse(remoteIp, "unknown"));
            m.insertLoginFailureSeed(creationRisk, now);
            LoginFailure creation = m.selectLoginFailureForUpdate(creationRisk);
            if (creation == null) throw AdminAuthFailure.unavailable();
            boolean freshWindow = !creation.windowStart.plusSeconds(60).isAfter(now);
            long attempts = freshWindow ? 0 : creation.count;
            if (attempts >= 30) throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
            m.updateLoginFailureWindow(
                freshWindow ? now : creation.windowStart, attempts + 1, creationRisk);
            m.insertAttempt(
                aid,
                AdminSecretCodec.digest(token),
                AdminSecretCodec.digest(cookie),
                now.plusSeconds(600),
                now,
                ip);
            try {
              m.insertAttemptCreation(key(requestId), aid, now);
            } catch (DuplicateKeyException e) {
              throw AdminAuthFailure.conflict();
            }
            return new AdminSecretResult(
                Map.of(
                    "attemptId",
                    Long.toString(aid),
                    "attemptToken",
                    token,
                    "expiresAt",
                    time(now.plusSeconds(600)),
                    "nextStep",
                    "PROVE_IDENTITY"),
                cookie);
          });
    } catch (AdminAuthStore.CommitUnknown e) {
      throw AdminAuthFailure.conflict();
    }
  }

  private LoginFailure failures(Tx tx, byte[] digest, boolean lock) {
    return lock
        ? tx.auth().selectLoginFailureForUpdate(digest)
        : tx.auth().selectLoginFailure(digest);
  }

  private boolean locked(LoginFailure f, Instant now) {
    return f != null && f.lockedUntil != null && f.lockedUntil.isAfter(now);
  }

  private int failureCount(LoginFailure f, Instant now) {
    return f == null || !f.windowStart.plusSeconds(900).isAfter(now)
        ? 0
        : f.count.intValue();
  }

  private void countFailure(Tx tx, byte[] digest, Instant now) {
    AdminAuthMapper m = tx.auth();
    m.insertLoginFailureSeed(digest, now);
    LoginFailure f = m.selectLoginFailureForUpdate(digest);
    int n = failureCount(f, now) + 1;
    m.updateLoginFailureCount(
        failureCount(f, now) == 0 || f == null ? now : f.windowStart,
        n,
        n >= 10 ? now.plusSeconds(900) : null,
        digest);
  }

  public AdminSecretResult requirements(long aid, String token, String cookie) {
    return store.read(
        tx -> {
          active(tx);
          AuthAttempt a = attempt(tx, aid, token, cookie, false);
          Instant now = tx.now();
          LoginFailure f = failures(tx, a.sourceIpDigest, false),
              accountRisk =
                  a.riskLookupDigest == null
                      ? null
                      : failures(tx, a.riskLookupDigest, false);
          if (locked(f, now) || locked(accountRisk, now))
            throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
          return new AdminSecretResult(
              Map.of(
                  "requiredVerification",
                  Math.max(failureCount(f, now), failureCount(accountRisk, now)) >= 5
                      ? "CAPTCHA"
                      : "NONE"));
        });
  }

  public AdminSecretResult login(
      String requestId,
      long aid,
      String token,
      String cookie,
      String account,
      char[] password,
      String captchaProof) {
    String accountKey = lookup(account);
    AdminPasswordHasher.require(password);
    String[] params = {
      "LOGIN", accountKey, new String(password), Objects.requireNonNullElse(captchaProof, "")
    };
    AuthCommand bound = bind(aid, token, cookie, "LOGIN", requestId, params);
    if (succeeded(bound)) return render(bound, null);
    com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.Account observed;
    try {
      observed =
          store.read(
              tx -> {
                AdminAuthMapper m = tx.auth();
                active(tx);
                AuthAttempt at = attempt(tx, aid, token, cookie, false);
                Instant now = tx.now();
                LoginFailure
                    af =
                        failures(
                            tx,
                            secrets.mac(bound.macKeyId, "ACCOUNT", accountKey),
                            false),
                    ipf = failures(tx, at.sourceIpDigest, false);
                if (locked(af, now) || locked(ipf, now))
                    throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
                return m.selectAccountByLookup(key(accountKey));
              });
    } catch (AdminAuthFailure failure) {
      if (failure.status() == 429) recordRejected("AUTH_LOCKED", requestId);
      throw failure;
    }
    boolean matched =
        passwords.matches(password, observed == null ? null : observed.passwordHash);
    long sid = id();
    String access = secrets.token("aw"), cacheRef = UUID.randomUUID().toString();
    Map<String, Object> fresh = new LinkedHashMap<>();
    int outcome;
    try {
      outcome =
          store.write(
              tx -> {
                AdminAuthMapper m = tx.auth();
                active(tx);
                com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.Account a =
                    observed == null ? null : m.selectAccountForUpdate(observed.id);
                AuthAttempt at = attempt(tx, aid, token, cookie, true);
                AuthCommand c = lockCommand(tx, bound.id);
                if (succeeded(c)) return 2;
                if (!"PROVE_IDENTITY".equals(at.status))
                  throw AdminAuthFailure.unauthorized();
                Instant now = tx.now();
                byte[] accountDigest = secrets.mac(c.macKeyId, "ACCOUNT", accountKey);
                m.updateAttemptRisk(accountDigest, aid);
                LoginFailure af = failures(tx, accountDigest, false),
                    ipf = failures(tx, at.sourceIpDigest, false);
                if (locked(af, now) || locked(ipf, now)) {
                  audit(
                      tx,
                      null,
                      aid,
                      "AUTH_LOCKED",
                      null,
                      requestId,
                      "DENIED",
                      "Login temporarily locked");
                  return 429;
                }
                boolean captchaRequired = failureCount(af, now) >= 5 || failureCount(ipf, now) >= 5;
                Captcha proof =
                    captchaProof == null
                        ? null
                        : m.selectCaptchaByProofForUpdate(aid, AdminSecretCodec.digest(captchaProof));
                boolean proofOk =
                    proof != null
                        && proof.proofConsumedAt == null
                        && proof.proofExpiresAt != null
                        && proof.proofExpiresAt.isAfter(now);
                boolean identityOk =
                    matched
                        && a != null
                        && "ENABLED".equals(a.status)
                        && a.credentialVersion.longValue() == observed.credentialVersion.longValue()
                        && a.sessionGeneration.longValue() == observed.sessionGeneration.longValue();
                if (!identityOk || (captchaRequired && !proofOk)) {
                  if (!c.failureCounted) {
                    countFailure(tx, accountDigest, now);
                    countFailure(tx, at.sourceIpDigest, now);
                    m.markFailureCounted(c.id);
                    audit(
                        tx,
                        null,
                        aid,
                        "AUTH_LOGIN",
                        null,
                        requestId,
                        "DENIED",
                        "Credentials rejected");
                  }
                  return 401;
                }
                if (proofOk) m.markCaptchaProofConsumed(now, proof.id);
                long generation = Math.addExact(a.sessionGeneration, 1);
                Instant expires = now.plusSeconds(1800);
                m.updateLoginSession(generation, now, now, a.id);
                m.insertSession(sid, a.id, AdminSecretCodec.digest(access), generation, now, now, expires);
                m.completeAttempt(a.id, a.credentialVersion, sid, aid);
                complete(tx, c.id, "SESSION_GRANT", sid, expires, cacheRef);
                audit(
                    tx,
                    a.id,
                    aid,
                    "AUTH_LOGIN",
                    sid,
                    requestId,
                    "ALLOWED",
                    "Password login");
                fresh.putAll(
                    Map.of(
                        "sessionId",
                        Long.toString(sid),
                        "operatorId",
                        Long.toString(a.id),
                        "audience",
                        "ADMIN_WEB",
                        "tokenType",
                        "Bearer",
                        "accessToken",
                        access,
                        "expiresAt",
                        time(expires)));
                return 1;
              });
    } catch (AdminAuthStore.CommitUnknown e) {
      AuthCommand confirmed = readCommand(bound.id);
      if (!succeeded(confirmed) || confirmed.resultId != sid)
        throw AdminAuthFailure.unavailable();
      outcome = 1;
    }
    if (outcome == 401) throw AdminAuthFailure.unauthorized();
    if (outcome == 429) throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
    return render(readCommand(bound.id), outcome == 1 ? fresh : null);
  }

  public AdminSecretResult createCaptcha(String requestId, long aid, String token, String cookie) {
    AuthCommand bound =
        bind(aid, token, cookie, "CAPTCHA_CREATE", requestId, "CAPTCHA_CREATE", Long.toString(aid));
    if (succeeded(bound)) return render(bound, null);
    store.read(
        tx -> {
          if (tx.auth().countCaptchasByAttempt(aid) >= 10)
            throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
          return null;
        });
    String answer = secrets.captchaAnswer(), keyId = secrets.currentKeyId();
    byte[] image = image(answer);
    long cid = id();
    try {
      store.write(
          tx -> {
            AdminAuthMapper m = tx.auth();
            active(tx);
            AuthAttempt a = attempt(tx, aid, token, cookie, true);
            if (!"PROVE_IDENTITY".equals(a.status)) throw AdminAuthFailure.unauthorized();
            AuthCommand c = lockCommand(tx, bound.id);
            if (succeeded(c)) return null;
            Instant now = tx.now();
            if (m.countCaptchasByAttempt(aid) >= 10)
              throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
            m.consumeStaleCaptchaChallenges(now, now, aid);
            m.insertCaptcha(
                cid,
                aid,
                secrets.mac(keyId, "CAPTCHA", answer),
                keyId,
                image,
                now,
                now.plusSeconds(120));
            complete(tx, c.id, "CAPTCHA_CHALLENGE", cid, now.plusSeconds(120), null);
            return null;
          });
    } catch (AdminAuthStore.CommitUnknown e) {
      if (!succeeded(readCommand(bound.id))) throw AdminAuthFailure.unavailable();
    }
    return render(readCommand(bound.id), null);
  }

  private static byte[] image(String answer) {
    try {
      BufferedImage image = new BufferedImage(220, 70, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, 220, 70);
      g.setColor(Color.DARK_GRAY);
      g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 32));
      g.drawString(answer, 18, 48);
      g.dispose();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ImageIO.write(image, "png", bytes);
      return bytes.toByteArray();
    } catch (Exception e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  public AdminSecretResult verifyCaptcha(
      String requestId, long aid, String token, String cookie, long captchaId, char[] answer) {
    if (answer == null || answer.length < 1 || answer.length > 32) throw AdminAuthFailure.invalid();
    String supplied = new String(answer);
    AuthCommand bound =
        bind(aid, token, cookie, "CAPTCHA_VERIFY", requestId, Long.toString(captchaId), supplied);
    if (succeeded(bound)) return render(bound, null);
    String proof = secrets.token("cp"), cacheRef = UUID.randomUUID().toString();
    Map<String, Object> fresh = new LinkedHashMap<>();
    int result;
    try {
      result =
          store.write(
              tx -> {
                AdminAuthMapper m = tx.auth();
                active(tx);
                AuthAttempt at = attempt(tx, aid, token, cookie, true);
                AuthCommand c = lockCommand(tx, bound.id);
                if (succeeded(c)) return 2;
                if (!"PROVE_IDENTITY".equals(at.status))
                  throw AdminAuthFailure.unauthorized();
                Captcha cap = m.selectCaptchaForVerification(captchaId, aid);
                Instant now = tx.now();
                if (cap == null
                    || cap.challengeConsumedAt != null
                    || !cap.challengeExpiresAt.isAfter(now)
                    || cap.failureCount >= 5) throw AdminAuthFailure.unauthorized();
                if (!MessageDigest.isEqual(
                    cap.answerMac, secrets.mac(cap.macKeyId, "CAPTCHA", supplied))) {
                  if (!c.failureCounted) {
                    m.bumpCaptchaFailure(captchaId);
                    m.markFailureCounted(c.id);
                    audit(
                        tx,
                        null,
                        aid,
                        "CAPTCHA_VERIFY",
                        captchaId,
                        requestId,
                        "DENIED",
                        "Captcha proof rejected");
                  }
                  return 401;
                }
                Instant expires = now.plusSeconds(120);
                m.issueCaptchaProof(now, AdminSecretCodec.digest(proof), now, expires, captchaId);
                complete(tx, c.id, "CAPTCHA_PROOF", captchaId, expires, cacheRef);
                fresh.putAll(Map.of("captchaProof", proof, "expiresAt", time(expires)));
                return 1;
              });
    } catch (AdminAuthStore.CommitUnknown e) {
      if (!succeeded(readCommand(bound.id))) throw AdminAuthFailure.unavailable();
      result = 1;
    }
    if (result == 401) throw AdminAuthFailure.unauthorized();
    return render(readCommand(bound.id), result == 1 ? fresh : null);
  }

  public AdminSecretResult attemptResult(
      long aid, String token, String cookie, String originalRequestId) {
    AuthAttempt a =
        store.read(
            tx -> {
              active(tx);
              return attempt(tx, aid, token, cookie, false);
            });
    Map<String, Object> body =
        new LinkedHashMap<>(
            Map.of(
                "attemptId",
                Long.toString(aid),
                "nextStep",
                a.status,
                "expiresAt",
                time(a.expiresAt)));
    if (originalRequestId != null) {
      request(originalRequestId);
      AuthCommand c =
          store.read(tx -> tx.auth().selectCommandByAttemptAndRequest(aid, key(originalRequestId)));
      if (c == null || !succeeded(c)) throw AdminAuthFailure.unavailable();
      body.put(
          "commandResult",
          Map.of(
              "requestId",
              originalRequestId,
              "kind",
              "SESSION_GRANT",
              "data",
              render(c, null).data()));
    }
    return new AdminSecretResult(body);
  }

  @Override
  public AdminSessionView resolveSession(String accessToken) {
    byte[] digest = AdminSecretCodec.digest(accessToken);
    return store.read(
        tx -> {
          AdminAuthMapper m = tx.auth();
          active(tx);
          WebSession s = m.selectSessionByTokenDigest(digest);
          if (s == null) throw AdminAuthFailure.unauthorized();
          validSession(tx, s.id, false);
          long actor = s.accountId;
          var revision = m.selectAuthzRevision();
          if (revision == null) throw AdminAuthFailure.unavailable();
          List<AdminPermissionSnapshot.Role> roles =
              m.selectEnabledRoles(actor).stream()
                  .map(
                      r ->
                          new AdminPermissionSnapshot.Role(
                              Long.toString(r.id), r.roleCode, r.displayName))
                  .toList();
          boolean superAdmin =
              roles.stream().anyMatch(r -> r.roleCode().equals("PLATFORM_SUPER_ADMIN"));
          var scopeRow = m.selectAccountScope(actor);
          if (scopeRow == null) throw AdminAuthFailure.unavailable();
          List<String> cities = m.selectScopeCities(actor);
          List<String> merchants =
              m.selectScopeMerchants(actor).stream().map(String::valueOf).toList();
          AdminDataScope scope = new AdminDataScope(scopeRow.mode, cities, merchants);
          if (superAdmin) scope = new AdminDataScope("ALL", List.of(), List.of());
          List<String> grants = new ArrayList<>(m.selectActionGrants(actor));
          var permissions =
              new AdminPermissionSnapshot(
                  Long.toString(actor),
                  revision.recoveryEpoch + ":" + revision.revision,
                  date(tx.now()),
                  roles,
                  scope,
                  AdminPermissionEvaluator.evaluate(
                      superAdmin, grants, AdminPermissionEvaluator.DEPLOYED_ACTIONS));
          return new AdminSessionView(
              new AdminSessionPrincipal(
                  "ADMIN_WEB",
                  Long.toString(s.id),
                  Long.toString(actor),
                  s.generation),
              date(s.idleExpiresAt),
              permissions);
        });
  }

  private AdminSecretResult sessionCommand(String requestId, String accessToken, boolean logout) {
    request(requestId);
    byte[] digest = AdminSecretCodec.digest(accessToken);
    WebSession observed =
        store.read(
            tx -> {
              active(tx);
              WebSession s = tx.auth().selectSessionByTokenDigest(digest);
              if (s == null) throw AdminAuthFailure.unauthorized();
              return s;
            });
    long cid = id();
    String namespace = logout ? "LOGOUT" : "ACTIVITY",
        scope = "S:" + observed.id,
        keyId = secrets.currentKeyId();
    try {
      store.write(
          tx -> {
            AdminAuthMapper m = tx.auth();
            active(tx);
            var account = m.selectAccountForUpdate(observed.accountId);
            if (account == null
                || !"ENABLED".equals(account.status)
                || account.sessionGeneration.longValue() != observed.generation.longValue())
              throw AdminAuthFailure.unauthorized();
            AuthCommand existing = command(tx, scope, namespace, requestId, true);
            if (existing != null && succeeded(existing)) {
              if (!logout) validSession(tx, observed.id, false);
              return null;
            }
            validSession(tx, observed.id, false);
            m.insertCommandForSession(
                cid,
                key(scope),
                observed.id,
                namespace,
                key(requestId),
                secrets.mac(keyId, namespace),
                keyId);
            WebSession s = m.selectSessionForUpdate(observed.id);
            if (s == null) throw AdminAuthFailure.unavailable();
            Instant now = tx.now(),
                expiry = logout ? s.idleExpiresAt : now.plusSeconds(1800);
            if (logout) m.revokeSession(now, s.id);
            else m.refreshSessionIdle(now, expiry, s.id);
            complete(tx, cid, logout ? "LOGGED_OUT" : "ACTIVITY", s.id, expiry, null);
            audit(
                tx,
                account.id,
                null,
                namespace,
                s.id,
                requestId,
                "ALLOWED",
                logout ? "Session logout" : "Foreground activity");
            return null;
          });
    } catch (AdminAuthStore.CommitUnknown e) {
      AuthCommand c = store.read(tx -> command(tx, scope, namespace, requestId, false));
      if (c == null || !succeeded(c)) throw AdminAuthFailure.unavailable();
    }
    AuthCommand c = store.read(tx -> command(tx, scope, namespace, requestId, false));
    if (c == null) throw AdminAuthFailure.unavailable();
    return render(c, null);
  }

  public AdminSecretResult logout(String requestId, String accessToken) {
    return sessionCommand(requestId, accessToken, true);
  }

  public AdminSecretResult activity(String requestId, String accessToken) {
    return sessionCommand(requestId, accessToken, false);
  }

  public long bootstrap(String account, String displayName, char[] password, String reason) {
    String normalized = lookup(account);
    text(displayName, 64);
    text(reason, 500);
    AdminPasswordHasher.requireInitialStrength(password);
    String hash = passwords.encode(password);
    long actor = id();
    long[] roleIds = new long[6];
    for (int i = 0; i < 6; i++) roleIds[i] = id();
    return store.write(
        tx -> {
          AdminAuthMapper m = tx.auth();
          if (m.selectAuthzRevisionForUpdate() == null) throw AdminAuthFailure.unavailable();
          var b = m.selectBootstrapForUpdate();
          if (b == null) throw AdminAuthFailure.unavailable();
          if (b.bootstrapComplete
              || !b.maintenanceMode
              || m.countAccounts() != 0)
            throw AdminAuthFailure.conflict();
          Instant now = tx.now();
          m.insertBootstrapAccount(actor, account, key(normalized), displayName, hash, now, now);
          for (int i = 0; i < 6; i++) m.insertRole(roleIds[i], ROLES[i], ROLE_NAMES[i]);
          m.insertAccountRole(actor, roleIds[5], actor, now);
          m.insertAccountScopeAll(actor);
          m.bumpAuthzRevision();
          m.completeBootstrap(now);
          audit(tx, actor, null, "AUTH_BOOTSTRAP", actor, null, "ALLOWED", reason);
          return actor;
        });
  }

  public void beginMaintenance(String reason) {
    text(reason, 500);
    store.write(
        tx -> {
          AdminAuthMapper m = tx.auth();
          if (m.selectAuthzRevisionForUpdate() == null) throw AdminAuthFailure.unavailable();
          if (m.selectBootstrapForUpdate() == null) throw AdminAuthFailure.unavailable();
          m.enableMaintenance();
          audit(tx, null, null, "AUTH_MAINTENANCE", null, null, "ALLOWED", reason);
          return null;
        });
  }

  public void recover(long actor, char[] password, String reason) {
    text(reason, 500);
    AdminPasswordHasher.requireInitialStrength(password);
    String hash = passwords.encode(password);
    store.write(
        tx -> {
          AdminAuthMapper m = tx.auth();
          if (m.selectAuthzRevisionForUpdate() == null) throw AdminAuthFailure.unavailable();
          var b = m.selectBootstrapForUpdate();
          if (b == null || !b.maintenanceMode) throw AdminAuthFailure.conflict();
          var a = m.selectAccountForUpdate(actor);
          if (a == null) throw AdminAuthFailure.unavailable();
          if (m.countSuperAdminGrants(actor) == 0) throw new AdminAuthFailure(403, "COMMON_FORBIDDEN");
          Instant now = tx.now();
          m.updateRecoveredAccount(hash, now, actor);
          m.bumpAuthzRevision();
          m.deleteLoginFailure(
              secrets.mac(
                  secrets.currentKeyId(),
                  "ACCOUNT",
                  new String(a.accountLookup, StandardCharsets.UTF_8)));
          audit(tx, actor, null, "AUTH_RECOVERY", actor, null, "ALLOWED", reason);
          m.disableMaintenance();
          return null;
        });
  }
}
