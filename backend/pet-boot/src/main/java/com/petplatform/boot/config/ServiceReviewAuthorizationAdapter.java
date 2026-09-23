package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceReviewAuthorizationPort;
import java.util.Objects;

/**
 * Composition-root bridge for the service review decisions: the service and admin implementation
 * modules never depend on one another (same pattern as MerchantApplicationAuthorizationAdapter).
 */
public final class ServiceReviewAuthorizationAdapter
        implements ServiceReviewAuthorizationPort {
    private static final System.Logger LOG =
            System.getLogger(ServiceReviewAuthorizationAdapter.class.getName());

    private final AdminAuthorizationQueryApi authorization;

    public ServiceReviewAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
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
                    decision.allowed(),
                    decision.checkedAt(),
                    decision.authzVersion(),
                    decision.reasonCode());
        } catch (RuntimeException unavailable) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "service review authorization check failed: {0}: {1}",
                    unavailable.getClass().getName(),
                    unavailable.getMessage());
            throw new ApiException(
                    CommonApiCodes.DEPENDENCY_UNAVAILABLE, "审核权限暂时无法确认");
        }
    }
}
