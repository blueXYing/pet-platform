package com.petplatform.user.api.query;

/** Contract 54 §1: fail-closed phone-equality check for the staff invitation confirm channel. */
public interface UserPhoneVerificationApi {

    /**
     * True only when the account exists, is ACTIVE, and its verified login phone (the S9 WeChat
     * getPhoneNumber exchange fact) equals the submitted 11-digit phone. The raw credential never
     * leaves the user module; any unreadable fact returns false.
     */
    boolean hasVerifiedPhone(UserIdQuery query, String phone);
}
