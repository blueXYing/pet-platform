package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.api.query.AdminOrderQueryApi;
import com.petplatform.order.biz.apiimpl.AdminOrderQueryApiImpl;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort.OrderAuthorizationPort;
import com.petplatform.refund.api.query.AdminRefundQueryApi;
import com.petplatform.refund.biz.apiimpl.AdminRefundQueryApiImpl;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.RefundAuthorizationPort;
import com.petplatform.verification.api.query.AdminVerificationQueryApi;
import com.petplatform.verification.biz.apiimpl.AdminVerificationQueryApiImpl;
import com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.VerificationAuthorizationPort;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract-63 runtime (ADM-002/A-003): the three platform trade read kernels behind
 * {@code pet.admin.trade.http.enabled}, default off. The controller beans inject the kernels
 * directly, so http=true with a kernel missing fails bean resolution at startup — the same
 * fail-fast shape as the points-task slice. Each kernel re-proves the current session, action
 * code and resource scope through its module-local authorization port, adapted here from the
 * admin authorization owner (no biz depends on another biz, mirroring the merchant
 * application slice's boot-level authorization adapter). The three ports share one shape but
 * are deliberately distinct types, so the adapters below qualify every nested name.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.trade.http.enabled"},
    havingValue = "true")
public class AdminTradeConfiguration {

  @Bean
  AdminOrderQueryApi adminOrderQueryApi(
      DataSource source,
      ObjectProvider<AdminAuthorizationQueryApi> authorization,
      ObjectProvider<java.time.Clock> clock) {
    return new AdminOrderQueryApiImpl(
        source,
        new OrderTradeAuthorizationAdapter(requireAuthorization(authorization)),
        clock.getIfAvailable(java.time.Clock::systemUTC));
  }

  @Bean
  AdminRefundQueryApi adminRefundQueryApi(
      DataSource source, ObjectProvider<AdminAuthorizationQueryApi> authorization) {
    return new AdminRefundQueryApiImpl(
        source, new RefundTradeAuthorizationAdapter(requireAuthorization(authorization)));
  }

  @Bean
  AdminVerificationQueryApi adminVerificationQueryApi(
      DataSource source, ObjectProvider<AdminAuthorizationQueryApi> authorization) {
    return new AdminVerificationQueryApiImpl(
        source, new VerificationTradeAuthorizationAdapter(requireAuthorization(authorization)));
  }

  private static AdminAuthorizationQueryApi requireAuthorization(
      ObjectProvider<AdminAuthorizationQueryApi> authorization) {
    AdminAuthorizationQueryApi api = authorization.getIfAvailable();
    if (api == null) {
      throw new IllegalStateException(
          "Trade query kernels require the admin authorization owner (pet.auth.admin.enabled)");
    }
    return api;
  }

  private static com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Decision decide(AdminActionDecision decision) {
    requirePresent(decision);
    return new com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Decision(
        decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
  }

  private static com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision refundDecided(AdminActionDecision decision) {
    requirePresent(decision);
    return new com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision(
        decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
  }

  private static com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision verificationDecided(
      AdminActionDecision decision) {
    requirePresent(decision);
    return new com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision(
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
      String resourceType, String resourceId, String merchantId, String cityCode,
      String scopeVersion) {
    return new AdminResourceScope(resourceType, resourceId, merchantId, cityCode, scopeVersion);
  }

  /** Boot-level adapter: order kernel port → admin authorization owner, fail-closed. */
  static final class OrderTradeAuthorizationAdapter implements OrderAuthorizationPort {
    private final AdminAuthorizationQueryApi authorization;

    OrderTradeAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
      this.authorization = Objects.requireNonNull(authorization);
    }

    private static AdminActionCheckQuery.CheckPhase phase(
        com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Phase phase) {
      return phase == com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Phase.EXECUTE
          ? AdminActionCheckQuery.CheckPhase.EXECUTE
          : AdminActionCheckQuery.CheckPhase.READ_RESULT;
    }

    @Override
    public com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Decision check(
        com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Check check) {
      return decide(
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
    public com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Decision checkCollection(
        com.petplatform.order.biz.application.AdminTradeAuthorizationPort.CollectionCheck check) {
      return decide(
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

  /** Boot-level adapter: refund kernel port → admin authorization owner, fail-closed. */
  static final class RefundTradeAuthorizationAdapter implements RefundAuthorizationPort {
    private final AdminAuthorizationQueryApi authorization;

    RefundTradeAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
      this.authorization = Objects.requireNonNull(authorization);
    }

    private static AdminActionCheckQuery.CheckPhase phase(
        com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Phase phase) {
      return phase == com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Phase.EXECUTE
          ? AdminActionCheckQuery.CheckPhase.EXECUTE
          : AdminActionCheckQuery.CheckPhase.READ_RESULT;
    }

    @Override
    public com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision check(
        com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Check check) {
      return refundDecided(
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
    public com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision checkCollection(
        com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.CollectionCheck check) {
      return refundDecided(
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

  /** Boot-level adapter: verification kernel port → admin authorization owner, fail-closed. */
  static final class VerificationTradeAuthorizationAdapter implements VerificationAuthorizationPort {
    private final AdminAuthorizationQueryApi authorization;

    VerificationTradeAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
      this.authorization = Objects.requireNonNull(authorization);
    }

    private static AdminActionCheckQuery.CheckPhase phase(
        com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Phase phase) {
      return phase == com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Phase.EXECUTE
          ? AdminActionCheckQuery.CheckPhase.EXECUTE
          : AdminActionCheckQuery.CheckPhase.READ_RESULT;
    }

    @Override
    public com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision check(
        com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Check check) {
      return verificationDecided(
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
    public com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.Decision checkCollection(
        com.petplatform.verification.biz.application.AdminTradeAuthorizationPort.CollectionCheck check) {
      return verificationDecided(
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
