package com.petplatform.user.api.query;

/**
 * Read-only cross-domain fact guard (contract 67 §5): whether the named account is currently
 * ACTIVE for write admission. Pure fact read — no session subject, no profile fields, no
 * enumeration surface beyond the stable error codes; other modules consume this instead of
 * reaching into user persistence (biz never depends on biz).
 */
public interface UserActiveStatusApi {

    /**
     * Returns silently when the named account exists and is ACTIVE; USER_FROZEN when the
     * account is FROZEN or CANCELED (the platform-wide write refusal code); unauthorized when
     * the account row is missing; 503-family when the stored status is unreadable.
     */
    void requireActiveUser(String userId);
}
