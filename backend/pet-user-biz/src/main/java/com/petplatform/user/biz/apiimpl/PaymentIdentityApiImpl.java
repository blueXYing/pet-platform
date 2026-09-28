package com.petplatform.user.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.PaymentIdentity;
import com.petplatform.user.api.query.PaymentIdentityApi;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** USER-owned current identity proof inside the caller's guarded local transaction. */
public final class PaymentIdentityApiImpl implements PaymentIdentityApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final UserAuthStore auth;

    public PaymentIdentityApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source = Objects.requireNonNull(source);
        this.guard = Objects.requireNonNull(guard);
        this.auth = new UserAuthStore(source);
    }

    @Override public PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId,
            String storeId, QueryContext context) {
        try {
            long id = IDS.fromApi(userId);
            IDS.fromApi(storeId);
            if (context == null || context.operatorType() != OperatorType.USER
                    || !userId.equals(context.operatorId())) throw forbidden();
            if (!valid(appId)) throw invalid();
            guard.requireHeld(storeId, source);

            List<String> accounts = auth.bookingAccountStatus(id, true);
            if (accounts.size() != 1) throw unavailable();
            if (!"ACTIVE".equals(accounts.getFirst())) {
                if ("FROZEN".equals(accounts.getFirst()) || "CANCELED".equals(accounts.getFirst()))
                    throw forbidden();
                throw unavailable();
            }

            // Read all types for this exact appId so a conflicting type cannot be ignored.
            List<UserAuthStore.PaymentIdentityRow> identities = auth.lockPaymentIdentities(id, appId);
            if (identities.size() != 1) throw unavailable();
            UserAuthStore.PaymentIdentityRow identity = identities.getFirst();
            if (!"WECHAT_MINI".equals(identity.type()) || !appId.equals(identity.appId())
                    || !valid(identity.openId())) throw unavailable();
            return new PaymentIdentity(identity.appId(), identity.openId());
        } catch (ApiException known) {
            rollback();
            throw known;
        } catch (IllegalArgumentException malformed) {
            rollback();
            throw invalid();
        } catch (RuntimeException failure) {
            rollback();
            throw unavailable();
        }
    }

    private static boolean valid(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private void rollback() {
        if (TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
            holder.setRollbackOnly();
    }
    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid payment identity input");
    }
    private static ApiException forbidden() {
        return new ApiException(CommonApiCodes.FORBIDDEN, "payment identity requires current user");
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "current payment identity unavailable");
    }
}
