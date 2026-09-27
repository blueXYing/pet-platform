package com.petplatform.boot.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.boot.config.ReservationProtectionFoundationConfiguration;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.merchant.biz.apiimpl.MerchantCurrentStaffFactsApiImpl;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.order.biz.apiimpl.OrderProtectionFactsApiImpl;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityProofApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.CapacityProofQuery;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Claim;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Interval;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Reservation;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Staff;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent foundation acceptance against two real MySQL connections and frozen public APIs. */
class ReservationProtectionFoundationAcceptanceTest {
    private static final QueryContext CTX = new QueryContext("foundation-qa", OperatorType.SYSTEM, "1");
    private static final String STORE = "700001";
    private static final String OTHER_STORE = "700002";
    private static final String MERCHANT = "700000";
    private static final String STAFF_A = "700010";
    private static final String STAFF_B = "700011";
    private static final long SERVICE_A = 700020L;
    private static final long SERVICE_B = 700021L;
    private static final String NINE = "2030-01-01 09:00:00";
    private static final String TEN = "2030-01-01 10:00:00";
    private static final String ELEVEN = "2030-01-01 11:00:00";
    private static final String NOON = "2030-01-01 12:00:00";
    private static final String ONE_PM = "2030-01-01 13:00:00";

    @Test
    void foundationBeansAreAbsentByDefaultAndPresentOnlyWhenExplicitlyEnabled() throws Exception {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(ReservationProtectionFoundationConfiguration.class);
        runner.run(context -> {
            assertFalse(context.containsBean("scheduleCapacityGuardApi"));
            assertFalse(context.containsBean("scheduleCapacityProofApi"));
            assertFalse(context.containsBean("orderProtectionFactsApi"));
        });
        try (Database db = new Database()) {
            runner.withBean(DataSource.class, () -> db.source)
                    .withPropertyValues("pet.schedule.protection.enabled=true")
                    .run(context -> {
                        assertNotNull(context.getBean(ScheduleCapacityGuardApi.class));
                        assertNotNull(context.getBean(ScheduleProtectionFactsApi.class));
                        assertNotNull(context.getBean(MerchantCurrentStaffFactsApi.class));
                        assertNotNull(context.getBean(OrderProtectionFactsApi.class));
                        assertNotNull(context.getBean(ScheduleCapacityProofApi.class));
                    });
        }
    }

