package com.petplatform.service.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantDisplayEligibilityDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityQuery;
import com.petplatform.service.api.command.ServiceFlashPriceReservationApi;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.AdminReviewCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CreateFlashPriceCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.error.ServiceFlashPriceApiCodes;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.biz.apiimpl.ServiceFlashPriceAdminApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceFlashPriceMerchantApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceFlashPriceReservationApiImpl;
import com.petplatform.service.biz.application.FlashPriceEffectiveResolver;
import com.petplatform.service.biz.application.FlashPriceReleaseService;
import com.petplatform.service.biz.application.ServiceAdmissionGate;
import com.petplatform.service.biz.application.ServiceQueryService;
import com.petplatform.service.biz.event.OrderPaidFlashPriceConsumer;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.infrastructure.persistence.ServiceReadStore;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 77 service-side acceptance over isolated real MySQL (HANDOFF §9.2 scenarios
 * 1/2/3/5/6/7/10): merchant lifecycle DRAFT→PENDING_REVIEW→review decisions, serialized window
 * overlap, approve-time price revalidation, reserve window/quota/limit CAS, the OrderPaid
 * consumer, the durable release round, the switch-off fail-closed face and the C read-side
 * resolution. Money math and quota counters are asserted on the authoritative rows.
 */
class ServiceFlashPriceMySqlTest {

    private static final long MERCHANT = 7_100_000_000_000_101L;
    private static final long STORE = 7_100_000_000_000_201L;
    private static final long OWNER = 7_100_000_000_000_301L;
    private static final long OPERATOR = 7_100_000_000_000_901L;

    /** Mutable clock: the lifecycle phases advance deterministically through it. */
    private static final class SettableClock extends Clock {
        private volatile Instant instant;

        SettableClock(Instant start) {
            this.instant = start;
        }

        void set(Instant value) {
            this.instant = value;
        }

        void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    // ------------------------------------------------------------------ scenario 1

    @Test
    void merchantLifecycleFollowsTheApprovedStateMachine() throws Exception {
        try (Database db = new Database()) {
            db.serviceRow(7_101L, "100.00", "ACTIVE");
            ServiceFlashPriceMerchantApiImpl merchant = db.merchant();
            ServiceFlashPriceAdminApiImpl admin = db.admin();

            // Create lands DRAFT with the server-computed flash price preview.
            CommandContext pin = userContext();
            CreateFlashPriceCommand firstCreate =
                    command(pin, 7_101L, null, "8.8", db.now.plusHours(1), db.now.plusHours(2),
                            20, 1);
            FlashPriceItem created = merchant.create(firstCreate);
            assertEquals("DRAFT", created.status());
            assertEquals("洗护服务", created.title(), "omitted title defaults to the service name");
            assertEquals(new BigDecimal("88.00"), created.flashPrice());
            assertEquals(new BigDecimal("100.00"), created.listPriceSnapshot());
            assertEquals(1, created.version());
            assertEquals(0, created.soldCount());
            assertEquals(0, created.participantCount());

            // requestId replay returns the first receipt; different parameters conflict.
            assertEquals(created.flashPriceId(),
                    merchant.create(firstCreate).flashPriceId());
            assertApi(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, () -> merchant.create(
                    command(pin, 7_101L, "另一个标题", "8.8",
                            db.now.plusHours(1), db.now.plusHours(2), 20, 1)));

            // Edit recomputes the preview from the current service price.
            FlashPriceItem updated = merchant.update(new com.petplatform.service.api.dto
                    .ServiceFlashPriceTypes.UpdateFlashPriceCommand(
                    userContext(), "7101", created.flashPriceId(), created.version(),
                    "新年秒杀", "全场洗护限时", null, new BigDecimal("5.0"),
                    db.now.plusHours(3), db.now.plusHours(4), 30, 2));
            assertEquals(new BigDecimal("50.00"), updated.flashPrice());
            assertEquals(2, updated.version());
            assertTrue(updated.version() > created.version());

            FlashPriceItem submitted = merchant.submit(submit(7_101L, updated));
            assertEquals("PENDING_REVIEW", submitted.status());
            // After submission the merchant can neither edit nor publish (no such route).
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_STATE_NOT_ALLOWED,
                    () -> merchant.update(new com.petplatform.service.api.dto
                            .ServiceFlashPriceTypes.UpdateFlashPriceCommand(
                            userContext(), "7101", submitted.flashPriceId(), submitted.version(),
                            "改标题", null, null, new BigDecimal("5.0"),
                            db.now.plusHours(3), db.now.plusHours(4), 30, 2)));

            // Approve with a future start lands SCHEDULED; pause/resume/end round the loop.
            FlashPriceItem scheduled = admin.approve(review(submitted));
            assertEquals("SCHEDULED", scheduled.status());
            FlashPriceItem paused = admin.pause(review(scheduled));
            assertEquals("PAUSED", paused.status());
            FlashPriceItem resumed = admin.approve(review(paused));
            assertEquals("SCHEDULED", resumed.status());
            assertEquals("ENDED", admin.end(review(resumed)).status());

            // Reject keeps the row merchant-editable and resubmittable.
            FlashPriceItem toReject = merchant.submit(submit(7_101L, merchant.create(
                    command(7_101L, "待驳回", "9.5",
                            db.now.plusHours(30), db.now.plusHours(31), null, null))));
            FlashPriceItem rejected = admin.reject(new AdminReviewCommand(
                    operatorContext(), toReject.flashPriceId(), toReject.version(), "折扣不合理请调整"));
            assertEquals("REJECTED", rejected.status());
            assertEquals("折扣不合理请调整", rejected.rejectReason());
            FlashPriceItem fixed = merchant.update(new com.petplatform.service.api.dto
                    .ServiceFlashPriceTypes.UpdateFlashPriceCommand(
                    userContext(), "7101", rejected.flashPriceId(), rejected.version(),
                    "新年秒杀", null, null, new BigDecimal("5.0"),
                    db.now.minusMinutes(5), db.now.plusHours(1), null, null));
            assertNull(fixed.rejectReason());
            FlashPriceItem relisted = admin.approve(review(merchant.submit(submit(7_101L, fixed))));
            assertEquals("ACTIVE", relisted.status(), "startAt<=now approves straight to ACTIVE");

            // Cancel is allowed before and during review, never after going live.
            FlashPriceItem cancellable = merchant.create(command(7_101L, "可取消", "8.0",
                    db.now.plusHours(24), db.now.plusHours(25), null, null));
            assertEquals("CANCELED", merchant.cancel(cancel(7_101L, cancellable)).status());
            FlashPriceItem pending = merchant.submit(submit(7_101L, merchant.create(
                    command(7_101L, "审核中取消", "8.0",
                            db.now.plusHours(26), db.now.plusHours(27), null, null))));
            assertEquals("CANCELED", merchant.cancel(cancel(7_101L, pending)).status());
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_STATE_NOT_ALLOWED,
                    () -> merchant.cancel(cancel(7_101L, relisted)));

