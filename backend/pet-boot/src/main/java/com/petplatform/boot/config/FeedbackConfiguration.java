package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.customerservice.biz.application.FeedbackOpsAuthorizationPort;
import com.petplatform.customerservice.biz.application.FeedbackTicketService;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.user.api.query.UserActiveStatusApi;
import com.petplatform.user.biz.apiimpl.UserActiveStatusApiImpl;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 67 feedback-ticket slice (PRD 5.1.28 complaint class; CCR-CS-FEEDBACK-001 plus the
 * 2026-10-19 extension): assembled only when the C session slice AND this slice's switch are
 * both on. The kernel consumes the user-owned active-status fact port, the thirdparty-owned
 * private-asset port (attachment re-proof, 31号 FEEDBACK_ATTACHMENT) and the admin-authorization
 * owner (ops-face re-verification) through fail-closed adapters — a missing owner bean makes
 * the affected capability answer 503, never silently allow. Default off in production until
 * the deployment gate opens it (pet.feedback.http.enabled).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet",
        name = {"auth.c.enabled", "feedback.http.enabled"}, havingValue = "true")
public class FeedbackConfiguration {

    @Bean
    UserActiveStatusApi userActiveStatus(DataSource source) {
        return new UserActiveStatusApiImpl(source);
    }

    @Bean
    FeedbackTicketService feedbackTicketService(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            UserActiveStatusApi activeUsers,
            ObjectProvider<PrivateAssetApi> assets,
            ObjectProvider<AdminAuthorizationQueryApi> authorization) {
        // The store stays module-internal (ARCH-002): boot never names another module's
        // persistence package.
        return new FeedbackTicketService(source, activeUsers, idProvider(ids),
                query -> {
                    PrivateAssetApi api = assets.getIfAvailable();
                    if (api == null) {
                        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                                "私有材料服务暂不可用，附件暂不可用");
                    }
                    return api.resolveOwned(query);
                },
                new FeedbackOpsAuthorizationAdapter(authorization.getIfAvailable()));
    }

    private static SnowflakeIdGenerator idProvider(ObjectProvider<SnowflakeIdGenerator> ids) {
        return java.util.Objects.requireNonNull(ids.getIfAvailable(),
                "PLAT-002 ID provider required before feedback writes can start");
    }

    /** Boot-level adapter: feedback ops port → admin authorization owner, fail-closed. */
    static final class FeedbackOpsAuthorizationAdapter implements FeedbackOpsAuthorizationPort.FeedbackPort {
        private final AdminAuthorizationQueryApi authorization;

        FeedbackOpsAuthorizationAdapter(AdminAuthorizationQueryApi authorization) {
            this.authorization = authorization;
        }

        @Override
        public FeedbackOpsAuthorizationPort.Decision check(FeedbackOpsAuthorizationPort.Check check) {
            AdminAuthorizationQueryApi owner = requireOwner();
            AdminActionDecision decision = owner.check(new AdminActionCheckQuery(
                    check.sessionId(), check.sessionGeneration(), check.operatorId(),
                    check.actionCode(),
                    new AdminResourceScope(check.resource().resourceType(),
                            check.resource().resourceId(), check.resource().merchantId(),
                            check.resource().cityCode(), check.resource().scopeVersion()),
                    check.purpose(),
                    check.phase() == FeedbackOpsAuthorizationPort.Phase.EXECUTE
                            ? AdminActionCheckQuery.CheckPhase.EXECUTE
                            : AdminActionCheckQuery.CheckPhase.READ_RESULT));
            requirePresent(decision);
            return new FeedbackOpsAuthorizationPort.Decision(decision.allowed(),
                    decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
        }

        @Override
        public FeedbackOpsAuthorizationPort.Decision checkCollection(
                FeedbackOpsAuthorizationPort.CollectionCheck check) {
            AdminAuthorizationQueryApi owner = requireOwner();
            AdminActionDecision decision = owner.checkCollection(new AdminCollectionActionCheckQuery(
                    check.sessionId(), check.sessionGeneration(), check.operatorId(),
                    check.actionCode(), check.purpose(),
                    check.phase() == FeedbackOpsAuthorizationPort.Phase.EXECUTE
                            ? AdminActionCheckQuery.CheckPhase.EXECUTE
                            : AdminActionCheckQuery.CheckPhase.READ_RESULT));
            requirePresent(decision);
            return new FeedbackOpsAuthorizationPort.Decision(decision.allowed(),
                    decision.checkedAt(), decision.authzVersion(), decision.reasonCode());
        }

        private AdminAuthorizationQueryApi requireOwner() {
            if (authorization == null) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
            }
            return authorization;
        }

        private static void requirePresent(AdminActionDecision decision) {
            if (decision == null || decision.checkedAt() == null
                    || decision.authzVersion() == null || decision.authzVersion().isBlank()) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
            }
        }
    }
}
