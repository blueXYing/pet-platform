package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantOrderEligibilityDTO;
import com.petplatform.merchant.api.dto.MerchantStaffDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDTO;
import com.petplatform.merchant.api.query.MerchantOrderEligibilityQuery;
import com.petplatform.merchant.api.query.MerchantStaffQuery;
import com.petplatform.merchant.api.query.StoreIdQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantQueryApiImpl;
import com.petplatform.merchant.biz.application.MerchantEligibilityFactsReader;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** S2 merchant read and qualification tests over an isolated real MySQL database. */
class MerchantQueryApiMySqlTest {
    private static final long MERCHANT_A = 9_007_199_254_740_993L;
    private static final long STORE_A = 9_007_199_254_740_995L;
    private static final long STAFF_A = 9_007_199_254_740_997L;
    private static final long OWNER_A = 7_000_000_001L;
    private static final long MERCHANT_B = 8_000_000_001L;
    private static final long STORE_B = 8_000_000_002L;
    private static final long STAFF_B = 8_000_000_003L;
    private static final long OWNER_B = 7_000_000_002L;

    private static QueryContext owner(long ownerUserId) {
        return new QueryContext("trace-merchant-test", OperatorType.USER, Long.toString(ownerUserId));
    }

    private static ApiException failure(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void ownerReadsStoreAndStaffWithStringIdsMaskedPhonesAndIndependentDtos() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "ACTIVE");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", "13800002222");
            db.seedStaff(STAFF_A, MERCHANT_A, STORE_A, "ACTIVE", true, "13900003333");
            var api = new MerchantQueryApiImpl(db.dataSource());

            MerchantStoreDTO store = api.getStore(new StoreIdQuery(Long.toString(STORE_A), owner(OWNER_A)));
            assertEquals(Long.toString(MERCHANT_A), store.merchantId());
            assertEquals(Long.toString(STORE_A), store.storeId());
            assertEquals("121.4737", store.longitude());
            assertEquals("31.2304", store.latitude());
            assertEquals("138****2222", store.phoneMasked());
            assertEquals("ACTIVE", store.merchantStatus());
            assertEquals("ACTIVE", store.storeStatus());
            assertEquals("0", store.version());

