package com.petplatform.admin.biz.application;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminDataScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.admin.biz.domain.service.AdminPermissionEvaluator;
import com.petplatform.admin.biz.infrastructure.persistence.AdminAuthStore;
import com.petplatform.admin.biz.infrastructure.persistence.mapper.AdminAuthMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Database-authoritative final check used after a domain caller has locked its resource. */
public final class AdminAuthorizationService implements AdminAuthorizationQueryApi {
  public static final String ALLOWED = "ALLOWED";
  public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
  public static final String SESSION_REVOKED = "SESSION_REVOKED";
  public static final String SESSION_CLOSED = "SESSION_CLOSED";
  public static final String SESSION_EXPIRED = "SESSION_EXPIRED";
  public static final String SESSION_GENERATION_CHANGED = "SESSION_GENERATION_CHANGED";
  public static final String OPERATOR_MISMATCH = "OPERATOR_MISMATCH";
  public static final String ACTION_NOT_GRANTED = "ACTION_NOT_GRANTED";
  public static final String RESOURCE_SCOPE_DENIED = "RESOURCE_SCOPE_DENIED";

  private final AdminAuthStore store;

  public AdminAuthorizationService(DataSource source) {
    this.store = new AdminAuthStore(Objects.requireNonNull(source));
  }

  @Override
  public AdminActionDecision check(AdminActionCheckQuery query) {
    Objects.requireNonNull(query, "query");
    return checkCurrent(
        query.sessionId(),
        query.sessionGeneration(),
        query.operatorId(),
        query.actionCode(),
        query.phase() == AdminActionCheckQuery.CheckPhase.EXECUTE,
        current -> {
          boolean scopeAllowed =
              switch (current.scope().mode()) {
                case "ALL" -> true;
                case "CITY" ->
                    query.resource().cityCode() != null
                        && current.scope().cityCodes().contains(query.resource().cityCode());
                case "MERCHANT" ->
                    query.resource().merchantId() != null
                        && current.scope().merchantIds().contains(query.resource().merchantId());
                case "NONE" -> false;
                default -> throw AdminAuthFailure.unavailable();
              };
          return scopeAllowed
              ? allowed(current.checkedAt(), current.authzVersion())
              : denied(current.checkedAt(), current.authzVersion(), RESOURCE_SCOPE_DENIED);
        });
  }

  @Override
  public AdminActionDecision checkCollection(AdminCollectionActionCheckQuery query) {
    Objects.requireNonNull(query, "query");
    if (!"merchant.application.read".equals(query.actionCode())
        || query.phase() != AdminActionCheckQuery.CheckPhase.READ_RESULT)
      throw AdminAuthFailure.invalid();
    return checkCurrent(
        query.sessionId(),
        query.sessionGeneration(),
        query.operatorId(),
        query.actionCode(),
        false,
        current -> allowed(current.checkedAt(), current.authzVersion()));
  }

  private AdminActionDecision checkCurrent(
      String sessionIdValue,
      long expectedGeneration,
      String operatorIdValue,
      String actionCode,
      boolean joinCaller,
      Authorized authorized) {
    long sessionId = id(sessionIdValue);
    long operatorId = id(operatorIdValue);
    return store.currentAuthorization(
        tx -> {
          AdminAuthMapper mapper = tx.auth();
          // Every supported RBAC mutation serializes through this singleton revision row. A
          // locking read also forces a current MySQL read when this joins an outer RR transaction.
          var revision = mapper.selectAuthzRevisionForUpdate();
          if (revision == null) throw AdminAuthFailure.unavailable();
          var bootstrap = mapper.selectBootstrapForUpdate();
          if (bootstrap == null || !bootstrap.bootstrapComplete || bootstrap.maintenanceMode)
            throw AdminAuthFailure.unavailable();
          String authzVersion = revision.recoveryEpoch + ":" + revision.revision;
          Instant checkedAt = tx.now();

          var session = mapper.selectSessionForUpdate(sessionId);
          if (session == null) return denied(checkedAt, authzVersion, SESSION_NOT_FOUND);
          if (session.accountId.longValue() != operatorId)
            return denied(checkedAt, authzVersion, OPERATOR_MISMATCH);
          if ("REVOKED".equals(session.status))
            return denied(checkedAt, authzVersion, SESSION_REVOKED);
          if (!"ACTIVE".equals(session.status))
            return denied(checkedAt, authzVersion, SESSION_CLOSED);
          if (!session.idleExpiresAt.isAfter(checkedAt))
            return denied(checkedAt, authzVersion, SESSION_EXPIRED);

          var account = mapper.selectAccountForUpdate(operatorId);
          if (account == null) throw AdminAuthFailure.unavailable();
          if (!"ENABLED".equals(account.status))
            return denied(checkedAt, authzVersion, SESSION_CLOSED);
          if (session.generation.longValue() != expectedGeneration
              || account.sessionGeneration.longValue() != session.generation.longValue())
            return denied(checkedAt, authzVersion, SESSION_GENERATION_CHANGED);

          boolean superAdmin =
              mapper.selectEnabledRolesForUpdate(operatorId).stream()
                  .anyMatch(role -> "PLATFORM_SUPER_ADMIN".equals(role.roleCode));
          List<String> grants = new ArrayList<>(mapper.selectRoleActionGrantsForUpdate(operatorId));
          grants.addAll(mapper.selectExtraActionGrantsForUpdate(operatorId));
          boolean actionAllowed =
              AdminPermissionEvaluator.evaluate(
                      superAdmin, grants, AdminPermissionEvaluator.DEPLOYED_ACTIONS)
                  .contains(actionCode);
          if (!actionAllowed) return denied(checkedAt, authzVersion, ACTION_NOT_GRANTED);

          var scopeRow = mapper.selectAccountScopeForUpdate(operatorId);
          if (scopeRow == null) throw AdminAuthFailure.unavailable();
          var scope =
              new AdminDataScope(
                  scopeRow.mode,
                  mapper.selectScopeCitiesForUpdate(operatorId),
                  mapper.selectScopeMerchantsForUpdate(operatorId).stream()
                      .map(String::valueOf)
                      .toList());
          if (superAdmin) scope = new AdminDataScope("ALL", List.of(), List.of());
          return authorized.finish(new CurrentAuthorization(checkedAt, authzVersion, scope));
        },
        joinCaller);
  }

  private static AdminActionDecision allowed(Instant at, String version) {
    return new AdminActionDecision(true, date(at), version, ALLOWED);
  }

  private static AdminActionDecision denied(Instant at, String version, String reason) {
    return new AdminActionDecision(false, date(at), version, reason);
  }

  private static OffsetDateTime date(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private static long id(String value) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0) throw AdminAuthFailure.invalid();
      return parsed;
    } catch (NumberFormatException failure) {
      throw AdminAuthFailure.invalid();
    }
  }

  @FunctionalInterface
  private interface Authorized {
    AdminActionDecision finish(CurrentAuthorization current);
  }

  private record CurrentAuthorization(
      Instant checkedAt, String authzVersion, AdminDataScope scope) {}
}