    @Test
    void guardFirstRowSerializesSameStoreAndRollbackReleasesIt() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            try (Database db = new Database()) {
                var guard = new ScheduleCapacityGuardApiImpl(db.source);
                try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                    CountDownLatch firstHeld = new CountDownLatch(1);
                    CountDownLatch allowRollback = new CountDownLatch(1);
                    CountDownLatch secondStarted = new CountDownLatch(1);
                    Future<?> first = workers.submit(() -> {
                        assertThrows(RollBackFirst.class, () -> db.inTransaction(() -> {
                            guard.acquire(List.of(STORE), CTX);
                            db.jdbc.update("UPDATE schedule_store_capacity_guard SET version=version+1 WHERE store_id=?", STORE);
                            firstHeld.countDown();
                            await(allowRollback);
                            throw new RollBackFirst();
                        }));
                    });
                    assertTrue(firstHeld.await(3, TimeUnit.SECONDS));
                    Future<?> second = workers.submit(() -> db.inTransaction(() -> {
                        secondStarted.countDown();
                        guard.acquire(List.of(STORE), CTX);
                        assertEquals(0L, db.jdbc.queryForObject(
                                "SELECT version FROM schedule_store_capacity_guard WHERE store_id=?", Long.class, STORE));
                        return null;
                    }));
                    assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS),
                            "same-store second connection must await rollback of first-row creator");
                    allowRollback.countDown();
                    first.get(3, TimeUnit.SECONDS);
                    second.get(3, TimeUnit.SECONDS);
                    assertEquals(1, db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM schedule_store_capacity_guard WHERE store_id=?", Integer.class, STORE));
                }
            }
        });
    }

    @Test
    void guardAllowsOtherStoreWhileOneStoreIsLockedAndDoesNotLeakAfterScope() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            try (Database db = new Database()) {
                var guard = new ScheduleCapacityGuardApiImpl(db.source);
                try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                    CountDownLatch firstHeld = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    Future<?> first = workers.submit(() -> db.inTransaction(() -> {
                        guard.acquire(List.of(STORE), CTX);
                        firstHeld.countDown();
                        await(release);
                        return null;
                    }));
                    assertTrue(firstHeld.await(3, TimeUnit.SECONDS));
                    Future<?> other = workers.submit(() -> db.inTransaction(() -> {
                        guard.acquire(List.of(OTHER_STORE), CTX);
                        guard.requireHeld(OTHER_STORE, db.source);
                        return null;
                    }));
                    other.get(3, TimeUnit.SECONDS);
                    assertFalse(first.isDone(), "other store must not need first store release");
                    release.countDown();
                    first.get(3, TimeUnit.SECONDS);
                }
                assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        () -> guard.requireHeld(STORE, db.source));
            }
        });
    }

    @Test
    void guardRejectsReadOnlyRepeatableReadDifferentDataSourceAndRequiresNew() throws Exception {
        try (Database db = new Database()) {
            var guard = new ScheduleCapacityGuardApiImpl(db.source);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    () -> guard.acquire(List.of(STORE), CTX));
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    () -> db.withMode(TransactionDefinition.ISOLATION_READ_COMMITTED, true,
                            () -> { guard.acquire(List.of(STORE), CTX); return null; }));
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    () -> db.withMode(TransactionDefinition.ISOLATION_REPEATABLE_READ, false,
                            () -> { guard.acquire(List.of(STORE), CTX); return null; }));
            assertThrows(UnexpectedRollbackException.class, () -> db.inTransaction(() -> {
                guard.acquire(List.of(STORE), CTX);
                guard.requireHeld(STORE, db.source);
                assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        () -> guard.requireHeld(STORE, db.otherSource));
                var wrongSourceMerchant = new MerchantCurrentStaffFactsApiImpl(db.otherSource, guard);
                assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        () -> wrongSourceMerchant.readStore(STORE, CTX));
                TransactionTemplate nested = db.newTransaction();
                assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        () -> nested.execute(status -> { guard.requireHeld(STORE, db.source); return null; }));
                var merchant = new MerchantCurrentStaffFactsApiImpl(db.source, guard);
                assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        () -> nested.execute(status -> merchant.readStore(STORE, CTX)));
                return null;
            }), "caught wrong-datasource failure still marks the parent transaction rollback-only");
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule_store_capacity_guard WHERE store_id=?", Integer.class, STORE));
        }
    }

    @Test
    void nestedIndependentTransactionCannotAcquireOuterHeldStoreWithoutWaitingForMysqlTimeout() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Database db = new Database()) {
                var guard = new ScheduleCapacityGuardApiImpl(db.source);
                db.inTransaction(() -> {
                    guard.acquire(List.of(STORE), CTX);
                    assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                            () -> db.newTransaction().execute(status -> {
                                guard.acquire(List.of(STORE), CTX);
                                return null;
                            }));
                    guard.requireHeld(STORE, db.source);
                    return null;
                });
                assertEquals(1, db.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM schedule_store_capacity_guard WHERE store_id=?", Integer.class, STORE));
            }
        });
    }

    @Test
    void merchantFactsUseGuardedCurrentReadAndFailOnUnknownOrBrokenRows() throws Exception {
        try (Database db = new Database()) {
            db.store(Long.parseLong(STORE));
            db.staff(Long.parseLong(STAFF_A), Long.parseLong(STORE), "ACTIVE", 1);
            var guard = new ScheduleCapacityGuardApiImpl(db.source);
            var merchant = new MerchantCurrentStaffFactsApiImpl(db.source, guard);
            db.inTransaction(() -> {
                guard.acquire(List.of(STORE), CTX);
                var facts = merchant.readStore(STORE, CTX);
                assertTrue(facts.complete());
                assertEquals(1, facts.items().size());
                assertEquals(STAFF_A, facts.items().getFirst().staffId());
                return null;
            });
            db.jdbc.update("UPDATE merchant_staff SET employment_status='UNKNOWN' WHERE id=?", STAFF_A);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                guard.acquire(List.of(STORE), CTX);
                merchant.readStore(STORE, CTX);
                return null;
            }));
            db.jdbc.update("UPDATE merchant_staff SET employment_status='INACTIVE' WHERE id=?", STAFF_A);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                guard.acquire(List.of(STORE), CTX);
                merchant.readStore(STORE, CTX);
                return null;
            }), "INACTIVE with service_enabled=1 is a damaged MER fact");
            db.jdbc.update("UPDATE merchant_staff SET service_enabled=0 WHERE id=?", STAFF_A);
            db.inTransaction(() -> {
                guard.acquire(List.of(STORE), CTX);
                var fact = merchant.readStore(STORE, CTX).items().getFirst();
                assertEquals("INACTIVE", fact.employmentStatus());
                assertFalse(fact.serviceEnabled());
                return null;
            });
            db.jdbc.update("UPDATE merchant_staff SET merchant_id=? WHERE id=?", 999999L, STAFF_A);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                guard.acquire(List.of(STORE), CTX);
                merchant.readStore(STORE, CTX);
                return null;
            }));
        }
    }

    @Test
    void merchantEmptyStoreIsCompleteButMalformedEnabledFlagIsNotEmpty() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            var guard = new ScheduleCapacityGuardApiImpl(db.source);
            var merchant = new MerchantCurrentStaffFactsApiImpl(db.source, guard);
            db.inTransaction(() -> {
                guard.acquire(List.of(STORE), CTX);
                var empty = merchant.readStore(STORE, CTX);
                assertTrue(empty.complete());
                assertTrue(empty.items().isEmpty());
                return null;
            });
            db.staff(Long.parseLong(STAFF_A), 700001, "ACTIVE", 1);
            db.jdbc.update("UPDATE merchant_staff SET service_enabled=2 WHERE id=?", STAFF_A);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                guard.acquire(List.of(STORE), CTX);
                merchant.readStore(STORE, CTX);
                return null;
            }));
        }
    }

    @Test
    void merchantFactsSeePostGuardCurrentVersionInReadCommittedTransaction() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            try (Database db = new Database()) {
                db.store(Long.parseLong(STORE));
                db.staff(Long.parseLong(STAFF_A), Long.parseLong(STORE), "ACTIVE", 1);
                var guard = new ScheduleCapacityGuardApiImpl(db.source);
                var merchant = new MerchantCurrentStaffFactsApiImpl(db.source, guard);
                try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                    CountDownLatch oldSnapshot = new CountDownLatch(1);
                    CountDownLatch writerLocked = new CountDownLatch(1);
                    CountDownLatch readerAtGuard = new CountDownLatch(1);
                    CountDownLatch releaseWriter = new CountDownLatch(1);
                    Future<?> reader = workers.submit(() -> db.inTransaction(() -> {
                        assertEquals(0L, db.jdbc.queryForObject(
                                "SELECT version FROM merchant_staff WHERE id=?", Long.class, STAFF_A));
                        oldSnapshot.countDown();
                        await(writerLocked);
                        readerAtGuard.countDown();
                        guard.acquire(List.of(STORE), CTX);
                        assertEquals("1", merchant.readStore(STORE, CTX).items().getFirst().version());
                        return null;
                    }));
                    Future<?> writer = workers.submit(() -> {
                        assertTrue(oldSnapshot.await(3, TimeUnit.SECONDS));
                        db.inTransaction(() -> {
                            guard.acquire(List.of(STORE), CTX);
                            db.jdbc.update("UPDATE merchant_staff SET version=1 WHERE id=?", STAFF_A);
                            writerLocked.countDown();
                            await(releaseWriter);
                            return null;
                        });
                        return null;
                    });
                    try {
                        assertTrue(readerAtGuard.await(3, TimeUnit.SECONDS));
                        assertThrows(TimeoutException.class, () -> reader.get(250, TimeUnit.MILLISECONDS),
                                "same-store reader must wait for writer's guard commit");
                    } finally {
                        releaseWriter.countDown();
                    }
                    writer.get(3, TimeUnit.SECONDS);
                    reader.get(3, TimeUnit.SECONDS);
                }
            }
        });
    }

    @Test
    void orderEnumeratesCurrentAndHistoricalAssignmentsBeforeFiltering() throws Exception {
        try (Database db = new Database()) {
            db.store(Long.parseLong(STORE));
            db.staff(Long.parseLong(STAFF_A), Long.parseLong(STORE), "ACTIVE", 1);
            db.staff(Long.parseLong(STAFF_B), Long.parseLong(STORE), "ACTIVE", 1);
            db.service(SERVICE_A, Long.parseLong(STORE), "IN_STORE");
            db.window(700030, Long.parseLong(STORE), SERVICE_A, "GENERAL", NINE, NOON, 3);
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700030,
                    NINE, TEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", Long.parseLong(STAFF_A));
            db.generalOrder(701001, 701101, 702001, 700001, SERVICE_A, 700030,
                    TEN, ELEVEN, "TEMP_LOCKED", "PENDING_PAYMENT", "UNVERIFIED", null);
            db.generalOrder(701002, 701102, 702002, 700001, SERVICE_A, 700030,
                    NINE, TEN, "RELEASED", "COMPLETED", "VERIFIED", Long.parseLong(STAFF_B));
            var apis = new ProtectionApis(db.source);
            db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                var all = apis.order.readStore(STORE, CTX);
                assertTrue(all.complete());
                assertEquals(3, all.totalOrders());
                assertEquals(2, all.totalCurrentAssignments());
                assertEquals(3, all.items().size());
                assertTrue(all.items().stream().filter(i -> i.orderId().equals("701000"))
                        .findFirst().orElseThrow().protectRequired());
                assertFalse(all.items().stream().filter(i -> i.orderId().equals("701001"))
                        .findFirst().orElseThrow().protectRequired());
                assertFalse(all.items().stream().filter(i -> i.orderId().equals("701002"))
                        .findFirst().orElseThrow().protectRequired());
                var byReservations = apis.order.getByReservations(STORE,
                        List.of("701100", "701101", "701102"), CTX);
                assertEquals(3, byReservations.items().size());
                var filtered = apis.order.getCurrentAssignments(STORE, List.of(STAFF_A), CTX);
                assertTrue(filtered.complete());
                assertEquals(2, filtered.totalCurrentAssignments(), "total precedes filtering");
                assertEquals(1, filtered.items().size());
                assertEquals("701000", filtered.items().getFirst().orderId());
                return null;
            });
        }
    }

    @Test
    void orderRejectsOneSidedCurrentAssignmentAndGlobalOrphan() throws Exception {
        try (Database db = new Database()) {
            db.store(Long.parseLong(STORE));
            db.staff(Long.parseLong(STAFF_A), Long.parseLong(STORE), "ACTIVE", 1);
            db.service(SERVICE_A, Long.parseLong(STORE), "IN_STORE");
            db.window(700030, 700001, SERVICE_A, "GENERAL", NINE, NOON, 2);
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700030,
                    NINE, TEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", Long.parseLong(STAFF_A));
            var apis = new ProtectionApis(db.source);
            db.jdbc.update("UPDATE pet_order SET service_staff_id=NULL WHERE id=701000");
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.order.readStore(STORE, CTX);
                return null;
            }));
            db.jdbc.update("UPDATE pet_order SET service_staff_id=? WHERE id=701000", STAFF_A);
            db.assignment(999001, 999000, Long.parseLong(STAFF_A), true);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.order.getCurrentAssignments(STORE, List.of("999999"), CTX);
                return null;
            }), "a filtered empty target cannot conceal a global orphan assignment");
        }
    }

    @Test
    void releasedButPendingServiceOrderFailsClosedEvenWithoutActiveClaim() throws Exception {
        try (Database db = new Database()) {
            db.store(Long.parseLong(STORE));
            db.staff(Long.parseLong(STAFF_A), Long.parseLong(STORE), "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "IN_STORE");
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700030,
                    NINE, TEN, "RELEASED", "PENDING_SERVICE", "UNVERIFIED", Long.parseLong(STAFF_A));
            var apis = new ProtectionApis(db.source);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.order.getCurrentAssignments(STORE, List.of("999999"), CTX);
                return null;
            }), "all-store validation must find released but in-flight assignment");
        }
    }

    @Test
    void orderRejectsMissingCurrentRowAndCompletedWithoutVerification() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            db.staff(Long.parseLong(STAFF_A), 700001, "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "IN_STORE");
            db.window(700030, 700001, SERVICE_A, "GENERAL", NINE, NOON, 1);
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700030,
                    NINE, TEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", Long.parseLong(STAFF_A));
            var apis = new ProtectionApis(db.source);
            db.jdbc.update("DELETE FROM order_staff_assignment WHERE order_id=701000");
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.order.readStore(STORE, CTX);
                return null;
            }));
            db.jdbc.update("UPDATE pet_order SET service_staff_id=NULL,order_stage='COMPLETED' WHERE id=701000");
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.order.readStore(STORE, CTX);
                return null;
            }), "COMPLETED + UNVERIFIED is not a released future-capacity fact");
        }
    }

    @Test
    void allStoreOrderReadDoesNotLockHealthyOrdersInOtherStore() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            try (Database db = new Database()) {
                db.store(Long.parseLong(STORE));
                db.store(Long.parseLong(OTHER_STORE));
                db.staff(Long.parseLong(STAFF_A), Long.parseLong(STORE), "ACTIVE", 1);
                db.staff(Long.parseLong(STAFF_B), Long.parseLong(OTHER_STORE), "ACTIVE", 1);
                db.service(SERVICE_A, Long.parseLong(STORE), "IN_STORE");
                db.service(SERVICE_B, Long.parseLong(OTHER_STORE), "IN_STORE");
                db.window(700030, 700001, SERVICE_A, "GENERAL", NINE, NOON, 1);
                db.window(700031, 700002, SERVICE_B, "GENERAL", NINE, NOON, 1);
                db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700030,
                        NINE, TEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", Long.parseLong(STAFF_A));
                db.generalOrder(701001, 701101, 702001, 700002, SERVICE_B, 700031,
                        NINE, TEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", Long.parseLong(STAFF_B));
                var apis = new ProtectionApis(db.source);
                try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                    CountDownLatch firstHeld = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    Future<?> first = workers.submit(() -> db.inTransaction(() -> {
                        apis.guard.acquire(List.of(STORE), CTX);
                        db.jdbc.queryForObject("SELECT id FROM pet_order WHERE id=701000 FOR UPDATE", Long.class);
                        db.jdbc.queryForObject("SELECT id FROM order_staff_assignment WHERE order_id=701000 FOR UPDATE", Long.class);
                        firstHeld.countDown();
                        await(release);
                        return null;
                    }));
                    assertTrue(firstHeld.await(3, TimeUnit.SECONDS));
                    Future<?> second = workers.submit(() -> db.inTransaction(() -> {
                        apis.guard.acquire(List.of(OTHER_STORE), CTX);
                        var facts = apis.order.readStore(OTHER_STORE, CTX);
                        assertTrue(facts.complete());
                        assertEquals(1, facts.totalCurrentAssignments());
                        assertEquals("701001", facts.items().getFirst().orderId());
                        return null;
                    }));
                    try { second.get(3, TimeUnit.SECONDS); }
                    finally { release.countDown(); }
                    assertFalse(first.isDone(), "Y facts must finish while X rows are locked");
                    first.get(3, TimeUnit.SECONDS);
                }
            }
        });
    }

    @Test
    void proofSearchesBeyondGreedyFirstStaffAndDoesNotAssignOrder() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            db.staff(Long.parseLong(STAFF_A), 700001, "ACTIVE", 1);
            db.staff(Long.parseLong(STAFF_B), 700001, "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "IN_STORE");
            db.service(SERVICE_B, 700001, "IN_STORE");
            db.window(700030, 700001, SERVICE_A, "GENERAL", NINE, NOON, 1);
            db.window(700031, 700001, SERVICE_B, "GENERAL", NINE, NOON, 1);
            db.capability(700040, Long.parseLong(STAFF_A), SERVICE_A);
            db.capability(700041, Long.parseLong(STAFF_A), SERVICE_B);
            db.capability(700042, Long.parseLong(STAFF_B), SERVICE_A);
            db.availability(700050, 700001, Long.parseLong(STAFF_A), NINE, NOON);
            db.availability(700051, 700001, Long.parseLong(STAFF_B), NINE, NOON);
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700030,
                    TEN, ELEVEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", null);
            var apis = new ProtectionApis(db.source);
            db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                var result = apis.proof.checkNewReservation(inStore(SERVICE_B, 700031, TEN, ELEVEN));
                assertTrue(result.feasible(), "X can use B so Y can use its only candidate A");
                assertTrue(result.evaluatedReservations() >= 2);
                return null;
            });
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pet_order WHERE service_staff_id IS NOT NULL", Integer.class));
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM order_staff_assignment", Integer.class));
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule_reservation", Integer.class), "proof writes no candidate reservation");
        }
    }

    @Test
    void proofRejectsCrossServiceDoubleUseAndRespectsFixedAssignment() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            db.staff(Long.parseLong(STAFF_A), 700001, "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "IN_STORE");
            db.service(SERVICE_B, 700001, "IN_STORE");
            db.window(700030, 700001, SERVICE_A, "GENERAL", NINE, NOON, 1);
            db.window(700031, 700001, SERVICE_B, "GENERAL", NINE, NOON, 1);
            db.capability(700040, Long.parseLong(STAFF_A), SERVICE_A);
            db.capability(700041, Long.parseLong(STAFF_A), SERVICE_B);
            db.availability(700050, 700001, Long.parseLong(STAFF_A), NINE, NOON);
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700030,
                    TEN, ELEVEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", Long.parseLong(STAFF_A));
            var apis = new ProtectionApis(db.source);
            assertCode("SCHEDULE_CAPACITY_EXCEEDED", () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.proof.checkNewReservation(inStore(SERVICE_B, 700031, TEN, ELEVEN));
                return null;
            }));
            db.staff(Long.parseLong(STAFF_B), 700001, "ACTIVE", 1);
            db.capability(700042, Long.parseLong(STAFF_B), SERVICE_A);
            db.availability(700051, 700001, Long.parseLong(STAFF_B), NINE, NOON);
            assertCode("SCHEDULE_CAPACITY_EXCEEDED", () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.proof.checkNewReservation(inStore(SERVICE_B, 700031, TEN, ELEVEN));
                return null;
            }), "existing X is fixed to A; solver cannot silently rematch it to B");
            assertEquals(Long.parseLong(STAFF_A), db.jdbc.queryForObject(
                    "SELECT service_staff_id FROM pet_order WHERE id=701000", Long.class));
        }
    }

    @Test
    void proofAllowsHalfOpenAdjacencyButEnforcesConfiguredWindowCapacity() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            db.staff(Long.parseLong(STAFF_A), 700001, "ACTIVE", 1);
            db.staff(Long.parseLong(STAFF_B), 700001, "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "IN_STORE");
            db.window(700030, 700001, SERVICE_A, "GENERAL", NINE, NOON, 1);
            db.capability(700040, Long.parseLong(STAFF_A), SERVICE_A);
            db.capability(700041, Long.parseLong(STAFF_B), SERVICE_A);
            db.availability(700050, 700001, Long.parseLong(STAFF_A), NINE, NOON);
            db.availability(700051, 700001, Long.parseLong(STAFF_B), NINE, NOON);
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700030,
                    NINE, TEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", Long.parseLong(STAFF_A));
            var apis = new ProtectionApis(db.source);
            db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                assertTrue(apis.proof.checkNewReservation(inStore(SERVICE_A, 700030, TEN, ELEVEN)).feasible());
                return null;
            });
            assertCode("SCHEDULE_CAPACITY_EXCEEDED", () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.proof.checkNewReservation(inStore(SERVICE_A, 700030, "2030-01-01 09:30:00", "2030-01-01 10:30:00"));
                return null;
            }), "two qualified workers do not override one configured window slot");
        }
    }

    @Test
    void proofChecksAllStoreWindowClaimsEvenWhenPersonnelClosureIsDisjoint() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            db.staff(Long.parseLong(STAFF_A), 700001, "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "IN_STORE");
            db.window(700029, 700001, SERVICE_A, "GENERAL", NINE, TEN, 1);
            db.window(700030, 700001, SERVICE_A, "GENERAL", NOON, ONE_PM, 1);
            db.capability(700040, Long.parseLong(STAFF_A), SERVICE_A);
            db.availability(700050, 700001, Long.parseLong(STAFF_A), NOON, ONE_PM);
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_A, 700029,
                    NINE, TEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", null);
            db.generalOrder(701001, 701101, 702001, 700001, SERVICE_A, 700029,
                    NINE, TEN, "CONFIRMED", "PENDING_SERVICE", "UNVERIFIED", null);
            var apis = new ProtectionApis(db.source);
            assertCode("SCHEDULE_CAPACITY_EXCEEDED", () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.proof.checkNewReservation(inStore(SERVICE_A, 700030, NOON, ONE_PM));
                return null;
            }), "unrelated earlier window already exceeds its configured capacity");
        }
    }

    @Test
    void proofRequiresOneWorkerForBothPickupAndReturnButAllowsGapBetweenThem() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            db.staff(Long.parseLong(STAFF_A), 700001, "ACTIVE", 1);
            db.staff(Long.parseLong(STAFF_B), 700001, "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "PICKUP_DELIVERY");
            db.window(700030, 700001, SERVICE_A, "PICKUP", TEN, ELEVEN, 1);
            db.window(700031, 700001, SERVICE_A, "RETURN", NOON, ONE_PM, 1);
            db.capability(700040, Long.parseLong(STAFF_A), SERVICE_A);
            db.capability(700041, Long.parseLong(STAFF_B), SERVICE_A);
            db.availability(700050, 700001, Long.parseLong(STAFF_A), TEN, ELEVEN);
            db.availability(700051, 700001, Long.parseLong(STAFF_A), NOON, ONE_PM);
            var apis = new ProtectionApis(db.source);
            CapacityProofQuery query = new CapacityProofQuery(STORE, Long.toString(SERVICE_A), "PICKUP_DELIVERY",
                    null, null, null, "700030", "700031", CTX);
            db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                assertTrue(apis.proof.checkNewReservation(query).feasible());
                return null;
            });
            db.jdbc.update("DELETE FROM staff_availability_window WHERE id=700051");
            db.availability(700052, 700001, Long.parseLong(STAFF_B), NOON, ONE_PM);
            assertCode("SCHEDULE_CAPACITY_EXCEEDED", () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.proof.checkNewReservation(query);
                return null;
            }), "different workers covering opposite legs do not form one feasible order worker");
        }
    }

    @Test
    void completedVerifiedPastClaimDoesNotRequireWorkersCurrentQualificationButFutureClaimFails() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            db.staff(Long.parseLong(STAFF_A), 700001, "INACTIVE", 0);
            db.staff(Long.parseLong(STAFF_B), 700001, "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "IN_STORE");
            db.service(SERVICE_B, 700001, "IN_STORE");
            String oldStart = "2020-01-01 10:00:00";
            String oldEnd = "2020-01-01 11:00:00";
            db.window(700029, 700001, SERVICE_B, "GENERAL", oldStart, oldEnd, 1);
            db.window(700030, 700001, SERVICE_A, "GENERAL", NINE, NOON, 1);
            db.generalOrder(701000, 701100, 702000, 700001, SERVICE_B, 700029,
                    oldStart, oldEnd, "CONFIRMED", "COMPLETED", "VERIFIED", Long.parseLong(STAFF_A));
            db.capability(700040, Long.parseLong(STAFF_B), SERVICE_A);
            db.availability(700050, 700001, Long.parseLong(STAFF_B), NINE, NOON);
            var apis = new ProtectionApis(db.source);
            db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                assertFalse(apis.order.readStore(STORE, CTX).items().getFirst().protectRequired());
                assertTrue(apis.proof.checkNewReservation(inStore(SERVICE_A, 700030, TEN, ELEVEN)).feasible());
                return null;
            });
            db.jdbc.update("UPDATE schedule_availability_window SET start_at=?,end_at=? WHERE id=700029", TEN, ELEVEN);
            db.jdbc.update("UPDATE schedule_reservation SET start_at=?,end_at=? WHERE id=701100", TEN, ELEVEN);
            db.jdbc.update("UPDATE schedule_reservation_claim SET start_at=?,end_at=? WHERE reservation_id=701100", TEN, ELEVEN);
            db.jdbc.update("UPDATE pet_order SET appointment_start_at=?,appointment_end_at=? WHERE id=701000", TEN, ELEVEN);
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                apis.proof.checkNewReservation(inStore(SERVICE_A, 700030, TEN, ELEVEN));
                return null;
            }), "COMPLETED + VERIFIED must not conceal a future active claim");
        }
    }

    @Test
    void exactSolverMatchesIndependentExhaustiveOracleAcrossSmallFixedSeedGraphs() {
        var random = new Random(250927L);
        var solver = new CapacityFeasibilitySolver(System::nanoTime);
        for (int caseId = 0; caseId < 150; caseId++) {
            List<Staff> staff = new ArrayList<>();
            for (int person = 0; person < 3; person++) {
                var services = new java.util.HashSet<String>();
                for (int service = 0; service < 3; service++)
                    if (random.nextBoolean()) services.add("s" + service);
                staff.add(new Staff("p" + person, services,
                        List.of(new Interval(Instant.parse("2030-01-01T08:00:00Z"),
                                Instant.parse("2030-01-01T15:00:00Z")))));
            }
            List<Reservation> reservations = new ArrayList<>();
            List<Window> windows = new ArrayList<>();
            int count = 4 + random.nextInt(2);
            for (int index = 0; index < count; index++) {
                int startHour = 9 + random.nextInt(4);
                int endHour = startHour + 1 + random.nextInt(2);
                Interval interval = new Interval(Instant.parse(String.format("2030-01-01T%02d:00:00Z", startHour)),
                        Instant.parse(String.format("2030-01-01T%02d:00:00Z", endHour)));
                String window = "w" + index;
                windows.add(new Window(window, 1));
                reservations.add(new Reservation("r" + index, "s" + random.nextInt(3),
                        List.of(new Claim(window, interval)),
                        random.nextInt(5) == 0 ? "p" + random.nextInt(3) : null));
            }
            boolean expected = independentOracle(reservations, staff);
            var actual = solver.solve("r0", reservations, windows, staff, 1000);
            assertEquals(expected ? CapacityFeasibilitySolver.Outcome.FEASIBLE
                            : CapacityFeasibilitySolver.Outcome.INFEASIBLE,
                    actual.outcome(), "fixed-seed graph " + caseId);
        }
    }

    @Test
    void deterministicProofBudgetExhaustionMapsToDependencyUnavailableWithNoWrites() throws Exception {
        try (Database db = new Database()) {
            db.store(700001);
            db.staff(Long.parseLong(STAFF_A), 700001, "ACTIVE", 1);
            db.service(SERVICE_A, 700001, "IN_STORE");
            db.window(700030, 700001, SERVICE_A, "GENERAL", NINE, NOON, 1);
            db.capability(700040, Long.parseLong(STAFF_A), SERVICE_A);
            db.availability(700050, 700001, Long.parseLong(STAFF_A), NINE, NOON);
            var apis = new ProtectionApis(db.source);
            AtomicLong elapsed = new AtomicLong();
            ScheduleCapacityProofApi bounded = new ScheduleCapacityProofApiImpl(db.source,
                    apis.guard, apis.schedule, apis.merchant, apis.order, Clock.systemUTC(),
                    1, () -> elapsed.getAndAdd(2_000_000L));
            assertCode(CommonApiCodes.DEPENDENCY_UNAVAILABLE, () -> db.inTransaction(() -> {
                apis.guard.acquire(List.of(STORE), CTX);
                bounded.checkNewReservation(inStore(SERVICE_A, 700030, TEN, ELEVEN));
                return null;
            }));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM schedule_reservation", Integer.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM pet_order", Integer.class));
        }
    }

    /** Tiny test-only brute force: ordered index enumeration, no production sort or pruning. */
    private static boolean independentOracle(List<Reservation> all, List<Staff> staff) {
        boolean[] included = new boolean[all.size()];
        included[0] = true;
        boolean expanded;
        do {
            expanded = false;
            for (int i = 0; i < all.size(); i++) {
                if (!included[i]) continue;
                for (int j = 0; j < all.size(); j++) {
                    if (!included[j] && overlaps(all.get(i), all.get(j))) {
                        included[j] = true;
                        expanded = true;
                    }
                }
            }
        } while (expanded);
        List<Reservation> relevant = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (included[i]) relevant.add(all.get(i));
        return enumerateAssignments(relevant, staff, 0, new int[relevant.size()]);
    }

    private static boolean enumerateAssignments(List<Reservation> reservations, List<Staff> staff,
            int position, int[] assignment) {
        if (position == reservations.size()) return true;
        Reservation current = reservations.get(position);
        for (int worker = 0; worker < staff.size(); worker++) {
            Staff person = staff.get(worker);
            if (current.fixedStaffId() != null && !current.fixedStaffId().equals(person.id())) continue;
            if (!person.serviceIds().contains(current.serviceId())) continue;
            boolean conflict = false;
            for (int earlier = 0; earlier < position; earlier++) {
                if (assignment[earlier] == worker && overlaps(current, reservations.get(earlier))) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) continue;
            assignment[position] = worker;
            if (enumerateAssignments(reservations, staff, position + 1, assignment)) return true;
        }
        return false;
    }

    private static boolean overlaps(Reservation left, Reservation right) {
        for (Claim a : left.claims()) for (Claim b : right.claims())
            if (a.interval().start().isBefore(b.interval().end())
                    && b.interval().start().isBefore(a.interval().end())) return true;
        return false;
    }

    private static void assertCode(String expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiException.class, action::run).code());
    }

    private static void assertCode(String expected, Runnable action, String message) {
        assertEquals(expected, assertThrows(ApiException.class, action::run, message).code(), message);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class RollBackFirst extends RuntimeException {}

    private static final class ProtectionApis {
        final ScheduleCapacityGuardApi guard;
        final ScheduleProtectionFactsApi schedule;
        final MerchantCurrentStaffFactsApi merchant;
        final OrderProtectionFactsApi order;
        final ScheduleCapacityProofApi proof;

        ProtectionApis(DataSource source) {
            guard = new ScheduleCapacityGuardApiImpl(source);
            schedule = new ScheduleProtectionFactsApiImpl(source, guard);
            merchant = new MerchantCurrentStaffFactsApiImpl(source, guard);
            order = new OrderProtectionFactsApiImpl(source, guard, schedule, merchant, Clock.systemUTC());
            proof = new ScheduleCapacityProofApiImpl(source, guard, schedule, merchant, order,
                    Clock.systemUTC(), 10_000);
        }
    }

    private static OffsetDateTime at(String utc) {
        return OffsetDateTime.parse(utc.replace(' ', 'T') + "Z");
    }

    private static CapacityProofQuery inStore(long serviceId, long windowId, String start, String end) {
        return new CapacityProofQuery(STORE, Long.toString(serviceId), "IN_STORE", at(start), at(end),
                Long.toString(windowId), null, null, CTX);
    }

    private static final class Database implements AutoCloseable {
        private final String name = "qa_reservation_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final DataSource otherSource;
        private final JdbcTemplate jdbc;
        private final DataSourceTransactionManager manager;

        Database() throws Exception {
            String prefix = System.getenv().containsKey("PROTECTION_MYSQL_URL") ? "PROTECTION"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "PROTECTION";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL", "jdbc:mysql://127.0.0.1:33457/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))
                throw new IllegalArgumentException(prefix + "_MYSQL_URL must be a dedicated local server root");
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            otherSource = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            manager = new DataSourceTransactionManager(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                script("docs/03-database/06-核心数据库Schema-v0.1.sql");
                script("docs/03-database/37-Reservation-Protection-Foundation-Schema-v0.1.sql");
            } catch (Exception failure) { close(); throw failure; }
        }

        <T> T inTransaction(java.util.concurrent.Callable<T> action) {
            return withMode(TransactionDefinition.ISOLATION_READ_COMMITTED, false, action);
        }

        <T> T withMode(int isolation, boolean readOnly, java.util.concurrent.Callable<T> action) {
            TransactionTemplate tx = new TransactionTemplate(manager);
            tx.setIsolationLevel(isolation);
            tx.setReadOnly(readOnly);
            return tx.execute(status -> {
                try { return action.call(); }
                catch (RuntimeException error) { throw error; }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
        }

        TransactionTemplate newTransaction() {
            TransactionTemplate tx = new TransactionTemplate(manager);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx;
        }

        void store(long id) {
            jdbc.update("INSERT IGNORE INTO merchant(id,owner_user_id,merchant_name,status,created_at,updated_at)"
                    + " VALUES(700000,700100,'QA Merchant','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,created_at,updated_at)"
                    + " VALUES(?,700000,'QA Store','QA Address','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", id);
        }

        void staff(long id, long storeId, String status, int enabled) {
            jdbc.update("INSERT INTO merchant_staff"
                    + "(id,merchant_id,store_id,staff_name,employment_status,service_enabled,created_at,updated_at)"
                    + " VALUES(?,700000,?,'QA Staff',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, storeId, status, enabled);
        }

        void service(long id, long storeId, String fulfillment) {
            jdbc.update("INSERT IGNORE INTO service_category"
                    + "(id,category_name,status,created_at,updated_at)"
                    + " VALUES(700090,'QA Category','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO service_item"
                    + "(id,merchant_id,store_id,category_id,service_name,price,duration_minutes,fulfillment_type,status,created_at,updated_at)"
                    + " VALUES(?,700000,?,700090,'QA Service',100.00,60,?,'ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, storeId, fulfillment);
        }

        void window(long id, long storeId, long serviceId, String kind,
                String start, String end, int configuredCapacity) {
            jdbc.update("INSERT INTO schedule_availability_window"
                    + "(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
                    + " VALUES(?,700000,?,?,?,?,?,'OPEN',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, storeId, serviceId, start, end, configuredCapacity, kind);
        }

        void capability(long id, long staffId, long serviceId) {
            jdbc.update("INSERT INTO staff_service_capability"
                    + "(id,staff_id,service_id,status,created_at,updated_at)"
                    + " VALUES(?,?,?,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, staffId, serviceId);
        }

        void availability(long id, long storeId, long staffId, String start, String end) {
            jdbc.update("INSERT INTO staff_availability_window"
                    + "(id,store_id,staff_id,start_at,end_at,status,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,'AVAILABLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, storeId, staffId, start, end);
        }

        void reservation(long id, long orderId, long userId, long storeId, long serviceId,
                String fulfillment, String start, String end, String pickup, String returned, String status) {
            jdbc.update("INSERT INTO schedule_reservation"
                    + "(id,order_id,user_id,merchant_id,store_id,service_id,fulfillment_type,start_at,end_at,"
                    + "pickup_start_at,return_start_at,status,capacity_snapshot,qualified_staff_count_snapshot,"
                    + "created_at,updated_at)"
                    + " VALUES(?,?,?,700000,?,?,?,?,?,?,?,?,1,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, orderId, userId, storeId, serviceId, fulfillment, start, end, pickup, returned, status);
        }

        void claim(long id, long reservationId, long windowId, long storeId, long serviceId,
                String kind, String start, String end) {
            jdbc.update("INSERT INTO schedule_reservation_claim"
                    + "(id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)", id, reservationId, windowId, storeId, serviceId, kind, start, end);
        }

        void order(long id, long reservationId, long userId, long storeId, long serviceId,
                String fulfillment, Long staffId, String stage, String verification, String start, String end) {
            jdbc.update("INSERT INTO pet_order"
                    + "(id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,service_staff_id,"
                    + "order_stage,payment_status,verification_status,fulfillment_type,original_amount,pay_amount,"
                    + "appointment_start_at,appointment_end_at,created_at,updated_at)"
                    + " VALUES(?,?,?,700000,?,?,700200,?,?,?, 'INIT',?,?,100.00,100.00,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, id + 100000L, userId, storeId, serviceId, reservationId, staffId,
                    stage, verification, fulfillment, start, end);
        }

        void assignment(long id, long orderId, long staffId, boolean current) {
            jdbc.update("INSERT INTO order_staff_assignment"
                    + "(id,order_id,staff_id,assigned_by_type,is_current,assigned_at,version)"
                    + " VALUES(?,?,?,'MERCHANT',?,UTC_TIMESTAMP(3),0)", id, orderId, staffId, current ? 1 : 0);
        }

        void generalOrder(long orderId, long reservationId, long userId, long storeId,
                long serviceId, long windowId, String start, String end,
                String reservationStatus, String orderStage, String verification, Long staffId) {
            reservation(reservationId, orderId, userId, storeId, serviceId, "IN_STORE",
                    start, end, null, null, reservationStatus);
            if ("TEMP_LOCKED".equals(reservationStatus) || "CONFIRMED".equals(reservationStatus))
                claim(reservationId + 100000L, reservationId, windowId, storeId,
                        serviceId, "GENERAL", start, end);
            order(orderId, reservationId, userId, storeId, serviceId, "IN_STORE", staffId,
                    orderStage, verification, start, end);
            if (staffId != null) assignment(orderId + 100000L, orderId, staffId, true);
        }

        private void script(String relative) throws Exception {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(root.resolve(relative))) root = root.getParent();
            assertNotNull(root, "schema root missing: " + relative);
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root.resolve(relative)), StandardCharsets.UTF_8));
            }
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(
                    url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password) {
                @Override public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (SQLException failure) { connection.close(); throw failure; }
                }
            };
        }

        @Override public void close() { admin.execute("DROP DATABASE `" + name + "`"); }
    }
}
