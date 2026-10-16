package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi;
import com.petplatform.community.biz.apiimpl.AdminEncyclopediaApiImpl;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.EncyclopediaPort;
import com.petplatform.coupon.api.command.AdminCouponCommandApi;
import com.petplatform.coupon.api.query.AdminCouponQueryApi;
import com.petplatform.coupon.biz.apiimpl.AdminCouponApiImpl;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CouponPort;
import com.petplatform.points.api.query.AdminPointsQueryApi;
import com.petplatform.points.biz.apiimpl.AdminPointsQueryApiImpl;
import com.petplatform.points.biz.application.AdminPointsAuthorizationPort.PointsPort;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract-65 runtime (A-005 运营治理台二期): the platform encyclopedia/coupon/points kernels
 * behind {@code pet.admin.ops.http.enabled}, default off. The controller beans inject the
 * kernels directly, so http=true with a kernel missing fails bean resolution at startup — the
 * same fail-fast shape as the contract-63 trade face. Each kernel re-proves the current
 * session, action code and resource scope through its module-local authorization port, adapted
 * here from the admin authorization owner (no biz depends on another biz, mirroring the
 * contract-63 boot-level authorization adapters). The three ports share one shape but are
 * deliberately distinct types, so the adapters below qualify every nested name.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.ops.http.enabled"},
    havingValue = "true")
public class AdminOpsConfiguration {

  @Bean
  AdminEncyclopediaQueryApi adminEncyclopediaQueryApi(
      DataSource source,
      ObjectProvider<AdminAuthorizationQueryApi> authorization,
      ObjectProvider<java.time.Clock> clock,
      ObjectProvider<com.petplatform.common.SnowflakeIdGenerator> ids) {
    return new AdminEncyclopediaApiImpl(
        source, requireIds(ids),
        new EncyclopediaOpsAuthorizationAdapter(requireAuthorization(authorization)),
        clock.getIfAvailable(java.time.Clock::systemUTC));
  }

  @Bean
  AdminEncyclopediaCommandApi adminEncyclopediaCommandApi(
      AdminEncyclopediaQueryApi queryKernel) {
    // One kernel serves both faces; the query bean already proved the full dependency set.
    return (AdminEncyclopediaCommandApi) queryKernel;
  }

  @Bean
  AdminCouponQueryApi adminCouponQueryApi(
      DataSource source,
      ObjectProvider<AdminAuthorizationQueryApi> authorization,
      ObjectProvider<java.time.Clock> clock,
      ObjectProvider<com.petplatform.common.SnowflakeIdGenerator> ids) {
    return new AdminCouponApiImpl(
        source, requireIds(ids),
        new CouponOpsAuthorizationAdapter(requireAuthorization(authorization)),
        clock.getIfAvailable(java.time.Clock::systemUTC));
  }

  @Bean
  AdminCouponCommandApi adminCouponCommandApi(AdminCouponQueryApi couponKernel) {
    return (AdminCouponCommandApi) couponKernel;
  }

  @Bean
  AdminPointsQueryApi adminPointsQueryApi(
      DataSource source, ObjectProvider<AdminAuthorizationQueryApi> authorization) {
    return new AdminPointsQueryApiImpl(
        source, new PointsOpsAuthorizationAdapter(requireAuthorization(authorization)));
  }

  private static com.petplatform.common.SnowflakeIdGenerator requireIds(
      ObjectProvider<com.petplatform.common.SnowflakeIdGenerator> ids) {
    com.petplatform.common.SnowflakeIdGenerator provider = ids.getIfAvailable();
    if (provider == null) {
      throw new IllegalStateException(
          "Ops kernels require a Snowflake ID provider (PLAT-002, pet.auth.admin.enabled)");
    }
    return provider;
  }

  private static AdminAuthorizationQueryApi requireAuthorization(
      ObjectProvider<AdminAuthorizationQueryApi> authorization) {
    AdminAuthorizationQueryApi api = authorization.getIfAvailable();
    if (api == null) {
      throw new IllegalStateException(
          "Ops kernels require the admin authorization owner (pet.auth.admin.enabled)");
    }
    return api;
  }

  private static AdminResourceScope scope(
      String resourceType, String resourceId, String merchantId, String cityCode,
      String scopeVersion) {
    return new AdminResourceScope(resourceType, resourceId, merchantId, cityCode, scopeVersion);
  }

  private static void requirePresent(AdminActionDecision decision) {
    if (decision == null
        || decision.checkedAt() == null
        || decision.authzVersion() == null
        || decision.authzVersion().isBlank()) {
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
    }
  }

