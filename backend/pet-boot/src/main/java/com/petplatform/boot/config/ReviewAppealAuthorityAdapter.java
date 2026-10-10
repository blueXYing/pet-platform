package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.admin.api.query.AdminSessionQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl;
import com.petplatform.review.biz.application.ReviewAppealPorts;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Contract56 appeal authority adapter (REV-002). The review kernel's {@link ReviewAppealPorts}
 * never trusts request-body actor fields: every call re-resolves the current MINIAPP or
 * ADMIN_WEB session and re-proves the OWNER link / admin action under the store guard the
 * kernel already holds (same shape as the Contract51 AfterSaleAuthorityAdapter). The MER
 * authority is assembled locally so the appeal slice does not force the merchant-order chain
 * on — boot already assembles biz apiimpl facades directly (ARCH-002).
 */
public final class ReviewAppealAuthorityAdapter implements ReviewAppealPorts {
    private static final Set<String> ACTIONS = Set.of("review.appeal.read", "review.appeal.decide");
    private static final String PURPOSE = "REVIEW_GOVERNANCE";

    private final UserAuthService users;
    private final AdminSessionQueryApi sessions;
    private final AdminAuthorizationQueryApi admins;
    private final MerchantOrderAuthorityApi merchants;

    public ReviewAppealAuthorityAdapter(DataSource source, ScheduleCapacityGuardApi guard,
            UserAuthService users, AdminSessionQueryApi sessions,
            AdminAuthorizationQueryApi admins) {
        this.users = users;
        this.sessions = sessions;
        this.admins = admins;
        this.merchants = new MerchantOrderAuthorityApiImpl(source, guard);
    }

    @Override
    public void requireReviewOwner(CommandContext context, String merchantId, String storeId) {
        miniSession(context, true);
        merchants.requireOwner(merchantId, storeId, q(context));
    }

    @Override
    public void requireReviewOwnerRead(CommandContext context, String merchantId, String storeId) {
        miniSession(context, false);
        merchants.requireOwnerRead(merchantId, storeId, q(context));
    }

    @Override
    public void requireAppealAdmin(CommandContext context, String appealId, String merchantId,
            String storeId, String action) {
        if (context == null || !ACTIONS.contains(action)) throw denied();
        Session admin = adminSession(context);
        // The mutable resource scope is read from MER under the already-held store guard.
        var scope = merchants.requireResourceScope(merchantId, storeId, q(context));
        var decision = admins.check(new AdminActionCheckQuery(admin.sessionId(),
                admin.generation(), admin.operatorId(), action,
                new AdminResourceScope("REVIEW_APPEAL", appealId, merchantId,
                        scope.cityCode(), scope.scopeVersion()), PURPOSE,
                "review.appeal.decide".equals(action)
                        ? AdminActionCheckQuery.CheckPhase.EXECUTE
                        : AdminActionCheckQuery.CheckPhase.READ_RESULT));
        if (!decision.allowed()) throw denied();
    }

    @Override
    public void requireAppealAdminList(CommandContext context) {
        if (context == null) throw denied();
        Session admin = adminSession(context);
        var entry = admins.checkCollection(new AdminCollectionActionCheckQuery(admin.sessionId(),
                admin.generation(), admin.operatorId(), "review.appeal.read", PURPOSE,
                AdminActionCheckQuery.CheckPhase.READ_RESULT));
        if (!entry.allowed()) throw denied();
    }

    private record Session(String sessionId, long generation, String operatorId) {}

    private MiniSessionView miniSession(CommandContext context, boolean requireActive) {
        if (context == null || context.operatorType() != OperatorType.USER
                || !(RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes attributes)) {
            throw denied();
        }
        MiniSessionView session;
        try {
            session = users.resolveSession(CBearerSessionFilter.bearer(attributes.getRequest()));
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Current session unavailable");
        }
        if (requireActive && !"ACTIVE".equals(session.userStatus())
                || !session.userId().equals(context.operatorId())) {
            throw denied();
        }
        return session;
    }

    private Session adminSession(CommandContext context) {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            throw denied();
        }
        try {
            var session = sessions.resolveSession(
                    AdminBearerAuthenticationFilter.bearer(attributes.getRequest()));
            var principal = session.principal();
            if (!"ADMIN_WEB".equals(principal.audience())
                    || !principal.operatorId().equals(context.operatorId())) {
                throw denied();
            }
            return new Session(principal.sessionId(), principal.sessionGeneration(),
                    principal.operatorId());
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Current admin session unavailable");
        }
    }

    private static QueryContext q(CommandContext context) {
        return new QueryContext(context.traceId(), context.operatorType(), context.operatorId());
    }

    private static ApiException denied() {
        return new ApiException(CommonApiCodes.FORBIDDEN, "Current review-appeal authority required");
    }
}