            // Every merchant/admin transition left an append-only audit row.
            Long audits = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM service_flash_price_audit", Long.class);
            assertNotNull(audits);
            assertTrue(audits >= 10, "audit trail must cover every transition");
        }
    }

    // ------------------------------------------------------------------ scenario 2

    @Test
    void overlappingWindowsAreRejectedAndConcurrentCreatesSerialize() throws Exception {
        try (Database db = new Database()) {
            db.serviceRow(7_102L, "200.00", "ACTIVE");
            ServiceFlashPriceMerchantApiImpl merchant = db.merchant();

            // A submitted (PENDING_REVIEW) window blocks any intersecting new window.
            FlashPriceItem morning = merchant.submit(submit(7_102L, merchant.create(
                    command(7_102L, "上午场", "8.0",
                            db.now.plusHours(1), db.now.plusHours(2), null, null))));
            assertEquals("PENDING_REVIEW", morning.status());
            // Any intersection with a live (PENDING_REVIEW/SCHEDULED/ACTIVE/PAUSED) window is
            // refused; terminal (DRAFT-never-submitted/CANCELED/ENDED/REJECTED) rows do not block.
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_WINDOW_OVERLAP, () -> merchant.create(
                    command(7_102L, "重叠场", "8.0",
                            db.now.plusMinutes(90), db.now.plusHours(3), null, null)));
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_WINDOW_OVERLAP, () -> merchant.create(
                    command(7_102L, "内含场", "8.0",
                            db.now.plusMinutes(70), db.now.plusMinutes(80), null, null)));
            // Back-to-back windows do not intersect (half-open intervals).
            assertEquals("DRAFT", merchant.create(command(7_102L, "下午场", "8.0",
                    db.now.plusHours(2), db.now.plusHours(3), null, null)).status());
            // A SCHEDULED window blocks just the same after approval.
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_WINDOW_OVERLAP, () -> merchant.create(
                    command(7_102L, "审批后重叠场", "8.0",
                            db.now.plusMinutes(95), db.now.plusMinutes(105), null, null)));
            FlashPriceItem approvedMorning = db.admin().approve(new AdminReviewCommand(
                    operatorContext(), morning.flashPriceId(), morning.version(), null));
            assertEquals("SCHEDULED", approvedMorning.status());
            // After the live row ends the window frees up again.
            assertEquals("ENDED", db.admin().end(new AdminReviewCommand(
                    operatorContext(), approvedMorning.flashPriceId(),
                    approvedMorning.version(), null)).status());
            assertEquals("DRAFT", merchant.create(command(7_102L, "复用场", "8.0",
                    db.now.plusHours(1), db.now.plusHours(2), null, null)).status());

            // Concurrent quota race (CCR §7: 总量 1 抢 2 成功 1): two buyers reserve the last
            // slot in parallel transactions — the FOR UPDATE flash row plus the sold_count CAS
            // serialize them, exactly one ledger row survives.
            db.serviceRow(7_103L, "300.00", "ACTIVE");
            FlashPriceItem singleQuota = merchant.submit(submit(7_103L, merchant.create(
                    command(7_103L, "抢一份", "8.0",
                            db.now.minusMinutes(5), db.now.plusHours(2), 1, null))));
            FlashPriceItem single = db.admin().approve(db.reviewBy(singleQuota.flashPriceId()));
            assertEquals("ACTIVE", single.status());
            ServiceFlashPriceReservationApiImpl racingReserve = db.reservation();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger successes = new AtomicInteger();
                AtomicReference<ApiException> failure = new AtomicReference<>();
                List<Future<Void>> jobs = List.of(
                        raceReserve(db, racingReserve, single.flashPriceId(),
                                "9103100000000001", "8103100000000001", start, successes, failure),
                        raceReserve(db, racingReserve, single.flashPriceId(),
                                "9103100000000002", "8103100000000002", start, successes, failure));
                start.countDown();
                for (Future<Void> job : jobs) job.get(30, TimeUnit.SECONDS);
                assertEquals(1, successes.get(), "exactly one concurrent reserve may win");
                assertNotNull(failure.get(), "the loser must fail closed");
                assertEquals(ServiceFlashPriceApiCodes.FLASH_PRICE_QUOTA_EXCEEDED,
                        failure.get().code());
                assertEquals(1, soldCount(db, single.flashPriceId()));
                Long ledger = db.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM service_flash_price_order WHERE flash_price_id=?"
                                + " AND status='RESERVED'",
                        Long.class, Long.parseLong(single.flashPriceId()));
                assertEquals(1L, ledger);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static Future<Void> raceReserve(
            Database db,
            ServiceFlashPriceReservationApiImpl reserveApi,
            String flashPriceId,
            String orderId,
            String userId,
            CountDownLatch start,
            AtomicInteger successes,
            AtomicReference<ApiException> failure) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Void> job = pool.submit(() -> {
            start.await();
            try {
                db.inTransaction(() -> reserveApi.reserve(reserve(
                        flashPriceId, orderId, userId, db.now.plusMinutes(15))));
                successes.incrementAndGet();
            } catch (ApiException failure_) {
                failure.set(failure_);
            }
            return null;
        });
        pool.shutdown();
        return job;
    }

    // ------------------------------------------------------------------ scenario 3

    @Test
    void approveRevalidatesAgainstTheCurrentServicePrice() throws Exception {
        try (Database db = new Database()) {
            db.serviceRow(7_104L, "100.00", "ACTIVE");
            ServiceFlashPriceMerchantApiImpl merchant = db.merchant();
            ServiceFlashPriceAdminApiImpl admin = db.admin();

            FlashPriceItem submitted = merchant.submit(submit(7_104L, merchant.create(
                    command(7_104L, "九折场", "9.0",
                            db.now.plusHours(1), db.now.plusHours(2), null, null))));
            // The service price drops to 80: flash 90 no longer beats it.
            db.jdbc.update("UPDATE service_item SET price='80.00' WHERE id=7104");
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_INVALID,
                    () -> admin.approve(review(submitted)));
            assertEquals("PENDING_REVIEW", db.jdbc.queryForObject(
                    "SELECT status FROM service_flash_price WHERE id=?",
                    String.class, Long.parseLong(submitted.flashPriceId())));

            // The merchant adjusts, resubmits; approve re-snapshots the new current price.
            // After the refused approve the row is still PENDING_REVIEW and not merchant-editable:
            // operations rejects it first so the merchant can edit again (77 §4.2).
            FlashPriceItem rejectedRow = admin.reject(new AdminReviewCommand(
                    operatorContext(), submitted.flashPriceId(), submitted.version(),
                    "服务已降价，请调整折扣"));
            FlashPriceItem fixed = merchant.update(new com.petplatform.service.api.dto
                    .ServiceFlashPriceTypes.UpdateFlashPriceCommand(
                    userContext(), "7104", rejectedRow.flashPriceId(), rejectedRow.version(),
                    "五折场", null, null, new BigDecimal("5.0"),
                    db.now.plusHours(1), db.now.plusHours(2), null, null));
            FlashPriceItem approved = admin.approve(review(merchant.submit(submit(7_104L, fixed))));
            assertEquals("SCHEDULED", approved.status());
            assertEquals(new BigDecimal("80.00"), approved.listPriceSnapshot());
            assertEquals(new BigDecimal("40.00"), approved.flashPrice());
        }
    }

    // ------------------------------------------------------------------ scenario 4

    @Test
    void reserveEnforcesWindowQuotaAndPerUserLimits() throws Exception {
        try (Database db = new Database()) {
            db.serviceRow(7_105L, "100.00", "ACTIVE");
            db.serviceRow(7_106L, "50.00", "ACTIVE");
            db.serviceRow(7_107L, "60.00", "ACTIVE");
            ServiceFlashPriceMerchantApiImpl merchant = db.merchant();
            ServiceFlashPriceAdminApiImpl admin = db.admin();
            ServiceFlashPriceReservationApiImpl reserveApi = db.reservation();

            // ACTIVE window: approved when startAt was already in the past.
            FlashPriceItem active = admin.approve(review(merchant.submit(submit(7_105L,
                    merchant.create(command(7_105L, "进行中", "8.8",
                            db.now.minusMinutes(10), db.now.plusHours(1), 10, 2))))));
            assertEquals("ACTIVE", active.status());

            ServiceFlashPriceReservationApi.ReserveCommand first = reserve(
                    active.flashPriceId(), "9105100000000001", "8105100000000001",
                    db.now.plusMinutes(15));
            ServiceFlashPriceReservationApi.ReservationResult result =
                    db.inTransaction(() -> reserveApi.reserve(first));
            assertEquals(new BigDecimal("88.00"), result.price());
            assertEquals(new BigDecimal("100.00"), result.listPrice());
            assertEquals(new BigDecimal("12.00"), result.flashDiscount());
            assertEquals("RESERVED", result.status());
            assertEquals(1, soldCount(db, active.flashPriceId()));

            // Same orderId replay is idempotent: no second ledger row, no double count.
            ServiceFlashPriceReservationApi.ReservationResult replay =
                    db.inTransaction(() -> reserveApi.reserve(first));
            assertEquals(result.price(), replay.price());
            assertEquals(1, soldCount(db, active.flashPriceId()));

            // PAUSED refuses; resume restores. The reserve CAS bumped the row version, so the
            // review command must carry the current stored version.
            FlashPriceItem pausedActive = admin.pause(db.reviewBy(active.flashPriceId()));
            assertEquals("PAUSED", pausedActive.status());
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_STATE_NOT_ALLOWED,
                    () -> db.inTransaction(() -> reserveApi.reserve(reserve(
                            active.flashPriceId(), "9105100000000002", "8105100000000002",
                            db.now.plusMinutes(15)))));
            assertEquals("ACTIVE", admin.approve(review(pausedActive)).status());

            // Outside any caller transaction the reservation fails closed (never auto-commits).
            assertApi(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    () -> reserveApi.reserve(reserve(active.flashPriceId(),
                            "9105100000000003", "8105100000000003", db.now.plusMinutes(15))));

            // totalQuota=1: the second buyer loses to QUOTA_EXCEEDED.
            FlashPriceItem single = admin.approve(review(merchant.submit(submit(7_106L,
                    merchant.create(command(7_106L, "仅一份", "8.0",
                            db.now.minusMinutes(5), db.now.plusHours(2), 1, null))))));
            db.inTransaction(() -> reserveApi.reserve(reserve(single.flashPriceId(),
                    "9106100000000001", "8106100000000001", db.now.plusMinutes(15))));
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_QUOTA_EXCEEDED,
                    () -> db.inTransaction(() -> reserveApi.reserve(reserve(single.flashPriceId(),
                            "9106100000000002", "8106100000000002", db.now.plusMinutes(15)))));

            // perUserLimit=1: the same user cannot take a second order, another user can.
            FlashPriceItem limited = admin.approve(review(merchant.submit(submit(7_107L,
                    merchant.create(command(7_107L, "每人一份", "5.0",
                            db.now.minusMinutes(5), db.now.plusHours(2), 10, 1))))));
            db.inTransaction(() -> reserveApi.reserve(reserve(limited.flashPriceId(),
                    "9107100000000001", "8107100000000001", db.now.plusMinutes(15))));
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_LIMIT_REACHED,
                    () -> db.inTransaction(() -> reserveApi.reserve(reserve(limited.flashPriceId(),
                            "9107100000000002", "8107100000000001", db.now.plusMinutes(15)))));
            db.inTransaction(() -> reserveApi.reserve(reserve(limited.flashPriceId(),
                    "9107100000000003", "8107100000000002", db.now.plusMinutes(15))));

            // A SCHEDULED (future) window is not reservable yet.
            FlashPriceItem future = admin.approve(review(merchant.submit(submit(7_105L,
                    merchant.create(command(7_105L, "未来场", "8.0",
                            db.now.plusHours(3), db.now.plusHours(4), null, null))))));
            assertEquals("SCHEDULED", future.status());
            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_STATE_NOT_ALLOWED,
                    () -> db.inTransaction(() -> reserveApi.reserve(reserve(future.flashPriceId(),
                            "9105100000000009", "8105100000000009", db.now.plusMinutes(15)))));

            // A reservation failure leaves no quota consumption behind (order-rollback shape):
            // active carries perUserLimit=2, so the third same-user reserve aborts the whole
            // transaction together with the two earlier writes of the same transaction.
            try {
                db.inTransaction(() -> {
                    reserveApi.reserve(reserve(active.flashPriceId(),
                            "9105100000000010", "8105100000000010", db.now.plusMinutes(15)));
                    reserveApi.reserve(reserve(active.flashPriceId(),
                            "9105100000000011", "8105100000000010", db.now.plusMinutes(15)));
                    return reserveApi.reserve(reserve(active.flashPriceId(),
                            "9105100000000012", "8105100000000010", db.now.plusMinutes(15)));
                });
                throw new AssertionError("per-user limit must abort the whole transaction");
            } catch (ApiException expected) {
                assertEquals(ServiceFlashPriceApiCodes.FLASH_PRICE_LIMIT_REACHED,
                        expected.code());
            }
            assertEquals(1, soldCount(db, active.flashPriceId()),
                    "the rolled-back batch must not consume quota");
        }
    }

    private static int soldCount(Database db, String flashPriceId) {
        Integer sold = db.jdbc.queryForObject(
                "SELECT sold_count FROM service_flash_price WHERE id=?", Integer.class,
                Long.parseLong(flashPriceId));
        return sold == null ? -1 : sold;
    }

    // ------------------------------------------------------------------ scenario 5

    @Test
    void orderPaidConsumerConsumesExactlyOnceAndIgnoresLateRows() throws Exception {
        try (Database db = new Database()) {
            db.serviceRow(7_108L, "100.00", "ACTIVE");
            ServiceFlashPriceMerchantApiImpl merchant = db.merchant();
            ServiceFlashPriceAdminApiImpl admin = db.admin();
            FlashPriceItem active = admin.approve(review(merchant.submit(submit(7_108L,
                    merchant.create(command(7_108L, "支付场", "8.0",
                            db.now.minusMinutes(5), db.now.plusHours(2), 10, null))))));
            long orderId = 9_108_100_000_000_001L;
            db.inTransaction(() -> db.reservation().reserve(reserve(active.flashPriceId(),
                    Long.toUnsignedString(orderId), "8108100000000001", db.now.plusMinutes(15))));
            assertEquals(1, soldCount(db, active.flashPriceId()));

            OrderPaidFlashPriceConsumer consumer = db.consumer();
            consumer.consume(paidEvent(orderId));
            assertEquals("CONSUMED", orderStatus(db, orderId));
            assertEquals(1, soldCount(db, active.flashPriceId()), "consume never touches quota");

            // Duplicate delivery is a NOOP.
            consumer.consume(paidEvent(orderId));
            assertEquals("CONSUMED", orderStatus(db, orderId));
            Long consumedRows = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM service_flash_price_order WHERE order_id=?"
                            + " AND status='CONSUMED'", Long.class, orderId);
            assertEquals(1L, consumedRows);

            // A late event for an already-RELEASED row stays released and never gives quota back.
            long lateOrder = 9_108_100_000_000_002L;
            db.inTransaction(() -> db.reservation().reserve(reserve(active.flashPriceId(),
                    Long.toUnsignedString(lateOrder), "8108100000000002", db.now.plusMinutes(15))));
            db.jdbc.update(
                    "UPDATE service_flash_price_order SET status='RELEASED', released_at=?"
                            + " WHERE order_id=?", java.sql.Timestamp.from(db.now.toInstant()),
                    lateOrder);
            db.jdbc.update("UPDATE service_flash_price SET sold_count=sold_count-1 WHERE id=?",
                    Long.parseLong(active.flashPriceId()));
            consumer.consume(paidEvent(lateOrder));
            assertEquals("RELEASED", orderStatus(db, lateOrder));

            // Unknown order and malformed payloads fail validation without side effects.
            consumer.consume(paidEvent(9_108_100_000_000_003L));
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.consume(new DispatchedEvent(
                            "970810000000000001", OrderPaidFlashPriceConsumer.TYPE, 1,
                            db.now, "ORDER", orderId, "trace-1",
                            "{\"orderId\":\"" + Long.toUnsignedString(orderId)
                                    + "\",\"reservationId\":\"970810000000000002\","
                                    + "\"couponInstanceId\":null,\"paidAt\":\"2026-11-18T10:00:00Z\","
                                    + "\"confirmDeadline\":\"2026-11-18T10:30:00Z\",\"extra\":1}")));
        }
    }

    private static String orderStatus(Database db, long orderId) {
        return db.jdbc.queryForObject(
                "SELECT status FROM service_flash_price_order WHERE order_id=?", String.class,
                orderId);
    }

    private static DispatchedEvent paidEvent(long orderId) {
        return new DispatchedEvent(
                "evt-" + Long.toUnsignedString(orderId),
                OrderPaidFlashPriceConsumer.TYPE, 1,
                OffsetDateTime.parse("2026-11-18T10:05:00Z"), "ORDER", orderId, "trace-1",
                "{\"orderId\":\"" + Long.toUnsignedString(orderId)
                        + "\",\"reservationId\":\"9708100000000009\","
                        + "\"couponInstanceId\":null,\"paidAt\":\"2026-11-18T10:05:00Z\","
                        + "\"confirmDeadline\":\"2026-11-18T10:35:00Z\"}");
    }

    // ------------------------------------------------------------------ scenario 6

    @Test
    void releaseRoundReleasesExpiredReservationsIdempotently() throws Exception {
        try (Database db = new Database()) {
            db.serviceRow(7_109L, "100.00", "ACTIVE");
            ServiceFlashPriceMerchantApiImpl merchant = db.merchant();
            ServiceFlashPriceAdminApiImpl admin = db.admin();
            FlashPriceItem active = admin.approve(review(merchant.submit(submit(7_109L,
                    merchant.create(command(7_109L, "过期场", "8.0",
                            db.now.minusMinutes(5), db.now.plusHours(2), 10, null))))));

            long paidOrder = 9_109_100_000_000_001L;
            long expiredOrder = 9_109_100_000_000_002L;
            long freshOrder = 9_109_100_000_000_003L;
            db.inTransaction(() -> db.reservation().reserve(reserve(active.flashPriceId(),
                    Long.toUnsignedString(paidOrder), "8109100000000001",
                    db.now.minusMinutes(10))));
            db.inTransaction(() -> db.reservation().reserve(reserve(active.flashPriceId(),
                    Long.toUnsignedString(expiredOrder), "8109100000000002",
                    db.now.minusMinutes(10))));
            db.inTransaction(() -> db.reservation().reserve(reserve(active.flashPriceId(),
                    Long.toUnsignedString(freshOrder), "8109100000000003",
                    db.now.plusMinutes(15))));
            assertEquals(3, soldCount(db, active.flashPriceId()));
            db.consumer().consume(paidEvent(paidOrder));

            int released = db.release().runRound();
            assertEquals(1, released, "only the unpaid expired row releases");
            assertEquals("CONSUMED", orderStatus(db, paidOrder));
            assertEquals("RELEASED", orderStatus(db, expiredOrder));
            assertEquals("RESERVED", orderStatus(db, freshOrder));
            assertEquals(2, soldCount(db, active.flashPriceId()),
                    "the released reservation returns its quota");

            // The successor round is enqueued deterministically; a rerun is a NOOP.
            Long tasks = db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM async_task WHERE task_type=?", Long.class,
                    FlashPriceReleaseService.TASK_TYPE);
            assertTrue(tasks != null && tasks >= 1, "the round must enqueue its successor");
            assertEquals(0, db.release().runRound());
            assertEquals(2, soldCount(db, active.flashPriceId()));
        }
    }

    // ------------------------------------------------------------------ scenario 7

    @Test
    void disabledSwitchFailsClosedAndReadsStayList() throws Exception {
        try (Database db = new Database()) {
            db.serviceRow(7_110L, "100.00", "ACTIVE");
            // A live flash row exists, but the disabled face never looks at it.
            ServiceFlashPriceMerchantApiImpl merchant = db.merchant();
            ServiceFlashPriceAdminApiImpl admin = db.admin();
            admin.approve(review(merchant.submit(submit(7_110L, merchant.create(
                    command(7_110L, "暗场", "8.0",
                            db.now.minusMinutes(5), db.now.plusHours(2), 10, null))))));

            assertApi(ServiceFlashPriceApiCodes.FLASH_PRICE_DISABLED,
                    () -> ServiceFlashPriceReservationApiImpl.disabled().reserve(
                            reserve("1", "9110100000000001", "8110100000000001",
                                    db.now.plusMinutes(15))));

            ServiceQueryService reads = db.queryService(FlashPriceEffectiveResolver.DISABLED);
            ServiceSnapshotDTO snapshot = reads.snapshot(
                    new ServiceSnapshotQuery("7110", db.queryContext()));
            assertEquals("LIST", snapshot.priceSource());
            assertNull(snapshot.flashPrice());
            assertNull(snapshot.listPrice());
            assertEquals(new BigDecimal("100.00"), snapshot.salePrice());
        }
    }

    // ------------------------------------------------------------------ scenario 8

    @Test
    void readSideResolvesTheEffectiveFlashWindow() throws Exception {
        try (Database db = new Database()) {
            db.serviceRow(7_111L, "100.00", "ACTIVE");
            ServiceFlashPriceMerchantApiImpl merchant = db.merchant();
            ServiceFlashPriceAdminApiImpl admin = db.admin();
            // SCHEDULED for a window 10 minutes out; the clock later walks into the window.
            FlashPriceItem scheduled = admin.approve(review(merchant.submit(submit(7_111L,
                    merchant.create(command(7_111L, "未开始", "8.8",
                            db.now.plusMinutes(10), db.now.plusMinutes(70), 10, null))))));
            assertEquals("SCHEDULED", scheduled.status());
            ServiceQueryService reads = db.queryService(db.resolver());

            ServiceSnapshotDTO before = reads.snapshot(
                    new ServiceSnapshotQuery("7111", db.queryContext()));
            assertEquals("LIST", before.priceSource());
            assertEquals(new BigDecimal("100.00"), before.salePrice());
            assertNull(before.flashRemaining());

            db.clock.advance(java.time.Duration.ofMinutes(11));
            ServiceSnapshotDTO inside = reads.snapshot(
                    new ServiceSnapshotQuery("7111", db.queryContext()));
            assertEquals("FLASH", inside.priceSource());
            assertEquals(new BigDecimal("88.00"), inside.flashPrice());
            assertEquals(new BigDecimal("88.00"), inside.salePrice(), "salePrice becomes flash");
            assertEquals(new BigDecimal("100.00"), inside.listPrice());
            assertEquals(10, inside.flashRemaining());
            assertNotNull(inside.flashStartAt());
            assertNotNull(inside.flashEndAt());

            // A service price cut below the flash price flips the read back to LIST.
            db.jdbc.update("UPDATE service_item SET price='80.00' WHERE id=7111");
            ServiceSnapshotDTO beaten = reads.snapshot(
                    new ServiceSnapshotQuery("7111", db.queryContext()));
            assertEquals("LIST", beaten.priceSource());
            assertEquals(new BigDecimal("80.00"), beaten.salePrice());
            assertNull(beaten.flashPrice());

            // Past endAt the window is over regardless of the stored status.
            db.jdbc.update("UPDATE service_item SET price='100.00' WHERE id=7111");
            db.clock.advance(java.time.Duration.ofHours(2));
            ServiceSnapshotDTO after = reads.snapshot(
                    new ServiceSnapshotQuery("7111", db.queryContext()));
            assertEquals("LIST", after.priceSource());
            assertNull(after.flashPrice());
        }
    }

    // ------------------------------------------------------------------ fixtures + helpers

    private static CreateFlashPriceCommand command(
            long serviceId, String title, String rate, OffsetDateTime start, OffsetDateTime end,
            Integer quota, Integer limit) {
        return command(userContext(), serviceId, title, rate, start, end, quota, limit);
    }

    private static CreateFlashPriceCommand command(
            CommandContext context, long serviceId, String title, String rate,
            OffsetDateTime start, OffsetDateTime end, Integer quota, Integer limit) {
        return new CreateFlashPriceCommand(
                context, Long.toString(serviceId), title, null, null,
                new BigDecimal(rate), start, end, quota, limit);
    }

    private static com.petplatform.service.api.dto.ServiceFlashPriceTypes.SubmitCommand submit(
            long serviceId, FlashPriceItem item) {
        return new com.petplatform.service.api.dto.ServiceFlashPriceTypes.SubmitCommand(
                userContext(), Long.toString(serviceId), item.flashPriceId(), item.version());
    }

    private static com.petplatform.service.api.dto.ServiceFlashPriceTypes.CancelCommand cancel(
            long serviceId, FlashPriceItem item) {
        return new com.petplatform.service.api.dto.ServiceFlashPriceTypes.CancelCommand(
                userContext(), Long.toString(serviceId), item.flashPriceId(), item.version());
    }

    private static AdminReviewCommand review(FlashPriceItem item) {
        return new AdminReviewCommand(operatorContext(), item.flashPriceId(), item.version(),
                null);
    }

    private static ServiceFlashPriceReservationApi.ReserveCommand reserve(
            String flashPriceId, String orderId, String userId, OffsetDateTime expireAt) {
        return new ServiceFlashPriceReservationApi.ReserveCommand(
                userContext(), flashPriceId, orderId, userId, expireAt);
    }

    private static final AtomicInteger REQUEST_SEQUENCE = new AtomicInteger();

    private static CommandContext userContext() {
        return new CommandContext("svc-flash-req-" + REQUEST_SEQUENCE.incrementAndGet(),
                "trace-" + REQUEST_SEQUENCE.get(), OperatorType.USER,
                Long.toUnsignedString(OWNER), "MINIAPP");
    }

    private static CommandContext operatorContext() {
        return new CommandContext("svc-flash-op-" + REQUEST_SEQUENCE.incrementAndGet(),
                "trace-" + REQUEST_SEQUENCE.get(), OperatorType.PLATFORM_OPERATOR,
                Long.toUnsignedString(OPERATOR), "ADMIN_WEB");
    }

    private static void assertApi(String code, Runnable call) {
        ApiException failure = assertThrows(ApiException.class, call::run);
        assertEquals(code, failure.code());
    }

    /** Isolated real-MySQL fixture: 06号 core + service migrations V27/V28/V29 + V31 + 13号. */
    private static final class Database implements AutoCloseable {
        private final String name = "svcflash_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private final SnowflakeIdGenerator ids;
        private final SettableClock clock;
        private final OffsetDateTime now;
        private final TransactionTemplate transaction;

        Database() throws Exception {
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33440/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                        "AUTH_MYSQL_URL must target a local isolated server, database-less");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            clock = new SettableClock(Instant.parse("2026-11-18T10:00:00Z"));
            now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            long[] seq = {9_711_000_000_000_000L};
            ids = () -> ++seq[0];
            try (Connection connection = source.getConnection()) {
                for (String script : List.of(
                        "docs/03-database/06-核心数据库Schema-v0.1.sql",
                        "backend/pet-boot/src/main/resources/db/service-migration/"
                                + "V27__service_write.sql",
                        "backend/pet-boot/src/main/resources/db/service-migration/"
                                + "V28__service_draft_nullable.sql",
                        "backend/pet-boot/src/main/resources/db/service-migration/"
                                + "V29__service_category_dictionary.sql",
                        "backend/pet-boot/src/main/resources/db/service-migration/"
                                + "V31__service_flash_price.sql",
                        "docs/03-database/13-Async-Infra-Schema-v0.1.sql")) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(root().resolve(script)),
                            StandardCharsets.UTF_8));
                }
                // 39号's async_task delta (submitted_execute_at) is the only task-core column
                // this slice needs; the rest of 39号 targets schedule tables this DB lacks.
                jdbc.execute(
                        "ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
            } catch (Exception failure) {
                try {
                    close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        ServiceFlashPriceMerchantApiImpl merchant() {
            return new ServiceFlashPriceMerchantApiImpl(
                    source, ids, admission(), clock);
        }

        ServiceFlashPriceAdminApiImpl admin() {
            return new ServiceFlashPriceAdminApiImpl(source, ids, null, clock);
        }

        ServiceFlashPriceReservationApiImpl reservation() {
            return new ServiceFlashPriceReservationApiImpl(source, ids, clock);
        }

        FlashPriceEffectiveResolver resolver() {
            return new FlashPriceEffectiveResolver(new FlashPriceStore(source, ids), clock);
        }

        OrderPaidFlashPriceConsumer consumer() {
            return new OrderPaidFlashPriceConsumer(new FlashPriceStore(source, ids),
                    (name, event) -> true, clock);
        }

        FlashPriceReleaseService release() {
            return new FlashPriceReleaseService(
                    new FlashPriceStore(source, ids),
                    new JdbcAsyncTaskSubmitter(source, ids), clock);
        }

        ServiceQueryService queryService(FlashPriceEffectiveResolver flashPrices) {
            return new ServiceQueryService(new ServiceReadStore(source), displayFacts(),
                    query -> List.of(), null, flashPrices);
        }

        QueryContext queryContext() {
            return new QueryContext("trace-query", OperatorType.USER,
                    Long.toUnsignedString(OWNER));
        }

        /** Review command carrying the CURRENT stored version (reserve CAS bumps it). */
        AdminReviewCommand reviewBy(String flashPriceId) {
            Integer version = jdbc.queryForObject(
                    "SELECT version FROM service_flash_price WHERE id=?", Integer.class,
                    Long.parseLong(flashPriceId));
            return new AdminReviewCommand(operatorContext(), flashPriceId, version, null);
        }

        <T> T inTransaction(java.util.function.Supplier<T> work) {
            return transaction.execute(status -> work.get());
        }

        void serviceRow(long id, String price, String status) {
            jdbc.update("""
                    INSERT INTO service_item
                    (id, merchant_id, store_id, category_id, service_name, price,
                     duration_minutes, fulfillment_type, applicable_pet_types, status,
                     verification_required, version, created_at, updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                    """,
                    id, MERCHANT, STORE, 3L, "洗护服务", price, 60, "IN_STORE", "ALL", status, 1, 0);
        }

        private MerchantAdmissionQueryApi admission() {
            return new MerchantAdmissionQueryApi() {
                @Override
                public MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query) {
                    return new MerchantAdmissionDTO(
                            Long.toUnsignedString(MERCHANT), Long.toUnsignedString(STORE),
                            "OWNER", "ALLOWED", OffsetDateTime.now(ZoneOffset.UTC), "authz-v1",
                            null, null, null, null, null, null, null, null);
                }

                @Override
                public com.petplatform.merchant.api.dto.MerchantMembershipPageDTO
                        listMemberships(com.petplatform.merchant.api.query.MerchantMembershipQuery query) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        private MerchantDisplayEligibilityApi displayFacts() {
            return new MerchantDisplayEligibilityApi() {
                @Override
                public MerchantDisplayEligibilityDTO checkDisplayEligibility(
                        MerchantDisplayEligibilityQuery query) {
                    return new MerchantDisplayEligibilityDTO(
                            query.merchantId(), query.storeId(), true, true, true);
                }
            };
        }

        private static Path root() {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(
                    root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) {
                throw new IllegalStateException("Repository root with schema 06 was not found");
            }
            return root;
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(
                    url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                    user, password) {
                @Override
                public Connection getConnection() throws java.sql.SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (java.sql.SQLException failure) {
                        connection.close();
                        throw failure;
                    }
                }
            };
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }
}
