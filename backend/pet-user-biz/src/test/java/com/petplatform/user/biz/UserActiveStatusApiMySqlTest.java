package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.user.biz.apiimpl.UserActiveStatusApiImpl;
import org.junit.jupiter.api.Test;

/**
 * Read-only user_account status probe over real MySQL (contract-65 §4.3 grant hardening,
 * user ruling 2026-10-19): existence in any status, null for unknown ids, operator-only
 * access and lexical id discipline. Reuses the module's USR001_* CI fixture (no new env
 * prefix).
 */
class UserActiveStatusApiMySqlTest {
    private static final QueryContext OPS =
            new QueryContext("t-ops", OperatorType.PLATFORM_OPERATOR, "9007199254740993");

    @Test void probesAnyStatusAnswersNullForUnknownAndGuardsAccess() throws Exception {
        try (MySqlUserDomainTestDatabase db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600101L, null, "ACTIVE");
            db.seedUser(600102L, null, "FROZEN");
            db.seedUser(600103L, null, "CANCELED");
            UserActiveStatusApiImpl api = new UserActiveStatusApiImpl(db.dataSource());

            // Existence means the account row exists in any status — no usability policy.
            assertEquals("ACTIVE", api.currentStatus("600101", OPS));
            assertEquals("FROZEN", api.currentStatus("600102", OPS));
            assertEquals("CANCELED", api.currentStatus("600103", OPS));
            assertTrue(api.exists("600102", OPS));
            // Unknown id: null so the caller owns the not-found mapping (404 upstream).
            assertNull(api.currentStatus("600999", OPS));
            assertFalse(api.exists("600999", OPS));

            // Non-operator context is rejected: the probe serves cross-domain owners only.
            assertCode(CommonApiCodes.FORBIDDEN, () -> api.currentStatus("600101",
                    new QueryContext("t-u", OperatorType.USER, "600101")));
            assertCode(CommonApiCodes.FORBIDDEN, () -> api.currentStatus("600101",
                    new QueryContext("t-s", OperatorType.SYSTEM, "600101")));
            assertCode(CommonApiCodes.FORBIDDEN, () -> api.currentStatus("600101", null));
            // Malformed id: 400 by the common lexical rule.
            assertCode(CommonApiCodes.INVALID_ARGUMENT, () -> api.currentStatus("not-an-id", OPS));
            assertCode(CommonApiCodes.INVALID_ARGUMENT, () -> api.currentStatus(null, OPS));
        }
    }

    private static void assertCode(String expected, Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected ApiException " + expected);
        } catch (ApiException known) {
            assertEquals(expected, known.code());
        }
    }
}
