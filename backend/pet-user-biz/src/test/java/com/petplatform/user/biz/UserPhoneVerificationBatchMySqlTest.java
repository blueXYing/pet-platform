package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.user.api.query.UserIdQuery;
import com.petplatform.user.biz.apiimpl.UserPhoneVerificationApiImpl;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Contract 54 §7 batch phone equality over isolated real MySQL: the account's verified login
 * phone (S9 fact) never leaves the module — only the matched subset of caller-owned candidate
 * values returns. Missing account, non-ACTIVE account, phone-less account or an unreadable
 * store all read as "no match", never an error disclosure; per-candidate validation keeps the
 * lexicon identical to the single-comparison form.
 */
class UserPhoneVerificationBatchMySqlTest {
    private static final long USER = 9_200_000_000_000_701L;
    private static final long NOPHONE = 9_200_000_000_000_702L;
    private static final long DISABLED = 9_200_000_000_000_703L;
    private static final String PHONE = "13911112222";
    private static final String OTHER = "13933334444";
    private static final String DISABLED_PHONE = "13955556666";

    @Test
    void batchEqualityMirrorsTheSingleComparisonSemantics() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(USER, PHONE, "ACTIVE");
            db.seedUser(NOPHONE, null, "ACTIVE");
            db.seedUser(DISABLED, DISABLED_PHONE, "DISABLED");
            UserPhoneVerificationApiImpl api = new UserPhoneVerificationApiImpl(db.dataSource());

            // Matching session: exactly the account phone among candidates (dedup included).
            assertEquals(Set.of(PHONE), api.verifiedPhonesEqualTo(query(USER),
                    List.of(PHONE, OTHER, PHONE)));
            // Malformed candidates never match; the account phone still does.
            assertEquals(Set.of(PHONE), api.verifiedPhonesEqualTo(query(USER),
                    List.of(PHONE, "abc", "139", "")));
            // Other phones for a matching session: empty, no disclosure.
            assertTrue(api.verifiedPhonesEqualTo(query(USER), List.of(OTHER)).isEmpty());
            // Phone-less, disabled and unknown accounts: the same empty set.
            assertTrue(api.verifiedPhonesEqualTo(query(NOPHONE), List.of(PHONE, OTHER)).isEmpty());
            assertTrue(api.verifiedPhonesEqualTo(query(DISABLED), List.of(DISABLED_PHONE)).isEmpty());
            assertTrue(api.verifiedPhonesEqualTo(query(9_200_000_000_000_799L),
                    List.of(PHONE)).isEmpty());
            // Null / empty candidate collections are a plain empty result.
            assertTrue(api.verifiedPhonesEqualTo(query(USER), null).isEmpty());
            assertTrue(api.verifiedPhonesEqualTo(query(USER), List.of()).isEmpty());
            // The single form keeps its byte-identical semantics on the same rows.
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
