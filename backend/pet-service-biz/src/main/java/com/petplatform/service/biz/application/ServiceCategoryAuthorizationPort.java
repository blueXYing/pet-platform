package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.service.api.error.ServiceCategoryApiCodes;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Service-side port for the authoritative admin authorization kernel. The service and admin
 * implementation modules do not depend on one another; the boot adapter maps this port to
 * {@code AdminAuthorizationQueryApi}. Every list read proves {@code service.category.read}
 * through {@link #checkCollection}, and every single-category write proves
 * {@code service.category.manage} through {@link #check} inside the category write transaction.
 */
public interface ServiceCategoryAuthorizationPort {

    Decision check(Check check);

    Decision checkCollection(CollectionCheck check);

    /**
     * Trusted session reference plus the server-bound category facts used for the final check.
     * {@code resourceId}/{@code scopeVersion} are never taken from the request body.
     */
    record Check(
            String sessionId,
            long sessionGeneration,
            String operatorId,
            String actionCode,
            String resourceId,
            long scopeVersion,
            String purpose,
            Phase phase) {}

    record CollectionCheck(
            String sessionId,
            long sessionGeneration,
            String operatorId,
            String actionCode,
            String purpose,
            Phase phase) {}

    enum Phase {
        EXECUTE,
        READ_RESULT
    }

    record Decision(
            boolean allowed, OffsetDateTime checkedAt, String authzVersion, String reasonCode) {}

    /**
     * Fails closed on an invalid or denied decision. Denials are mapped to the same public
     * statuses as the service review kernel: revoked/missing session -> 401, action not granted
     * -> 403, resource scope denied -> 404, anything unknown -> 503.
     */
    static void enforce(Decision decision) {
        if (decision == null
                || decision.checkedAt() == null
                || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(
                    CommonApiCodes.DEPENDENCY_UNAVAILABLE, "分类权限判定无效");
        }
        if (decision.allowed()) return;
        String reason = decision.reasonCode();
        if (reason == null || reason.isBlank()) {
            throw new ApiException(
                    CommonApiCodes.DEPENDENCY_UNAVAILABLE, "分类权限拒绝原因缺失");
        }
        if ("RESOURCE_SCOPE_DENIED".equals(reason)) {
            throw new ApiException(
                    ServiceCategoryApiCodes.CATEGORY_NOT_FOUND, "服务分类不存在");
        }
        if ("ACTION_NOT_GRANTED".equals(reason)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
        }
        if (Set.of("SESSION_NOT_FOUND", "SESSION_REVOKED", "SESSION_CLOSED", "SESSION_EXPIRED",
                "SESSION_GENERATION_CHANGED", "OPERATOR_MISMATCH").contains(reason)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        throw new ApiException(
                CommonApiCodes.DEPENDENCY_UNAVAILABLE, "分类权限拒绝原因不受支持");
    }
}