            MerchantStaffDTO staff = api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)));
            assertEquals(Long.toString(MERCHANT_A), staff.merchantId());
            assertEquals(Long.toString(STORE_A), staff.storeId());
            assertEquals(Long.toString(STAFF_A), staff.staffId());
            assertEquals("139****3333", staff.phoneMasked());
            assertEquals("ACTIVE", staff.employmentStatus());
            assertTrue(staff.serviceEnabled());
            assertEquals("0", staff.version());

            // DTOs are immutable records and raw phone facts never appear in their serialization.
            assertFalse(store.toString().contains("13800002222"));
            assertFalse(staff.toString().contains("13900003333"));
            assertEquals(store, api.getStore(new StoreIdQuery(Long.toString(STORE_A), owner(OWNER_A))));
        }
    }

    @Test
    void crossOwnerMerchantStoreAndStaffAreUniformNotFound() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "ACTIVE");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", "13800002222");
            db.seedStaff(STAFF_A, MERCHANT_A, STORE_A, "ACTIVE", true, "13900003333");
            long secondStore = 9_007_199_254_740_999L;
            long secondStaff = 9_007_199_254_741_001L;
            db.seedStore(secondStore, MERCHANT_A, "ACTIVE", "13500006666");
            db.seedStaff(secondStaff, MERCHANT_A, secondStore, "ACTIVE", true, "13400007777");
            db.seedMerchant(MERCHANT_B, OWNER_B, "ACTIVE");
            db.seedStore(STORE_B, MERCHANT_B, "ACTIVE", "13700004444");
            db.seedStaff(STAFF_B, MERCHANT_B, STORE_B, "ACTIVE", true, "13600005555");
            var api = new MerchantQueryApiImpl(db.dataSource());

            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.getStore(
                    new StoreIdQuery(Long.toString(STORE_A), owner(OWNER_B)))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.getStore(
                    new StoreIdQuery(Long.toString(STORE_B), owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_B), owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_B), Long.toString(STORE_B), Long.toString(STAFF_A), owner(OWNER_B)))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_B), Long.toString(STAFF_A), owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(secondStaff), owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.getStore(
                    new StoreIdQuery("9007199254741999", owner(OWNER_A)))).code());
        }
    }

    @Test
    void invalidIdsAndContextsAreRejectedBeforeDatabaseAccess() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "ACTIVE");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", null);
            var api = new MerchantQueryApiImpl(db.dataSource());

            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.getStore(
                    new StoreIdQuery("0", owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.getStore(
                    new StoreIdQuery("09", owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.getStore(
                    new StoreIdQuery("not-a-number", owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.getStore(
                    new StoreIdQuery("9223372036854775808", owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.getStore(
                    new StoreIdQuery(Long.toString(STORE_A) + "\n", owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.UNAUTHORIZED, failure(() -> api.getStore(
                    new StoreIdQuery(Long.toString(STORE_A), null))).code());
            assertEquals(CommonApiCodes.UNAUTHORIZED, failure(() -> api.getStore(
                    new StoreIdQuery(Long.toString(STORE_A), new QueryContext("t", OperatorType.USER, "01")))).code());
            assertEquals(CommonApiCodes.UNAUTHORIZED, failure(() -> api.getStore(
                    new StoreIdQuery(Long.toString(STORE_A), new QueryContext(
                            "t", OperatorType.USER, Long.toString(OWNER_A) + "\n")))).code());
            assertEquals(CommonApiCodes.FORBIDDEN, failure(() -> api.getStore(
                    new StoreIdQuery(Long.toString(STORE_A), new QueryContext(
                            "t", OperatorType.MERCHANT_STAFF, Long.toString(OWNER_A))))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.getStaff(new MerchantStaffQuery(
                    "", Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.checkOrderEligibility(
                    new MerchantOrderEligibilityQuery(Long.toString(MERCHANT_A), "-1", owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.getStore(null)).code());
        }
    }

    @Test
    void unknownStoredEnumsAndMalformedFactsFailClosed() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "UNKNOWN");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", "123");
            db.seedStaff(STAFF_A, MERCHANT_A, STORE_A, "UNKNOWN", false, "123");
            var api = new MerchantQueryApiImpl(db.dataSource());

            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getStore(
                    new StoreIdQuery(Long.toString(STORE_A), owner(OWNER_A)))).code());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)))).code());
            db.jdbc().update("UPDATE merchant SET status='ACTIVE' WHERE id=?", MERCHANT_A);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)))).code());
            db.jdbc().update("UPDATE merchant_staff SET employment_status='ACTIVE' WHERE id=?", STAFF_A);
            db.jdbc().update("UPDATE merchant_store SET status='BROKEN' WHERE id=?", STORE_A);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)))).code());
        }
    }

    @Test
    void eligibilityReturnsTrueOnlyForActiveApprovedSignedFacts() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "ACTIVE");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", null);
            AtomicInteger reads = new AtomicInteger();
            MerchantEligibilityFactsReader facts = (merchantId, storeId) -> {
                assertEquals(MERCHANT_A, merchantId);
                assertEquals(STORE_A, storeId);
                reads.incrementAndGet();
                return new MerchantEligibilityFactsReader.Facts("APPROVED", "SIGNED");
            };
            var api = new MerchantQueryApiImpl(db.dataSource(), facts);

            MerchantOrderEligibilityDTO result = api.checkOrderEligibility(
                    new MerchantOrderEligibilityQuery(Long.toString(MERCHANT_A), Long.toString(STORE_A), owner(OWNER_A)));
            assertEquals(Long.toString(MERCHANT_A), result.merchantId());
            assertEquals(Long.toString(STORE_A), result.storeId());
            assertTrue(result.merchantEnabled());
            assertTrue(result.storeEnabled());
            assertTrue(result.acceptsNewOrders());
            assertEquals(1, reads.get());
        }
    }

    @Test
    void eligibilityReturnsFalseForExplicitlyKnownInvalidStatesWithoutOverstatingOtherDomains() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "OFFLINE");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", null);
            var facts = new MerchantEligibilityFactsReader.Facts("APPROVED", "SIGNED");
            var api = new MerchantQueryApiImpl(db.dataSource(), (m, s) -> facts);

            MerchantOrderEligibilityDTO result = api.checkOrderEligibility(
                    new MerchantOrderEligibilityQuery(Long.toString(MERCHANT_A), Long.toString(STORE_A), owner(OWNER_A)));
            assertFalse(result.merchantEnabled());
            assertTrue(result.storeEnabled());
            assertFalse(result.acceptsNewOrders());

            db.jdbc().update("UPDATE merchant SET status='ACTIVE' WHERE id=?", MERCHANT_A);
            db.jdbc().update("UPDATE merchant_store SET status='FROZEN' WHERE id=?", STORE_A);
            result = api.checkOrderEligibility(new MerchantOrderEligibilityQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), owner(OWNER_A)));
            assertTrue(result.merchantEnabled());
            assertFalse(result.storeEnabled());
            assertFalse(result.acceptsNewOrders());

            // Explicitly known application/signing failure is a normal false, not a dependency outage.
            db.jdbc().update("UPDATE merchant_store SET status='ACTIVE' WHERE id=?", STORE_A);
            var rejected = new MerchantQueryApiImpl(db.dataSource(),
                    (m, s) -> new MerchantEligibilityFactsReader.Facts("REJECTED", "NOT_SIGNED"));
            result = rejected.checkOrderEligibility(new MerchantOrderEligibilityQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), owner(OWNER_A)));
            assertTrue(result.merchantEnabled());
            assertTrue(result.storeEnabled());
            assertFalse(result.acceptsNewOrders());

            for (String signingStatus : new String[] {"SIGNING", "FAILED"}) {
                var notSigned = new MerchantQueryApiImpl(db.dataSource(),
                        (m, s) -> new MerchantEligibilityFactsReader.Facts("APPROVED", signingStatus));
                result = notSigned.checkOrderEligibility(new MerchantOrderEligibilityQuery(
                        Long.toString(MERCHANT_A), Long.toString(STORE_A), owner(OWNER_A)));
                assertTrue(result.merchantEnabled());
                assertTrue(result.storeEnabled());
                assertFalse(result.acceptsNewOrders(), signingStatus);
            }
        }
    }

    @Test
    void missingUnknownOrUnavailableEligibilityFactsFailClosed() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "ACTIVE");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", null);
            var query = new MerchantOrderEligibilityQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), owner(OWNER_A));

            var defaultApi = new MerchantQueryApiImpl(db.dataSource());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> defaultApi.checkOrderEligibility(query)).code());
            var missing = new MerchantQueryApiImpl(db.dataSource(), (m, s) -> null);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> missing.checkOrderEligibility(query)).code());
            var unknown = new MerchantQueryApiImpl(db.dataSource(),
                    (m, s) -> new MerchantEligibilityFactsReader.Facts("UNKNOWN", "SIGNED"));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> unknown.checkOrderEligibility(query)).code());
            var unavailable = new MerchantQueryApiImpl(db.dataSource(), (m, s) -> {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "facts source down");
            });
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> unavailable.checkOrderEligibility(query)).code());

            for (String sourceCode : new String[] {
                    CommonApiCodes.INVALID_ARGUMENT, CommonApiCodes.NOT_FOUND, CommonApiCodes.FORBIDDEN
            }) {
                var sourceRejected = new MerchantQueryApiImpl(db.dataSource(), (m, s) -> {
                    throw new ApiException(sourceCode, "sensitive source detail");
                });
                ApiException sanitized = failure(() -> sourceRejected.checkOrderEligibility(query));
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, sanitized.code());
                assertEquals("merchant eligibility facts unavailable", sanitized.getMessage());
            }
            var runtimeFailure = new MerchantQueryApiImpl(db.dataSource(), (m, s) -> {
                throw new IllegalStateException("sensitive runtime detail");
            });
            ApiException sanitized = failure(() -> runtimeFailure.checkOrderEligibility(query));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, sanitized.code());
            assertEquals("merchant eligibility facts unavailable", sanitized.getMessage());

            db.jdbc().update("UPDATE merchant SET status='OFFLINE' WHERE id=?", MERCHANT_A);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    failure(() -> defaultApi.checkOrderEligibility(query)).code(),
                    "missing authority facts remain unavailable even when the base state is disabled");
        }
    }

    @Test
    void injectedReaderUsesSameRepeatableReadSnapshotAsMerchantRows() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "ACTIVE");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", null);
            MerchantEligibilityFactsReader snapshotReader = (merchantId, storeId) -> {
                String before = new org.springframework.jdbc.core.JdbcTemplate(db.dataSource())
                        .queryForObject("SELECT status FROM merchant_store WHERE id=?", String.class, STORE_A);
                db.independentJdbc().update("UPDATE merchant_store SET status='FROZEN' WHERE id=?", STORE_A);
                String after = new org.springframework.jdbc.core.JdbcTemplate(db.dataSource())
                        .queryForObject("SELECT status FROM merchant_store WHERE id=?", String.class, STORE_A);
                assertEquals("ACTIVE", before);
                assertEquals("ACTIVE", after,
                        "the reader must join the service's repeatable-read connection snapshot");
                return new MerchantEligibilityFactsReader.Facts("APPROVED", "SIGNED");
            };
            var api = new MerchantQueryApiImpl(db.dataSource(), snapshotReader);
            MerchantOrderEligibilityDTO result = api.checkOrderEligibility(new MerchantOrderEligibilityQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), owner(OWNER_A)));
            assertTrue(result.acceptsNewOrders());
            assertEquals("FROZEN", db.jdbc().queryForObject(
                    "SELECT status FROM merchant_store WHERE id=?", String.class, STORE_A));
        }
    }

    @Test
    void nullPhoneRemainsNullAndMalformedStoredPhoneIsNotReturned() throws Exception {
        try (var db = new MySqlMerchantDomainTestDatabase()) {
            db.seedMerchant(MERCHANT_A, OWNER_A, "ACTIVE");
            db.seedStore(STORE_A, MERCHANT_A, "ACTIVE", null);
            db.seedStaff(STAFF_A, MERCHANT_A, STORE_A, "INACTIVE", false, null);
            var api = new MerchantQueryApiImpl(db.dataSource());
            assertNull(api.getStore(new StoreIdQuery(Long.toString(STORE_A), owner(OWNER_A))).phoneMasked());
            assertNull(api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A))).phoneMasked());

            db.jdbc().update("UPDATE merchant_store SET phone='123' WHERE id=?", STORE_A);
            assertEquals("****", api.getStore(new StoreIdQuery(Long.toString(STORE_A), owner(OWNER_A))).phoneMasked());
            db.jdbc().update("UPDATE merchant_store SET phone='1234567' WHERE id=?", STORE_A);
            assertEquals("****", api.getStore(new StoreIdQuery(Long.toString(STORE_A), owner(OWNER_A))).phoneMasked());

            db.jdbc().update("UPDATE merchant_staff SET phone='1234567', employment_status='ACTIVE', service_enabled=0 WHERE id=?", STAFF_A);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)))).code());
            db.jdbc().update("UPDATE merchant_staff SET phone='13800001111', employment_status='ACTIVE', service_enabled=1 WHERE id=?", STAFF_A);
            assertEquals("138****1111", api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A))).phoneMasked());
            db.jdbc().update("UPDATE merchant_staff SET employment_status='INACTIVE', service_enabled=1 WHERE id=?", STAFF_A);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)))).code());
            db.jdbc().update("UPDATE merchant_staff SET employment_status='ACTIVE', service_enabled=2 WHERE id=?", STAFF_A);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)))).code());
            db.jdbc().update("UPDATE merchant_staff SET service_enabled=-1 WHERE id=?", STAFF_A);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getStaff(new MerchantStaffQuery(
                    Long.toString(MERCHANT_A), Long.toString(STORE_A), Long.toString(STAFF_A), owner(OWNER_A)))).code());
        }
    }
}
