package com.petplatform.points.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.points.api.dto.PointsBalanceDTO;
import com.petplatform.points.api.dto.PointsLedgerDTO;
import com.petplatform.points.api.query.PointsQueryApi.PointsBalanceQuery;
import com.petplatform.points.api.query.PointsQueryApi.PointsLedgerQuery;
import com.petplatform.points.biz.apiimpl.PointsQueryApiImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CCR-C006-COUPON-POINTS-READ-001 P1 acceptance against isolated real MySQL (schema 06 section
 * 9): balance equals the account row (and reads "0" with no row), the ledger keeps its fixed
 * created_at DESC ordering across pages, the five V1 biz types cross the wire with REFUND_CLAWBACK
 * deltas negative, and every read stays scoped to the calling subject.
 */
class PointsQueryMySqlTest {

    private static final long OWNER = 9_400_000_000_000_401L;
    private static final long OTHER = 9_400_000_000_000_402L;
    private static final long NO_ACCOUNT = 9_400_000_000_000_403L;

    // Ledger ids: distinct so the created_at DESC, id DESC ordering is observable.
    private static final long LEDGER_SIGN_IN = 9_400_000_000_000_501L;
    private static final long LEDGER_INVITE = 9_400_000_000_000_502L;
    private static final long LEDGER_TASK = 9_400_000_000_000_503L;
    private static final long LEDGER_ORDER_REWARD = 9_400_000_000_000_504L;
    private static final long LEDGER_CLAWBACK = 9_400_000_000_000_505L;
    private static final long OTHER_LEDGER = 9_400_000_000_000_506L;

    private PointsQueryMySqlTestDatabase database;
    private PointsQueryApiImpl api;

    @BeforeEach
    void start() throws Exception {
        database = new PointsQueryMySqlTestDatabase();
        seed(database);
        api = new PointsQueryApiImpl(database.dataSource());
    }

