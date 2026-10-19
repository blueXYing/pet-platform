package com.petplatform.user.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.user.api.query.UserActiveStatusApi;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import java.util.List;
import java.util.Objects;

/**
 * Read-only user_account status probe (contract-65 §4.3 grant hardening, user ruling
 * 2026-10-19). One indexed primary-key read, no lock, no write; any account status counts
 * as existing — usability policy stays with the caller. Requires an authenticated
 * PLATFORM_OPERATOR query context: the probe exists for cross-domain owners, not for
 * self-service enumeration. A malformed user id answers 400; unknown ids answer null so
 * the caller owns the not-found mapping.
 */
public final class UserActiveStatusApiImpl implements UserActiveStatusApi {

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final UserAuthStore auth;

    public UserActiveStatusApiImpl(javax.sql.DataSource source) {
        this.auth = new UserAuthStore(Objects.requireNonNull(source, "dataSource is required"));
    }

    @Override
    public String currentStatus(String userId, QueryContext context) {
        long id = id(userId);
        if (context == null || context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN,
                    "user status probe belongs to platform operators");
        }
        List<String> rows = auth.bookingAccountStatus(id, false);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static long id(String value) {
        if (value == null) throw invalid();
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid user id");
    }
}
