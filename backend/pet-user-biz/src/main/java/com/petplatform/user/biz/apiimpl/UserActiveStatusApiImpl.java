package com.petplatform.user.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.user.api.query.UserActiveStatusApi;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Local implementation of the user-owned active-status fact guard (contract 67 §5). The same
 * account status read the booking facts port uses; non-ACTIVE maps to the platform-wide
 * USER_FROZEN write-refusal code, unknown rows to unauthorized, unreadable words to 503.
 */
public final class UserActiveStatusApiImpl implements UserActiveStatusApi {
    private static final com.petplatform.common.DecimalPublicIdCodec IDS = new com.petplatform.common.DecimalPublicIdCodec();
    private final UserAuthStore auth;

    public UserActiveStatusApiImpl(DataSource dataSource) {
        this.auth = new UserAuthStore(Objects.requireNonNull(dataSource, "dataSource is required"));
    }

    @Override
    public void requireActiveUser(String userId) {
        long id;
        try {
            id = Long.parseLong(userId);
        } catch (NumberFormatException malformed) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "user unavailable");
        }
        if (id <= 0) throw new ApiException(CommonApiCodes.UNAUTHORIZED, "user unavailable");
        try {
            var rows = auth.bookingAccountStatus(id, false);
            if (rows.isEmpty()) throw new ApiException(CommonApiCodes.UNAUTHORIZED, "user unavailable");
            String status = rows.getFirst();
            if (!Set.of("ACTIVE", "FROZEN", "CANCELED").contains(status)) throw unavailable();
            if (!"ACTIVE".equals(status)) {
                throw new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
            }
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failed) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "服务暂不可用，请稍后重试");
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
