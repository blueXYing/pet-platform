package com.petplatform.merchant.biz.application;

/**
 * Contract 54 §1/§7 confirm-and-list channel port: compares invitation phones against the
 * session user's verified login phone (the S9 WeChat getPhoneNumber account fact). The confirm
 * and detail channels stay boolean (their shape is "does this invitation target the session");
 * the §7 list (user ruling 2026-10-07) additionally reads the session phone once as the
 * purpose-bound in-memory seek key for the indexed invitation list — implementations must fail
 * closed and the value must never be persisted, logged, audited or projected.
 */
public interface StaffLoginPhonePort {
    boolean matchesSessionUserPhone(long userId, String phone);

    /**
     * The session account's verified 11-digit phone, or null when the account is missing,
     * non-ACTIVE, has no phone fact or the store is unreadable. Null always renders the §7 list
     * as the same empty page as a non-matching phone (anti-enumeration).
     */
    String sessionUserPhone(long userId);
}
