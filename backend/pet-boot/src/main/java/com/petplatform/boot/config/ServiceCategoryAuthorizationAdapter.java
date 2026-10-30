package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort;
import java.util.Objects;

/**
 * Composition-root bridge for the admin category dictionary: pet-service-biz owns the port and
 * never depends on pet-admin-biz; this adapter maps it to the authoritative admin kernel. Runtime
 * failures while reading authorization facts fail closed as 503.
 */
public final class ServiceCategoryAuthorizationAdapter
        implements ServiceCategoryAuthorizationPort {
    private static final System.Logger LOG =
            System.getLogger(ServiceCategoryAuthorizationAdapter.class.getName());

    private final AdminAuthorizationQueryApi authorization;

    public ServiceCategoryAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
        this.authorization =
                Objects.requireNonNull(authorization, "authorization is required");
    }

    @Override
    public Decision check(Check check) {
        AdminActionDecision decision;
        try {
            decision =
                    authorization.check(
                            new AdminActionCheckQuery(
                                    check.sessionId(),
                                    check.sessionGeneration(),
                                    check.operatorId(),
                                    check.actionCode(),
                                    new AdminResourceScope(
                                            "SERVICE_CATEGORY",
                                            check.resourceId(),
                                            null,
                                            null,
                                            Long.toString(check.scopeVersion())),
                                    check.purpose(),
                                    AdminActionCheckQuery.CheckPhase.valueOf(
                                            check.phase().name())));
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
        return decision(decision);
    }

    @Override
    public Decision checkCollection(CollectionCheck check) {
        AdminActionDecision decision;
        try {
            decision =
                    authorization.checkCollection(
                            new AdminCollectionActionCheckQuery(
                                    check.sessionId(),
                                    check.sessionGeneration(),
                                    check.operatorId(),
                                    check.actionCode(),
                                    check.purpose(),
                                    AdminActionCheckQuery.CheckPhase.valueOf(
                                            check.phase().name())));
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
        return decision(decision);
    }

    private static Decision decision(AdminActionDecision decision) {
        if (decision == null) {
            throw new ApiException(
                    CommonApiCodes.DEPENDENCY_UNAVAILABLE, "分类权限判定无效");
        }
        return new Decision(
                decision.allowed(),
                decision.checkedAt(),
                decision.authzVersion(),
                decision.reasonCode());
    }

    private static ApiException unavailable(RuntimeException failure) {
        LOG.log(
                System.Logger.Level.WARNING,
                "service category authorization check failed: {0}: {1}",
                failure.getClass().getName(),
                failure.getMessage());
        return new ApiException(
                CommonApiCodes.DEPENDENCY_UNAVAILABLE, "分类权限暂时无法确认");
    }
}
