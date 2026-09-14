package com.petplatform.admin.biz.application;

import com.petplatform.admin.api.dto.*;
import com.petplatform.admin.api.query.AdminSessionQueryApi;
import com.petplatform.admin.biz.domain.service.AdminPermissionEvaluator;
import com.petplatform.admin.biz.infrastructure.persistence.AdminAuthStore;
import com.petplatform.admin.biz.infrastructure.persistence.AdminAuthStore.Row;
import com.petplatform.admin.biz.infrastructure.persistence.AdminAuthStore.Tx;
import com.petplatform.admin.biz.infrastructure.provider.*;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.sql.DataSource;

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

  private void active(Tx tx) throws SQLException {
    Row b = tx.one("SELECT * FROM admin_bootstrap WHERE id=1");
    if (!b.bool("bootstrap_complete") || b.bool("maintenance_mode"))
      throw AdminAuthFailure.unavailable();
  }

  private Row attempt(Tx tx, long attemptId, String token, String binding, boolean lock)
      throws SQLException {
    Row a =
        tx.optional(
            "SELECT * FROM admin_auth_attempt WHERE id=?" + (lock ? " FOR UPDATE" : ""), attemptId);
    if (a == null
        || !MessageDigest.isEqual(a.bytes("secret_digest"), AdminSecretCodec.digest(token))
        || !MessageDigest.isEqual(a.bytes("binding_digest"), AdminSecretCodec.digest(binding))
        || !a.time("expires_at").isAfter(tx.now())) throw AdminAuthFailure.unauthorized();
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
      String reason)
      throws SQLException {
    boolean host = Set.of("AUTH_BOOTSTRAP", "AUTH_RECOVERY", "AUTH_MAINTENANCE").contains(action);
    String actorReference =
        host
            ? "HOST:"
                + ProcessHandle.current().info().user().orElseThrow(AdminAuthFailure::unavailable)
                + ":"
                + ProcessHandle.current().pid()
            : actor == null ? "ANONYMOUS" : "OPERATOR:" + actor;
    Instant now = tx.now();
    tx.update(
        "INSERT INTO"
            + " admin_audit_intent(id,actor_id,actor_reference,attempt_id,action_code,resource_id,request_id,trace_ref,outcome,reason,occurred_at,next_attempt_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
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

  private Row command(Tx tx, String scope, String namespace, String rid, boolean lock)
      throws SQLException {
    return tx.optional(
        "SELECT * FROM admin_auth_command WHERE scope_key=? AND namespace=? AND request_id=?"
            + (lock ? " FOR UPDATE" : ""),
        key(scope),
        namespace,
        key(rid));
  }

  private Row bind(
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
            active(tx);
            attempt(tx, attemptId, token, cookie, true);
            String scope = "A:" + attemptId;
            tx.update(
                "INSERT INTO"
                    + " admin_auth_command(id,scope_key,attempt_id,namespace,request_id,parameter_mac,mac_key_id)"
                    + " VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                commandId,
                key(scope),
                attemptId,
                namespace,
                key(rid),
                secrets.mac(current, parameters),
                current);
            Row c = command(tx, scope, namespace, rid, true);
            if (!MessageDigest.isEqual(
                c.bytes("parameter_mac"), secrets.mac(c.text("mac_key_id"), parameters)))
              throw new AdminAuthFailure(409, "IDEMPOTENCY_KEY_CONFLICT");
            return c;
          });
    } catch (AdminAuthStore.CommitUnknown e) {
      return store.read(
          tx -> {
            active(tx);
            attempt(tx, attemptId, token, cookie, false);
            Row c = command(tx, "A:" + attemptId, namespace, rid, false);
            if (c == null) throw AdminAuthFailure.unavailable();
            if (!MessageDigest.isEqual(
                c.bytes("parameter_mac"), secrets.mac(c.text("mac_key_id"), parameters)))
              throw new AdminAuthFailure(409, "IDEMPOTENCY_KEY_CONFLICT");
            return c;
          });
    }
  }

  private Row lockCommand(Tx tx, long id) throws SQLException {
    return tx.one("SELECT * FROM admin_auth_command WHERE id=? FOR UPDATE", id);
  }

  private Row readCommand(long id) {
    return store.read(tx -> tx.one("SELECT * FROM admin_auth_command WHERE id=?", id));
  }

  private static boolean succeeded(Row c) {
    return "SUCCEEDED".equals(c.text("state"));
  }

  private void complete(
      Tx tx, long commandId, String kind, long resultId, Instant resultExpires, String cacheRef)
      throws SQLException {
    Instant anchor = tx.now();
    int changed =
        tx.update(
            "UPDATE admin_auth_command SET"
                + " state='SUCCEEDED',result_kind=?,result_id=?,execution_ref=?,cache_ref=?,completed_at=?,receipt_window_anchor_at=?,secret_expires_at=?,result_expires_at=?"
                + " WHERE id=? AND state='RESERVED'",
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

  private static String aad(Row c) {
    return "ADMIN_WEB|"
        + c.number("id")
        + "|"
        + c.text("namespace")
        + "|"
        + c.number("result_id")
        + "|"
        + c.text("cache_ref")
        + "|"
        + c.time("secret_expires_at").toEpochMilli();
  }

  private Row validSession(Tx tx, long sessionId, boolean lock) throws SQLException {
    Row session =
        tx.optional(
            "SELECT * FROM admin_web_session WHERE id=?" + (lock ? " FOR UPDATE" : ""), sessionId);
    if (session == null) throw AdminAuthFailure.unauthorized();
    Row a = tx.optional("SELECT * FROM admin_account WHERE id=?", session.number("account_id"));
    if (a == null
        || !"ENABLED".equals(a.text("status"))
        || a.number("session_generation") != session.number("generation")
        || !"ACTIVE".equals(session.text("status"))
        || !session.time("idle_expires_at").isAfter(tx.now()))
      throw AdminAuthFailure.unauthorized();
    return session;
  }

  private void readableResult(Tx tx, Row c) throws SQLException {
    active(tx);
    if (!succeeded(c)) throw AdminAuthFailure.unavailable();
    if (c.text("cache_ref") != null && !c.time("secret_expires_at").isAfter(tx.now()))
      throw AdminAuthFailure.unauthorized();
    if ("SESSION_GRANT".equals(c.text("result_kind")) || "ACTIVITY".equals(c.text("result_kind")))
      validSession(tx, c.number("result_id"), false);
    if ("CAPTCHA_PROOF".equals(c.text("result_kind"))) {
      Row cap = tx.one("SELECT * FROM admin_captcha WHERE id=?", c.number("result_id"));
      if (cap.time("proof_consumed_at") != null
          || cap.time("proof_expires_at") == null
          || !cap.time("proof_expires_at").isAfter(tx.now())) throw AdminAuthFailure.unauthorized();
    }
  }

  private boolean ownSecret(Tx tx, Row c, Map<String, Object> body) throws SQLException {
    if (body == null || body.isEmpty()) return false;
    if ("SESSION_GRANT".equals(c.text("result_kind"))) {
      if (!body.keySet()
          .equals(
              Set.of(
                  "sessionId", "operatorId", "audience", "tokenType", "accessToken", "expiresAt")))
        return false;
      Row session = tx.one("SELECT * FROM admin_web_session WHERE id=?", c.number("result_id"));
      return Long.toString(session.number("id")).equals(body.get("sessionId"))
          && Long.toString(session.number("account_id")).equals(body.get("operatorId"))
          && MessageDigest.isEqual(
              session.bytes("token_digest"),
              AdminSecretCodec.digest((String) body.get("accessToken")));
    }
    if ("CAPTCHA_PROOF".equals(c.text("result_kind"))) {
      if (!body.keySet().equals(Set.of("captchaProof", "expiresAt"))) return false;
      Row cap = tx.one("SELECT * FROM admin_captcha WHERE id=?", c.number("result_id"));
      return MessageDigest.isEqual(
          cap.bytes("proof_digest"), AdminSecretCodec.digest((String) body.get("captchaProof")));
    }
    return false;
  }

  private AdminSecretResult render(Row command, Map<String, Object> freshlyCreated) {
    Row c =
        store.read(
            tx -> {
              Row current =
                  tx.one("SELECT * FROM admin_auth_command WHERE id=?", command.number("id"));
              readableResult(tx, current);
              return current;
            });
    Map<String, Object> body;
    if (c.text("cache_ref") != null) {
      boolean own = store.read(tx -> ownSecret(tx, c, freshlyCreated));
      if (own) {
        Instant now = store.read(Tx::now);
        if (!c.time("secret_expires_at").isAfter(now)) throw AdminAuthFailure.unauthorized();
        byte[] encrypted = secrets.encrypt(c.text("mac_key_id"), aad(c), freshlyCreated);
        cache.putIfAbsent(
            c.text("cache_ref"), encrypted, Duration.between(now, c.time("secret_expires_at")));
      }
      byte[] encrypted = cache.get(c.text("cache_ref")).orElseThrow(AdminAuthFailure::unavailable);
      body = secrets.decrypt(c.text("mac_key_id"), aad(c), encrypted);
      // A fresh transaction after cache I/O: a previous RR snapshot cannot hide
      // logout/relogin/revocation.
      store.read(
          tx -> {
            Row current = tx.one("SELECT * FROM admin_auth_command WHERE id=?", c.number("id"));
            readableResult(tx, current);
            if (!Objects.equals(current.text("cache_ref"), c.text("cache_ref"))
                || !ownSecret(tx, current, body)) throw AdminAuthFailure.unavailable();
            return null;
          });
    } else {
      body =
          store.read(
              tx -> {
                Row current = tx.one("SELECT * FROM admin_auth_command WHERE id=?", c.number("id"));
                readableResult(tx, current);
                return switch (current.text("result_kind")) {
                  case "CAPTCHA_CHALLENGE" -> {
                    Row cap =
                        tx.one(
                            "SELECT * FROM admin_captcha WHERE id=?", current.number("result_id"));
                    if (cap.time("challenge_consumed_at") != null
                        || !cap.time("challenge_expires_at").isAfter(tx.now()))
                      throw AdminAuthFailure.unauthorized();
                    yield Map.<String, Object>of(
                        "captchaId",
                        Long.toString(cap.number("id")),
                        "imageDataUrl",
                        "data:image/png;base64,"
                            + Base64.getEncoder().encodeToString(cap.bytes("image_png")),
                        "expiresAt",
                        time(cap.time("challenge_expires_at")));
                  }
                  case "LOGGED_OUT" -> Map.<String, Object>of("loggedOut", true);
                  case "ACTIVITY" ->
                      Map.<String, Object>of(
                          "idleExpiresAt", time(current.time("result_expires_at")));
                  default -> throw AdminAuthFailure.unavailable();
                };
              });
    }
    return new AdminSecretResult(body);
  }

  public AdminSecretResult createAttempt(String requestId, String remoteIp) {
    request(requestId);
    String token = secrets.token("at"), cookie = secrets.token("ab");
    long aid = id();
    try {
      return store.write(
          tx -> {
            active(tx);
            if (tx.optional(
                    "SELECT attempt_id FROM admin_attempt_creation WHERE"
                        + " namespace='ADMIN_LOGIN_CREATE' AND request_id=?",
                    key(requestId))
                != null) throw AdminAuthFailure.conflict();
            Instant now = tx.now();
            byte[] ip =
                secrets.mac(
                    secrets.currentKeyId(), "IP", Objects.requireNonNullElse(remoteIp, "unknown"));
            byte[] creationRisk =
                secrets.mac(
                    secrets.currentKeyId(),
                    "BOOTSTRAP_IP",
                    Objects.requireNonNullElse(remoteIp, "unknown"));
            tx.update(
                "INSERT INTO admin_login_failure(lookup_digest,window_start,count,version)"
                    + " VALUES(?,?,0,0) ON DUPLICATE KEY UPDATE lookup_digest=lookup_digest",
                creationRisk,
                now);
            Row creation = failures(tx, creationRisk, true);
            boolean freshWindow = !creation.time("window_start").plusSeconds(60).isAfter(now);
            long attempts = freshWindow ? 0 : creation.number("count");
            if (attempts >= 30) throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
            tx.update(
                "UPDATE admin_login_failure SET window_start=?,count=?,version=version+1 WHERE"
                    + " lookup_digest=?",
                freshWindow ? now : creation.time("window_start"),
                attempts + 1,
                creationRisk);
            tx.update(
                "INSERT INTO"
                    + " admin_auth_attempt(id,secret_digest,binding_digest,expires_at,created_at,source_ip_digest)"
                    + " VALUES(?,?,?,?,?,?)",
                aid,
                AdminSecretCodec.digest(token),
                AdminSecretCodec.digest(cookie),
                now.plusSeconds(600),
                now,
                ip);
            try {
              tx.update(
                  "INSERT INTO admin_attempt_creation(namespace,request_id,attempt_id,created_at)"
                      + " VALUES('ADMIN_LOGIN_CREATE',?,?,?)",
                  key(requestId),
                  aid,
                  now);
            } catch (SQLException e) {
              if ("23000".equals(e.getSQLState())) throw AdminAuthFailure.conflict();
              throw e;
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

  private Row failures(Tx tx, byte[] digest, boolean lock) throws SQLException {
    return tx.optional(
        "SELECT * FROM admin_login_failure WHERE lookup_digest=?" + (lock ? " FOR UPDATE" : ""),
        digest);
  }

  private boolean locked(Row f, Instant now) {
    return f != null && f.time("locked_until") != null && f.time("locked_until").isAfter(now);
  }

  private int failureCount(Row f, Instant now) {
    return f == null || !f.time("window_start").plusSeconds(900).isAfter(now)
        ? 0
        : (int) f.number("count");
  }

  private void countFailure(Tx tx, byte[] digest, Instant now) throws SQLException {
    tx.update(
        "INSERT INTO admin_login_failure(lookup_digest,window_start,count,version) VALUES(?,?,0,0)"
            + " ON DUPLICATE KEY UPDATE lookup_digest=lookup_digest",
        digest,
        now);
    Row f = failures(tx, digest, true);
    int n = failureCount(f, now) + 1;
    tx.update(
        "UPDATE admin_login_failure SET window_start=?,count=?,locked_until=?,version=version+1"
            + " WHERE lookup_digest=?",
        failureCount(f, now) == 0 ? now : f.time("window_start"),
        n,
        n >= 10 ? now.plusSeconds(900) : null,
        digest);
  }

  public AdminSecretResult requirements(long aid, String token, String cookie) {
    return store.read(
        tx -> {
          active(tx);
          Row a = attempt(tx, aid, token, cookie, false);
          Instant now = tx.now();
          Row f = failures(tx, a.bytes("source_ip_digest"), false),
              accountRisk =
                  a.bytes("risk_lookup_digest") == null
                      ? null
                      : failures(tx, a.bytes("risk_lookup_digest"), false);
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
    Row bound = bind(aid, token, cookie, "LOGIN", requestId, params);
    if (succeeded(bound)) return render(bound, null);
    Row observed;
    try {
      observed =
          store.read(
              tx -> {
                active(tx);
                Row at = attempt(tx, aid, token, cookie, false);
                Instant now = tx.now();
                Row
                    af =
                        failures(
                            tx,
                            secrets.mac(bound.text("mac_key_id"), "ACCOUNT", accountKey),
                            false),
                    ipf = failures(tx, at.bytes("source_ip_digest"), false);
                if (locked(af, now) || locked(ipf, now))
                  throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
                return tx.optional(
                    "SELECT * FROM admin_account WHERE account_lookup=?", key(accountKey));
              });
    } catch (AdminAuthFailure failure) {
      if (failure.status() == 429) recordRejected("AUTH_LOCKED", requestId);
      throw failure;
    }
    boolean matched =
        passwords.matches(password, observed == null ? null : observed.text("password_hash"));
    long sid = id();
    String access = secrets.token("aw"), cacheRef = UUID.randomUUID().toString();
    Map<String, Object> fresh = new LinkedHashMap<>();
    int outcome;
    try {
      outcome =
          store.write(
              tx -> {
                active(tx);
                Row a =
                    observed == null
                        ? null
                        : tx.optional(
                            "SELECT * FROM admin_account WHERE id=? FOR UPDATE",
                            observed.number("id"));
                Row at = attempt(tx, aid, token, cookie, true);
                Row c = lockCommand(tx, bound.number("id"));
                if (succeeded(c)) return 2;
                if (!"PROVE_IDENTITY".equals(at.text("status")))
                  throw AdminAuthFailure.unauthorized();
                Instant now = tx.now();
                byte[] accountDigest = secrets.mac(c.text("mac_key_id"), "ACCOUNT", accountKey);
                tx.update(
                    "UPDATE admin_auth_attempt SET risk_lookup_digest=? WHERE id=?",
                    accountDigest,
                    aid);
                Row af = failures(tx, accountDigest, false),
                    ipf = failures(tx, at.bytes("source_ip_digest"), false);
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
                Row proof =
                    captchaProof == null
                        ? null
                        : tx.optional(
                            "SELECT * FROM admin_captcha WHERE attempt_id=? AND proof_digest=? FOR"
                                + " UPDATE",
                            aid,
                            AdminSecretCodec.digest(captchaProof));
                boolean proofOk =
                    proof != null
                        && proof.time("proof_consumed_at") == null
                        && proof.time("proof_expires_at") != null
                        && proof.time("proof_expires_at").isAfter(now);
                boolean identityOk =
                    matched
                        && a != null
                        && "ENABLED".equals(a.text("status"))
                        && a.number("credential_version") == observed.number("credential_version")
                        && a.number("session_generation") == observed.number("session_generation");
                if (!identityOk || (captchaRequired && !proofOk)) {
                  if (!c.bool("failure_counted")) {
                    countFailure(tx, accountDigest, now);
                    countFailure(tx, at.bytes("source_ip_digest"), now);
                    tx.update(
                        "UPDATE admin_auth_command SET failure_counted=TRUE WHERE id=?",
                        c.number("id"));
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
                if (proofOk)
                  tx.update(
                      "UPDATE admin_captcha SET proof_consumed_at=? WHERE id=?",
                      now,
                      proof.number("id"));
                long generation = Math.addExact(a.number("session_generation"), 1);
                Instant expires = now.plusSeconds(1800);
                tx.update(
                    "UPDATE admin_account SET session_generation=?,last_login_at=?,updated_at=?"
                        + " WHERE id=?",
                    generation,
                    now,
                    now,
                    a.number("id"));
                tx.update(
                    "INSERT INTO"
                        + " admin_web_session(id,account_id,token_digest,generation,issued_at,last_interactive_at,idle_expires_at)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    sid,
                    a.number("id"),
                    AdminSecretCodec.digest(access),
                    generation,
                    now,
                    now,
                    expires);
                tx.update(
                    "UPDATE admin_auth_attempt SET"
                        + " status='COMPLETED',account_id=?,credential_version=?,completed_result_id=?"
                        + " WHERE id=?",
                    a.number("id"),
                    a.number("credential_version"),
                    sid,
                    aid);
                complete(tx, c.number("id"), "SESSION_GRANT", sid, expires, cacheRef);
                audit(
                    tx,
                    a.number("id"),
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
                        Long.toString(a.number("id")),
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
      Row confirmed = readCommand(bound.number("id"));
      if (!succeeded(confirmed) || confirmed.number("result_id") != sid)
        throw AdminAuthFailure.unavailable();
      outcome = 1;
    }
    if (outcome == 401) throw AdminAuthFailure.unauthorized();
    if (outcome == 429) throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
    return render(readCommand(bound.number("id")), outcome == 1 ? fresh : null);
  }

  public AdminSecretResult createCaptcha(String requestId, long aid, String token, String cookie) {
    Row bound =
        bind(aid, token, cookie, "CAPTCHA_CREATE", requestId, "CAPTCHA_CREATE", Long.toString(aid));
    if (succeeded(bound)) return render(bound, null);
    store.read(
        tx -> {
          if (tx.one("SELECT COUNT(*) AS n FROM admin_captcha WHERE attempt_id=?", aid).number("n")
              >= 10) throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
          return null;
        });
    String answer = secrets.captchaAnswer(), keyId = secrets.currentKeyId();
    byte[] image = image(answer);
    long cid = id();
    try {
      store.write(
          tx -> {
            active(tx);
            Row a = attempt(tx, aid, token, cookie, true);
            if (!"PROVE_IDENTITY".equals(a.text("status"))) throw AdminAuthFailure.unauthorized();
            Row c = lockCommand(tx, bound.number("id"));
            if (succeeded(c)) return null;
            Instant now = tx.now();
            if (tx.one("SELECT COUNT(*) AS n FROM admin_captcha WHERE attempt_id=?", aid)
                    .number("n")
                >= 10) throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
            tx.update(
                "UPDATE admin_captcha SET"
                    + " challenge_consumed_at=COALESCE(challenge_consumed_at,?),proof_consumed_at=CASE"
                    + " WHEN proof_digest IS NOT NULL THEN COALESCE(proof_consumed_at,?) ELSE NULL"
                    + " END WHERE attempt_id=?",
                now,
                now,
                aid);
            tx.update(
                "INSERT INTO"
                    + " admin_captcha(id,attempt_id,answer_mac,mac_key_id,image_png,challenge_created_at,challenge_expires_at)"
                    + " VALUES(?,?,?,?,?,?,?)",
                cid,
                aid,
                secrets.mac(keyId, "CAPTCHA", answer),
                keyId,
                image,
                now,
                now.plusSeconds(120));
            complete(tx, c.number("id"), "CAPTCHA_CHALLENGE", cid, now.plusSeconds(120), null);
            return null;
          });
    } catch (AdminAuthStore.CommitUnknown e) {
      if (!succeeded(readCommand(bound.number("id")))) throw AdminAuthFailure.unavailable();
    }
    return render(readCommand(bound.number("id")), null);
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
    Row bound =
        bind(aid, token, cookie, "CAPTCHA_VERIFY", requestId, Long.toString(captchaId), supplied);
    if (succeeded(bound)) return render(bound, null);
    String proof = secrets.token("cp"), cacheRef = UUID.randomUUID().toString();
    Map<String, Object> fresh = new LinkedHashMap<>();
    int result;
    try {
      result =
          store.write(
              tx -> {
                active(tx);
                Row at = attempt(tx, aid, token, cookie, true);
                Row c = lockCommand(tx, bound.number("id"));
                if (succeeded(c)) return 2;
                if (!"PROVE_IDENTITY".equals(at.text("status")))
                  throw AdminAuthFailure.unauthorized();
                Row cap =
                    tx.optional(
                        "SELECT * FROM admin_captcha WHERE id=? AND attempt_id=? FOR UPDATE",
                        captchaId,
                        aid);
                Instant now = tx.now();
                if (cap == null
                    || cap.time("challenge_consumed_at") != null
                    || !cap.time("challenge_expires_at").isAfter(now)
                    || cap.number("failure_count") >= 5) throw AdminAuthFailure.unauthorized();
                if (!MessageDigest.isEqual(
                    cap.bytes("answer_mac"),
                    secrets.mac(cap.text("mac_key_id"), "CAPTCHA", supplied))) {
                  if (!c.bool("failure_counted")) {
                    tx.update(
                        "UPDATE admin_captcha SET failure_count=failure_count+1 WHERE id=?",
                        captchaId);
                    tx.update(
                        "UPDATE admin_auth_command SET failure_counted=TRUE WHERE id=?",
                        c.number("id"));
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
                tx.update(
                    "UPDATE admin_captcha SET"
                        + " challenge_consumed_at=?,proof_digest=?,proof_issued_at=?,proof_expires_at=?"
                        + " WHERE id=?",
                    now,
                    AdminSecretCodec.digest(proof),
                    now,
                    expires,
                    captchaId);
                complete(tx, c.number("id"), "CAPTCHA_PROOF", captchaId, expires, cacheRef);
                fresh.putAll(Map.of("captchaProof", proof, "expiresAt", time(expires)));
                return 1;
              });
    } catch (AdminAuthStore.CommitUnknown e) {
      if (!succeeded(readCommand(bound.number("id")))) throw AdminAuthFailure.unavailable();
      result = 1;
    }
    if (result == 401) throw AdminAuthFailure.unauthorized();
    return render(readCommand(bound.number("id")), result == 1 ? fresh : null);
  }

  public AdminSecretResult attemptResult(
      long aid, String token, String cookie, String originalRequestId) {
    Row a =
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
                a.text("status"),
                "expiresAt",
                time(a.time("expires_at"))));
    if (originalRequestId != null) {
      request(originalRequestId);
      Row c =
          store.read(
              tx ->
                  tx.optional(
                      "SELECT * FROM admin_auth_command WHERE attempt_id=? AND request_id=? AND"
                          + " namespace='LOGIN'",
                      aid,
                      key(originalRequestId)));
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
          active(tx);
          Row s = tx.optional("SELECT * FROM admin_web_session WHERE token_digest=?", digest);
          if (s == null) throw AdminAuthFailure.unauthorized();
          validSession(tx, s.number("id"), false);
          long actor = s.number("account_id");
          Row revision = tx.one("SELECT * FROM admin_authz_revision WHERE id=1");
          List<AdminPermissionSnapshot.Role> roles =
              tx
                  .rows(
                      "SELECT r.* FROM admin_role r JOIN admin_account_role ar ON ar.role_id=r.id"
                          + " WHERE ar.account_id=? AND r.status='ENABLED' ORDER BY r.role_code",
                      actor)
                  .stream()
                  .map(
                      r ->
                          new AdminPermissionSnapshot.Role(
                              Long.toString(r.number("id")),
                              r.text("role_code"),
                              r.text("display_name")))
                  .toList();
          boolean superAdmin =
              roles.stream().anyMatch(r -> r.roleCode().equals("PLATFORM_SUPER_ADMIN"));
          Row scopeRow = tx.optional("SELECT * FROM admin_account_scope WHERE account_id=?", actor);
          if (scopeRow == null) throw AdminAuthFailure.unavailable();
          List<String> cities =
              tx.rows("SELECT city_code FROM admin_scope_city WHERE account_id=?", actor).stream()
                  .map(r -> r.text("city_code"))
                  .toList();
          List<String> merchants =
              tx
                  .rows("SELECT merchant_id FROM admin_scope_merchant WHERE account_id=?", actor)
                  .stream()
                  .map(r -> Long.toString(r.number("merchant_id")))
                  .toList();
          AdminDataScope scope = new AdminDataScope(scopeRow.text("mode"), cities, merchants);
          if (superAdmin) scope = new AdminDataScope("ALL", List.of(), List.of());
          List<String> grants = new ArrayList<>();
          for (Row r :
              tx.rows(
                  "SELECT ra.action_code FROM admin_role_action ra JOIN admin_role r ON"
                      + " r.id=ra.role_id JOIN admin_account_role ar ON ar.role_id=r.id WHERE"
                      + " ar.account_id=? AND r.status='ENABLED' UNION SELECT action_code FROM"
                      + " admin_extra_grant WHERE account_id=?",
                  actor,
                  actor)) grants.add(r.text("action_code"));
          var permissions =
              new AdminPermissionSnapshot(
                  Long.toString(actor),
                  revision.text("recovery_epoch") + ":" + revision.number("revision"),
                  date(tx.now()),
                  roles,
                  scope,
                  AdminPermissionEvaluator.evaluate(
                      superAdmin, grants, AdminPermissionEvaluator.DEPLOYED_ACTIONS));
          return new AdminSessionView(
              new AdminSessionPrincipal(
                  "ADMIN_WEB",
                  Long.toString(s.number("id")),
                  Long.toString(actor),
                  s.number("generation")),
              date(s.time("idle_expires_at")),
              permissions);
        });
  }

  private AdminSecretResult sessionCommand(String requestId, String accessToken, boolean logout) {
    request(requestId);
    byte[] digest = AdminSecretCodec.digest(accessToken);
    Row observed =
        store.read(
            tx -> {
              active(tx);
              Row s = tx.optional("SELECT * FROM admin_web_session WHERE token_digest=?", digest);
              if (s == null) throw AdminAuthFailure.unauthorized();
              return s;
            });
    long cid = id();
    String namespace = logout ? "LOGOUT" : "ACTIVITY",
        scope = "S:" + observed.number("id"),
        keyId = secrets.currentKeyId();
    try {
      store.write(
          tx -> {
            active(tx);
            Row account =
                tx.one(
                    "SELECT * FROM admin_account WHERE id=? FOR UPDATE",
                    observed.number("account_id"));
            if (!"ENABLED".equals(account.text("status"))
                || account.number("session_generation") != observed.number("generation"))
              throw AdminAuthFailure.unauthorized();
            Row existing = command(tx, scope, namespace, requestId, true);
            if (existing != null && succeeded(existing)) {
              if (!logout) validSession(tx, observed.number("id"), false);
              return null;
            }
            validSession(tx, observed.number("id"), false);
            tx.update(
                "INSERT INTO"
                    + " admin_auth_command(id,scope_key,session_id,namespace,request_id,parameter_mac,mac_key_id)"
                    + " VALUES(?,?,?,?,?,?,?)",
                cid,
                key(scope),
                observed.number("id"),
                namespace,
                key(requestId),
                secrets.mac(keyId, namespace),
                keyId);
            Row s =
                tx.one(
                    "SELECT * FROM admin_web_session WHERE id=? FOR UPDATE", observed.number("id"));
            Instant now = tx.now(),
                expiry = logout ? s.time("idle_expires_at") : now.plusSeconds(1800);
            if (logout)
              tx.update(
                  "UPDATE admin_web_session SET status='REVOKED',revoked_at=? WHERE id=?",
                  now,
                  s.number("id"));
            else
              tx.update(
                  "UPDATE admin_web_session SET last_interactive_at=?,idle_expires_at=? WHERE id=?",
                  now,
                  expiry,
                  s.number("id"));
            complete(tx, cid, logout ? "LOGGED_OUT" : "ACTIVITY", s.number("id"), expiry, null);
            audit(
                tx,
                account.number("id"),
                null,
                namespace,
                s.number("id"),
                requestId,
                "ALLOWED",
                logout ? "Session logout" : "Foreground activity");
            return null;
          });
    } catch (AdminAuthStore.CommitUnknown e) {
      Row c = store.read(tx -> command(tx, scope, namespace, requestId, false));
      if (c == null || !succeeded(c)) throw AdminAuthFailure.unavailable();
    }
    Row c = store.read(tx -> command(tx, scope, namespace, requestId, false));
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
          Row rev = tx.one("SELECT * FROM admin_authz_revision WHERE id=1 FOR UPDATE");
          Row b = tx.one("SELECT * FROM admin_bootstrap WHERE id=1 FOR UPDATE");
          if (b.bool("bootstrap_complete")
              || !b.bool("maintenance_mode")
              || tx.one("SELECT COUNT(*) AS n FROM admin_account").number("n") != 0)
            throw AdminAuthFailure.conflict();
          Instant now = tx.now();
          tx.update(
              "INSERT INTO"
                  + " admin_account(id,account_display,account_lookup,display_name,password_hash,created_at,updated_at)"
                  + " VALUES(?,?,?,?,?,?,?)",
              actor,
              account,
              key(normalized),
              displayName,
              hash,
              now,
              now);
          for (int i = 0; i < 6; i++)
            tx.update(
                "INSERT INTO admin_role(id,role_code,display_name) VALUES(?,?,?)",
                roleIds[i],
                ROLES[i],
                ROLE_NAMES[i]);
          tx.update(
              "INSERT INTO admin_account_role(account_id,role_id,granted_by,granted_at)"
                  + " VALUES(?,?,?,?)",
              actor,
              roleIds[5],
              actor,
              now);
          tx.update("INSERT INTO admin_account_scope(account_id,mode) VALUES(?,'ALL')", actor);
          tx.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
          tx.update(
              "UPDATE admin_bootstrap SET"
                  + " bootstrap_complete=TRUE,maintenance_mode=FALSE,version=version+1,completed_at=?"
                  + " WHERE id=1",
              now);
          audit(tx, actor, null, "AUTH_BOOTSTRAP", actor, null, "ALLOWED", reason);
          return actor;
        });
  }

  public void beginMaintenance(String reason) {
    text(reason, 500);
    store.write(
        tx -> {
          tx.one("SELECT * FROM admin_authz_revision WHERE id=1 FOR UPDATE");
          tx.one("SELECT * FROM admin_bootstrap WHERE id=1 FOR UPDATE");
          tx.update(
              "UPDATE admin_bootstrap SET maintenance_mode=TRUE,version=version+1 WHERE id=1");
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
          tx.one("SELECT * FROM admin_authz_revision WHERE id=1 FOR UPDATE");
          Row b = tx.one("SELECT * FROM admin_bootstrap WHERE id=1 FOR UPDATE");
          if (!b.bool("maintenance_mode")) throw AdminAuthFailure.conflict();
          Row a = tx.one("SELECT * FROM admin_account WHERE id=? FOR UPDATE", actor);
          if (tx.one(
                      "SELECT COUNT(*) AS n FROM admin_account_role ar JOIN admin_role r ON"
                          + " r.id=ar.role_id WHERE ar.account_id=? AND"
                          + " r.role_code='PLATFORM_SUPER_ADMIN' AND r.status='ENABLED'",
                      actor)
                  .number("n")
              == 0) throw new AdminAuthFailure(403, "COMMON_FORBIDDEN");
          Instant now = tx.now();
          tx.update(
              "UPDATE admin_account SET"
                  + " password_hash=?,credential_version=credential_version+1,session_generation=session_generation+1,status='ENABLED',version=version+1,updated_at=?"
                  + " WHERE id=?",
              hash,
              now,
              actor);
          tx.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
          tx.update(
              "DELETE FROM admin_login_failure WHERE lookup_digest=?",
              secrets.mac(
                  secrets.currentKeyId(),
                  "ACCOUNT",
                  new String(a.bytes("account_lookup"), StandardCharsets.UTF_8)));
          audit(tx, actor, null, "AUTH_RECOVERY", actor, null, "ALLOWED", reason);
          tx.update(
              "UPDATE admin_bootstrap SET maintenance_mode=FALSE,version=version+1 WHERE id=1");
          return null;
        });
  }
}
