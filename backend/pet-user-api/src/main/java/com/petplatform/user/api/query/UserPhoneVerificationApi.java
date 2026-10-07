package com.petplatform.user.api.query;

import java.util.Collection;
import java.util.Set;

/** Contract 54 §1: fail-closed phone-equality check for the staff invitation confirm channel. */
public interface UserPhoneVerificationApi {

    /**
     * True only when the account exists, is ACTIVE, and its verified login phone (the S9 WeChat
     * getPhoneNumber exchange fact) equals the submitted 11-digit phone. The raw credential never
     * leaves the user module; any unreadable fact returns false.
     */
    boolean hasVerifiedPhone(UserIdQuery query, String phone);

    /**
     * Contract 54 §7 batch form of the same equality fact (employee invitation list): returns the
     * subset of submitted candidate phones equal to the account's verified login phone. The
     * candidates are caller-owned values echoed back, never the account credential; a missing /
     * non-ACTIVE / phone-less account or an unreadable store yields an empty set, never an error
     * disclosure. Same fail-closed semantics as {@link #hasVerifiedPhone}, one account read.
     */
    Set<String> verifiedPhonesEqualTo(UserIdQuery query, Collection<String> phones);
}
