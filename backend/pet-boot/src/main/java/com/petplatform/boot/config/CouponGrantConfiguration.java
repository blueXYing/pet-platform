package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.coupon.api.command.AdminCouponGrantApi;
import com.petplatform.coupon.biz.apiimpl.AdminCouponGrantApiImpl;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CouponPort;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract-65 §4.3 coupon grant channel runtime (user ruling 2026-10-16), behind its own
 * {@code pet.coupon.grant.enabled} switch, default off — the default value stays with the
 * 35号 switch-liberation ruling owner for a unified flip (registered in the 21号 list). This
 * configuration only exists when the admin authorization owner is also assembled
 * (pet.auth.admin.enabled): the grant kernel re-proves every command against the
 * authorization owner through the boot-level adapter below, so http-less auth means no
 * kernel bean and the contract-65 grant route answers 503 fail-closed (61号
 * provider-unassembled semantics), never an open write.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "coupon.grant.enabled"},
    havingValue = "true")
public class CouponGrantConfiguration {

  @Bean
  AdminCouponGrantApi adminCouponGrantApi(
      javax.sql.DataSource source,
      ObjectProvider<AdminAuthorizationQueryApi> authorization,
      ObjectProvider<java.time.Clock> clock,
      ObjectProvider<com.petplatform.common.SnowflakeIdGenerator> ids) {
    return new AdminCouponGrantApiImpl(
        source, requireIds(ids),
        new CouponGrantAuthorizationAdapter(requireAuthorization(authorization)),
        clock.getIfAvailable(java.time.Clock::systemUTC));
  }

  private static com.petplatform.common.SnowflakeIdGenerator requireIds(
      ObjectProvider<com.petplatform.common.SnowflakeIdGenerator> ids) {
    com.petplatform.common.SnowflakeIdGenerator provider = ids.getIfAvailable();
    if (provider == null) {
      throw new IllegalStateException(
          "Coupon grant kernel requires a Snowflake ID provider (PLAT-002, pet.auth.admin.enabled)");
    }
    return provider;
  }

  private static AdminAuthorizationQueryApi requireAuthorization(
      ObjectProvider<AdminAuthorizationQueryApi> authorization) {
    AdminAuthorizationQueryApi api = authorization.getIfAvailable();
    if (api == null) {
      throw new IllegalStateException(
          "Coupon grant kernel requires the admin authorization owner (pet.auth.admin.enabled)");
    }
    return api;
  }

  /** Boot-level adapter: coupon kernel port → admin authorization owner, fail-closed. */
  static final class CouponGrantAuthorizationAdapter implements CouponPort {
    private final AdminAuthorizationQueryApi authorization;

    CouponGrantAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
      this.authorization = Objects.requireNonNull(authorization);
    }

    @Override
    public com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision check(
        com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Check check) {
      AdminActionDecision decision =
          authorization.check(
              new AdminActionCheckQuery(
                  check.sessionId(), check.sessionGeneration(), check.operatorId(),
                  check.actionCode(),
                  new AdminResourceScope(
                      check.resource().resourceType(), check.resource().resourceId(),
                      check.resource().merchantId(), check.resource().cityCode(),
                      check.resource().scopeVersion()),
                  check.purpose(),
                  check.phase()
                      == com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort
                              .Phase.EXECUTE
                  ? AdminActionCheckQuery.CheckPhase.EXECUTE
                  : AdminActionCheckQuery.CheckPhase.READ_RESULT));
      requirePresent(decision);
      return new com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    }
  }

  private static void requirePresent(AdminActionDecision decision) {
    if (decision == null
        || decision.checkedAt() == null
        || decision.authzVersion() == null
        || decision.authzVersion().isBlank()) {
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
    }
  }
}
