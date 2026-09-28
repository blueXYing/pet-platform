package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.PaymentIdentity;
import com.petplatform.user.biz.apiimpl.PaymentIdentityApiImpl;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL 8, isolated per test via the existing USER fixture. */
class PaymentIdentityApiMySqlTest {
    private static final String USER = "600001";
    private static final String STORE = "800001";
    private static final String APP = "wx-mini-pay";

    @Test void guardedCurrentUserGetsOnlyTheExactMiniIdentity() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600001, null, "ACTIVE");
            identity(db, 700001, USER, "WECHAT_MINI", APP, "open-current");
            Guard guard = new Guard(db.dataSource());
            PaymentIdentityApiImpl api = new PaymentIdentityApiImpl(db.dataSource(), guard);

            PaymentIdentity result = inTransaction(db, guard, () ->
                    api.requireCurrentPaymentIdentity(USER, APP, STORE, user(USER)));
            assertEquals(APP, result.appId());
            assertEquals("open-current", result.openId());
            assertFalse(result.toString().contains(result.openId()));

            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, "WX-MINI-PAY", STORE, user(USER))));
            assertCode(CommonApiCodes.FORBIDDEN, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, user("600002"))));
            assertCode(CommonApiCodes.FORBIDDEN, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, new QueryContext("trace", OperatorType.SYSTEM, USER))));
        }
    }

    @Test void duplicateWrongTypeBlankAndMissingBindingsFailClosed() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600001, null, "ACTIVE");
            Guard guard = new Guard(db.dataSource());
            PaymentIdentityApiImpl api = new PaymentIdentityApiImpl(db.dataSource(), guard);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, user(USER))));

            identity(db, 700001, USER, "OTHER", APP, "open-other-type");
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, user(USER))));

            db.jdbc().update("DELETE FROM user_auth_identity");
            identity(db, 700002, USER, "WECHAT_MINI", APP, "open-one");
            identity(db, 700003, USER, "WECHAT_MINI", APP, "open-two");
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, user(USER))));

            db.jdbc().update("DELETE FROM user_auth_identity WHERE id=700003");
            db.jdbc().update("UPDATE user_auth_identity SET open_id=NULL WHERE id=700002");
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, user(USER))));
        }
    }

    @Test void accountAndGuardFailuresMarkTheWholeTransactionRollbackOnly() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600001, null, "FROZEN");
            identity(db, 700001, USER, "WECHAT_MINI", APP, "open-current");
            Guard guard = new Guard(db.dataSource());
            PaymentIdentityApiImpl api = new PaymentIdentityApiImpl(db.dataSource(), guard);
            assertCode(CommonApiCodes.FORBIDDEN, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, user(USER))));

            db.jdbc().update("UPDATE user_account SET status='ALIEN' WHERE id=600001");
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () ->
                    inTransaction(db, guard, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, user(USER))));

            db.jdbc().update("UPDATE user_account SET status='ACTIVE' WHERE id=600001");
            assertThrows(UnexpectedRollbackException.class, () -> inTransaction(db, guard, () -> {
                db.jdbc().update("INSERT INTO user_account (id,status,created_at,updated_at) "
                        + "VALUES (600002,'ACTIVE',NOW(3),NOW(3))");
                assertCode(CommonApiCodes.INVALID_ARGUMENT, () ->
                        api.requireCurrentPaymentIdentity(USER, " ", STORE, user(USER)));
                return null;
            }));
            assertEquals(0L, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_account WHERE id=600002", Long.class));

            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () ->
                    transaction(db, () -> api.requireCurrentPaymentIdentity(
                            USER, APP, STORE, user(USER))));
        }
    }

    private static QueryContext user(String userId) {
        return new QueryContext("trace", OperatorType.USER, userId);
    }

    private static void identity(MySqlUserDomainTestDatabase db, long id, String userId,
            String type, String appId, String openId) {
        db.jdbc().update("INSERT INTO user_auth_identity "
                + "(id,user_id,identity_type,app_id,open_id,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,NOW(3),NOW(3))",
                id, Long.parseLong(userId), type, appId, openId);
    }

    private static <T> T inTransaction(MySqlUserDomainTestDatabase db, Guard guard,
            java.util.function.Supplier<T> action) {
        return transaction(db, () -> {
            guard.acquire(List.of(STORE), user(USER));
            return action.get();
        });
    }

    private static <T> T transaction(MySqlUserDomainTestDatabase db,
            java.util.function.Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> action.get());
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        ApiException failure = assertThrows(ApiException.class, action);
        assertEquals(code, failure.code());
    }

    private static final class Guard implements ScheduleCapacityGuardApi {
        private final DataSource source;
        private final ThreadLocal<ConnectionHolder> acquired = new ThreadLocal<>();
        private Guard(DataSource source) { this.source = source; }
        @Override public void acquire(List<String> storeIds, QueryContext context) {
            if (!storeIds.equals(List.of(STORE)) || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder))
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "test guard absent");
            acquired.set(holder);
        }
        @Override public void requireHeld(String storeId, DataSource callerSource) {
            if (!STORE.equals(storeId) || callerSource != source
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                    || acquired.get() != holder)
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "test guard absent");
        }
    }
}