  private static AdminActionCheckQuery.CheckPhase phase(
      com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Phase phase) {
    return phase
            == com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Phase.EXECUTE
        ? AdminActionCheckQuery.CheckPhase.EXECUTE
        : AdminActionCheckQuery.CheckPhase.READ_RESULT;
  }

  private static AdminActionCheckQuery.CheckPhase couponPhase(
      com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Phase phase) {
    return phase == com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Phase.EXECUTE
        ? AdminActionCheckQuery.CheckPhase.EXECUTE
        : AdminActionCheckQuery.CheckPhase.READ_RESULT;
  }

  private static AdminActionCheckQuery.CheckPhase pointsPhase(
      com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Phase phase) {
    return phase == com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Phase.EXECUTE
        ? AdminActionCheckQuery.CheckPhase.EXECUTE
        : AdminActionCheckQuery.CheckPhase.READ_RESULT;
  }

  /** Boot-level adapter: community kernel port → admin authorization owner, fail-closed. */
  static final class EncyclopediaOpsAuthorizationAdapter implements EncyclopediaPort {
    private final AdminAuthorizationQueryApi authorization;

    EncyclopediaOpsAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
      this.authorization = Objects.requireNonNull(authorization);
    }

    @Override
    public com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Decision
            check(
                com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Check
                        check) {
      AdminActionDecision decision =
          authorization.check(
              new AdminActionCheckQuery(
                  check.sessionId(), check.sessionGeneration(), check.operatorId(),
                  check.actionCode(),
                  scope(check.resource().resourceType(), check.resource().resourceId(),
                      check.resource().merchantId(), check.resource().cityCode(),
                      check.resource().scopeVersion()),
                  check.purpose(),
                  phase(check.phase())));
      requirePresent(decision);
      return new com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort
              .Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    }

    @Override
    public com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Decision
            checkCollection(
                com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort
                        .CollectionCheck check) {
      AdminActionDecision decision =
          authorization.checkCollection(
              new AdminCollectionActionCheckQuery(
                  check.sessionId(), check.sessionGeneration(), check.operatorId(),
                  check.actionCode(), check.purpose(), phase(check.phase())));
      requirePresent(decision);
      return new com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort
              .Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    }
  }

  /** Boot-level adapter: coupon kernel port → admin authorization owner, fail-closed. */
  static final class CouponOpsAuthorizationAdapter implements CouponPort {
    private final AdminAuthorizationQueryApi authorization;

    CouponOpsAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
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
                  scope(check.resource().resourceType(), check.resource().resourceId(),
                      check.resource().merchantId(), check.resource().cityCode(),
                      check.resource().scopeVersion()),
                  check.purpose(),
                  couponPhase(check.phase())));
      requirePresent(decision);
      return new com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    }

    @Override
    public com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision
            checkCollection(
                com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CollectionCheck
                        check) {
      AdminActionDecision decision =
          authorization.checkCollection(
              new AdminCollectionActionCheckQuery(
                  check.sessionId(), check.sessionGeneration(), check.operatorId(),
                  check.actionCode(), check.purpose(), couponPhase(check.phase())));
      requirePresent(decision);
      return new com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    }
  }

  /** Boot-level adapter: points kernel port → admin authorization owner, fail-closed. */
  static final class PointsOpsAuthorizationAdapter implements PointsPort {
    private final AdminAuthorizationQueryApi authorization;

    PointsOpsAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
      this.authorization = Objects.requireNonNull(authorization);
    }

    @Override
    public com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Decision check(
        com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Check check) {
      AdminActionDecision decision =
          authorization.check(
              new AdminActionCheckQuery(
                  check.sessionId(), check.sessionGeneration(), check.operatorId(),
                  check.actionCode(),
                  scope(check.resource().resourceType(), check.resource().resourceId(),
                      check.resource().merchantId(), check.resource().cityCode(),
                      check.resource().scopeVersion()),
                  check.purpose(),
                  pointsPhase(check.phase())));
      requirePresent(decision);
      return new com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    }

    @Override
    public com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Decision
            checkCollection(
                com.petplatform.points.biz.application.AdminPointsAuthorizationPort.CollectionCheck
                        check) {
      AdminActionDecision decision =
          authorization.checkCollection(
              new AdminCollectionActionCheckQuery(
                  check.sessionId(), check.sessionGeneration(), check.operatorId(),
                  check.actionCode(), check.purpose(), pointsPhase(check.phase())));
      requirePresent(decision);
      return new com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    }
  }
}
