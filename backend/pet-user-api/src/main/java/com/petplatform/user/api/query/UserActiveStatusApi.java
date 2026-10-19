package com.petplatform.user.api.query;

import com.petplatform.common.QueryContext;

/**
 * Read-only user_account status probe for cross-domain owners (contract-65 §4.3 grant
 * hardening, user ruling 2026-10-19 "继续扩展CCR"). The caller proves a recipient exists
 * before writing an owned row — the coupon grant face uses it to eliminate ownerless
 * coupons. Existence means the account row exists in any status (ACTIVE/FROZEN/CANCELED);
 * no usability policy lives here. Pure read: no lock, no write, no PII beyond the status
 * word. Lexical Snowflake validation stays with the caller; a malformed id or a context
 * that is not an authenticated platform operator answers 400/401/403 by the common rules.
 */
public interface UserActiveStatusApi {

    /**
     * @return the account status word (ACTIVE/FROZEN/CANCELED), or null when no account
     *         row exists for the id — the caller owns the not-found mapping.
     */
    String currentStatus(String userId, QueryContext context);

    /** Existence convenience: true only when {@link #currentStatus} finds the account row. */
    default boolean exists(String userId, QueryContext context) {
        return currentStatus(userId, context) != null;
    }
}