    @AfterEach
    void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static void seed(PointsQueryMySqlTestDatabase db) {
        var jdbc = db.jdbc();
        jdbc.update("INSERT INTO points_account(id,user_id,balance,version,created_at,updated_at)"
                + " VALUES(?,?,1280,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                9_400_000_000_000_451L, OWNER);
        jdbc.update("INSERT INTO points_account(id,user_id,balance,version,created_at,updated_at)"
                + " VALUES(?,?,50,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                9_400_000_000_000_452L, OTHER);

        // Newest first: 10-06 clawback, 10-05 reward, 10-04 task, 10-03 invite, 10-02 sign-in.
        // The invite and task rows share created_at so the id DESC tie-break is exercised
        // (LEDGER_TASK 505 > LEDGER_INVITE 502 with identical timestamps).
        ledger(jdbc, LEDGER_SIGN_IN, OWNER, "SIGN_IN", 5, 35, "2026-10-02 01:00:00.000");
        ledger(jdbc, LEDGER_INVITE, OWNER, "INVITE", 15, 50, "2026-10-03 02:00:00.000");
        ledger(jdbc, LEDGER_TASK, OWNER, "TASK", 30, 80, "2026-10-03 02:00:00.000");
        ledger(jdbc, LEDGER_ORDER_REWARD, OWNER, "ORDER_REWARD", 1700, 1780,
                "2026-10-05 04:00:00.000");
        ledger(jdbc, LEDGER_CLAWBACK, OWNER, "REFUND_CLAWBACK", -500, 1280,
                "2026-10-06 05:00:00.000");
        ledger(jdbc, OTHER_LEDGER, OTHER, "SIGN_IN", 5, 5, "2026-10-06 06:00:00.000");
    }

    private static void ledger(org.springframework.jdbc.core.JdbcTemplate jdbc, long id, long user,
            String bizType, long delta, long balanceAfter, String createdAt) {
        jdbc.update("INSERT INTO points_ledger(id,user_id,biz_type,biz_id,order_id,delta,"
                        + "balance_after,request_id,created_at) VALUES(?,?,?,NULL,NULL,?,?,?,?)",
                id, user, bizType, delta, balanceAfter, "req-" + id, createdAt);
    }

    private static QueryContext as(long user) {
        return new QueryContext("test", OperatorType.USER, Long.toString(user));
    }

    @Test
    void balanceEqualsAccountRowAsNonNegativeString() {
        PointsBalanceDTO value = api.getBalance(new PointsBalanceQuery(as(OWNER)));
        assertEquals("1280", value.balance());
        assertEquals("50", api.getBalance(new PointsBalanceQuery(as(OTHER))).balance());
    }

    @Test
    void balanceWithoutAccountRowReadsZero() {
        PointsBalanceDTO value = api.getBalance(new PointsBalanceQuery(as(NO_ACCOUNT)));
        assertEquals("0", value.balance());
    }

    @Test
    void ledgerKeepsCreatedAtDescendingFixedOrderWithIdTieBreak() {
        PageResult<PointsLedgerDTO> page = api.queryLedger(new PointsLedgerQuery(1, 20, as(OWNER)));
        assertEquals(5, page.total());
        List<String> ids = page.items().stream().map(PointsLedgerDTO::ledgerId).toList();
        assertEquals(List.of("9400000000000505", "9400000000000504", "9400000000000503",
                "9400000000000502", "9400000000000501"), ids);
    }

    @Test
    void ledgerPaginationIsStableAcrossPages() {
        PageResult<PointsLedgerDTO> first = api.queryLedger(new PointsLedgerQuery(1, 2, as(OWNER)));
        PageResult<PointsLedgerDTO> second = api.queryLedger(new PointsLedgerQuery(2, 2, as(OWNER)));
        PageResult<PointsLedgerDTO> third = api.queryLedger(new PointsLedgerQuery(3, 2, as(OWNER)));
        List<String> whole = new java.util.ArrayList<>();
        first.items().forEach(item -> whole.add(item.ledgerId()));
        second.items().forEach(item -> whole.add(item.ledgerId()));
        third.items().forEach(item -> whole.add(item.ledgerId()));
        assertEquals(List.of("9400000000000505", "9400000000000504", "9400000000000503",
                "9400000000000502", "9400000000000501"), whole);
        // 5 lines over pageSize=2: the last page carries the remainder (1), not zero.
        assertEquals(1, third.items().size());
        assertEquals(5, third.total());
    }

    @Test
    void clawbackIsNegativeAndEveryLineCarriesSignedStringsAndIsoTime() {
        PageResult<PointsLedgerDTO> page = api.queryLedger(new PointsLedgerQuery(1, 20, as(OWNER)));
        PointsLedgerDTO clawback = page.items().get(0);
        assertEquals("REFUND_CLAWBACK", clawback.bizType());
        assertEquals("-500", clawback.delta());
        assertEquals("1280", clawback.balanceAfter());
        assertEquals("2026-10-06T05:00Z", clawback.createdAt().toString());

        PointsLedgerDTO newest = page.items().get(1);
        assertEquals("ORDER_REWARD", newest.bizType());
        assertEquals("1700", newest.delta());
        assertEquals("1780", newest.balanceAfter());

        for (PointsLedgerDTO item : page.items()) {
            long delta = Long.parseLong(item.delta());
            long after = Long.parseLong(item.balanceAfter());
            assertNotEquals(0L, delta);
            assertTrue(after >= 0);
            assertTrue(item.createdAt().isAfter(item.createdAt().minusSeconds(1)));
        }
        // All five V1 biz types are present exactly once.
        List<String> types = page.items().stream().map(PointsLedgerDTO::bizType).sorted().toList();
        assertEquals(List.of("INVITE", "ORDER_REWARD", "REFUND_CLAWBACK", "SIGN_IN", "TASK"), types);
    }

    @Test
    void readsNeverCrossSubjects() {
        PageResult<PointsLedgerDTO> page = api.queryLedger(new PointsLedgerQuery(1, 20, as(OTHER)));
        assertEquals(1, page.total());
        assertEquals("9400000000000506", page.items().get(0).ledgerId());
        assertEquals("0", api.getBalance(new PointsBalanceQuery(as(NO_ACCOUNT))).balance());
        assertEquals(0, api.queryLedger(new PointsLedgerQuery(1, 20, as(NO_ACCOUNT))).total());
    }
}
