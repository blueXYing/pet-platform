package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.biz.apiimpl.CouponCampaignApiImpl;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort;
import com.petplatform.coupon.biz.application.NewUserEligibilityPort;
import com.petplatform.order.api.query.OrderFirstPaidFactsApi;
import com.petplatform.order.api.query.OrderFirstPaidFactsApi.UserOrderFactQuery;
import com.petplatform.order.biz.apiimpl.OrderFirstPaidFactsApiImpl;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Coupon campaign/claim runtime (contract 72, CCR-MKT-001). Default OFF. The kernel depends on
 * the admin authorization owner for ops writes and on the order-owned first-paid fact for the
 * new-user gate; missing owners fail closed 503, never open issuance.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.coupon.campaign", name = "enabled", havingValue = "true")
public class CouponCampaignConfiguration {

    @Bean
    AdminCouponAuthorizationPort.CouponPort couponCampaignAuthorization(
            ObjectProvider<AdminAuthorizationQueryApi> authorization) {
        return new CouponCampaignAuthorizationAdapter(authorization);
    }

    @Bean
    NewUserEligibilityPort couponCampaignNewUserEligibility(DataSource source) {
        return new CampaignNewUserEligibility(new OrderFirstPaidFactsApiImpl(source));
    }

    @Bean
    CouponCampaignApiImpl couponCampaignApi(
            DataSource source, ObjectProvider<SnowflakeIdGenerator> ids,
            AdminCouponAuthorizationPort.CouponPort authorization,
            NewUserEligibilityPort newUserEligibility, ObjectProvider<Clock> clock) {
        SnowflakeIdGenerator provider = ids.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("Coupon campaign kernel requires a Snowflake ID provider");
        }
        return new CouponCampaignApiImpl(source, provider, authorization, newUserEligibility,
                clock.getIfAvailable(Clock::systemUTC));
    }

    static final class CampaignNewUserEligibility implements NewUserEligibilityPort {
        private final OrderFirstPaidFactsApi firstPaid;

        CampaignNewUserEligibility(OrderFirstPaidFactsApi firstPaid) {
            this.firstPaid = Objects.requireNonNull(firstPaid);
        }

        @Override
        public boolean isNewUser(long userId, String traceId) {
            try {
                return firstPaid.firstPaidOrder(new UserOrderFactQuery(Long.toUnsignedString(userId))) == null;
            } catch (RuntimeException failure) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        "new-user eligibility facts are unavailable");
            }
        }
    }

    static final class CouponCampaignAuthorizationAdapter
            implements AdminCouponAuthorizationPort.CouponPort {
        private final ObjectProvider<AdminAuthorizationQueryApi> authorization;

        CouponCampaignAuthorizationAdapter(ObjectProvider<AdminAuthorizationQueryApi> authorization) {
            this.authorization = Objects.requireNonNull(authorization);
        }

        @Override
        public AdminCouponAuthorizationPort.Decision check(AdminCouponAuthorizationPort.Check check) {
            AdminAuthorizationQueryApi api = authorization.getIfAvailable();
            if (api == null) throw unavailable();
            AdminActionDecision decision = api.check(new AdminActionCheckQuery(
                    check.sessionId(), check.sessionGeneration(), check.operatorId(),
                    check.actionCode(),
                    new AdminResourceScope(check.resource().resourceType(), check.resource().resourceId() == null || "0".equals(check.resource().resourceId()) ? "1" : check.resource().resourceId(),
                            check.resource().merchantId(), check.resource().cityCode(),
                            check.resource().scopeVersion()),
                    check.purpose(), phase(check.phase())));
            return decision(decision);
        }

        @Override
        public AdminCouponAuthorizationPort.Decision checkCollection(
                AdminCouponAuthorizationPort.CollectionCheck check) {
            AdminAuthorizationQueryApi api = authorization.getIfAvailable();
            if (api == null) throw unavailable();
            AdminActionDecision decision = api.checkCollection(new AdminCollectionActionCheckQuery(
                    check.sessionId(), check.sessionGeneration(), check.operatorId(),
                    check.actionCode(), check.purpose(), phase(check.phase())));
            return decision(decision);
        }

        private static AdminActionCheckQuery.CheckPhase phase(AdminCouponAuthorizationPort.Phase phase) {
            return phase == AdminCouponAuthorizationPort.Phase.EXECUTE
                    ? AdminActionCheckQuery.CheckPhase.EXECUTE
                    : AdminActionCheckQuery.CheckPhase.READ_RESULT;
        }

        private static AdminCouponAuthorizationPort.Decision decision(AdminActionDecision decision) {
            if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                    || decision.authzVersion().isBlank()) {
                throw unavailable();
            }
            return new AdminCouponAuthorizationPort.Decision(
                    decision.allowed(), decision.checkedAt(), decision.authzVersion(),
                    decision.reasonCode());
        }

        private static ApiException unavailable() {
            return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
    }
}
