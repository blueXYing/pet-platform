package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.application.ApplicationFinalAuthorizationPort;
import java.util.Objects;

/**
 * Composition-root bridge: merchant and admin implementation modules never depend on one another.
 */
public final class MerchantApplicationAuthorizationAdapter
    implements ApplicationFinalAuthorizationPort {
  private final AdminAuthorizationQueryApi authorization;

  public MerchantApplicationAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
    this.authorization = Objects.requireNonNull(authorization);
  }

  @Override
  public Decision check(Check check) {
    try {
      Resource resource = check.resource();
      var decision =
          authorization.check(
              new AdminActionCheckQuery(
                  check.sessionId(),
                  check.sessionGeneration(),
                  check.operatorId(),
                  check.actionCode(),
                  new AdminResourceScope(
                      resource.resourceType(),
                      resource.resourceId(),
                      resource.merchantId(),
                      resource.cityCode(),
                      resource.scopeVersion()),
                  check.purpose(),
                  AdminActionCheckQuery.CheckPhase.valueOf(check.phase().name())));
      return new Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    } catch (RuntimeException unavailable) {
      // No account/token/resource input or persistence failure text crosses the module boundary.
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "审核权限暂时无法确认");
    }
  }

  @Override
  public Decision checkCollection(CollectionCheck check) {
    try {
      var decision =
          authorization.checkCollection(
              new AdminCollectionActionCheckQuery(
                  check.sessionId(),
                  check.sessionGeneration(),
                  check.operatorId(),
                  check.actionCode(),
                  check.purpose(),
                  AdminActionCheckQuery.CheckPhase.valueOf(check.phase().name())));
      return new Decision(
          decision.allowed(), decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
    } catch (RuntimeException unavailable) {
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "审核权限暂时无法确认");
    }
  }
}
