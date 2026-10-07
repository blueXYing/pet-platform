package com.petplatform.user.api.query;

/**
 * Contract 54 §1/§7 phone-equality channel for the staff invitation flow. The account's
 * verified login phone (the S9 WeChat getPhoneNumber exchange fact) stays inside the user
 * module except for the single purpose-bound read below.
 */
public interface UserPhoneVerificationApi {

    /**
     * True only when the account exists, is ACTIVE, and its verified login phone (the S9 WeChat
     * getPhoneNumber exchange fact) equals the submitted 11-digit phone. The raw credential never
     * leaves the user module; any unreadable fact returns false.
     */
    boolean hasVerifiedPhone(UserIdQuery query, String phone);

    /**
     * Contract 54 §7 employee invitation list, user ruling 2026-10-07: purpose-bound read of the
     * session account's verified login phone — the in-memory seek key for the indexed invitation
     * list (phone equality + id DESC). The value may only ever be used as a query bind for this
     * listing; it must never be persisted, logged, audited or carried in any projection, event
     * or error. Missing account, non-ACTIVE account, missing phone fact or an unreadable store
     * all read as "no phone" (null) — never an error disclosure.
     */
    String verifiedPhone(UserIdQuery query);
}
