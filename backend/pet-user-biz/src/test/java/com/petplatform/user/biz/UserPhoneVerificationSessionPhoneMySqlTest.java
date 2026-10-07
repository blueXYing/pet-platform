package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.user.api.query.UserIdQuery;
import com.petplatform.user.biz.apiimpl.UserPhoneVerificationApiImpl;
import org.junit.jupiter.api.Test;

/**
 * Contract 54 §7 purpose-bound session-phone read over isolated real MySQL (user ruling
 * 2026-10-07): verifiedPhone returns the account's verified login phone (the S9 fact) solely as
 * the in-memory seek key of the indexed invitation list. Missing account, non-ACTIVE account,
 * phone-less account or an unreadable store all read as null — never an error disclosure; the
 * boolean form keeps its byte-identical confirm-channel semantics on the same rows.
 */
class UserPhoneVerificationSessionPhoneMySqlTest {
    private static final long USER = 9_200_000_000_000_701L;
    private static final long NOPHONE = 9_200_000_000_000_702L;
    private static final long DISABLED = 9_200_000_000_000_703L;
    private static final String PHONE = "13911112222";
    private static final String OTHER = "13933334444";
    private static final String DISABLED_PHONE = "13955556666";

    @Test
    void purposeBoundSessionPhoneReadFailsClosedLikeTheBooleanForm() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(USER, PHONE, "ACTIVE");
            db.seedUser(NOPHONE, null, "ACTIVE");
            db.seedUser(DISABLED, DISABLED_PHONE, "DISABLED");
            UserPhoneVerificationApiImpl api = new UserPhoneVerificationApiImpl(db.dataSource());

            // The matching session's verified phone is readable exactly once, as the seek key.
            assertEquals(PHONE, api.verifiedPhone(query(USER)));
            // Phone-less, disabled and unknown accounts all read as "no phone" (null).
            assertNull(api.verifiedPhone(query(NOPHONE)));
            assertNull(api.verifiedPhone(query(DISABLED)));
            assertNull(api.verifiedPhone(query(9_200_000_000_000_799L)));
            // The boolean form keeps its byte-identical semantics on the same rows.
            assertTrue(api.hasVerifiedPhone(query(USER), PHONE));
            assertFalse(api.hasVerifiedPhone(query(USER), OTHER));
            assertFalse(api.hasVerifiedPhone(query(NOPHONE), PHONE));
            assertFalse(api.hasVerifiedPhone(query(DISABLED), DISABLED_PHONE));
            assertFalse(api.hasVerifiedPhone(query(9_200_000_000_000_799L), PHONE));
        }
    }

    private static UserIdQuery query(long user) {
        return new UserIdQuery(Long.toUnsignedString(user));
    }
}
