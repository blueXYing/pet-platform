package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.StoreStaffFactsDTO;
import com.petplatform.merchant.api.query.StoreStaffFactsQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantStoreStaffFactsApiImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class MerchantStoreStaffFactsApiMySqlTest {
    private static final long MERCHANT = 800001L;
    private static final long STORE = 800002L;
    private static final long OTHER_STORE = 800003L;
    private static final QueryContext CONTEXT =
            new QueryContext("staff-facts-test", OperatorType.SYSTEM, "schedule-capacity");

    @Test
    void returnsOnlyActiveEnabledIdsWithoutOwnershipOrPersonalData() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT, 900001L, "ACTIVE");
            db.seedStore(STORE, MERCHANT, "ACTIVE", null);
            db.seedStore(OTHER_STORE, MERCHANT, "ACTIVE", null);
            db.seedStore(OTHER_STORE + 1, MERCHANT, "ACTIVE", null);
            db.seedStaff(800013L, MERCHANT, STORE, "ACTIVE", true, "13800000013");
            db.seedStaff(800009L, MERCHANT, STORE, "ACTIVE", true, "13800000009");
            db.seedStaff(800010L, MERCHANT, STORE, "ACTIVE", false, null);
            db.seedStaff(800011L, MERCHANT, STORE, "INACTIVE", false, null);
            db.seedStaff(800005L, MERCHANT, OTHER_STORE, "ACTIVE", true, null);
            var api = new MerchantStoreStaffFactsApiImpl(db.dataSource());
            StoreStaffFactsDTO facts = api.listActiveStoreStaffFacts(query(STORE));
            assertEquals(Long.toString(STORE), facts.storeId());
            assertEquals(List.of("800009", "800013"), facts.activeStaffIds());
            assertEquals(List.of(), api.listActiveStoreStaffFacts(query(OTHER_STORE + 1)).activeStaffIds());
        }
    }

    @Test
    void invalidFactsAndConfirmedAbsenceStayDistinct() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT, 900001L, "ACTIVE");
            db.seedStore(STORE, MERCHANT, "ACTIVE", null);
            db.seedStaff(800009L, MERCHANT, STORE, "ACTIVE", true, null);
            var api = new MerchantStoreStaffFactsApiImpl(db.dataSource());
            assertEquals(CommonApiCodes.NOT_FOUND,
                    failure(() -> api.listActiveStoreStaffFacts(query(OTHER_STORE))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    failure(() -> api.listActiveStoreStaffFacts(new StoreStaffFactsQuery("00", CONTEXT))).code());
            db.jdbc().update("UPDATE merchant_staff SET employment_status='UNKNOWN' WHERE id=800009");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> api.listActiveStoreStaffFacts(query(STORE))).code());
            db.jdbc().update("UPDATE merchant_staff SET employment_status='ACTIVE', service_enabled=2 WHERE id=800009");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> api.listActiveStoreStaffFacts(query(STORE))).code());
            db.jdbc().update("UPDATE merchant_staff SET service_enabled=1, merchant_id=999999 WHERE id=800009");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> api.listActiveStoreStaffFacts(query(STORE))).code());
        }
    }

    private static StoreStaffFactsQuery query(long storeId) {
        return new StoreStaffFactsQuery(Long.toString(storeId), CONTEXT);
    }

    private static ApiException failure(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }
}
