package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi;
import com.petplatform.merchant.biz.apiimpl.AdminMerchantGovernanceQueryApiImpl;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.MerchantGovernanceAuthorizationPort;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi;
import com.petplatform.schedule.biz.apiimpl.AdminScheduleQueryApiImpl;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.ScheduleGovernanceAuthorizationPort;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract-64 runtime (ADM-001 gap slice / A-002 phase 2): the merchant and schedule
 * governance read kernels behind {@code pet.admin.governance.http.enabled}, default ON per the 2026-10-16 ruling (doc 35); it assembles only with pet.auth.admin.enabled. The
 * controller beans inject the kernels directly, so http=true with a kernel missing fails bean
 * resolution at startup — the same fail-fast shape as the contract-63 trade slice. Each
 * kernel re-proves the current session, action code and resource scope through its
 * module-local authorization port, adapted here from the admin authorization owner (no biz
 * depends on another biz). The two ports share one shape but are deliberately distinct types.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.governance.http.enabled"},
    havingValue = "true")
public class AdminGovernanceConfiguration {

  @Bean
  AdminMerchantGovernanceQueryApi adminMerchantGovernanceQueryApi(
      DataSource source, ObjectProvider<AdminAuthorizationQueryApi> authorization) {
    return new AdminMerchantGovernanceQueryApiImpl(
        source, new MerchantGovernanceAuthorizationAdapter(requireAuthorization(authorization)));
  }

  @Bean
  AdminScheduleQueryApi adminScheduleQueryApi(
      DataSource source,
      ObjectProvider<AdminAuthorizationQueryApi> authorization,
      ObjectProvider<java.time.Clock> clock) {
    return new AdminScheduleQueryApiImpl(
        source,
        new ScheduleGovernanceAuthorizationAdapter(requireAuthorization(authorization)),
        clock.getIfAvailable(java.time.Clock::systemUTC));
  }

  private static AdminAuthorizationQueryApi requireAuthorization(
      ObjectProvider<AdminAuthorizationQueryApi> authorization) {
    AdminAuthorizationQueryApi api = authorization.getIfAvailable();
    if (api == null) {
      throw new IllegalStateException(
          "Governance query kernels require the admin authorization owner (pet.auth.admin.enabled)");
    }
    return api;
  }

  private static com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Decision merchantDecided(
      AdminActionDecision decision) {
    requirePresent(decision);
    return new com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Decision(
        decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
  }

  private static com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Decision scheduleDecided(
      AdminActionDecision decision) {
    requirePresent(decision);
    return new com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Decision(
        decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
  }

  private static void requirePresent(AdminActionDecision decision) {
    if (decision == null
        || decision.checkedAt() == null
        || decision.authzVersion() == null
        || decision.authzVersion().isBlank()) {
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
    }
  }

  private static AdminResourceScope scope(
      String resourceType,
      String resourceId,
      String merchantId,
      String cityCode,
      String scopeVersion) {
    return new AdminResourceScope(resourceType, resourceId, merchantId, cityCode, scopeVersion);
  }

  /** Boot-level adapter: merchant kernel port → admin authorization owner, fail-closed. */
  static final class MerchantGovernanceAuthorizationAdapter
      implements MerchantGovernanceAuthorizationPort {
    private final AdminAuthorizationQueryApi authorization;

    MerchantGovernanceAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
      this.authorization = Objects.requireNonNull(authorization);
    }

    private static AdminActionCheckQuery.CheckPhase phase(
        com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Phase phase) {
      return phase
              == com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Phase
                  .EXECUTE
          ? AdminActionCheckQuery.CheckPhase.EXECUTE
          : AdminActionCheckQuery.CheckPhase.READ_RESULT;
    }

    @Override
    public com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Decision check(
        com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Check check) {
      return merchantDecided(
          authorization.check(
              new AdminActionCheckQuery(
                  check.sessionId(),
                  check.sessionGeneration(),
                  check.operatorId(),
                  check.actionCode(),
                  scope(
                      check.resource().resourceType(),
                      check.resource().resourceId(),
                      check.resource().merchantId(),
                      check.resource().cityCode(),
                      check.resource().scopeVersion()),
                  check.purpose(),
                  phase(check.phase()))));
    }

    @Override
    public com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Decision
        checkCollection(
            com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.CollectionCheck
                    check) {
      return merchantDecided(
          authorization.checkCollection(
              new AdminCollectionActionCheckQuery(
                  check.sessionId(),
                  check.sessionGeneration(),
                  check.operatorId(),
                  check.actionCode(),
                  check.purpose(),
                  phase(check.phase()))));
    }
  }

  /** Boot-level adapter: schedule kernel port → admin authorization owner, fail-closed. */
  static final class ScheduleGovernanceAuthorizationAdapter
      implements ScheduleGovernanceAuthorizationPort {
    private final AdminAuthorizationQueryApi authorization;

    ScheduleGovernanceAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
      this.authorization = Objects.requireNonNull(authorization);
    }

    private static AdminActionCheckQuery.CheckPhase phase(
        com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Phase phase) {
      return phase
              == com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Phase
                  .EXECUTE
          ? AdminActionCheckQuery.CheckPhase.EXECUTE
          : AdminActionCheckQuery.CheckPhase.READ_RESULT;
    }

    @Override
    public com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Decision check(
        com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Check check) {
      return scheduleDecided(
          authorization.check(
              new AdminActionCheckQuery(
                  check.sessionId(),
                  check.sessionGeneration(),
                  check.operatorId(),
                  check.actionCode(),
                  scope(
                      check.resource().resourceType(),
                      check.resource().resourceId(),
                      check.resource().merchantId(),
                      check.resource().cityCode(),
                      check.resource().scopeVersion()),
                  check.purpose(),
                  phase(check.phase()))));
    }

    @Override
    public com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Decision
        checkCollection(
            com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.CollectionCheck
                    check) {
      return scheduleDecided(
          authorization.checkCollection(
              new AdminCollectionActionCheckQuery(
                  check.sessionId(),
                  check.sessionGeneration(),
                  check.operatorId(),
                  check.actionCode(),
                  check.purpose(),
                  phase(check.phase()))));
    }
  }
}
