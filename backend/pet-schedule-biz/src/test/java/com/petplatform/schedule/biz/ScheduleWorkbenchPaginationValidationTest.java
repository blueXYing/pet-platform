package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.schedule.api.query.WorkbenchWindowQuery;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantQueryApiImpl;
import com.petplatform.schedule.biz.application.ScheduleAdmissionGate;
import org.junit.jupiter.api.Test;

/**
 * Contract53 §3.3 (2026-10-07) API-level paging bounds: the query API re-validates the
 * notification-list baseline (page 1..10000, pageSize 1..50, both-or-neither) before any
 * store access, so a direct terminal caller gets the same 400 as the HTTP surface without a
 * database being reachable.
 */
class ScheduleWorkbenchPaginationValidationTest {

    private final ScheduleMerchantQueryApiImpl api = new ScheduleMerchantQueryApiImpl(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/never-connected"),
            new ScheduleAdmissionGate(new MerchantAdmissionQueryApi() {
                @Override
                public MerchantMembershipPageDTO listMemberships(MerchantMembershipQuery query) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query) {
                    throw new UnsupportedOperationException(
                            "admission is unreachable in the validation-only tests");
                }
            }));

    private static WorkbenchWindowQuery query(Integer page, Integer pageSize) {
        return new WorkbenchWindowQuery("10", "201", null, null, null, page, pageSize,
                new QueryContext("schpg-test", OperatorType.USER, "601"));
    }

    @Test
    void outOfRangePagingIsRejectedBeforeAnyStoreAccess() {
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listWindows(query(0, 20))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listWindows(query(10_001, 20)))
                        .code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listWindows(query(1, 0))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listWindows(query(1, 51))).code());
    }

    @Test
    void pagingIsAllOrNothing() {
        // Either field alone is ambiguous paging intent and is refused like an HTTP caller's
        // partial query string would be normalized away; the store is never reached.
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listWindows(query(null, 20)))
                        .code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listWindows(query(1, null))).code());
    }
}
