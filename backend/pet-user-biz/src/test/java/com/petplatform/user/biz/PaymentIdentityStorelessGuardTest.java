package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.PaymentIdentity;
import com.petplatform.user.api.query.PaymentIdentityApi;
import com.petplatform.user.biz.apiimpl.PaymentIdentityApiImpl;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Offline guards of the Contract 75 store-less payment identity variant (no MySQL). */
class PaymentIdentityStorelessGuardTest {
    private static QueryContext user(String id) { return new QueryContext("t", OperatorType.USER, id); }

    @Test void defaultInterfaceMethodFailsClosed() {
        PaymentIdentityApi legacy = (userId, appId, storeId, context) -> new PaymentIdentity(appId, "open");
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> legacy.requireCurrentPaymentIdentity("1", "wx", user("1"))).code());
    }

    @Test void rejectsWrongActorBadInputAndMissingTransactionBeforeAnySql() {
        DataSource source = mock(DataSource.class);
        ScheduleCapacityGuardApi guard = mock(ScheduleCapacityGuardApi.class);
        var api = new PaymentIdentityApiImpl(source, guard);
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api.requireCurrentPaymentIdentity("1", "wx", new QueryContext("t", OperatorType.SYSTEM, "1"))).code());
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api.requireCurrentPaymentIdentity("1", "wx", user("2"))).code());
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api.requireCurrentPaymentIdentity("1", "wx", null)).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> api.requireCurrentPaymentIdentity("01", "wx", user("01"))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> api.requireCurrentPaymentIdentity("1", " ", user("1"))).code());
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> api.requireCurrentPaymentIdentity("1", "wx", user("1"))).code());
        verifyNoInteractions(source, guard);
    }
}
