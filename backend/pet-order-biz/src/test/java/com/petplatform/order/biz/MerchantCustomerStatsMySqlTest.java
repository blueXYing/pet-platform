package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerDetail;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerListQuery;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerRow;
import com.petplatform.order.api.query.MerchantStatsQueryApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi.StatsQuery;
import com.petplatform.order.api.query.MerchantStatsQueryApi.TrendBucket;
import com.petplatform.order.api.query.MerchantStatsQueryApi.Overview;
import com.petplatform.order.api.query.StoreReviewScoreFactsApi;
import com.petplatform.order.biz.apiimpl.MerchantCustomerQueryApiImpl;
import com.petplatform.order.biz.apiimpl.MerchantStatsQueryApiImpl;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.query.UserQueryApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Contracts 69/70 acceptance over isolated real MySQL: the derived customer list/detail
 * (net-of-refund spend, masked phone, breed-level pets, cross-store isolation, bounded
 * nickname search) and the stats overview/top/trend (UTC windows, one-decimal rate strings,
 * previous-window deltas, optional review average). OWNER re-proof is a seam that answers
 * only the seeded coordinate; everything else fails the way the real port does.
 */
class MerchantCustomerStatsMySqlTest {

    private static final long MERCHANT = 7_300_000_000_000_001L;
    private static final long STORE = 7_300_000_000_000_002L;
    private static final long OTHER_STORE = 7_300_000_000_000_003L;
    private static final long ALICE = 8_300_000_000_000_101L;
    private static final long BOB = 8_300_000_000_000_102L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-19T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void customerListDerivesFromStoreOrdersOnlyWithMaskedBasics() throws Exception {
        try (Database db = new Database()) {
            MerchantCustomerQueryApi api = db.customers(null);
            db.order(9_100L, ALICE, "2026-10-12 08:00:00", "300.00", "0.00", true);
            db.order(9_101L, ALICE, "2026-10-15 10:00:00", "200.00", "50.00", true); // partial refund
            db.order(9_102L, BOB, "2026-10-16 09:00:00", "100.00", "100.00", false); // full refund
            db.petSnapshot(9_100L, 700L, "小橘", "CAT", "英短");
            db.petSnapshot(9_101L, 700L, "小橘", "CAT", "英短");
            db.petSnapshot(9_102L, 701L, "旺财", "DOG", "金毛");
            // Another store's orders for ALICE never leak into this store's derivation.
            db.orderInStore(9_199L, OTHER_STORE, ALICE, "2026-10-17 09:00:00", "999.00", "0.00", false);

            PageResult<CustomerRow> page = api.listCustomers(new CustomerListQuery(
                    Long.toString(MERCHANT), Long.toString(STORE), null, null, 1, 20, context()));
            assertEquals(2, page.total());
            // Spend ranking: ALICE 300 + 150 net = 450; BOB 0 (fully refunded, order counted).
            assertEquals(ALICE_STR, page.items().get(0).userId());
            assertEquals(0, page.items().get(0).totalSpend().compareTo(new BigDecimal("450.00")));
            assertEquals(2, page.items().get(0).orderCount());
            assertEquals(0, page.items().get(0).avgTicket().compareTo(new BigDecimal("225.00")));
            assertEquals("138****0100", page.items().get(0).phoneMasked());
            assertEquals("爱丽丝", page.items().get(0).nickname());
            assertEquals("小橘", page.items().get(0).topPet().petName());
            assertEquals("英短", page.items().get(0).topPet().breedName());
            assertEquals(BOB_STR, page.items().get(1).userId());
            assertEquals(0, page.items().get(1).totalSpend().compareTo(BigDecimal.ZERO));
            assertEquals(0, page.items().get(1).avgTicket().compareTo(BigDecimal.ZERO));
            // Detail: deduped breed-level pets only.
            CustomerDetail detail = api.getCustomer(new MerchantCustomerQueryApi.CustomerGetQuery(
                    Long.toString(MERCHANT), Long.toString(STORE), ALICE_STR, context()));
            assertEquals(1, detail.pets().size());
            assertEquals("CAT", detail.pets().get(0).petType());
            // A user with no store order reads as the anti-enumeration NOT_FOUND.
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> api.getCustomer(new MerchantCustomerQueryApi.CustomerGetQuery(
                            Long.toString(MERCHANT), Long.toString(STORE), "8300000000000103",
                            context()))).code());
        }
    }

    @Test
    void nicknameSearchMatchesBoundedScanAndForeignOwnerFailsClosed() throws Exception {
        try (Database db = new Database()) {
            MerchantCustomerQueryApi api = db.customers(null);
            db.order(9_110L, ALICE, "2026-10-12 08:00:00", "120.00", "0.00", false);
            db.order(9_111L, BOB, "2026-10-12 09:00:00", "80.00", "0.00", false);
            PageResult<CustomerRow> hit = api.listCustomers(new CustomerListQuery(
                    Long.toString(MERCHANT), Long.toString(STORE), "爱丽丝", null, 1, 20, context()));
            assertEquals(1, hit.total());
            assertEquals(ALICE_STR, hit.items().get(0).userId());
            PageResult<CustomerRow> miss = api.listCustomers(new CustomerListQuery(
                    Long.toString(MERCHANT), Long.toString(STORE), "不存在", null, 1, 20, context()));
            assertEquals(0, miss.total());
            assertTrue(miss.items().isEmpty());
            // Foreign coordinates: the authority seam answers the same 403 as production.
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> api.listCustomers(new CustomerListQuery(Long.toString(MERCHANT),
                            Long.toString(OTHER_STORE), null, null, 1, 20, context()))).code());
        }
    }

    @Test
    void statsOverviewComputesNetRevenueRatesAndDeltas() throws Exception {
        try (Database db = new Database()) {
            // LAST_7D window is [2026-10-13, 2026-10-20); previous is [2026-10-06, 2026-10-13).
            db.order(9_120L, ALICE, "2026-10-14 08:00:00", "300.00", "0.00", true);   // net 300
            db.order(9_121L, BOB, "2026-10-15 09:00:00", "200.00", "50.00", true);    // net 150
            db.order(9_122L, ALICE, "2026-10-16 10:00:00", "100.00", "100.00", false); // refunded
            db.order(9_123L, BOB, "2026-10-08 10:00:00", "500.00", "0.00", true);     // previous window [10-06,10-13)
            var review = new StoreReviewScoreFactsApi() {
                @Override
                public ScoreSummary summary(SummaryQuery query) {
                    return new ScoreSummary(2, "4.6");
                }
            };
            MerchantStatsQueryApi api = db.stats(review);
            Overview overview = api.overview(stats("LAST_7D"));
            assertEquals("LAST_7D", overview.range());
            assertEquals(0, overview.revenue().compareTo(new BigDecimal("450.00")));
            assertEquals(3, overview.orderCount());
            assertEquals(2, overview.verifiedOrderCount());
            assertEquals(2, overview.refundOrderCount());   // 部分退款 + 全额退款都计数
            assertEquals(0, overview.avgTicket().compareTo(new BigDecimal("225.00")));
            assertEquals("66.6", overview.verifyRate());    // 2/3 向下取 1 位（比率截断口径）
            assertEquals("66.6", overview.refundRate());    // 2/3 向下取 1 位
            assertEquals("50.0", overview.repeatRate());    // ALICE 2 orders / 2 customers
            assertEquals(1, overview.newCustomers());       // BOB's first order lands this window
            assertEquals("4.6", overview.reviewAvg());
            // Deltas vs previous window (revenue 500 → 450 → -10.0%).
            assertEquals("-10.0", overview.deltas().revenue().toPlainString());
            // TODAY only holds nothing (fixed clock 2026-10-19, orders are earlier).
            Overview today = api.overview(stats("TODAY"));
            assertEquals(0, today.revenue().compareTo(BigDecimal.ZERO));
            assertEquals(0, today.orderCount());
            assertNull(today.verifyRate());
            // Absent review port: reviewAvg reads null ("--"), everything else intact.
            MerchantStatsQueryApi withoutReviews = db.stats(null);
            assertNull(withoutReviews.overview(stats("LAST_7D")).reviewAvg());
        }
    }

    @Test
    void serviceTopRanksByOrdersAndTrendFillsEveryDayOfTheWindow() throws Exception {
        try (Database db = new Database()) {
            db.order(9_130L, ALICE, "2026-10-13 08:00:00", "100.00", "0.00", false, 501L);
            db.order(9_131L, BOB, "2026-10-14 09:00:00", "120.00", "0.00", false, 501L);
            db.order(9_132L, ALICE, "2026-10-16 10:00:00", "90.00", "0.00", false, 502L);
            db.serviceSnapshot(9_130L, 501L, "猫咪洗澡");
            db.serviceSnapshot(9_131L, 501L, "猫咪洗澡");
            db.serviceSnapshot(9_132L, 502L, "狗狗美容");
            MerchantStatsQueryApi api = db.stats(null);
            var top = api.serviceTop(statsTop("LAST_7D", 5));
            assertEquals(2, top.size());
            assertEquals("501", top.get(0).serviceId());
            assertEquals(2, top.get(0).orderCount());
            assertEquals("猫咪洗澡", top.get(0).serviceName());
            assertEquals(0, top.get(0).revenue().compareTo(new BigDecimal("220.00")));
            var trend = api.revenueTrend(stats("LAST_7D"));
            assertEquals(7, trend.size()); // every UTC day of the window, gaps filled with zero
            assertEquals("2026-10-13", trend.get(0).date());
            assertEquals(0, trend.get(0).revenue().compareTo(new BigDecimal("100.00")));
            assertEquals(0, trend.get(2).revenue().compareTo(BigDecimal.ZERO)); // 2026-10-15 has no orders
            assertEquals(0, trend.get(3).revenue().compareTo(new BigDecimal("90.00"))); // 2026-10-16
            // limit bounds
            assertEquals(1, api.serviceTop(statsTop("LAST_7D", 1)).size());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.serviceTop(statsTop("LAST_7D", 6))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.overview(stats("CUSTOM"))).code());
        }
    }

    /** 2026-10-19 expansion: CUSTOM from/to window with the 92-day guardrail and the reject
     *  rate (merchant-rejected cancels over paid orders), previous window equal length. */
    @Test
    void customWindowAndRejectRateFollowTheRegisteredRules() throws Exception {
        try (Database db = new Database()) {
            // CUSTOM window [2026-09-01, 2026-09-30]: 30 closed days; previous [08-02, 09-01).
            db.order(9_140L, ALICE, "2026-09-05 08:00:00", "300.00", "0.00", true);
            db.canceledOrder(9_141L, BOB, "2026-09-10 09:00:00", "MERCHANT_REJECT_ORDER"); // rejected after pay
            db.canceledOrder(9_142L, BOB, "2026-09-11 09:00:00", "USER_CLOSE"); // plain close, never paid
            db.order(9_143L, BOB, "2026-10-15 09:00:00", "50.00", "0.00", true); // outside the window
            MerchantStatsQueryApi api = db.stats(null);
            Overview view = api.overview(statsCustom("2026-09-01", "2026-09-30"));
            assertEquals("CUSTOM", view.range());
            assertEquals(3, view.orderCount());       // 09-05 + two cancels; 10-15 excluded
            // paid = the completed order + the merchant reject (paid then fully refunded); the
            // plain close was never paid.
            assertEquals("50.0", view.rejectRate());  // 1 reject / 2 paid, one decimal down
            assertEquals(1, view.refundOrderCount()); // only the reject carries refunded > 0
            // The 92-day guardrail, the inverted interval and the loose date format are 400.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.overview(statsCustom("2026-01-01", "2026-04-03"))).code()); // 93 days
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.overview(statsCustom("2026-10-02", "2026-10-01"))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.overview(statsCustom("2026-9-1", "2026-09-30"))).code());
            // Trend buckets cover exactly the custom window's days.
            var trend = api.revenueTrend(statsCustom("2026-09-01", "2026-09-03"));
            assertEquals(3, trend.size());
            assertEquals("2026-09-01", trend.get(0).date());
            // The utilization port absence keeps the whole metric null ("--").
            assertNull(api.scheduleUtilization(stats("LAST_7D")).utilizationRate());
        }
    }

    @Test
    void scheduleUtilizationReadsTheSchFactsPort() throws Exception {
        try (Database db = new Database()) {
            var port = new com.petplatform.schedule.api.query.ScheduleUtilizationQueryApi() {
                @Override
                public Utilization utilization(UtilizationQuery query) {
                    return new Utilization(600L, 90L);
                }
            };
            MerchantStatsQueryApi api = db.stats(null, port);
            var view = api.scheduleUtilization(stats("LAST_7D"));
            assertEquals(600L, view.availableMinutes());
            assertEquals(90L, view.bookedMinutes());
            assertEquals("15.0", view.utilizationRate()); // one decimal down
            var failing = new com.petplatform.schedule.api.query.ScheduleUtilizationQueryApi() {
                @Override
                public Utilization utilization(UtilizationQuery query) {
                    throw new IllegalStateException("schedule facts exploded");
                }
            };
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> db.stats(null, failing).scheduleUtilization(stats("LAST_7D"))).code());
        }
    }

    /** 2026-10-19 expansion: the service drill-down — paged read-only rows of one window
     *  service, anti-enumeration 404 for services without window orders. */
    @Test
    void serviceDrillDownListsWindowOrdersPaged() throws Exception {
        try (Database db = new Database()) {
            db.order(9_150L, ALICE, "2026-10-13 08:00:00", "100.00", "0.00", true, 501L);
            db.order(9_151L, BOB, "2026-10-14 09:00:00", "120.00", "20.00", true, 501L);
            db.order(9_152L, ALICE, "2026-10-16 10:00:00", "90.00", "0.00", true, 501L);
            db.order(9_153L, BOB, "2026-10-18 11:00:00", "70.00", "0.00", true, 502L);
            db.serviceSnapshot(9_151L, 501L, "猫咪洗澡");
            MerchantStatsQueryApi api = db.stats(null);
            var page = api.serviceOrders(stats("LAST_7D"), "501", 1, 2);
            assertEquals("501", page.serviceId());
            assertEquals("猫咪洗澡", page.serviceName());
            assertEquals(3, page.total());
            assertEquals(2, page.items().size());
            // Newest creation first; the row keeps this store's own order facts only.
            assertEquals("9152", page.items().get(0).orderId());
            assertEquals("120.00", page.items().get(1).payAmount().toPlainString());
            assertEquals("20.00", page.items().get(1).refundedAmount().toPlainString());
            var second = api.serviceOrders(stats("LAST_7D"), "501", 2, 2);
            assertEquals(1, second.items().size());
            assertEquals("9150", second.items().get(0).orderId());
            // A service with no window orders is the same anti-enumeration NOT_FOUND.
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> api.serviceOrders(stats("LAST_7D"), "503", 1, 20)).code());
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> api.serviceOrders(new StatsQuery(Long.toString(MERCHANT),
                            Long.toString(OTHER_STORE), "LAST_7D", null, null, null, context()),
                            "501", 1, 20)).code());
        }
    }

    /** 2026-10-19 expansion: the order-no-suffix search hits only this store's own tails. */
    @Test
    void orderNoSuffixSearchMatchesOnlyStoreOrders() throws Exception {
        try (Database db = new Database()) {
            MerchantCustomerQueryApi api = db.customers(null);
            // order_no = id in the fixture: ALICE 9160 → tail "9160"; BOB 9161 → "9161".
            db.order(9_160L, ALICE, "2026-10-12 08:00:00", "300.00", "0.00", true);
            db.order(9_161L, BOB, "2026-10-13 09:00:00", "200.00", "0.00", true);
            // Another store's order for ALICE must never widen the hit set.
            db.orderInStore(9_169L, OTHER_STORE, ALICE, "2026-10-14 08:00:00", "999.00", "0.00", false);
            var hit = api.listCustomers(new CustomerListQuery(Long.toString(MERCHANT),
                    Long.toString(STORE), null, "9160", 1, 20, context()));
            assertEquals(1, hit.total());
            assertEquals(ALICE_STR, hit.items().get(0).userId());
            // Leading zeros are part of the tail: no order ends in "0160".
            var padded = api.listCustomers(new CustomerListQuery(Long.toString(MERCHANT),
                    Long.toString(STORE), null, "0160", 1, 20, context()));
            assertEquals(0, padded.total());
            // Foreign coordinate stays 403; malformed suffixes and the double filter are 400.
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> api.listCustomers(new CustomerListQuery(Long.toString(MERCHANT),
                            Long.toString(OTHER_STORE), null, "9160", 1, 20, context()))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.listCustomers(new CustomerListQuery(Long.toString(MERCHANT),
                            Long.toString(STORE), null, "1", 1, 20, context()))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.listCustomers(new CustomerListQuery(Long.toString(MERCHANT),
                            Long.toString(STORE), "爱丽丝", "9160", 1, 20, context()))).code());
        }
    }

    /** 2026-10-19 expansion: the merchant-internal note — lazy create, CAS, replay, audit. */
    @Test
    void customerNoteWritesCreateCasAndReplay() throws Exception {
        try (Database db = new Database()) {
            db.order(9_170L, ALICE, "2026-10-12 08:00:00", "300.00", "0.00", true);
            MerchantCustomerQueryApi reads = db.customers(null);
            java.util.concurrent.atomic.AtomicLong counter =
                    new java.util.concurrent.atomic.AtomicLong(7_500_000_000_000_000L);
            com.petplatform.common.SnowflakeIdGenerator ids = counter::incrementAndGet;
            var notes = db.notes(ids);
            String requestId = UUID.randomUUID().toString();
            var saved = notes.updateNote(new com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand(
                    Long.toString(MERCHANT), Long.toString(STORE), ALICE_STR, "对猫毛过敏，注意戴手套",
                    "0", new com.petplatform.common.CommandContext(requestId, "test",
                            OperatorType.USER, "8300000000000099", "MINIAPP")));
            assertEquals("1", saved.version());
            // The detail face carries the note.
            var detail = reads.getCustomer(new MerchantCustomerQueryApi.CustomerGetQuery(
                    Long.toString(MERCHANT), Long.toString(STORE), ALICE_STR, context()));
            assertEquals("对猫毛过敏，注意戴手套", detail.note().content());
            assertEquals("1", detail.note().version());
            // CAS: a stale expectedVersion is 409.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> notes.updateNote(new com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand(
                            Long.toString(MERCHANT), Long.toString(STORE), ALICE_STR, "旧版本",
                            "0", new com.petplatform.common.CommandContext(UUID.randomUUID().toString(),
                                    "test", OperatorType.USER, "8300000000000099", "MINIAPP")))).code());
            // Second write rides the version; the audit keeps the field name only.
            notes.updateNote(new com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand(
                    Long.toString(MERCHANT), Long.toString(STORE), ALICE_STR, "第二版备注", "1",
                    new com.petplatform.common.CommandContext(UUID.randomUUID().toString(), "test",
                            OperatorType.USER, "8300000000000099", "MINIAPP")));
            assertEquals("第二版备注", db.jdbc.queryForObject(
                    "SELECT note FROM merchant_customer_note WHERE store_id=" + STORE, String.class));
            assertEquals(2, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM merchant_customer_note_audit", Integer.class));
            // Same requestId + same payload replays without a second write.
            var replay = notes.updateNote(new com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand(
                    Long.toString(MERCHANT), Long.toString(STORE), ALICE_STR, "对猫毛过敏，注意戴手套",
                    "0", new com.petplatform.common.CommandContext(requestId, "test",
                            OperatorType.USER, "8300000000000099", "MINIAPP")));
            assertEquals("1", replay.version());
            assertEquals(2, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM merchant_customer_note_audit", Integer.class));
            // A user with no store order is the anti-enumeration NOT_FOUND.
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> notes.updateNote(new com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand(
                            Long.toString(MERCHANT), Long.toString(STORE), "8300000000000103",
                            "无订单客户", "0", new com.petplatform.common.CommandContext(
                                    UUID.randomUUID().toString(), "test", OperatorType.USER,
                                    "8300000000000099", "MINIAPP")))).code());
            // Foreign coordinate is 403; the empty string clears the note (stored NULL).
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> notes.updateNote(new com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand(
                            Long.toString(MERCHANT), Long.toString(OTHER_STORE), ALICE_STR, "越权",
                            "2", new com.petplatform.common.CommandContext(UUID.randomUUID().toString(),
                                    "test", OperatorType.USER, "8300000000000099", "MINIAPP")))).code());
            notes.updateNote(new com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand(
                    Long.toString(MERCHANT), Long.toString(STORE), ALICE_STR, "", "2",
                    new com.petplatform.common.CommandContext(UUID.randomUUID().toString(), "test",
                            OperatorType.USER, "8300000000000099", "MINIAPP")));
            assertNull(db.jdbc.queryForObject(
                    "SELECT note FROM merchant_customer_note WHERE store_id=" + STORE, String.class));
        }
    }

    private static QueryContext context() {
        return new QueryContext("test", OperatorType.USER, "8300000000000099");
    }

    private static StatsQuery stats(String range) {
        return new StatsQuery(Long.toString(MERCHANT), Long.toString(STORE), range, null, null,
                null, context());
    }

    private static StatsQuery statsTop(String range, int limit) {
        return new StatsQuery(Long.toString(MERCHANT), Long.toString(STORE), range, limit, null,
                null, context());
    }

    private static StatsQuery statsCustom(String from, String to) {
        return new StatsQuery(Long.toString(MERCHANT), Long.toString(STORE), "CUSTOM", null, from,
                to, context());
    }

    private static final String ALICE_STR = "8300000000000101";
    private static final String BOB_STR = "8300000000000102";

    /** Isolated real-MySQL fixture over the authoritative 06 schema; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "ordcuststat_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

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
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                // Companion schemas for the 2026-10-19 expansion: 40号 adds pet_order.cancel_reason
                // (the 拒单率 fact); the 42号 refund-execution base (required by the 45号 ALTER);
                // the 45号 command binding rows; the 69号 note tables (no FKs into 06).
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/40-Payment-Foundation-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/42-Late-Refund-Execution-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/45-Merchant-Order-Actions-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/69-Merchant-Customer-Note-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
            } catch (Exception failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            jdbc.update("INSERT INTO user_account(id,phone,nickname,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    ALICE, "13800000100", "爱丽丝");
            jdbc.update("INSERT INTO user_account(id,phone,nickname,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    BOB, "13900000200", "鲍勃");
            // 69号备注伴随表 FK 指向 merchant_store（同 27号伴随表先例）：补本店种子行。
            jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    MERCHANT, "8300000000000099", "统计测试商家");
            jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,'测试地址 1 号','ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    STORE, MERCHANT, "统计测试门店");
        }

        MerchantCustomerQueryApi customers(UserQueryApi users) {
            UserQueryApi basics = users != null ? users : new UserQueryApi() {
                @Override
                public UserBasicDTO getUser(com.petplatform.user.api.query.UserIdQuery query) { return null; }
                @Override
                public com.petplatform.user.api.dto.PetSnapshotDTO getPetSnapshot(
                        com.petplatform.user.api.query.PetSnapshotQuery query) { return null; }
                @Override
                public boolean existsEnabledUser(com.petplatform.user.api.query.UserIdQuery query) { return false; }
                @Override
                public List<UserBasicDTO> listUsers(UserBasicsQuery query) {
                    return jdbc.queryForList(
                            "SELECT id, phone, nickname FROM user_account WHERE id IN ("
                                    + String.join(",", query.userIds()) + ")").stream()
                            .map(row -> new UserBasicDTO(Long.toString(((Number) row.get("id")).longValue()),
                                    masked((String) row.get("phone")), (String) row.get("nickname"), null))
                            .toList();
                }
            };
            return new MerchantCustomerQueryApiImpl(source, guard(), authority(), basics);
        }

        MerchantStatsQueryApi stats(StoreReviewScoreFactsApi reviewScores) {
            return stats(reviewScores, null);
        }

        MerchantStatsQueryApi stats(StoreReviewScoreFactsApi reviewScores,
                com.petplatform.schedule.api.query.ScheduleUtilizationQueryApi utilization) {
            return new MerchantStatsQueryApiImpl(source, guard(), authority(), reviewScores,
                    utilization, CLOCK);
        }

        com.petplatform.order.api.query.MerchantCustomerNoteCommandApi notes(
                com.petplatform.common.SnowflakeIdGenerator ids) {
            return new com.petplatform.order.biz.apiimpl.MerchantCustomerNoteCommandApiImpl(
                    source, guard(), authority(), ids);
        }

        private static String masked(String phone) {
            return phone == null ? null : phone.substring(0, 3) + "****" + phone.substring(7);
        }

        private static ScheduleCapacityGuardApi guard() {
            return new ScheduleCapacityGuardApi() {
                @Override
                public void acquire(List<String> storeIds, QueryContext context) {}
                @Override
                public void requireHeld(String storeId, DataSource callerSource) {}
            };
        }

        private static MerchantOrderAuthorityApi authority() {
            return new MerchantOrderAuthorityApi() {
                void check(String merchantId, String storeId) {
                    if (!Long.toString(STORE).equals(storeId)
                            || !Long.toString(MERCHANT).equals(merchantId)) {
                        throw new ApiException(CommonApiCodes.FORBIDDEN, "foreign coordinate");
                    }
                }
                @Override
                public void requireOwner(String merchantId, String storeId, QueryContext context) {
                    // Mirror production: the write family verifies the same coordinate.
                    check(merchantId, storeId);
                }
                @Override
                public void requireOwnerRead(String merchantId, String storeId, QueryContext context) {
                    check(merchantId, storeId);
                }
            };
        }

        void order(long id, long userId, String createdAt, String pay, String refunded,
                boolean verified) {
            order(id, userId, createdAt, pay, refunded, verified, 7500L);
        }

        void order(long id, long userId, String createdAt, String pay, String refunded,
                boolean verified, long serviceId) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,verification_status,fulfillment_type,
                     original_amount,discount_amount,pay_amount,refunded_amount,
                     appointment_start_at,appointment_end_at,paid_at,verified_at,
                     created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?, 'COMPLETED','PAID',?,'IN_STORE',
                           ?,0.00,?,?, '2030-01-01 09:00:00','2030-01-01 10:00:00',
                           '2026-10-01 08:00:00',?, ?, UTC_TIMESTAMP(3))
                    """, id, id, userId, MERCHANT, STORE, serviceId, 7600L, id + 5000,
                    verified ? "VERIFIED" : "UNVERIFIED", new BigDecimal(pay), new BigDecimal(pay),
                    new BigDecimal(refunded), verified ? createdAt : null, createdAt);
        }

        /** A canceled order: the merchant-reject family paid and was fully refunded; a plain
         *  close was never paid. Feeds the 拒单率 denominator/numerator rules. */
        void canceledOrder(long id, long userId, String createdAt, String cancelReason) {
            boolean rejected = "MERCHANT_REJECT_ORDER".equals(cancelReason);
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,verification_status,fulfillment_type,cancel_reason,
                     original_amount,discount_amount,pay_amount,refunded_amount,
                     appointment_start_at,appointment_end_at,paid_at,
                     created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?, 'CANCELED','PAID','UNVERIFIED','IN_STORE',?,
                           ?,0.00,?,?, '2030-01-01 09:00:00','2030-01-01 10:00:00',?,
                           ?, UTC_TIMESTAMP(3))
                    """, id, id, userId, MERCHANT, STORE, 7500L, 7600L, id + 5000,
                    cancelReason, new BigDecimal("100.00"), new BigDecimal("100.00"),
                    rejected ? new BigDecimal("100.00") : new BigDecimal("0.00"),
                    rejected ? createdAt : null, createdAt);
        }

        void orderInStore(long id, long storeId, long userId, String createdAt, String pay,
                String refunded, boolean verified) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,verification_status,fulfillment_type,
                     original_amount,discount_amount,pay_amount,refunded_amount,
                     appointment_start_at,appointment_end_at,paid_at,verified_at,
                     created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?, 'COMPLETED','PAID',?,'IN_STORE',
                           ?,0.00,?,?, '2030-01-01 09:00:00','2030-01-01 10:00:00',
                           '2026-10-01 08:00:00',?, ?, UTC_TIMESTAMP(3))
                    """, id, id, userId, MERCHANT, storeId, 7500L, 7600L, id + 5000,
                    verified ? "VERIFIED" : "UNVERIFIED", new BigDecimal(pay), new BigDecimal(pay),
                    new BigDecimal(refunded), verified ? createdAt : null, createdAt);
        }

        void petSnapshot(long orderId, long petId, String name, String type, String breed) {
            jdbc.update("INSERT INTO order_pet_snapshot(id,order_id,pet_id,pet_name,pet_type,breed_name,created_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                    orderId * 10, orderId, petId, name, type, breed);
        }

        void serviceSnapshot(long orderId, long serviceId, String name) {
            jdbc.update("""
                    INSERT INTO order_service_snapshot
                    (id,order_id,service_id,service_name,service_price,service_duration_minutes,
                     merchant_name,store_name,store_address,created_at)
                    VALUES(?,?,?,?,100.00,60,'商家','门店','地址',UTC_TIMESTAMP(3))
                    """, orderId * 10, orderId, serviceId, name);
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
                public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (SQLException failure) {
                        connection.close();
                        throw failure;
                    }
                }
            };
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }
}
